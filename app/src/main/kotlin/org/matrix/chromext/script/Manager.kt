package org.matrix.chromext.script

import android.content.Context
import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.bridge.JsBridge
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.invokeMethod
import org.matrix.chromext.utils.isChromeXtFrontEnd
import org.matrix.chromext.utils.isDevToolsFrontEnd
import org.matrix.chromext.utils.isUserScript
import org.matrix.chromext.utils.matching
import org.matrix.chromext.utils.parseOrigin
import org.matrix.chromext.utils.resolveContentUrl
import org.matrix.chromext.utils.shouldBypassSandbox

object ScriptDbManager {

  private const val PREFS = "ChromeXt"
  private const val MIGRATED_KEY = "migrated_to_central_v10"

  // Read from page-injection threads while actions may add/remove scripts from the UI thread.
  val scripts: MutableList<Script> = CopyOnWriteArrayList(query())
  val cosmeticFilters: MutableMap<String, String>
  val userAgents: MutableMap<String, String>
  val cspRules: MutableMap<String, String>
  var keepStorage: Boolean

  @Volatile private var lastRefresh = 0L

  init {
    val ctx = Chrome.getContext()
    @Suppress("UNCHECKED_CAST")
    cosmeticFilters =
        ctx.getSharedPreferences("CosmeticFilter", Context.MODE_PRIVATE).getAll()
            as MutableMap<String, String>
    @Suppress("UNCHECKED_CAST")
    userAgents =
        ctx.getSharedPreferences("UserAgent", Context.MODE_PRIVATE).getAll()
            as MutableMap<String, String>
    @Suppress("UNCHECKED_CAST")
    cspRules =
        ctx.getSharedPreferences("CSPRule", Context.MODE_PRIVATE).getAll()
            as MutableMap<String, String>

    keepStorage =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("keep_storage", true)

    Chrome.IO.submit { syncWithCentral() }
  }

  fun insert(vararg script: Script) {
    val store = ScriptStore(Chrome.getContext())
    script.forEach {
      val existing = scripts.find { s -> s.id == it.id }
      if (existing != null && keepStorage) it.storage = existing.storage
      val record =
          it.toRecord().let { r ->
            if (r.lastUpdated == 0L) r.copy(lastUpdated = System.currentTimeMillis()) else r
          }
      if (store.upsert(record)) {
        scripts.removeAll(scripts.filter { s -> s.id == it.id })
        scripts.add(it.copy(lastUpdated = record.lastUpdated))
        Chrome.IO.submit { CentralBridge.upsert(record) }
      } else {
        Log.e("Fail to store script ${it.id} into SQL database.")
      }
    }
    store.close()
  }

  fun delete(ids: Array<String>) {
    if (ids.isEmpty()) return
    val store = ScriptStore(Chrome.getContext())
    store.delete(ids)
    store.close()
    scripts.removeAll(scripts.filter { ids.contains(it.id) })
    Chrome.IO.submit { CentralBridge.delete(ids) }
  }

  fun setEnabled(id: String, enabled: Boolean) {
    val store = ScriptStore(Chrome.getContext())
    store.setEnabled(id, enabled)
    store.close()
    val index = scripts.indexOfFirst { it.id == id }
    if (index >= 0) scripts[index] = scripts[index].copy(enabled = enabled)
    Chrome.IO.submit { CentralBridge.setEnabled(id, enabled) }
  }

  /** Pull the central database when it is reachable; keep the local list otherwise. */
  fun refreshFromCentral() {
    val records = CentralBridge.queryRecords() ?: return
    val loaded =
        records.mapNotNull { record ->
          val existing = scripts.find { it.id == record.id }
          val parsed =
              parseScript(record.meta + record.code, record.storage, hydrateRequires = false)
          if (parsed == null || parsed.id != record.id) {
            null
          } else {
            parsed.copy(
                enabled = record.enabled,
                sortOrder = record.sortOrder,
                customMatch = record.customMatch,
                customInclude = record.customInclude,
                customExclude = record.customExclude,
                customRunAt = record.customRunAt,
                updateUrl = record.updateUrl,
                iconUrl = record.iconUrl,
                lastUpdated = record.lastUpdated,
                lib = existing?.lib ?: emptyList(),
            )
          }
        }
    scripts.clear()
    scripts.addAll(loaded)
    lastRefresh = System.currentTimeMillis()
  }

  /**
   * Cheap throttle for the injection path: the provider is queried at most once every two seconds,
   * on the IO pool, so a page load never blocks on a binder call.
   */
  fun refreshIfStale() {
    val now = System.currentTimeMillis()
    if (now - lastRefresh < 2000) return
    lastRefresh = now
    Chrome.IO.submit { syncWithCentral() }
  }

  private fun syncWithCentral() {
    if (!CentralBridge.isAvailable()) return
    val prefs = Chrome.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(MIGRATED_KEY, false)) {
      val inserted = CentralBridge.migrate(scripts.map { it.toRecord() })
      Log.i("Migrated $inserted scripts to the central database")
      prefs.edit().putBoolean(MIGRATED_KEY, true).apply()
    }
    refreshFromCentral()
  }

  private fun fixEncoding(url: String, path: String, codes: MutableList<String>) {
    if (path.endsWith(".js") || path.endsWith(".txt")) {
      // Fix encoding for local text files
      val inputStream = Chrome.getContext().contentResolver.openInputStream(Uri.parse(url))
      val text = inputStream?.bufferedReader()?.readText()
      if (text != null) {
        val data = JSONObject(mapOf("utf-8" to text))
        codes.add("window.content=${data};")
        codes.add(Local.encoding)
      }
      inputStream?.close()
    } else if (url.endsWith(".js") || url.endsWith(".txt")) {
      // Fix encoding for remote text files
      codes.add(Local.encoding)
    }

    if (codes.size > 1 && (url.endsWith(".txt") || path.endsWith(".txt")))
        codes.add("fixEncoding();")
  }

  fun invokeScript(url: String, webView: Any? = null, frameId: String? = null) {
    refreshIfStale()
    val codes = mutableListOf<String>(Local.initChromeXt)
    val path = resolveContentUrl(url)
    val webSettings = webView?.invokeMethod { name == "getSettings" }

    var trustedPage = true
    // Whether ChromeXt is accessible in the global context
    var runScripts = false
    // Whether UserScripts are invoked
    var bypassSandbox = false

    fixEncoding(url, path, codes)

    if (isUserScript(url, path)) {
      trustedPage = false
      codes.add(Local.promptInstallUserScript)
      bypassSandbox = shouldBypassSandbox(url)
    } else if (isDevToolsFrontEnd(url)) {
      codes.add(Local.customizeDevTool)
      webSettings?.invokeMethod(null) { name == "setUserAgentString" }
    } else if (!isChromeXtFrontEnd(url)) {
      val origin = parseOrigin(url)
      if (origin != null) {
        if (cspRules.contains(origin)) {
          runCatching {
            val rule = JSONArray(cspRules.get(origin))
            codes.add("Symbol.ChromeXt.cspRules.push(...${rule});${Local.cspRule}")
          }
        }
        if (cosmeticFilters.contains(origin)) {
          runCatching {
            val filter = JSONArray(cosmeticFilters.get(origin))
            codes.add("Symbol.ChromeXt.filters.push(...${filter});${Local.cosmeticFilter}")
          }
        }
        if (userAgents.contains(origin)) {
          val agent = userAgents.get(origin)
          codes.add("Object.defineProperties(window.navigator,{userAgent:{value:'${agent}'}});")
          webSettings?.invokeMethod(agent) { name == "setUserAgentString" }
        }
        trustedPage = false
        runScripts = true
      }
    }

    if (trustedPage) {
      codes.add("globalThis.ChromeXt = Symbol.ChromeXt;")
    } else if (runScripts) {
      codes.add("Symbol.ChromeXt.lock(${Local.key}, '${Local.name}');")
    }
    codes.add("//# sourceURL=${JsBridge.SOURCE_PREFIX}" + if (frameId == null) "" else "/" + frameId)
    webSettings?.invokeMethod(true) { name == "setJavaScriptEnabled" }
    val initScript = codes.joinToString("\n")

    val asyncEvaluation = bypassSandbox || frameId != null
    var framesGranted = false

    if (!asyncEvaluation)
        Chrome.evaluateJavascript(
            listOf(initScript),
            webView,
            frameId,
            bypassSandbox,
            bypassSandbox,
        )
    codes.clear()
    if (asyncEvaluation) codes.add(initScript)
    if (runScripts) {
      scripts
          .filter { it.enabled && matching(it, url) && !(frameId != null && it.noframes) }
          .sortedBy { it.sortOrder }
          .forEach {
            if (it.grant.contains("frames")) framesGranted = true
            GM.bootstrap(it, codes)
          }
      if (!asyncEvaluation) Chrome.evaluateJavascript(codes, webView, frameId)
    }
    if (asyncEvaluation)
        Chrome.evaluateJavascript(codes, webView, frameId, bypassSandbox, bypassSandbox)

    if (framesGranted && frameId == null) Chrome.injectFrames(webView)
  }

  fun updateScriptStorage() {
    val store = ScriptStore(Chrome.getContext())
    scripts.forEach {
      if (it.storage != null) {
        val json = it.storage.toString()
        if (store.updateStorage(it.id, json)) {
          Chrome.IO.submit { CentralBridge.updateStorage(it.id, json) }
        } else {
          Log.e("Updating scriptStorage failed for: " + it.id)
        }
      }
    }
    store.close()
  }

  private fun query(): MutableList<Script> {
    val store = ScriptStore(Chrome.getContext())
    val records = store.records()
    store.close()
    return records
        .mapNotNull { record ->
          val parsed = parseScript(record.meta + record.code, record.storage)
          if (parsed == null || parsed.id != record.id) {
            null
          } else {
            parsed.copy(
                enabled = record.enabled,
                sortOrder = record.sortOrder,
                customMatch = record.customMatch,
                customInclude = record.customInclude,
                customExclude = record.customExclude,
                customRunAt = record.customRunAt,
                updateUrl = record.updateUrl,
                iconUrl = record.iconUrl,
                lastUpdated = record.lastUpdated,
            )
          }
        }
        .toMutableList()
  }
}
