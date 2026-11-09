package org.matrix.chromext.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResourceKeyTest {

  @Test
  fun mapsHostAndPath() {
    assertEquals(
        "http/hoothin.github.io/UserScripts/Pagetual/pagetualRules.json",
        ScriptFiles.resourceKeyForUrl(
            "https://hoothin.github.io/UserScripts/Pagetual/pagetualRules.json"))
  }

  @Test
  fun rejectsUnsupportedSchemes() {
    assertNull(ScriptFiles.resourceKeyForUrl("file:///tmp/demo.js"))
    assertNull(ScriptFiles.resourceKeyForUrl("data:text/plain,hello"))
    assertNull(ScriptFiles.resourceKeyForUrl("ftp://example.com/a"))
  }

  @Test
  fun stripsPathTraversal() {
    val key = ScriptFiles.resourceKeyForUrl("https://example.com/a/../../etc/passwd")!!
    assertFalse(key.contains(".."))
    assertEquals("http/example.com/a/etc/passwd", key)
  }

  @Test
  fun queryStringsGetDistinctKeys() {
    val first = ScriptFiles.resourceKeyForUrl("https://example.com/rules.json?v=1")
    val second = ScriptFiles.resourceKeyForUrl("https://example.com/rules.json?v=2")
    assertNotNull(first)
    assertNotNull(second)
    assertNotEquals(first, second)
    assertEquals(first, ScriptFiles.resourceKeyForUrl("https://example.com/rules.json?v=1"))
  }

  @Test
  fun sanitizeKeyRemovesEmptyAndParentSegments() {
    assertEquals("a/b", ScriptFiles.sanitizeKey("/a/./b/../"))
    assertEquals("misc", ScriptFiles.sanitizeKey("../.."))
  }
}
