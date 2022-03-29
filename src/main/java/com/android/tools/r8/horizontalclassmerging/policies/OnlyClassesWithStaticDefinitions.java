// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.horizontalclassmerging.policies;

import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.horizontalclassmerging.SingleClassPolicy;
import com.google.common.collect.Iterables;

/** Prevent merging of classes that has non-static methods or fields. */
public class OnlyClassesWithStaticDefinitions extends SingleClassPolicy {

  @Override
  public boolean canMerge(DexProgramClass program) {
    return !Iterables.any(program.members(), member -> !member.isStatic());
  }

  @Override
  public String getName() {
    return "OnlyStaticDefinitions";
  }
}
