// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.ProgramResource.Kind;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexLibraryClass;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.DirectMappedDexApplication;
import com.android.tools.r8.graph.EnclosingMethodAttribute;
import com.android.tools.r8.graph.InnerClassAttribute;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.NestHostClassAttribute;
import com.android.tools.r8.graph.NestMemberClassAttribute;
import com.android.tools.r8.graph.ParameterAnnotationsList;
import com.android.tools.r8.origin.SynthesizedOrigin;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

public class DesugaredLibraryRetargeter {

  public static DirectMappedDexApplication amendLibraryWithRetargetedMembers(
      AppView<AppInfoWithSubtyping> appView) {
    Map<DexString, Map<DexType, DexType>> retargetCoreLibMember =
        appView.options().desugaredLibraryConfiguration.getRetargetCoreLibMember();
    Map<DexType, DexLibraryClass> synthesizedLibraryClasses =
        synthesizeLibraryClassesForRetargetedMembers(appView, retargetCoreLibMember);
    Map<DexLibraryClass, Set<DexEncodedMethod>> synthesizedLibraryMethods =
        synthesizedMembersForRetargetClasses(
            appView, retargetCoreLibMember, synthesizedLibraryClasses);
    synthesizedLibraryMethods.forEach(DexLibraryClass::appendDirectMethods);
    DirectMappedDexApplication newApplication =
        appView
            .appInfo()
            .app()
            .asDirect()
            .builder()
            .addLibraryClasses(synthesizedLibraryClasses.values())
            .build();
    appView.setAppInfo(new AppInfoWithSubtyping(newApplication));
    return newApplication;
  }

  private static Map<DexType, DexLibraryClass> synthesizeLibraryClassesForRetargetedMembers(
      AppView<AppInfoWithSubtyping> appView,
      Map<DexString, Map<DexType, DexType>> retargetCoreLibMember) {
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    Map<DexType, DexLibraryClass> synthesizedLibraryClasses = new LinkedHashMap<>();
    for (Map<DexType, DexType> oldToNewTypeMap : retargetCoreLibMember.values()) {
      for (DexType newType : oldToNewTypeMap.values()) {
        if (appView.definitionFor(newType) == null) {
          synthesizedLibraryClasses.computeIfAbsent(
              newType,
              type ->
                  // Synthesize a library class with the given name. Note that this is assuming that
                  // the library class inherits directly from java.lang.Object, does not implement
                  // any interfaces, etc.
                  new DexLibraryClass(
                      type,
                      Kind.CF,
                      new SynthesizedOrigin(
                          "Desugared library retargeter", DesugaredLibraryRetargeter.class),
                      ClassAccessFlags.fromCfAccessFlags(Constants.ACC_PUBLIC),
                      dexItemFactory.objectType,
                      DexTypeList.empty(),
                      dexItemFactory.createString("DesugaredLibraryRetargeter"),
                      NestHostClassAttribute.none(),
                      NestMemberClassAttribute.emptyList(),
                      EnclosingMethodAttribute.none(),
                      InnerClassAttribute.emptyList(),
                      DexAnnotationSet.empty(),
                      DexEncodedField.EMPTY_ARRAY,
                      DexEncodedField.EMPTY_ARRAY,
                      DexEncodedMethod.EMPTY_ARRAY,
                      DexEncodedMethod.EMPTY_ARRAY,
                      dexItemFactory.getSkipNameValidationForTesting()));
        }
      }
    }
    return synthesizedLibraryClasses;
  }

  private static Map<DexLibraryClass, Set<DexEncodedMethod>> synthesizedMembersForRetargetClasses(
      AppView<AppInfoWithSubtyping> appView,
      Map<DexString, Map<DexType, DexType>> retargetCoreLibMember,
      Map<DexType, DexLibraryClass> synthesizedLibraryClasses) {
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    Map<DexLibraryClass, Set<DexEncodedMethod>> synthesizedMembers = new IdentityHashMap<>();
    for (Entry<DexString, Map<DexType, DexType>> entry : retargetCoreLibMember.entrySet()) {
      DexString methodName = entry.getKey();
      Map<DexType, DexType> types = entry.getValue();
      types.forEach(
          (oldType, newType) -> {
            DexClass oldClass = appView.definitionFor(oldType);
            DexLibraryClass newClass = synthesizedLibraryClasses.get(newType);
            if (oldClass == null || newClass == null) {
              return;
            }
            for (DexEncodedMethod method :
                oldClass.methods(method -> method.getName() == methodName)) {
              DexMethod retargetMethod = method.getReference().withHolder(newType, dexItemFactory);
              if (!method.isStatic()) {
                retargetMethod = retargetMethod.withExtraArgumentPrepended(oldType, dexItemFactory);
              }
              synthesizedMembers
                  .computeIfAbsent(
                      newClass,
                      ignore ->
                          new TreeSet<>((x, y) -> x.getReference().slowCompareTo(y.getReference())))
                  .add(
                      new DexEncodedMethod(
                          retargetMethod,
                          MethodAccessFlags.fromCfAccessFlags(
                              Constants.ACC_PUBLIC | Constants.ACC_STATIC, false),
                          DexAnnotationSet.empty(),
                          ParameterAnnotationsList.empty(),
                          null,
                          true));
            }
          });
    }
    return synthesizedMembers;
  }
}
