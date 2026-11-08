package org.matrix.chromext.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ReflectTest {

  @Test
  fun ranksShortNamesInDeclarationOrder() {
    assertEquals(0, r8Rank("a"))
    assertEquals(25, r8Rank("z"))
    assertEquals(26, r8Rank("A"))
    assertEquals(51, r8Rank("Z"))
    assertEquals(52, r8Rank("a0"))
  }

  @Test
  fun nonObfuscatedNamesRankLast() {
    assertEquals(Int.MAX_VALUE, r8Rank("get_url"))
  }
}
