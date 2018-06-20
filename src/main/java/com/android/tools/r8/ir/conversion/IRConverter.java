// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.conversion;

import static com.android.tools.r8.ir.desugar.InterfaceMethodRewriter.Flavor.ExcludeDexResources;
import static com.android.tools.r8.ir.desugar.InterfaceMethodRewriter.Flavor.IncludeAllResources;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexApplication.Builder;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.ir.analysis.constant.SparseConditionalConstantPropagation;
import com.android.tools.r8.ir.analysis.type.TypeAnalysis;
import com.android.tools.r8.ir.analysis.type.TypeEnvironment;
import com.android.tools.r8.ir.code.AlwaysMaterializingDefinition;
import com.android.tools.r8.ir.code.AlwaysMaterializingUser;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.desugar.CovariantReturnTypeAnnotationTransformer;
import com.android.tools.r8.ir.desugar.InterfaceMethodRewriter;
import com.android.tools.r8.ir.desugar.LambdaRewriter;
import com.android.tools.r8.ir.desugar.StringConcatRewriter;
import com.android.tools.r8.ir.optimize.CodeRewriter;
import com.android.tools.r8.ir.optimize.ConstantCanonicalizer;
import com.android.tools.r8.ir.optimize.DeadCodeRemover;
import com.android.tools.r8.ir.optimize.Devirtualizer;
import com.android.tools.r8.ir.optimize.Inliner;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.ir.optimize.MemberValuePropagation;
import com.android.tools.r8.ir.optimize.NonNullTracker;
import com.android.tools.r8.ir.optimize.Outliner;
import com.android.tools.r8.ir.optimize.PeepholeOptimizer;
import com.android.tools.r8.ir.optimize.RedundantFieldLoadElimination;
import com.android.tools.r8.ir.optimize.classinliner.ClassInliner;
import com.android.tools.r8.ir.optimize.lambda.LambdaMerger;
import com.android.tools.r8.ir.regalloc.LinearScanRegisterAllocator;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.naming.IdentifierNameStringMarker;
import com.android.tools.r8.shaking.protolite.ProtoLitePruner;
import com.android.tools.r8.utils.CfgPrinter;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.InternalOptions.OutlineOptions;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class IRConverter {

  private static final int PEEPHOLE_OPTIMIZATION_PASSES = 2;

  private final Timing timing;
  public final AppInfo appInfo;
  private final Outliner outliner;
  private final StringConcatRewriter stringConcatRewriter;
  private final LambdaRewriter lambdaRewriter;
  private final InterfaceMethodRewriter interfaceMethodRewriter;
  private final LambdaMerger lambdaMerger;
  private final ClassInliner classInliner;
  private final InternalOptions options;
  private final CfgPrinter printer;
  private final GraphLense graphLense;
  private final CodeRewriter codeRewriter;
  private final MemberValuePropagation memberValuePropagation;
  private final LensCodeRewriter lensCodeRewriter;
  private final NonNullTracker nonNullTracker;
  private final Inliner inliner;
  private final ProtoLitePruner protoLiteRewriter;
  private final IdentifierNameStringMarker identifierNameStringMarker;
  private final Devirtualizer devirtualizer;
  private final CovariantReturnTypeAnnotationTransformer covariantReturnTypeAnnotationTransformer;

  private final OptimizationFeedback ignoreOptimizationFeedback = new OptimizationFeedbackIgnore();
  private DexString highestSortingString;

  private IRConverter(
      AppInfo appInfo,
      InternalOptions options,
      Timing timing,
      CfgPrinter printer,
      GraphLense graphLense,
      boolean enableWholeProgramOptimizations) {
    assert appInfo != null;
    assert options != null;
    assert options.programConsumer != null;
    this.timing = timing != null ? timing : new Timing("internal");
    this.appInfo = appInfo;
    this.graphLense = graphLense != null ? graphLense : GraphLense.getIdentityLense();
    this.options = options;
    this.printer = printer;
    this.codeRewriter = new CodeRewriter(appInfo, libraryMethodsReturningReceiver(), options);
    this.stringConcatRewriter = new StringConcatRewriter(options.itemFactory);
    this.lambdaRewriter = options.enableDesugaring ? new LambdaRewriter(this) : null;
    this.interfaceMethodRewriter =
        (options.enableDesugaring && enableInterfaceMethodDesugaring())
            ? new InterfaceMethodRewriter(this, options) : null;
    this.lambdaMerger = options.enableLambdaMerging
        ? new LambdaMerger(appInfo.dexItemFactory, options.reporter) : null;
    this.covariantReturnTypeAnnotationTransformer =
        options.processCovariantReturnTypeAnnotations
            ? new CovariantReturnTypeAnnotationTransformer(this, appInfo.dexItemFactory)
            : null;
    if (enableWholeProgramOptimizations) {
      assert appInfo.hasLiveness();
      this.nonNullTracker = new NonNullTracker();
      this.inliner = new Inliner(appInfo.withLiveness(), graphLense, options);
      this.outliner = new Outliner(appInfo.withLiveness(), options);
      this.memberValuePropagation =
          options.enableValuePropagation ?
              new MemberValuePropagation(appInfo.withLiveness()) : null;
      this.lensCodeRewriter = new LensCodeRewriter(graphLense, appInfo.withSubtyping(), options);
      if (appInfo.hasLiveness()) {
        // When disabling the pruner here, also disable the ProtoLiteExtension in R8.java.
        this.protoLiteRewriter =
            options.forceProguardCompatibility ? null : new ProtoLitePruner(appInfo.withLiveness());
        if (!appInfo.withLiveness().identifierNameStrings.isEmpty() && options.enableMinification) {
          this.identifierNameStringMarker = new IdentifierNameStringMarker(appInfo.withLiveness());
        } else {
          this.identifierNameStringMarker = null;
        }
        this.devirtualizer =
            options.enableDevirtualization ? new Devirtualizer(appInfo.withLiveness()) : null;
      } else {
        this.protoLiteRewriter = null;
        this.identifierNameStringMarker = null;
        this.devirtualizer = null;
      }
    } else {
      this.nonNullTracker = null;
      this.inliner = null;
      this.outliner = null;
      this.memberValuePropagation = null;
      this.lensCodeRewriter = null;
      this.protoLiteRewriter = null;
      this.identifierNameStringMarker = null;
      this.devirtualizer = null;
    }
    this.classInliner =
        (options.enableClassInlining && options.enableInlining && inliner != null)
            ? new ClassInliner(appInfo.dexItemFactory, options.classInliningInstructionLimit)
            : null;
  }

  /**
   * Create an IR converter for processing methods with full program optimization disabled.
   */
  public IRConverter(
      AppInfo appInfo,
      InternalOptions options) {
    this(appInfo, options, null, null, null, false);
  }

  /**
   * Create an IR converter for processing methods with full program optimization disabled.
   */
  public IRConverter(
      AppInfo appInfo,
      InternalOptions options,
      Timing timing,
      CfgPrinter printer) {
    this(appInfo, options, timing, printer, null, false);
  }

  /**
   * Create an IR converter for processing methods with full program optimization enabled.
   */
  public IRConverter(
      AppInfoWithSubtyping appInfo,
      InternalOptions options,
      Timing timing,
      CfgPrinter printer,
      GraphLense graphLense) {
    this(appInfo, options, timing, printer, graphLense, true);
  }

  private boolean enableInterfaceMethodDesugaring() {
    switch (options.interfaceMethodDesugaring) {
      case Off:
        return false;
      case Auto:
        return !options.canUseDefaultAndStaticInterfaceMethods();
    }
    throw new Unreachable();
  }

  private boolean enableTryWithResourcesDesugaring() {
    switch (options.tryWithResourcesDesugaring) {
      case Off:
        return false;
      case Auto:
        return !options.canUseSuppressedExceptions();
    }
    throw new Unreachable();
  }

  private Set<DexMethod> libraryMethodsReturningReceiver() {
    Set<DexMethod> methods = new HashSet<>();
    DexItemFactory dexItemFactory = appInfo.dexItemFactory;
    dexItemFactory.stringBufferMethods.forEachAppendMethod(methods::add);
    dexItemFactory.stringBuilderMethods.forEachAppendMethod(methods::add);
    return methods;
  }

  private void removeLambdaDeserializationMethods() {
    if (lambdaRewriter != null) {
      lambdaRewriter.removeLambdaDeserializationMethods(appInfo.classes());
    }
  }

  private void synthesizeLambdaClasses(Builder<?> builder) {
    if (lambdaRewriter != null) {
      lambdaRewriter.adjustAccessibility();
      lambdaRewriter.synthesizeLambdaClasses(builder);
    }
  }

  private void desugarInterfaceMethods(
      Builder<?> builder, InterfaceMethodRewriter.Flavor includeAllResources) {
    if (interfaceMethodRewriter != null) {
      interfaceMethodRewriter.desugarInterfaceMethods(builder, includeAllResources);
    }
  }

  private void processCovariantReturnTypeAnnotations(Builder<?> builder) {
    if (covariantReturnTypeAnnotationTransformer != null) {
      covariantReturnTypeAnnotationTransformer.process(builder);
    }
  }

  public DexApplication convertToDex(DexApplication application, ExecutorService executor)
      throws ExecutionException {
    removeLambdaDeserializationMethods();

    timing.begin("IR conversion");
    convertClassesToDex(application.classes(), executor);

    // Build a new application with jumbo string info,
    Builder<?> builder = application.builder();
    builder.setHighestSortingString(highestSortingString);

    synthesizeLambdaClasses(builder);
    desugarInterfaceMethods(builder, ExcludeDexResources);
    processCovariantReturnTypeAnnotations(builder);

    handleSynthesizedClassMapping(builder);
    timing.end();

    return builder.build();
  }

  private void handleSynthesizedClassMapping(Builder<?> builder) {
    if (options.intermediate) {
      updateSynthesizedClassMapping(builder);
    }

    updateMainDexListWithSynthesizedClassMap(builder);

    if (!options.intermediate) {
      clearSynthesizedClassMapping(builder);
    }
  }

  private void updateMainDexListWithSynthesizedClassMap(Builder<?> builder) {
    Set<DexType> inputMainDexList = builder.getMainDexList();
    if (!inputMainDexList.isEmpty()) {
      Map<DexType, DexProgramClass> programClasses = builder.getProgramClasses().stream()
          .collect(Collectors.toMap(
              programClass -> programClass.type,
              Function.identity()));
      Collection<DexType> synthesized = new ArrayList<>();
      for (DexType dexType : inputMainDexList) {
        DexProgramClass programClass = programClasses.get(dexType);
        if (programClass != null) {
          synthesized.addAll(DexAnnotation.readAnnotationSynthesizedClassMap(
              programClass, builder.dexItemFactory));
        }
      }
      builder.addToMainDexList(synthesized);
    }
  }

  private void clearSynthesizedClassMapping(Builder<?> builder) {
    for (DexProgramClass programClass : builder.getProgramClasses()) {
      programClass.annotations =
          programClass.annotations.getWithout(builder.dexItemFactory.annotationSynthesizedClassMap);
    }
  }

  private void updateSynthesizedClassMapping(Builder<?> builder) {
    ListMultimap<DexProgramClass, DexProgramClass> originalToSynthesized =
        ArrayListMultimap.create();
    for (DexProgramClass synthesized : builder.getSynthesizedClasses()) {
      for (DexProgramClass original : synthesized.getSynthesizedFrom()) {
        originalToSynthesized.put(original, synthesized);
      }
    }

    for (Map.Entry<DexProgramClass, Collection<DexProgramClass>> entry :
        originalToSynthesized.asMap().entrySet()) {
      DexProgramClass original = entry.getKey();
      Set<DexType> synthesized = new HashSet<>();
      entry.getValue()
          .stream()
          .map(dexProgramClass -> dexProgramClass.type)
          .forEach(synthesized::add);
      synthesized.addAll(
          DexAnnotation.readAnnotationSynthesizedClassMap(original, builder.dexItemFactory));

      DexAnnotation updatedAnnotation =
          DexAnnotation.createAnnotationSynthesizedClassMap(synthesized, builder.dexItemFactory);

      original.annotations = original.annotations.getWithAddedOrReplaced(updatedAnnotation);
    }
  }

  private void convertClassesToDex(Iterable<DexProgramClass> classes,
      ExecutorService executor) throws ExecutionException {
    List<Future<?>> futures = new ArrayList<>();
    for (DexProgramClass clazz : classes) {
      futures.add(
          executor.submit(
              () -> {
                clazz.forEachMethodThrowing(this::convertMethodToDex);
                return null; // we want a Callable not a Runnable to be able to throw
              }));
    }
    ThreadUtils.awaitFutures(futures);
  }

  void convertMethodToDex(DexEncodedMethod method) {
    assert options.isGeneratingDex();
    if (method.getCode() != null) {
      boolean matchesMethodFilter = options.methodMatchesFilter(method);
      if (matchesMethodFilter) {
        if (!(options.passthroughDexCode && method.getCode().isDexCode())) {
          // We do not process in call graph order, so anything could be a leaf.
          rewriteCode(method, ignoreOptimizationFeedback, x -> true, CallSiteInformation.empty(),
              Outliner::noProcessing);
        }
        updateHighestSortingStrings(method);
      }
    }
  }

  public DexApplication optimize(DexApplication application) throws ExecutionException {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      return optimize(application, executor);
    } finally {
      executor.shutdown();
    }
  }

  public DexApplication optimize(DexApplication application, ExecutorService executorService)
      throws ExecutionException {
    removeLambdaDeserializationMethods();
    collectLambdaMergingCandidates(application);

    // The process is in two phases.
    // 1) Subject all DexEncodedMethods to optimization (except outlining).
    //    - a side effect is candidates for outlining are identified.
    // 2) Perform outlining for the collected candidates.
    // Ideally, we should outline eagerly when threshold for a template has been reached.

    // Process the application identifying outlining candidates.
    OptimizationFeedback directFeedback = new OptimizationFeedbackDirect();
    {
      timing.begin("Build call graph");
      CallGraph callGraph = CallGraph
          .build(application, appInfo.withLiveness(), graphLense, options);
      timing.end();
      timing.begin("IR conversion phase 1");
      BiConsumer<IRCode, DexEncodedMethod> outlineHandler =
          outliner == null ? Outliner::noProcessing : outliner.identifyCandidateMethods();
      callGraph.forEachMethod(
          (method, isProcessedConcurrently) -> {
            processMethod(
                method, directFeedback, isProcessedConcurrently, callGraph, outlineHandler);
          },
          executorService);
      timing.end();
    }

    // Build a new application with jumbo string info.
    Builder<?> builder = application.builder();
    builder.setHighestSortingString(highestSortingString);

    // Second inlining pass for dealing with double inline callers.
    if (inliner != null) {
      // Use direct feedback still, since methods after inlining may
      // change their status or other properties.
      inliner.processDoubleInlineCallers(this, directFeedback);
    }

    synthesizeLambdaClasses(builder);
    desugarInterfaceMethods(builder, IncludeAllResources);

    handleSynthesizedClassMapping(builder);
    finalizeLambdaMerging(application, directFeedback, builder, executorService);

    if (outliner != null) {
      timing.begin("IR conversion phase 2");
      if (outliner.selectMethodsForOutlining()) {
        forEachSelectedOutliningMethod(
            executorService,
            (code, method) -> {
              printMethod(code, "IR before outlining (SSA)");
              outliner.identifyOutlineSites(code, method);
            });
        DexProgramClass outlineClass = outliner.buildOutlinerClass(computeOutlineClassType());
        optimizeSynthesizedClass(outlineClass);
        forEachSelectedOutliningMethod(
            executorService,
            (code, method) -> {
              outliner.applyOutliningCandidate(code, method);
              printMethod(code, "IR after outlining (SSA)");
              finalizeIR(method, code, ignoreOptimizationFeedback);
            });
        assert outliner.checkAllOutlineSitesFoundAgain();
        builder.addSynthesizedClass(outlineClass, true);
        clearDexMethodCompilationState(outlineClass);
      }
      timing.end();
    }
    clearDexMethodCompilationState();

    if (identifierNameStringMarker != null) {
      identifierNameStringMarker.decoupleIdentifierNameStringsInFields();
    }

    return builder.build();
  }

  private void forEachSelectedOutliningMethod(
      ExecutorService executorService, BiConsumer<IRCode, DexEncodedMethod> consumer)
      throws ExecutionException {
    assert !options.skipIR;
    Set<DexEncodedMethod> methods = outliner.getMethodsSelectedForOutlining();
    List<Future<?>> futures = new ArrayList<>();
    for (DexEncodedMethod method : methods) {
      futures.add(
          executorService.submit(
              () -> {
                IRCode code =
                    method.buildIR(appInfo, options, appInfo.originFor(method.method.holder));
                assert code != null;
                assert !method.getCode().isOutlineCode();
                // Instead of repeating all the optimizations of rewriteCode(), only run the
                // optimizations needed for outlining: rewriteMoveResult() to remove out-values on
                // StringBuilder/StringBuffer method invocations, and removeDeadCode() to remove
                // unused out-values.
                codeRewriter.rewriteMoveResult(code);
                DeadCodeRemover.removeDeadCode(code, codeRewriter, graphLense, options);
                consumer.accept(code, method);
                return null;
              }));
    }
    ThreadUtils.awaitFutures(futures);
  }

  private void collectLambdaMergingCandidates(DexApplication application) {
    if (lambdaMerger != null) {
      lambdaMerger.collectGroupCandidates(application, appInfo.withLiveness(), options);
    }
  }

  private void finalizeLambdaMerging(
      DexApplication application,
      OptimizationFeedback directFeedback,
      Builder<?> builder,
      ExecutorService executorService)
      throws ExecutionException {
    if (lambdaMerger != null) {
      lambdaMerger.applyLambdaClassMapping(
          application, this, directFeedback, builder, executorService);
    }
  }

  private void clearDexMethodCompilationState() {
    appInfo.classes().forEach(this::clearDexMethodCompilationState);
  }

  private void clearDexMethodCompilationState(DexProgramClass clazz) {
    clazz.forEachMethod(DexEncodedMethod::markNotProcessed);
  }

  /**
   * This will replace the Dex code in the method with the Dex code generated from the provided IR.
   * <p>
   * This method is *only* intended for testing, where tests manipulate the IR and need runnable Dex
   * code.
   *
   * @param method the method to replace code for
   * @param code the IR code for the method
   */
  public void replaceCodeForTesting(DexEncodedMethod method, IRCode code) {
    if (Log.ENABLED) {
      Log.debug(getClass(), "Initial (SSA) flow graph for %s:\n%s", method.toSourceString(), code);
    }
    assert code.isConsistentSSA();
    code.traceBlocks();
    RegisterAllocator registerAllocator = performRegisterAllocation(code, method);
    method.setCode(code, registerAllocator, options);
    if (Log.ENABLED) {
      Log.debug(getClass(), "Resulting dex code for %s:\n%s",
          method.toSourceString(), logCode(options, method));
    }
  }

  // Find an unused name for the outlining class. When multiple runs produces additional
  // outlining the default outlining class might already be present.
  private DexType computeOutlineClassType() {
    DexType result;
    int count = 0;
    do {
      String name = OutlineOptions.CLASS_NAME + (count == 0 ? "" : Integer.toString(count));
      count++;
      result = appInfo.dexItemFactory.createType(DescriptorUtils.javaTypeToDescriptor(name));
    } while (appInfo.definitionFor(result) != null);
    // Register the newly generated type in the subtyping hierarchy, if we have one.
    appInfo.registerNewType(result, appInfo.dexItemFactory.objectType);
    return result;
  }

  public void optimizeSynthesizedClass(DexProgramClass clazz) {
    try {
      codeRewriter.enterCachedClass(clazz);
      // Process the generated class, but don't apply any outlining.
      clazz.forEachMethodThrowing(this::optimizeSynthesizedMethod);
    } finally {
      codeRewriter.leaveCachedClass(clazz);
    }
  }

  public void optimizeSynthesizedMethod(DexEncodedMethod method) {
    // Process the generated method, but don't apply any outlining.
    processMethod(method, ignoreOptimizationFeedback, x -> false, CallSiteInformation.empty(),
        Outliner::noProcessing);
  }

  private String logCode(InternalOptions options, DexEncodedMethod method) {
    return options.useSmaliSyntax ? method.toSmaliString(null) : method.codeToString();
  }

  public void processMethod(
      DexEncodedMethod method,
      OptimizationFeedback feedback,
      Predicate<DexEncodedMethod> isProcessedConcurrently,
      CallSiteInformation callSiteInformation,
      BiConsumer<IRCode, DexEncodedMethod> outlineHandler) {
    Code code = method.getCode();
    boolean matchesMethodFilter = options.methodMatchesFilter(method);
    if (code != null && matchesMethodFilter) {
      rewriteCode(method, feedback, isProcessedConcurrently, callSiteInformation, outlineHandler);
    } else {
      // Mark abstract methods as processed as well.
      method.markProcessed(Constraint.NEVER);
    }
  }

  private static void invertConditionalsForTesting(IRCode code) {
    for (BasicBlock block : code.blocks) {
      if (block.exit().isIf()) {
        block.exit().asIf().invert();
      }
    }
  }

  private void rewriteCode(
      DexEncodedMethod method,
      OptimizationFeedback feedback,
      Predicate<DexEncodedMethod> isProcessedConcurrently,
      CallSiteInformation callSiteInformation,
      BiConsumer<IRCode, DexEncodedMethod> outlineHandler) {
    if (options.verbose) {
      options.reporter.info(
          new StringDiagnostic("Processing: " + method.toSourceString()));
    }
    if (Log.ENABLED) {
      Log.debug(getClass(), "Original code for %s:\n%s",
          method.toSourceString(), logCode(options, method));
    }
    if (options.skipIR) {
      feedback.markProcessed(method, Constraint.NEVER);
      return;
    }
    IRCode code = method.buildIR(appInfo, options, appInfo.originFor(method.method.holder));
    if (code == null) {
      feedback.markProcessed(method, Constraint.NEVER);
      return;
    }
    if (Log.ENABLED) {
      Log.debug(getClass(), "Initial (SSA) flow graph for %s:\n%s", method.toSourceString(), code);
    }
    // Compilation header if printing CFGs for this method.
    printC1VisualizerHeader(method);
    printMethod(code, "Initial IR (SSA)");

    if (options.canHaveArtStringNewInitBug()) {
      codeRewriter.ensureDirectStringNewToInit(code);
    }

    if (options.debug) {
      codeRewriter.simplifyDebugLocals(code);
    }

    if (!method.isProcessed()) {
      if (protoLiteRewriter != null && protoLiteRewriter.appliesTo(method)) {
        protoLiteRewriter.rewriteProtoLiteSpecialMethod(code, method);
      }
      if (lensCodeRewriter != null) {
        lensCodeRewriter.rewrite(code, method);
      } else {
        assert graphLense.isIdentityLense();
      }
    }

    if (identifierNameStringMarker != null) {
      identifierNameStringMarker.decoupleIdentifierNameStringsInMethod(method, code);
      assert code.isConsistentSSA();
    }

    if (memberValuePropagation != null) {
      memberValuePropagation.rewriteWithConstantValues(code, method.method.holder);
    }
    if (options.enableSwitchMapRemoval && appInfo.hasLiveness()) {
      codeRewriter.removeSwitchMaps(code);
    }
    if (options.disableAssertions) {
      codeRewriter.disableAssertions(code);
    }
    if (options.enableNonNullTracking && nonNullTracker != null) {
      nonNullTracker.addNonNull(code);
      assert code.isConsistentSSA();
    }
    TypeEnvironment typeEnvironment = TypeAnalysis.getDefaultTypeEnvironment();
    if (options.enableInlining && inliner != null) {
      typeEnvironment = new TypeAnalysis(appInfo, method, code);
      // TODO(zerny): Should we support inlining in debug mode? b/62937285
      assert !options.debug;
      inliner.performInlining(
          method, code, typeEnvironment, isProcessedConcurrently, callSiteInformation);
    }
    if (devirtualizer != null) {
      devirtualizer.devirtualizeInvokeInterface(code, typeEnvironment, method.method.getHolder());
    }
    codeRewriter.removeCasts(code, typeEnvironment);
    codeRewriter.rewriteLongCompareAndRequireNonNull(code, options);
    codeRewriter.commonSubexpressionElimination(code);
    codeRewriter.simplifyArrayConstruction(code);
    codeRewriter.rewriteMoveResult(code);
    codeRewriter.splitRangeInvokeConstants(code);
    new SparseConditionalConstantPropagation(code).run();
    codeRewriter.rewriteSwitch(code);
    codeRewriter.processMethodsNeverReturningNormally(code);
    codeRewriter.simplifyIf(code, typeEnvironment);
    new RedundantFieldLoadElimination(code).run();

    if (options.testing.invertConditionals) {
      invertConditionalsForTesting(code);
    }

    if (options.enableNonNullTracking && nonNullTracker != null) {
      nonNullTracker.cleanupNonNull(code);
      assert code.isConsistentSSA();
    }
    if (!options.debug) {
      codeRewriter.collectClassInitializerDefaults(method, code);
    }
    if (Log.ENABLED) {
      Log.debug(getClass(), "Intermediate (SSA) flow graph for %s:\n%s",
          method.toSourceString(), code);
    }
    // Dead code removal. Performed after simplifications to remove code that becomes dead
    // as a result of those simplifications. The following optimizations could reveal more
    // dead code which is removed right before register allocation in performRegisterAllocation.
    DeadCodeRemover.removeDeadCode(code, codeRewriter, graphLense, options);
    assert code.isConsistentSSA();

    if (options.enableDesugaring && enableTryWithResourcesDesugaring()) {
      codeRewriter.rewriteThrowableAddAndGetSuppressed(code);
    }

    stringConcatRewriter.desugarStringConcats(method.method, code);

    if (lambdaRewriter != null) {
      lambdaRewriter.desugarLambdas(method, code);
      assert code.isConsistentSSA();
    }

    if (interfaceMethodRewriter != null) {
      interfaceMethodRewriter.rewriteMethodReferences(method, code);
      assert code.isConsistentSSA();
    }

    if (classInliner != null) {
      // Class inliner should work before lambda merger, so if it inlines the
      // lambda, it is not get collected by merger.
      assert options.enableInlining && inliner != null;
      classInliner.processMethodCode(
          appInfo.withLiveness(), method, code, isProcessedConcurrently,
          methodsToInline -> inliner.performForcedInlining(method, code, methodsToInline)
      );
      assert code.isConsistentSSA();
    }

    if (lambdaMerger != null) {
      lambdaMerger.processMethodCode(method, code);
      assert code.isConsistentSSA();
    }

    if (options.outline.enabled) {
      outlineHandler.accept(code, method);
      assert code.isConsistentSSA();
    }

    ConstantCanonicalizer.canonicalize(code);
    codeRewriter.useDedicatedConstantForLitInstruction(code);
    codeRewriter.shortenLiveRanges(code);
    codeRewriter.identifyReturnsArgument(method, code, feedback);
    if (options.enableInlining && inliner != null) {
      codeRewriter.identifyInvokeSemanticsForInlining(method, code, feedback);
    }

    // Insert code to log arguments if requested.
    if (options.methodMatchesLogArgumentsFilter(method)) {
      codeRewriter.logArgumentTypes(method, code);
      assert code.isConsistentSSA();
    }

    // Analysis must be done after method is rewritten by logArgumentTypes()
    codeRewriter.identifyClassInlinerEligibility(method, code, feedback);
    if (method.isInstanceInitializer() || method.isClassInitializer()) {
      codeRewriter.identifyTrivialInitializer(method, code, feedback);
    }

    printMethod(code, "Optimized IR (SSA)");
    finalizeIR(method, code, feedback);
  }

  private void finalizeIR(DexEncodedMethod method, IRCode code, OptimizationFeedback feedback) {
    code.traceBlocks();
    if (options.isGeneratingClassFiles()) {
      finalizeToCf(method, code, feedback);
    } else {
      assert options.isGeneratingDex();
      finalizeToDex(method, code, feedback);
    }
  }

  private void finalizeToCf(DexEncodedMethod method, IRCode code, OptimizationFeedback feedback) {
    assert !method.getCode().isDexCode();
    CfBuilder builder = new CfBuilder(method, code, options.itemFactory);
    CfCode result = builder.build(codeRewriter, graphLense, options, appInfo.withSubtyping());
    method.setCode(result);
    markProcessed(method, code, feedback);
  }

  private void finalizeToDex(DexEncodedMethod method, IRCode code, OptimizationFeedback feedback) {
    // Perform register allocation.
    RegisterAllocator registerAllocator = performRegisterAllocation(code, method);
    method.setCode(code, registerAllocator, options);
    updateHighestSortingStrings(method);
    if (Log.ENABLED) {
      Log.debug(getClass(), "Resulting dex code for %s:\n%s",
          method.toSourceString(), logCode(options, method));
    }
    printMethod(code, "Final IR (non-SSA)");
    markProcessed(method, code, feedback);
  }

  private void markProcessed(DexEncodedMethod method, IRCode code, OptimizationFeedback feedback) {
    // After all the optimizations have take place, we compute whether method should be inlinedex.
    Constraint state;
    if (!options.enableInlining || inliner == null) {
      state = Constraint.NEVER;
    } else {
      state = inliner.computeInliningConstraint(code, method);
    }
    feedback.markProcessed(method, state);
  }

  private synchronized void updateHighestSortingStrings(DexEncodedMethod method) {
    DexString highestSortingReferencedString = method.getCode().asDexCode().highestSortingString;
    if (highestSortingReferencedString != null) {
      if (highestSortingString == null
          || highestSortingReferencedString.slowCompareTo(highestSortingString) > 0) {
        highestSortingString = highestSortingReferencedString;
      }
    }
  }

  private RegisterAllocator performRegisterAllocation(IRCode code, DexEncodedMethod method) {
    // Always perform dead code elimination before register allocation. The register allocator
    // does not allow dead code (to make sure that we do not waste registers for unneeded values).
    DeadCodeRemover.removeDeadCode(code, codeRewriter, graphLense, options);
    materializeInstructionBeforeLongOperationsWorkaround(code, options);
    LinearScanRegisterAllocator registerAllocator = new LinearScanRegisterAllocator(code, options);
    registerAllocator.allocateRegisters(options.debug);
    printMethod(code, "After register allocation (non-SSA)");
    for (int i = 0; i < PEEPHOLE_OPTIMIZATION_PASSES; i++) {
      CodeRewriter.collapsTrivialGotos(method, code);
      PeepholeOptimizer.optimize(code, registerAllocator);
    }
    CodeRewriter.collapsTrivialGotos(method, code);
    if (Log.ENABLED) {
      Log.debug(getClass(), "Final (non-SSA) flow graph for %s:\n%s",
          method.toSourceString(), code);
    }
    return registerAllocator;
  }

  private static void materializeInstructionBeforeLongOperationsWorkaround(
      IRCode code, InternalOptions options) {
    if (!options.canHaveDex2OatLinkedListBug()) {
      return;
    }
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator();
      Instruction firstMaterializing =
          it.nextUntil(IRConverter::isMaterializingInstructionOnArtArmVersionM);
      if (needsInstructionBeforeLongOperation(firstMaterializing)) {
        ensureInstructionBeforeLongOperation(code, block, firstMaterializing, it);
      }
    }
  }

  private static void ensureInstructionBeforeLongOperation(
      IRCode code, BasicBlock block, Instruction firstMaterializing, InstructionListIterator it) {
    // Force materialize a constant-zero before the long operation.
    Instruction check = it.previous();
    assert firstMaterializing == check;
    Value fixitValue = code.createValue(ValueType.INT);
    // Forced definition of const-zero
    Instruction fixitDefinition = new AlwaysMaterializingDefinition(fixitValue);
    fixitDefinition.setBlock(block);
    fixitDefinition.setPosition(firstMaterializing.getPosition());
    it.add(fixitDefinition);
    // Forced user of the forced definition to ensure it has a user and thus live range.
    Instruction fixitUser = new AlwaysMaterializingUser(fixitValue);
    fixitUser.setBlock(block);
    it.add(fixitUser);
  }

  private static boolean needsInstructionBeforeLongOperation(Instruction instruction) {
    // The cortex fixup will only trigger on long sub and long add instructions.
    if (!((instruction.isAdd() || instruction.isSub()) && instruction.outType().isWide())) {
      return false;
    }
    // If the block with the instruction is a fallthrough block, then it can't end up being
    // preceded by the incorrectly linked prologue/epilogue..
    BasicBlock block = instruction.getBlock();
    for (BasicBlock pred : block.getPredecessors()) {
      if (pred.exit().fallthroughBlock() == block) {
        return false;
      }
    }
    return true;
  }

  private static boolean isMaterializingInstructionOnArtArmVersionM(Instruction instruction) {
    return !instruction.isDebugInstruction()
        && !instruction.isMove()
        && !isPossiblyNonMaterializingLongOperationOnArtArmVersionM(instruction);
  }

  private static boolean isPossiblyNonMaterializingLongOperationOnArtArmVersionM(
      Instruction instruction) {
    return (instruction.isMul() || instruction.isDiv()) && instruction.outType().isWide();
  }

  private void printC1VisualizerHeader(DexEncodedMethod method) {
    if (printer != null) {
      printer.begin("compilation");
      printer.print("name \"").append(method.toSourceString()).append("\"").ln();
      printer.print("method \"").append(method.toSourceString()).append("\"").ln();
      printer.print("date 0").ln();
      printer.end("compilation");
    }
  }

  private void printMethod(IRCode code, String title) {
    if (printer != null) {
      printer.resetUnusedValue();
      printer.begin("cfg");
      printer.print("name \"").append(title).append("\"\n");
      code.print(printer);
      printer.end("cfg");
    }
  }
}
