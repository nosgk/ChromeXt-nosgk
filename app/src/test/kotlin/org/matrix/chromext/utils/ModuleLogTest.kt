package org.matrix.chromext.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleLogTest {

  @Test
  fun multiLineMessagesBecomeOneLine() {
    assertEquals("a b c", ModuleLog.sanitizeLine("a\nb\r\nc"))
  }

  @Test
  fun longMessagesAreTruncated() {
    val line = ModuleLog.sanitizeLine("x".repeat(2000))
    assertTrue(line.length <= ModuleLog.MAX_LINE + 4)
    assertTrue(line.endsWith("..."))
  }

  @Test
  fun formattedLineCarriesTheTagAndTheMessage() {
    val line = ModuleLog.formatLine(0L, "com.example#42", "hello")
    assertTrue(line.contains("com.example#42"))
    assertTrue(line.endsWith("hello"))
  }
}
