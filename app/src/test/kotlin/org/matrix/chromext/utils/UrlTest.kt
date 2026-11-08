package org.matrix.chromext.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.chromext.script.Script

class UrlTest {

  private fun script(
      match: Array<String>,
      exclude: Array<String> = emptyArray(),
  ): Script =
      Script(
          id = "ChromeXt:test",
          match = match,
          grant = arrayOf("none"),
          exclude = exclude,
          meta = "// ==UserScript==\n// ==/UserScript==\n",
          code = "",
          storage = null,
          noframes = false,
      )

  @Test
  fun matchesWildcardPatterns() {
    assertTrue(matching(script(arrayOf("https://example.com/*")), "https://example.com/page"))
    assertFalse(matching(script(arrayOf("https://example.com/*")), "https://other.com/page"))
  }

  @Test
  fun excludesWin() {
    assertFalse(
        matching(
            script(
                arrayOf("https://example.com/*"),
                arrayOf("https://example.com/private/*"),
            ),
            "https://example.com/private/page",
        ))
  }

  @Test
  fun customRulesOverrideOriginalRules() {
    val custom = script(arrayOf("https://example.com/*")).copy(customMatch = "https://custom.example/*")
    assertTrue(matching(custom, "https://custom.example/page"))
    assertFalse(matching(custom, "https://example.com/page"))
  }

  @Test
  fun parsesOrigins() {
    assertEquals("https://example.com:8080", parseOrigin("https://example.com:8080/path"))
    assertNull(parseOrigin("chrome://settings"))
  }

  @Test
  fun rejectsUserScriptsFromGithub() {
    assertFalse(isUserScript("https://github.com/user/repo/demo.user.js"))
    assertTrue(isUserScript("https://example.com/demo.user.js"))
  }
}
