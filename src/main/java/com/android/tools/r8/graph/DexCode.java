// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.code.CfOrDexInstruction;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.code.ReturnVoid;
import com.android.tools.r8.code.SwitchPayload;
import com.android.tools.r8.dex.CodeToKeep;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.JumboStringRewriter;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexCode.TryHandler.TypeAddrPair;
import com.android.tools.r8.graph.DexDebugEvent.SetInlineFrame;
import com.android.tools.r8.graph.DexDebugEvent.StartLocal;
import com.android.tools.r8.graph.DexDebugInfo.EventBasedDebugInfo;
import com.android.tools.r8.graph.bytecodemetadata.BytecodeInstructionMetadata;
import com.android.tools.r8.graph.bytecodemetadata.BytecodeMetadata;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.NumberGenerator;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.code.Position.SyntheticPosition;
import com.android.tools.r8.ir.conversion.DexSourceCode;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.ir.conversion.LensCodeRewriterUtils;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.utils.ArrayUtils;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.structural.Equatable;
import com.android.tools.r8.utils.structural.HashCodeVisitor;
import com.android.tools.r8.utils.structural.HashingVisitor;
import com.android.tools.r8.utils.structural.StructuralItem;
import com.android.tools.r8.utils.structural.StructuralMapping;
import com.android.tools.r8.utils.structural.StructuralSpecification;
import com.google.common.base.Strings;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

// DexCode corresponds to code item in dalvik/dex-format.html
public class DexCode extends Code implements DexWritableCode, StructuralItem<DexCode> {

  public static final String FAKE_THIS_PREFIX = "_";
  public static final String FAKE_THIS_SUFFIX = "this";

  public final int registerSize;
  public final int incomingRegisterSize;
  public final int outgoingRegisterSize;
  public final Try[] tries;
  public final TryHandler[] handlers;
  public final Instruction[] instructions;

  public DexString highestSortingString;
  private DexDebugInfo debugInfo;
  private DexDebugInfoForWriting debugInfoForWriting;

  private final BytecodeMetadata<Instruction> metadata;

  private static void specify(StructuralSpecification<DexCode, ?> spec) {
    spec.withInt(c -> c.registerSize)
        .withInt(c -> c.incomingRegisterSize)
        .withInt(c -> c.outgoingRegisterSize)
        .withItemArray(c -> c.tries)
        .withItemArray(c -> c.handlers)
        .withNullableItem(c -> c.debugInfo)
        .withItemArray(c -> c.instructions);
  }

  public DexCode(
      int registerSize,
      int insSize,
      int outsSize,
      Instruction[] instructions,
      Try[] tries,
      TryHandler[] handlers,
      DexDebugInfo debugInfo) {
    this(
        registerSize,
        insSize,
        outsSize,
        instructions,
        tries,
        handlers,
        debugInfo,
        BytecodeMetadata.empty());
  }

  public DexCode(
      int registerSize,
      int insSize,
      int outsSize,
      Instruction[] instructions,
      Try[] tries,
      TryHandler[] handlers,
      DexDebugInfo debugInfo,
      BytecodeMetadata<Instruction> metadata) {
    this.incomingRegisterSize = insSize;
    this.registerSize = registerSize;
    this.outgoingRegisterSize = outsSize;
    this.instructions = instructions;
    this.tries = tries;
    this.handlers = handlers;
    this.debugInfo = debugInfo;
    this.metadata = metadata;
    assert tries != null;
    assert handlers != null;
    assert instructions != null;
    hashCode();  // Cache the hash code eagerly.
  }

  @Override
  public DexCode self() {
    return this;
  }

  @Override
  public BytecodeInstructionMetadata getMetadata(CfOrDexInstruction instruction) {
    return getMetadata(instruction.asDexInstruction());
  }

  public BytecodeInstructionMetadata getMetadata(Instruction instruction) {
    return metadata.getMetadata(instruction);
  }

  @Override
  public DexWritableCodeKind getDexWritableCodeKind() {
    return DexWritableCodeKind.DEFAULT;
  }

  @Override
  public StructuralMapping<DexCode> getStructuralMapping() {
    return DexCode::specify;
  }

  @Override
  public DexWritableCode rewriteCodeWithJumboStrings(
      ProgramMethod method, ObjectToOffsetMapping mapping, DexItemFactory factory, boolean force) {
    DexString firstJumboString = null;
    if (force) {
      firstJumboString = mapping.getFirstString();
    } else {
      assert highestSortingString != null
          || Arrays.stream(instructions).noneMatch(Instruction::isConstString);
      assert Arrays.stream(instructions).noneMatch(Instruction::isDexItemBasedConstString);
      if (highestSortingString != null
          && highestSortingString.isGreaterThanOrEqualTo(mapping.getFirstJumboString())) {
        firstJumboString = mapping.getFirstJumboString();
      }
    }
    return firstJumboString != null
        ? new JumboStringRewriter(method.getDefinition(), firstJumboString, factory).rewrite()
        : this;
  }

  @Override
  public void setCallSiteContexts(ProgramMethod method) {
    for (Instruction instruction : instructions) {
      DexCallSite callSite = instruction.getCallSite();
      if (callSite != null) {
        callSite.setContext(method.getReference(), instruction.getOffset());
      }
    }
  }

  public DexCode withoutThisParameter(DexItemFactory factory) {
    // Note that we assume the original code has a register associated with 'this'
    // argument of the (former) instance method. We also assume (but do not check)
    // that 'this' register is never used, so when we decrease incoming register size
    // by 1, it becomes just a regular register which is never used, and thus will be
    // gone when we build an IR from this code. Rebuilding IR for methods 'staticized'
    // this way is highly recommended to improve register allocation.
    return new DexCode(
        registerSize,
        incomingRegisterSize - 1,
        outgoingRegisterSize,
        instructions,
        tries,
        handlers,
        debugInfoWithoutFirstParameter(factory));
  }

  @Override
  public boolean isDexCode() {
    return true;
  }

  @Override
  public boolean isDexWritableCode() {
    return true;
  }

  @Override
  public DexWritableCode asDexWritableCode() {
    return this;
  }

  @Override
  public int estimatedSizeForInlining() {
    return codeSizeInBytes();
  }

  @Override
  public int estimatedDexCodeSizeUpperBoundInBytes() {
    return codeSizeInBytes();
  }

  @Override
  public DexCode asDexCode() {
    return this;
  }

  public DexDebugInfo getDebugInfo() {
    return debugInfo;
  }

  public void setDebugInfo(DexDebugInfo debugInfo) {
    this.debugInfo = debugInfo;
    if (debugInfoForWriting != null) {
      debugInfoForWriting = null;
    }
  }

  public DexDebugInfo debugInfoWithFakeThisParameter(DexItemFactory factory) {
    EventBasedDebugInfo eventBasedInfo = DexDebugInfo.convertToEventBased(this, factory);
    if (eventBasedInfo == null) {
      return eventBasedInfo;
    }
    // User code may already have variables named '_*this'. Use one more than the largest number of
    // underscores present as a prefix to 'this'.
    int largestPrefix = 0;
    for (DexString parameter : eventBasedInfo.parameters) {
      largestPrefix = Integer.max(largestPrefix, getLargestPrefix(factory, parameter));
    }
    for (DexDebugEvent event : eventBasedInfo.events) {
      if (event instanceof DexDebugEvent.StartLocal) {
        DexString name = ((StartLocal) event).name;
        largestPrefix = Integer.max(largestPrefix, getLargestPrefix(factory, name));
      }
    }

    String fakeThisName = Strings.repeat(FAKE_THIS_PREFIX, largestPrefix + 1) + FAKE_THIS_SUFFIX;
    DexString[] parameters = eventBasedInfo.parameters;
    DexString[] newParameters = new DexString[parameters.length + 1];
    newParameters[0] = factory.createString(fakeThisName);
    System.arraycopy(parameters, 0, newParameters, 1, parameters.length);
    return new EventBasedDebugInfo(eventBasedInfo.startLine, newParameters, eventBasedInfo.events);
  }

  @Override
  public Code getCodeAsInlining(DexMethod caller, DexMethod callee, DexItemFactory factory) {
    return new DexCode(
        registerSize,
        incomingRegisterSize,
        outgoingRegisterSize,
        instructions,
        tries,
        handlers,
        debugInfoAsInlining(caller, callee, factory));
  }

  private DexDebugInfo debugInfoAsInlining(
      DexMethod caller, DexMethod callee, DexItemFactory factory) {
    Position callerPosition = SyntheticPosition.builder().setLine(0).setMethod(caller).build();
    EventBasedDebugInfo eventBasedInfo = DexDebugInfo.convertToEventBased(this, factory);
    if (eventBasedInfo == null) {
      // If the method has no debug info we generate a preamble position to denote the inlining.
      // This is consistent with the building IR for inlining which will always ensure the method
      // has a position.
      return new EventBasedDebugInfo(
          0,
          new DexString[callee.getArity()],
          new DexDebugEvent[] {
            new DexDebugEvent.SetInlineFrame(callee, callerPosition), factory.zeroChangeDefaultEvent
          });
    }
    DexDebugEvent[] oldEvents = eventBasedInfo.events;
    DexDebugEvent[] newEvents = new DexDebugEvent[oldEvents.length + 1];
    int i = 0;
    newEvents[i++] = new DexDebugEvent.SetInlineFrame(callee, callerPosition);
    for (DexDebugEvent event : oldEvents) {
      if (event instanceof SetInlineFrame) {
        SetInlineFrame oldFrame = (SetInlineFrame) event;
        newEvents[i++] =
            new SetInlineFrame(
                oldFrame.callee,
                oldFrame.caller == null
                    ? callerPosition
                    : oldFrame.caller.withOutermostCallerPosition(callerPosition));
      } else {
        newEvents[i++] = event;
      }
    }
    return new EventBasedDebugInfo(eventBasedInfo.startLine, eventBasedInfo.parameters, newEvents);
  }

  public static int getLargestPrefix(DexItemFactory factory, DexString name) {
    if (name != null && name.endsWith(factory.thisName)) {
      String string = name.toString();
      for (int i = 0; i < string.length(); i++) {
        if (string.charAt(i) != '_') {
          return i;
        }
      }
    }
    return 0;
  }

  public DexDebugInfo debugInfoWithoutFirstParameter(DexItemFactory factory) {
    EventBasedDebugInfo eventBasedInfo = DexDebugInfo.convertToEventBased(this, factory);
    if (eventBasedInfo == null) {
      return eventBasedInfo;
    }
    DexString[] parameters = eventBasedInfo.parameters;
    if(parameters.length == 0) {
      return eventBasedInfo;
    }
    DexString[] newParameters = new DexString[parameters.length - 1];
    System.arraycopy(parameters, 1, newParameters, 0, parameters.length - 1);
    return new EventBasedDebugInfo(eventBasedInfo.startLine, newParameters, eventBasedInfo.events);
  }

  @Override
  public void acceptHashing(HashingVisitor visitor) {
    visitor.visit(this, getStructuralMapping());
  }

  @Override
  public int computeHashCode() {
    return incomingRegisterSize * 2
        + registerSize * 3
        + outgoingRegisterSize * 5
        + Arrays.hashCode(instructions) * 7
        + ((debugInfo == null) ? 0 : debugInfo.hashCode()) * 11
        + Arrays.hashCode(tries) * 13
        + Arrays.hashCode(handlers) * 17;
  }

  @Override
  public boolean computeEquals(Object other) {
    return Equatable.equalsImpl(this, other);
  }

  @Override
  public boolean isEmptyVoidMethod() {
    return instructions.length == 1 && instructions[0] instanceof ReturnVoid;
  }

  @Override
  public Code asCode() {
    return this;
  }

  @Override
  public IRCode buildIR(ProgramMethod method, AppView<?> appView, Origin origin) {
    DexSourceCode source =
        new DexSourceCode(
            this,
            method,
            appView.graphLens().getOriginalMethodSignature(method.getReference()),
            null,
            appView.dexItemFactory());
    return IRBuilder.create(method, appView, source, origin).build(method);
  }

  @Override
  public IRCode buildInliningIR(
      ProgramMethod context,
      ProgramMethod method,
      AppView<?> appView,
      GraphLens codeLens,
      NumberGenerator valueNumberGenerator,
      Position callerPosition,
      Origin origin,
      RewrittenPrototypeDescription protoChanges) {
    DexSourceCode source =
        new DexSourceCode(
            this,
            method,
            appView.graphLens().getOriginalMethodSignature(method.getReference()),
            callerPosition,
            appView.dexItemFactory());
    return IRBuilder.createForInlining(
            method, appView, codeLens, source, origin, valueNumberGenerator, protoChanges)
        .build(context);
  }

  @Override
  public void registerCodeReferences(ProgramMethod method, UseRegistry registry) {
    internalRegisterCodeReferences(method, registry);
  }

  @Override
  public void registerCodeReferencesForDesugaring(ClasspathMethod method, UseRegistry registry) {
    internalRegisterCodeReferences(method, registry);
  }

  private void internalRegisterCodeReferences(DexClassAndMethod method, UseRegistry registry) {
    assert registry.getTraversalContinuation().shouldContinue();
    for (Instruction insn : instructions) {
      insn.registerUse(registry);
      if (registry.getTraversalContinuation().shouldBreak()) {
        return;
      }
    }
    for (TryHandler handler : handlers) {
      for (TypeAddrPair pair : handler.pairs) {
        registry.registerExceptionGuard(pair.type);
        if (registry.getTraversalContinuation().shouldBreak()) {
          return;
        }
      }
    }
  }

  @Override
  public String toString() {
    return toString(null, null);
  }

  @Override
  public String toString(DexEncodedMethod method, ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    if (method != null) {
      builder.append(method.toSourceString()).append("\n");
    }
    builder.append("registers: ").append(registerSize);
    builder.append(", inputs: ").append(incomingRegisterSize);
    builder.append(", outputs: ").append(outgoingRegisterSize).append("\n");
    builder.append("------------------------------------------------------------\n");
    builder.append("inst#  offset  instruction         arguments\n");
    builder.append("------------------------------------------------------------\n");

    // Collect payload users.
    Map<Integer, Instruction> payloadUsers = new HashMap<>();
    for (Instruction dex : instructions) {
      if (dex.hasPayload()) {
        payloadUsers.put(dex.getOffset() + dex.getPayloadOffset(), dex);
      }
    }

    DexDebugEntry debugInfo = null;
    Iterator<DexDebugEntry> debugInfoIterator = Collections.emptyIterator();
    boolean isPcBasedInfo = getDebugInfo() != null && getDebugInfo().isPcBasedInfo();
    if (!isPcBasedInfo && getDebugInfo() != null && method != null) {
      debugInfoIterator = new DexDebugEntryBuilder(method, new DexItemFactory()).build().iterator();
      debugInfo = debugInfoIterator.hasNext() ? debugInfoIterator.next() : null;
    }
    int instructionNumber = 0;
    Map<Integer, DebugLocalInfo> locals = Collections.emptyMap();
    for (Instruction insn : instructions) {
      debugInfo = advanceToOffset(insn.getOffset() - 1, debugInfo, debugInfoIterator);
      while (debugInfo != null && debugInfo.address == insn.getOffset()) {
        if (debugInfo.lineEntry || !locals.equals(debugInfo.locals)) {
          builder.append("         ").append(debugInfo.toString(false)).append("\n");
        }
        locals = debugInfo.locals;
        debugInfo = debugInfoIterator.hasNext() ? debugInfoIterator.next() : null;
      }
      StringUtils.appendLeftPadded(builder, Integer.toString(instructionNumber++), 5);
      builder.append(": ");
      if (insn.isSwitchPayload()) {
        Instruction payloadUser = payloadUsers.get(insn.getOffset());
        builder.append(insn.toString(naming, payloadUser));
      } else {
        builder.append(insn.toString(naming));
      }
      builder.append('\n');
    }
    if (isPcBasedInfo) {
      builder.append(getDebugInfo()).append("\n");
    } else if (debugInfoIterator.hasNext()) {
      Instruction lastInstruction = ArrayUtils.last(instructions);
      debugInfo = advanceToOffset(lastInstruction.getOffset(), debugInfo, debugInfoIterator);
      if (debugInfo != null) {
        throw new Unreachable("Could not print all debug information.");
      } else {
        builder.append("(has debug events past last pc)\n");
      }
    }
    if (tries.length > 0) {
      builder.append("Tries (numbers are offsets)\n");
      for (Try atry : tries) {
        builder.append("  ");
        builder.append(atry.toString());
        builder.append('\n');
      }
      builder.append("Handlers (numbers are offsets)\n");
      for (int handlerIndex = 0; handlerIndex < handlers.length; handlerIndex++) {
        TryHandler handler = handlers[handlerIndex];
        builder.append("  ").append(handlerIndex).append(": ");
        builder.append(handler.toString());
        builder.append('\n');
      }
    }
    return builder.toString();
  }

  DexDebugEntry advanceToOffset(
      int offset, DexDebugEntry current, Iterator<DexDebugEntry> iterator) {
    while (current != null && current.address <= offset) {
      current = iterator.hasNext() ? iterator.next() : null;
    }
    return current;
  }

  public String toSmaliString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    // Find labeled targets.
    Map<Integer, Instruction> payloadUsers = new HashMap<>();
    Set<Integer> labledTargets = new HashSet<>();
    // Collect payload users and labeled targets for non-payload instructions.
    for (Instruction dex : instructions) {
      int[] targets = dex.getTargets();
      if (targets != Instruction.NO_TARGETS && targets != Instruction.EXIT_TARGET) {
        assert targets.length <= 2;
        // For if instructions the second target is the fallthrough, for which no label is needed.
        labledTargets.add(dex.getOffset() + targets[0]);
      } else if (dex.hasPayload()) {
        labledTargets.add(dex.getOffset() + dex.getPayloadOffset());
        payloadUsers.put(dex.getOffset() + dex.getPayloadOffset(), dex);
      }
    }
    // Collect labeled targets for payload instructions.
    for (Instruction dex : instructions) {
      if (dex.isSwitchPayload()) {
        Instruction payloadUser = payloadUsers.get(dex.getOffset());
        if (dex instanceof SwitchPayload) {
          SwitchPayload payload = (SwitchPayload) dex;
          for (int target : payload.switchTargetOffsets()) {
            labledTargets.add(payloadUser.getOffset() + target);
          }
        }
      }
    }
    // Generate smali for all instructions.
    for (Instruction dex : instructions) {
      if (labledTargets.contains(dex.getOffset())) {
        builder.append("  :label_");
        builder.append(dex.getOffset());
        builder.append("\n");
      }
      if (dex.isSwitchPayload()) {
        Instruction payloadUser = payloadUsers.get(dex.getOffset());
        builder.append(dex.toSmaliString(payloadUser)).append('\n');
      } else {
        builder.append(dex.toSmaliString(naming)).append('\n');
      }
    }
    if (tries.length > 0) {
      builder.append("Tries (numbers are offsets)\n");
      for (Try atry : tries) {
        builder.append("  ");
        builder.append(atry.toString());
        builder.append('\n');
      }
        builder.append("Handlers (numbers are offsets)\n");
        for (TryHandler handler : handlers) {
          builder.append(handler.toString());
          builder.append('\n');
        }
    }
    return builder.toString();
  }

  @Override
  public void collectIndexedItems(
      IndexedItemCollection indexedItems,
      ProgramMethod context,
      GraphLens graphLens,
      LensCodeRewriterUtils rewriter) {
    highestSortingString = null;
    for (Instruction insn : instructions) {
      assert !insn.isDexItemBasedConstString();
      insn.collectIndexedItems(indexedItems, context, graphLens, rewriter);
      if (insn.isConstString()) {
        updateHighestSortingString(insn.asConstString().getString());
      } else if (insn.isConstStringJumbo()) {
        updateHighestSortingString(insn.asConstStringJumbo().getString());
      }
    }
    if (debugInfo != null) {
      getDebugInfoForWriting().collectIndexedItems(indexedItems, graphLens);
    }
    for (TryHandler handler : handlers) {
      handler.collectIndexedItems(indexedItems, graphLens);
    }
  }

  @Override
  public DexDebugInfoForWriting getDebugInfoForWriting() {
    if (debugInfoForWriting == null) {
      debugInfoForWriting = DexDebugInfo.convertToWritable(debugInfo);
    }
    return debugInfoForWriting;
  }

  @Override
  public TryHandler[] getHandlers() {
    return handlers;
  }

  @Override
  public DexString getHighestSortingString() {
    return highestSortingString;
  }

  @Override
  public Try[] getTries() {
    return tries;
  }

  @Override
  public int getRegisterSize(ProgramMethod method) {
    return registerSize;
  }

  @Override
  public int getIncomingRegisterSize(ProgramMethod method) {
    return incomingRegisterSize;
  }

  @Override
  public int getOutgoingRegisterSize() {
    return outgoingRegisterSize;
  }

  private void updateHighestSortingString(DexString candidate) {
    assert candidate != null;
    if (highestSortingString == null || highestSortingString.compareTo(candidate) < 0) {
      highestSortingString = candidate;
    }
  }

  @Override
  public void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    if (debugInfo != null) {
      getDebugInfoForWriting().collectMixedSectionItems(mixedItems);
    }
  }

  @Override
  public int codeSizeInBytes() {
    Instruction last = instructions[instructions.length - 1];
    assert last.hasOffset();
    int result = last.getOffset() + last.getSize();
    assert result == computeCodeSizeInBytes();
    return result;
  }

  private int computeCodeSizeInBytes() {
    int size = 0;
    for (Instruction insn : instructions) {
      size += insn.getSize();
    }
    return size;
  }

  @Override
  public void writeKeepRulesForDesugaredLibrary(CodeToKeep desugaredLibraryCodeToKeep) {
    for (Instruction instruction : instructions) {
      DexMethod method = instruction.getMethod();
      DexField field = instruction.getField();
      if (field != null) {
        assert method == null;
        desugaredLibraryCodeToKeep.recordField(field);
      } else if (method != null) {
        desugaredLibraryCodeToKeep.recordMethod(method);
      } else if (instruction.isConstClass()) {
        desugaredLibraryCodeToKeep.recordClass(instruction.asConstClass().getType());
      } else if (instruction.isInstanceOf()) {
        desugaredLibraryCodeToKeep.recordClass(instruction.asInstanceOf().getType());
      } else if (instruction.isCheckCast()) {
        desugaredLibraryCodeToKeep.recordClass(instruction.asCheckCast().getType());
      }
    }
  }

  @Override
  public void writeDex(
      ShortBuffer shortBuffer,
      ProgramMethod context,
      GraphLens graphLens,
      LensCodeRewriterUtils lensCodeRewriter,
      ObjectToOffsetMapping mapping) {
    for (Instruction instruction : instructions) {
      instruction.write(shortBuffer, context, graphLens, mapping, lensCodeRewriter);
    }
  }

  public static class Try extends DexItem implements StructuralItem<Try> {

    public static final int NO_INDEX = -1;

    public final int handlerOffset;
    public /* offset */ int startAddress;
    public /* offset */ int instructionCount;
    public int handlerIndex;

    private static void specify(StructuralSpecification<Try, ?> spec) {
      // The handler offset is the offset given by the dex input and does not determine the item.
      spec.withInt(t -> t.startAddress)
          .withInt(t -> t.instructionCount)
          .withInt(t -> t.handlerIndex);
    }

    public Try(int startAddress, int instructionCount, int handlerOffset) {
      this.startAddress = startAddress;
      this.instructionCount = instructionCount;
      this.handlerOffset = handlerOffset;
      this.handlerIndex = NO_INDEX;
    }

    @Override
    public Try self() {
      return this;
    }

    @Override
    public StructuralMapping<Try> getStructuralMapping() {
      return Try::specify;
    }

    public void setHandlerIndex(Int2IntMap map) {
      handlerIndex = map.get(handlerOffset);
    }

    @Override
    public int hashCode() {
      return startAddress * 2 + instructionCount * 3 + handlerIndex * 5;
    }

    @Override
    public boolean equals(Object other) {
      return Equatable.equalsImpl(this, other);
    }

    @Override
    public String toString() {
      return "["
          + StringUtils.hexString(startAddress, 2)
          + " .. "
          + StringUtils.hexString(startAddress + instructionCount - 1, 2)
          + "] -> "
          + handlerIndex;
    }

    @Override
    void collectMixedSectionItems(MixedSectionCollection mixedItems) {
      // Should never be visited.
      assert false;
    }

  }

  public static class TryHandler extends DexItem implements StructuralItem<TryHandler> {

    public static final int NO_HANDLER = -1;

    public final TypeAddrPair[] pairs;
    public final /* offset */ int catchAllAddr;

    private static void specify(StructuralSpecification<TryHandler, ?> spec) {
      spec.withInt(h -> h.catchAllAddr).withItemArray(h -> h.pairs);
    }

    public TryHandler(TypeAddrPair[] pairs, int catchAllAddr) {
      this.pairs = pairs;
      this.catchAllAddr = catchAllAddr;
    }

    @Override
    public TryHandler self() {
      return this;
    }

    @Override
    public StructuralMapping<TryHandler> getStructuralMapping() {
      return TryHandler::specify;
    }

    @Override
    public int hashCode() {
      return HashCodeVisitor.run(this);
    }

    @Override
    public boolean equals(Object other) {
      return Equatable.equalsImpl(this, other);
    }

    public void collectIndexedItems(IndexedItemCollection indexedItems, GraphLens graphLens) {
      for (TypeAddrPair pair : pairs) {
        pair.collectIndexedItems(indexedItems, graphLens);
      }
    }

    @Override
    void collectMixedSectionItems(MixedSectionCollection mixedItems) {
      // Should never be visited.
      assert false;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("[\n");
      for (TypeAddrPair pair : pairs) {
        builder.append("       ");
        builder.append(pair.type);
        builder.append(" -> ");
        builder.append(StringUtils.hexString(pair.addr, 2));
        builder.append("\n");
      }
      if (catchAllAddr != NO_HANDLER) {
        builder.append("       default -> ");
        builder.append(StringUtils.hexString(catchAllAddr, 2));
        builder.append("\n");
      }
      builder.append("     ]");
      return builder.toString();
    }

    public static class TypeAddrPair extends DexItem implements StructuralItem<TypeAddrPair> {

      private final DexType type;
      public final /* offset */ int addr;

      private static void specify(StructuralSpecification<TypeAddrPair, ?> spec) {
        spec.withItem(p -> p.type).withInt(p -> p.addr);
      }

      public TypeAddrPair(DexType type, int addr) {
        this.type = type;
        this.addr = addr;
      }

      @Override
      public TypeAddrPair self() {
        return this;
      }

      @Override
      public StructuralMapping<TypeAddrPair> getStructuralMapping() {
        return TypeAddrPair::specify;
      }

      public DexType getType() {
        return type;
      }

      public DexType getType(GraphLens lens) {
        return lens.lookupType(type);
      }

      public void collectIndexedItems(IndexedItemCollection indexedItems, GraphLens graphLens) {
        DexType rewritten = getType(graphLens);
        rewritten.collectIndexedItems(indexedItems);
      }

      @Override
      void collectMixedSectionItems(MixedSectionCollection mixedItems) {
        // Should never be visited.
        assert false;
      }

      @Override
      public int hashCode() {
        return type.hashCode() * 7 + addr;
      }

      @Override
      public boolean equals(Object other) {
        return Equatable.equalsImpl(this, other);
      }
    }
  }
}
