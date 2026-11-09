package org.matrix.chromext.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineConfigTest {

  @Test
  fun emptyValuesKeepTheDefaults() {
    val config = EngineConfig.from(emptyMap())
    assertTrue(config.keepStorage)
    assertTrue(config.cspRules)
    assertTrue(config.webViewDebug)
    assertTrue(config.erudaEnabled)
    assertFalse(config.erudaAuto)
    assertTrue(config.allowPrivileged)
    assertTrue(config.injectionEnabled)
    assertTrue(config.installConfirm)
    assertTrue(config.resourceCache)
    assertFalse(config.verboseLog)
  }

  @Test
  fun parsesExplicitValues() {
    val config =
        EngineConfig.from(
            mapOf(
                EngineConfig.KEY_KEEP_STORAGE to "false",
                EngineConfig.KEY_CSP_RULES to "false",
                EngineConfig.KEY_WEBVIEW_DEBUG to "true",
                EngineConfig.KEY_ERUDA to "false",
                EngineConfig.KEY_ERUDA_AUTO to "true",
                EngineConfig.KEY_ALLOW_PRIVILEGED to "false",
                EngineConfig.KEY_INJECTION to "false",
                EngineConfig.KEY_INSTALL_CONFIRM to "false",
                EngineConfig.KEY_RESOURCE_CACHE to "false",
                EngineConfig.KEY_VERBOSE_LOG to "true",
            ))
    assertFalse(config.keepStorage)
    assertFalse(config.cspRules)
    assertTrue(config.webViewDebug)
    assertFalse(config.erudaEnabled)
    assertTrue(config.erudaAuto)
    assertFalse(config.allowPrivileged)
    assertFalse(config.injectionEnabled)
    assertFalse(config.installConfirm)
    assertFalse(config.resourceCache)
    assertTrue(config.verboseLog)
  }

  @Test
  fun roundTripsThroughTheStoredMap() {
    val config = EngineConfig(keepStorage = false, erudaEnabled = false, verboseLog = true)
    assertEquals(config, EngineConfig.from(config.toMap()))
    assertTrue(EngineConfig.from(config.toMap()).verboseLog)
  }

  @Test
  fun unknownValuesFallBackToTheDefault() {
    val config = EngineConfig.from(mapOf(EngineConfig.KEY_KEEP_STORAGE to "nonsense"))
    assertTrue(config.keepStorage)
  }
}
