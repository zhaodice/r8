// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin;

/**
 * Represents the definition of a Kotlin companion class.
 *
 * <p>See https://kotlinlang.org/docs/reference/object-declarations.html#companion-objects</p>
 */
public class KotlinCompanionClass extends KotlinClass {

  private final String outerClassName;

  public KotlinCompanionClass(String outerClassName) {
    super(outerClassName + "$Companion");
    this.outerClassName = outerClassName;
  }

  @Override
  public KotlinCompanionClass addProperty(String name, String type, Visibility visibility) {
    return (KotlinCompanionClass) super.addProperty(name, type, visibility);
  }

  public String getOuterClassName() {
    return outerClassName;
  }
}
