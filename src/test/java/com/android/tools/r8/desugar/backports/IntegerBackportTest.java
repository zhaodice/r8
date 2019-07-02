// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.backports;

import com.android.tools.r8.TestParameters;
import com.android.tools.r8.utils.AndroidApiLevel;
import java.math.BigInteger;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public final class IntegerBackportTest extends AbstractBackportTest {
  @Parameters(name = "{0}")
  public static Iterable<?> data() {
    return getTestParameters().withDexRuntimes().build();
  }

  public IntegerBackportTest(TestParameters parameters) {
    super(parameters, Integer.class, Main.class);
    registerTarget(AndroidApiLevel.O, 35);
    registerTarget(AndroidApiLevel.N, 11);
    registerTarget(AndroidApiLevel.K, 7);
  }

  static final class Main extends MiniAssert {
    private static final int[] interestingValues = {
        Integer.MIN_VALUE, Integer.MAX_VALUE,
        Short.MIN_VALUE, Short.MAX_VALUE,
        Byte.MIN_VALUE, Byte.MAX_VALUE,
        0,
        -1, 1,
        -42, 42
    };

    public static void main(String[] args) {
      testHashCode();
      testToUnsignedLong();
      testCompare();
      testMax();
      testMin();
      testSum();
      testCompareUnsigned();
      testDivideUnsigned();
      testRemainderUnsigned();
    }

    private static void testHashCode() {
      for (int value : interestingValues) {
        assertEquals(value, Integer.hashCode(value));
      }
    }

    private static void testToUnsignedLong() {
      assertEquals(0L, Integer.toUnsignedLong(0));
      assertEquals(2_147_483_647L, Integer.toUnsignedLong(Integer.MAX_VALUE));
      assertEquals(2_147_483_648L, Integer.toUnsignedLong(Integer.MIN_VALUE));
      assertEquals(4_294_967_295L, Integer.toUnsignedLong(-1));
    }

    private static void testCompare() {
      assertTrue(Integer.compare(1, 0) > 0);
      assertTrue(Integer.compare(0, 0) == 0);
      assertTrue(Integer.compare(0, 1) < 0);
      assertTrue(Integer.compare(Integer.MIN_VALUE, Integer.MAX_VALUE) < 0);
      assertTrue(Integer.compare(Integer.MAX_VALUE, Integer.MIN_VALUE) > 0);
      assertTrue(Integer.compare(Integer.MIN_VALUE, Integer.MIN_VALUE) == 0);
      assertTrue(Integer.compare(Integer.MAX_VALUE, Integer.MAX_VALUE) == 0);
    }

    private static void testMax() {
      for (int x : interestingValues) {
        for (int y : interestingValues) {
          assertEquals(Math.max(x, y), Integer.max(x, y));
        }
      }
    }

    private static void testMin() {
      for (int x : interestingValues) {
        for (int y : interestingValues) {
          assertEquals(Math.min(x, y), Integer.min(x, y));
        }
      }
    }

    private static void testSum() {
      for (int x : interestingValues) {
        for (int y : interestingValues) {
          assertEquals(x + y, Integer.sum(x, y));
        }
      }
    }

    private static void testCompareUnsigned() {
      assertEquals(0, Integer.compareUnsigned(0, 0));
      assertEquals(-1, Integer.compareUnsigned(0, Integer.MAX_VALUE));
      assertEquals(-1, Integer.compareUnsigned(0, Integer.MIN_VALUE));
      assertEquals(-1, Integer.compareUnsigned(0, -1));

      assertEquals(1, Integer.compareUnsigned(Integer.MAX_VALUE, 0));
      assertEquals(0, Integer.compareUnsigned(Integer.MAX_VALUE, Integer.MAX_VALUE));
      assertEquals(-1, Integer.compareUnsigned(Integer.MAX_VALUE, Integer.MIN_VALUE));
      assertEquals(-1, Integer.compareUnsigned(Integer.MAX_VALUE, -1));

      assertEquals(1, Integer.compareUnsigned(Integer.MIN_VALUE, 0));
      assertEquals(1, Integer.compareUnsigned(Integer.MIN_VALUE, Integer.MAX_VALUE));
      assertEquals(0, Integer.compareUnsigned(Integer.MIN_VALUE, Integer.MIN_VALUE));
      assertEquals(-1, Integer.compareUnsigned(Integer.MIN_VALUE, -1));

      assertEquals(1, Integer.compareUnsigned(-1, 0));
      assertEquals(1, Integer.compareUnsigned(-1, Integer.MAX_VALUE));
      assertEquals(1, Integer.compareUnsigned(-1, Integer.MIN_VALUE));
      assertEquals(0, Integer.compareUnsigned(-1, -1));
    }

    private static void testDivideUnsigned() {
      for (int x : interestingValues) {
        for (int y : interestingValues) {
          if (y == 0) continue;

          BigInteger xUnsigned = BigInteger.valueOf(x & 0xffffffffL);
          BigInteger yUnsigned = BigInteger.valueOf(y & 0xffffffffL);
          int expected = xUnsigned.divide(yUnsigned).intValue();

          assertEquals(expected, Integer.divideUnsigned(x, y));
        }
      }

      try {
        throw new AssertionError(Integer.divideUnsigned(1, 0));
      } catch (ArithmeticException expected) {
      }
    }

    private static void testRemainderUnsigned() {
      for (int x : interestingValues) {
        for (int y : interestingValues) {
          if (y == 0) continue;

          BigInteger xUnsigned = BigInteger.valueOf(x & 0xffffffffL);
          BigInteger yUnsigned = BigInteger.valueOf(y & 0xffffffffL);
          int expected = xUnsigned.remainder(yUnsigned).intValue();

          assertEquals(expected, Integer.remainderUnsigned(x, y));
        }
      }

      try {
        throw new AssertionError(Integer.divideUnsigned(1, 0));
      } catch (ArithmeticException expected) {
      }
    }
  }
}
