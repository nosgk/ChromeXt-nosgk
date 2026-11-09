package org.matrix.chromext.script

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.bridge.JsBridge
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.menu.MenuEntryPolicy
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
  private const val LEGACY_IMPORTED_KEY = "legacy_userscript_imported_v13"
  private const val CACHE_FILE = "chromext-scripts.json"
  private const val REVISION_INTERVAL_MS = 1_000L
  private const val ACTIVATION_FLUSH_MS = 60_000L
  private const val HYDRATE_RETRY_MS = 10 * 60_000L
  private val requireReg = Regex("""(?m)^//\s+@require\s+\S+""")

  // Read from page-injection threads while actions may add/remove scripts from the UI thread.
  val scripts: MutableList<Script> = CopyOnWriteArrayList(loadCache())
  val cosmeticFilters: MutableMap<String, String>
  val userAgents: MutableMap<String, String>
  val cspRules: MutableMap<String, String>
  var keepStorage: Boolean

  /** Whether the central provider answered the last sync; shown by the manager telemetry. */
  @Volatile var providerReady = false
    private set

  @Volatile private var lastRevisionCheck = 0L
  @Volatile private var cachedRevision = -1L
  @Volatile private var lastActivationFlush = 0L
  @Volatile private var lastHookedReport = 0L

  private val pendingActivations = ConcurrentHashMap<String, Long>()
  private val hydrationAttempts = ConcurrentHashMap<String, Long>()
  private val parseCache = ConcurrentHashMap<String, Pair<String, Script>>()

  /** Engine switches pulled from the central config table; defaults match pre-3.10 behavior. */
  @Volatile var engine = EngineConfig()

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

  /**
   * Stores scripts through the central provider. Returns false when the module process could not
   * be reached, so the caller can report a real failure instead of silently losing the script.
   */
  fun insert(vararg script: Script): Boolean {
    var ok = true
    script.forEach {
      val existing = scripts.find { s -> s.id == it.id }
      val toStore = if (existing != null && keepStorage) it.copy(storage = existing.storage) else it
      val record =
          toStore.toRecord().let { r ->
            if (r.lastUpdated == 0L) r.copy(lastUpdated = System.currentTimeMillis()) else r
          }
      if (CentralBridge.upsert(record)) {
        scripts.removeAll(scripts.filter { s -> s.id == it.id })
        scripts.add(toStore.copy(lastUpdated = record.lastUpdated))
      } else {
        Log.e("Fail to store script ${it.id} in the central database.")
        ok = false
      }
    }
    if (ok) {
      saveCache()
      cachedRevision = CentralBridge.revision() ?: cachedRevision
    }
    return ok
  }

  fun delete(ids: Array<String>): Boolean {
    if (ids.isEmpty()) return true
    val ok = CentralBridge.delete(ids)
    scripts.removeAll(scripts.filter { ids.contains(it.id) })
    saveCache()
    if (ok) cachedRevision = CentralBridge.revision() ?: cachedRevision
    return ok
  }

  fun setEnabled(id: String, enabled: Boolean): Boolean {
    val ok = CentralBridge.setEnabled(id, enabled)
    if (ok) {
      val index = scripts.indexOfFirst { it.id == id }
      if (index >= 0) scripts[index] = scripts[index].copy(enabled = enabled)
      saveCache()
      cachedRevision = CentralBridge.revision() ?: cachedRevision
    }
    return ok
  }

  /** Pull the central database when it is reachable; keep the cached list otherwise. */
  fun refreshFromCentral(records: List<ScriptRecord>? = null) {
    val source = records ?: CentralBridge.queryRecords() ?: return
    val loaded = source.mapNotNull { parseRecord(it) }.toMutableList()
    scripts.clear()
    scripts.addAll(loaded)
    saveCache(source)
  }

  /**
   * Cheap change check for the injection path: a revision mismatch triggers a full refresh. This
   * makes manager-side toggles apply on the next page load instead of waiting for onResume or the
   * old 30 second throttle.
   */
  fun refreshIfStale() {
    val now = System.currentTimeMillis()
    if (now - lastRevisionCheck < REVISION_INTERVAL_MS) return
    lastRevisionCheck = now
    Chrome.IO.submit {
      val revision = CentralBridge.revision() ?: return@submit
      if (revision != cachedRevision) syncWithCentral()
    }
  }

  private fun syncWithCentral() {
    // The provider accepts catalog browsers and packages reported in `hooked_app`. A Chromium or
    // WebView host outside the catalog may have lost its startup report (module process not
    // running yet), which made staging/installs fail; retry the report before the availability
    // check, since reportHookedApp is handled before the caller check.
    reportHookedOnce()
    if (!CentralBridge.isAvailable()) {
      providerReady = false
      return
    }
    providerReady = true
    applyConfig(CentralBridge.getConfig() ?: emptyMap())
    importLegacyOnce()
    val central = CentralBridge.queryRecords() ?: return
    cachedRevision = CentralBridge.revision() ?: cachedRevision
    refreshFromCentral(central)
    hydrateMissingRequires(central)
  }

  private fun reportHookedOnce() {
    val now = System.currentTimeMillis()
    if (now - lastHookedReport < 10 * 60_000) return
    val ctx = runCatching { Chrome.getContext() }.getOrNull() ?: return
    val packageName =
        Chrome.packageName ?: runCatching { ctx.packageName }.getOrNull() ?: return
    val ok =
        runCatching {
              val pm = ctx.packageManager
              val info = pm.getApplicationInfo(packageName, 0)
              CentralBridge.reportHookedApp(
                  packageName, pm.getApplicationLabel(info).toString(), Chrome.version ?: "")
            }
            .getOrDefault(false)
    // Only remember a successful report; a provider that was not running yet must be retried on
    // the next sync instead of waiting ten minutes.
    if (ok) lastHookedReport = now
  }

  /**
   * Scripts installed by older builds (or imported before hydration existed) have no `@require`
   * payloads. Ask the module process to download them; the next page load picks the result up.
   * Attempts are throttled so an offline device does not retry on every sync.
   */
  private fun hydrateMissingRequires(records: List<ScriptRecord>) {
    val now = System.currentTimeMillis()
    records.forEach { record ->
      if (record.lib != "[]" || !requireReg.containsMatchIn(record.meta)) return@forEach
      val last = hydrationAttempts[record.id] ?: 0L
      if (now - last < HYDRATE_RETRY_MS) return@forEach
      hydrationAttempts[record.id] = now
      Chrome.IO.submit {
        if (CentralBridge.hydrate(record.id)) hydrationAttempts.remove(record.id)
      }
    }
  }

  /**
   * Upstream ChromeXt wrote scripts into each browser's own `userscript` database. Import it once,
   * read-only, so switching builds does not hide old scripts. This fork never writes that file.
   */
  private fun importLegacyOnce() {
    if (Chrome.packageName == null) return
    val ctx = runCatching { Chrome.getContext() }.getOrNull() ?: return
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(LEGACY_IMPORTED_KEY, false)) return
    val path = ctx.getDatabasePath(ScriptDbHelper.LEGACY_DATABASE_NAME)
    if (!path.exists()) {
      prefs.edit().putBoolean(LEGACY_IMPORTED_KEY, true).apply()
      return
    }
    val result = LegacyDatabase.read(path.path)
    if (result.error != null) return
    val inserted = CentralBridge.migrate(result.records) ?: return
    prefs.edit().putBoolean(LEGACY_IMPORTED_KEY, true).apply()
    Log.i("Imported $inserted scripts from ${path.path}")
  }

  /**
   * Applies the central engine switches. The manager process owns the values; browsers only read
   * them, so a change takes effect on the next sync (page load / revision check).
   */
  private fun applyConfig(values: Map<String, String>) {
    runCatching {
          val updated = EngineConfig.from(values)
          if (updated == engine) return
          val previous = engine
          engine = updated
          keepStorage = updated.keepStorage
          MenuEntryPolicy.erudaEnabled = updated.erudaEnabled
          // Only WebView hosts may touch the WebView API. Calling
          // setWebContentsDebuggingEnabled in a Chromium browser process initializes the WebView
          // provider there and crashes Chrome, and it must also run on the main thread.
          if (WebViewHook.isInit && updated.webViewDebug != previous.webViewDebug) {
            Handler(Looper.getMainLooper())
                .post {
                  runCatching { WebView.setWebContentsDebuggingEnabled(updated.webViewDebug) }
                      .onFailure { Log.ex(it) }
                }
          }
        }
        .onFailure { Log.ex(it) }
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
        if (engine.cspRules && cspRules.contains(origin)) {
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
      if (engine.injectionEnabled) {
        scripts
            .filter {
              it.enabled &&
                  matching(it, url) &&
                  it.appliesTo(Chrome.packageName) &&
                  !(frameId != null && it.noframes)
            }
            .sortedBy { it.sortOrder }
            .forEach {
              if (it.grant.contains("frames")) framesGranted = true
              GM.bootstrap(it, codes)
            }
      }
      if (frameId == null && engine.erudaEnabled && engine.erudaAuto) {
        // Mounts the console on load; the snippet asks the module for the Eruda source the first
        // time and reuses the browser-local cache afterwards.
        codes.add(Local.openEruda)
      }
      if (!asyncEvaluation) Chrome.evaluateJavascript(codes, webView, frameId)
    }
    if (asyncEvaluation)
        Chrome.evaluateJavascript(codes, webView, frameId, bypassSandbox, bypassSandbox)

    if (framesGranted && frameId == null) Chrome.injectFrames(webView)
  }

  fun updateScriptStorage() {
    scripts.forEach {
      if (it.storage != null) {
        val json = it.storage.toString()
        if (!CentralBridge.updateStorage(it.id, json)) {
          Log.e("Updating scriptStorage failed for: " + it.id)
        }
      }
    }
  }

  /** Called by the `scriptActive` action after a script body actually ran in a page. */
  fun reportActivation(id: String) {
    if (id.isBlank()) return
    pendingActivations[id] = System.currentTimeMillis()
    val now = System.currentTimeMillis()
    if (now - lastActivationFlush >= ACTIVATION_FLUSH_MS) flushActivations()
  }

  /** Sends accumulated activation reports to the central database. */
  fun flushActivations() {
    if (pendingActivations.isEmpty()) return
    val packageName = Chrome.packageName ?: return
    val rows =
        pendingActivations.entries.map { (id, timestamp) ->
          ScriptActivation(id, packageName, timestamp, 1)
        }
    pendingActivations.clear()
    lastActivationFlush = System.currentTimeMillis()
    Chrome.IO.submit {
      if (!CentralBridge.reportActivations(rows)) {
        rows.forEach { pendingActivations[it.scriptId] = it.lastActive }
      }
    }
  }

  private fun parseRecord(record: ScriptRecord): Script? {
    val source = record.meta + record.code
    val cached = parseCache[record.id]
    val parsed =
        if (cached != null && cached.first == source) cached.second
        else
            parseScript(source, record.storage, hydrateRequires = false)
                ?.takeIf { it.id == record.id }
                ?.also { parseCache[record.id] = source to it }
    return parsed?.withRecord(record)
  }

  // ---------------------------------------------------------------------------------------------
  // Browser-local cache. The central database is authoritative; this file only keeps injection
  // working when the module process cannot be started (OEM background limits, force-stop).
  // ---------------------------------------------------------------------------------------------

  private fun cacheFile(): File? =
      runCatching { File(Chrome.getContext().filesDir, CACHE_FILE) }.getOrNull()

  private fun loadCache(): MutableList<Script> {
    val file = cacheFile()
    if (file != null && file.isFile) {
      // The cache is authoritative once it exists, even when it is an empty array: a user who
      // deleted every script must not have them resurrected from the legacy database below.
      return runCatching {
            val array = JSONArray(file.readText())
            List(array.length()) { index ->
              parseRecord(ScriptRecord.fromJson(array.getJSONObject(index)))
            }
                .filterNotNull()
                .toMutableList()
          }
          .getOrDefault(mutableListOf<Script>())
    }
    // First run after the upgrade: seed from the legacy browser database read-only, so the very
    // first page load still injects scripts before the provider answers.
    return runCatching {
          val ctx = Chrome.getContext()
          val path = ctx.getDatabasePath(ScriptDbHelper.LEGACY_DATABASE_NAME)
          if (!path.exists()) mutableListOf<Script>()
          else LegacyDatabase.read(path.path).records.mapNotNull { parseRecord(it) }.toMutableList()
        }
        .getOrDefault(mutableListOf<Script>())
  }

  private fun saveCache(records: List<ScriptRecord>? = null) {
    val file = cacheFile() ?: return
    val source = records ?: scripts.map { it.toRecord() }
    runCatching {
          val array = JSONArray()
          source.forEach { array.put(it.toJson()) }
          file.writeText(array.toString())
        }
        .onFailure { Log.d("Cannot write the script cache: ${it.message}") }
  }
}
