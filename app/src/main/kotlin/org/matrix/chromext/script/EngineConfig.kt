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
    val erudaAuto: Boolean = false,
    val allowPrivileged: Boolean = true,
    val injectionEnabled: Boolean = true,
    val installConfirm: Boolean = true,
    /** Serve GM_xmlhttpRequest GETs from Download/ChromeXT/resources and cache successful ones. */
    val resourceCache: Boolean = true,
) {
  fun toMap(): Map<String, String> =
      mapOf(
          KEY_KEEP_STORAGE to keepStorage.toString(),
          KEY_CSP_RULES to cspRules.toString(),
          KEY_WEBVIEW_DEBUG to webViewDebug.toString(),
          KEY_ERUDA to erudaEnabled.toString(),
          KEY_ERUDA_AUTO to erudaAuto.toString(),
          KEY_ALLOW_PRIVILEGED to allowPrivileged.toString(),
          KEY_INJECTION to injectionEnabled.toString(),
          KEY_INSTALL_CONFIRM to installConfirm.toString(),
          KEY_RESOURCE_CACHE to resourceCache.toString(),
      )

  companion object {
    const val KEY_KEEP_STORAGE = "keep_storage"
    const val KEY_CSP_RULES = "csp_rules"
    const val KEY_WEBVIEW_DEBUG = "webview_debug"
    const val KEY_ERUDA = "eruda_enabled"
    const val KEY_ERUDA_AUTO = "eruda_auto"
    const val KEY_ALLOW_PRIVILEGED = "allow_privileged"
    const val KEY_INJECTION = "injection_enabled"
    const val KEY_INSTALL_CONFIRM = "install_confirm"
    const val KEY_RESOURCE_CACHE = "resource_cache"

    fun from(values: Map<String, String>): EngineConfig =
        EngineConfig(
            keepStorage = values[KEY_KEEP_STORAGE].toBooleanOrDefault(true),
            cspRules = values[KEY_CSP_RULES].toBooleanOrDefault(true),
            webViewDebug = values[KEY_WEBVIEW_DEBUG].toBooleanOrDefault(true),
            erudaEnabled = values[KEY_ERUDA].toBooleanOrDefault(true),
            erudaAuto = values[KEY_ERUDA_AUTO].toBooleanOrDefault(false),
            allowPrivileged = values[KEY_ALLOW_PRIVILEGED].toBooleanOrDefault(true),
            injectionEnabled = values[KEY_INJECTION].toBooleanOrDefault(true),
            installConfirm = values[KEY_INSTALL_CONFIRM].toBooleanOrDefault(true),
            resourceCache = values[KEY_RESOURCE_CACHE].toBooleanOrDefault(true),
        )

    private fun String?.toBooleanOrDefault(default: Boolean): Boolean =
        when (this?.trim()?.lowercase()) {
          "true" -> true
          "false" -> false
          else -> default
        }
  }
}
