package org.matrix.chromext.script

/**
 * Engine switches shared by the manager process and every hooked browser.
 *
 * They live in the central `config` table (schema v12) instead of SharedPreferences because the
 * manager and the browser are different UIDs: the manager writes through [ScriptStore], and browser
 * processes pull the values over the provider during each sync.
 */
data class EngineConfig(
    val keepStorage: Boolean = true,
    val cspRules: Boolean = true,
    val webViewDebug: Boolean = true,
    val erudaEnabled: Boolean = true,
    val allowPrivileged: Boolean = true,
) {
  fun toMap(): Map<String, String> =
      mapOf(
          KEY_KEEP_STORAGE to keepStorage.toString(),
          KEY_CSP_RULES to cspRules.toString(),
          KEY_WEBVIEW_DEBUG to webViewDebug.toString(),
          KEY_ERUDA to erudaEnabled.toString(),
          KEY_ALLOW_PRIVILEGED to allowPrivileged.toString(),
      )

  companion object {
    const val KEY_KEEP_STORAGE = "keep_storage"
    const val KEY_CSP_RULES = "csp_rules"
    const val KEY_WEBVIEW_DEBUG = "webview_debug"
    const val KEY_ERUDA = "eruda_enabled"
    const val KEY_ALLOW_PRIVILEGED = "allow_privileged"

    fun from(values: Map<String, String>): EngineConfig =
        EngineConfig(
            keepStorage = values[KEY_KEEP_STORAGE].toBooleanOrDefault(true),
            cspRules = values[KEY_CSP_RULES].toBooleanOrDefault(true),
            webViewDebug = values[KEY_WEBVIEW_DEBUG].toBooleanOrDefault(true),
            erudaEnabled = values[KEY_ERUDA].toBooleanOrDefault(true),
            allowPrivileged = values[KEY_ALLOW_PRIVILEGED].toBooleanOrDefault(true),
        )

    private fun String?.toBooleanOrDefault(default: Boolean): Boolean =
        when (this?.trim()?.lowercase()) {
          "true" -> true
          "false" -> false
          else -> default
        }
  }
}
