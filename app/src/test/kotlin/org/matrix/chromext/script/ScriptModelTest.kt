package org.matrix.chromext.script

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptModelTest {

  private fun script(): Script =
      Script(
          id = "x:y",
          match = arrayOf("https://a/*"),
          grant = emptyArray(),
          exclude = arrayOf("https://a/private/*"),
          meta = "",
          code = "",
          storage = null,
          noframes = false,
      )

  @Test
  fun customRulesOverrideOriginalRules() {
    val custom =
        script().copy(
            customMatch = "https://b/*",
            customInclude = "https://c/*",
            customExclude = "https://b/skip/*",
        )
    assertEquals(listOf("https://b/*", "https://c/*"), custom.effectiveMatchRules())
    assertEquals(listOf("https://b/skip/*"), custom.effectiveExcludeRules())
  }

  @Test
  fun emptyOverridesKeepOriginalRules() {
    val original = script()
    assertEquals(listOf("https://a/*"), original.effectiveMatchRules())
    assertEquals(listOf("https://a/private/*"), original.effectiveExcludeRules())
  }
}
