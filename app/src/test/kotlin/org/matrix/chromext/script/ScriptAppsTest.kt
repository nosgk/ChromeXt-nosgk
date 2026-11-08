package org.matrix.chromext.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptAppsTest {

  private fun script(apps: List<String>): Script =
      Script(
          id = "test:demo",
          match = arrayOf("https://example.com/*"),
          grant = emptyArray(),
          exclude = emptyArray(),
          meta = "// ==UserScript==\n// ==/UserScript==\n",
          code = "",
          storage = null,
          noframes = false,
          apps = apps,
      )

  @Test
  fun emptyAppListAppliesEverywhere() {
    assertTrue(script(emptyList()).appliesTo("com.android.chrome"))
    assertTrue(script(emptyList()).appliesTo("com.example.custom"))
  }

  @Test
  fun appListRestrictsInjection() {
    val scoped = script(listOf("com.android.chrome", "com.brave.browser"))
    assertTrue(scoped.appliesTo("com.android.chrome"))
    assertTrue(scoped.appliesTo("com.brave.browser"))
    assertFalse(scoped.appliesTo("com.naver.whale"))
  }

  @Test
  fun unknownPackageIsNeverFilteredOut() {
    assertTrue(script(listOf("com.android.chrome")).appliesTo(null))
  }

  @Test
  fun appsRoundTripThroughTheStoredEncoding() {
    assertEquals(listOf("a.b", "c.d"), parseApps(encodeApps(listOf("a.b", " c.d ", "a.b"))))
    assertEquals(emptyList<String>(), parseApps(""))
  }
}
