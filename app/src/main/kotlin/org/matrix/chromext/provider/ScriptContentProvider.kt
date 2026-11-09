package org.matrix.chromext.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.browser.BrowserCatalog
import org.matrix.chromext.script.ScriptActivation
import org.matrix.chromext.script.ScriptFiles
import org.matrix.chromext.script.ScriptHydrator
import org.matrix.chromext.script.ScriptRecord
import org.matrix.chromext.script.ScriptStore
import org.matrix.chromext.script.toRecord
import org.matrix.chromext.script.toScript
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.ModuleLog

/**
 * Central script database exposed to hooked browser processes.
 *
 * The provider is exported because the browsers are different UIDs, but every call is checked
 * against the UID's installed package names. A caller that is not a supported browser is rejected
 * before it can read scripts or GM storage.
 */
class ScriptContentProvider : ContentProvider() {

  private var store: ScriptStore? = null

  override fun onCreate(): Boolean {
    store = ScriptStore(context!!)
    // The provider runs in the module process, so its own log lines go straight to the file.
    ModuleLog.attach(context!!)
    return true
  }

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
    val bundle = extras ?: Bundle()
    // Reporting is open to any hooked app (including ones the user added to the Xposed scope by
    // hand), so it is validated by package ownership instead of the browser whitelist below.
    if (method == "reportHookedApp") return reportHookedApp(bundle)
    // Read-only metadata answered before the caller check: a hooked host whose script access is
    // still denied must be able to read the verbose-log switch (that is exactly when its log is
    // needed) and ship its own log lines. Neither method exposes script rows and neither records
    // the caller as hooked, so this cannot be used to gain script access.
    when (method) {
      "getConfig" -> return configBundle()
      "appendLog" -> return appendLog(bundle)
    }
    if (!callerAllowed(method)) {
      throw SecurityException("ChromeXt provider: caller UID is not a supported browser")
    }
    val db = store ?: return null
    recordCaller()
    return when (method) {
      "ping" -> Bundle().apply { putBoolean("available", true) }
      "queryScripts" -> {
        val array = JSONArray()
        db.records().forEach { array.put(it.toJson()) }
        // Scripts can be hundreds of kilobytes; a Binder Bundle caps out around 1 MB, so the
        // browser reads the snapshot through openFile() instead of a string extra. Each call gets
        // its own file: another browser may still be reading the previous snapshot, and deleting
        // an open file is harmless on Linux.
        val name = SNAPSHOT_PREFIX + System.currentTimeMillis() + ".json"
        val file = File(context!!.filesDir, name)
        file.writeText(array.toString())
        context!!.filesDir
            .listFiles { candidate -> candidate.name.startsWith(SNAPSHOT_PREFIX) }
            ?.filter {
              it.name != name && System.currentTimeMillis() - it.lastModified() > 60_000
            }
            ?.forEach { runCatching { it.delete() } }
        Log.d("provider queryScripts: ${array.length()} records -> $name")
        Bundle().apply {
          putString("uri", "content://org.matrix.chromext.scripts/file/$name")
        }
      }
      "upsertScript" -> {
        val json = readInbox(bundle) ?: bundle.getString("record")
        val parsed =
            json?.let { runCatching { ScriptRecord.fromJson(JSONObject(it)) }.getOrNull() }
        val ok = parsed != null && db.upsert(parsed)
        if (ok && parsed != null) {
          runCatching { ScriptFiles.mirrorScript(context!!, db, parsed) }
          runCatching { ScriptFiles.writeManifest(context!!, db) }
        }
        Log.d("provider upsertScript: ${parsed?.id ?: "unparsable"} ok=$ok")
        Bundle().apply { putBoolean("ok", ok) }
      }
      "toggleScript" -> {
        val id = bundle.getString("id") ?: return Bundle().apply { putBoolean("ok", false) }
        val enabled = bundle.getBoolean("enabled", true)
        val ok = db.setEnabled(id, enabled)
        if (ok) runCatching { ScriptFiles.writeManifest(context!!, db) }
        Log.d("provider toggleScript: $id -> $enabled ok=$ok")
        Bundle().apply { putBoolean("ok", ok) }
      }
      "deleteScripts" -> {
        val ids = bundle.getStringArray("ids") ?: emptyArray<String>()
        ids.forEach { runCatching { ScriptFiles.deleteScriptMirror(context!!, db, it) } }
        db.delete(ids)
        runCatching { ScriptFiles.writeManifest(context!!, db) }
        Log.d("provider deleteScripts: ${ids.size} ids")
        Bundle().apply { putBoolean("ok", true) }
      }
      "updateStorage" -> {
        val id = bundle.getString("id") ?: return Bundle().apply { putBoolean("ok", false) }
        val storage =
            bundle.getString("storage") ?: return Bundle().apply { putBoolean("ok", false) }
        Bundle().apply { putBoolean("ok", db.updateStorage(id, storage)) }
      }
      "migrateLegacyScripts" -> {
        val json = readInbox(bundle) ?: bundle.getString("records")
        if (json == null) return Bundle().apply { putInt("inserted", 0) }
        var inserted = 0
        runCatching {
          val array = JSONArray(json)
          for (i in 0 until array.length()) {
            val record = ScriptRecord.fromJson(array.getJSONObject(i))
            if (db.insertIfAbsent(record)) {
              inserted += 1
              runCatching { ScriptFiles.mirrorScript(context!!, db, record) }
            }
          }
          if (inserted > 0) ScriptFiles.writeManifest(context!!, db)
        }
        Bundle().apply { putInt("inserted", inserted) }
      }
      "revision" -> Bundle().apply { putLong("revision", db.revision()) }
      "hydrateScript" -> {
        val id = bundle.getString("id") ?: return Bundle().apply { putBoolean("ok", false) }
        val script = db.record(id)?.toScript()
        val ok =
            script != null &&
                runCatching {
                      val hydrated = ScriptHydrator.hydrate(context!!, script)
                      val record = hydrated.toRecord()
                      if (db.upsert(record)) {
                        ScriptFiles.mirrorScript(context!!, db, record)
                        ScriptFiles.writeManifest(context!!, db)
                        true
                      } else false
                    }
                    .getOrDefault(false)
        Log.d("provider hydrateScript: $id ok=$ok")
        Bundle().apply { putBoolean("ok", ok) }
      }
      "stageInstall" -> {
        // The browser cannot pass an 800 KB script through an Intent extra or a Binder Bundle.
        // Keep the payload in the module's private directory and hand the manager screen a token.
        val source = inboxFile(bundle)
        if (source == null) {
          Log.e("provider stageInstall: inbox payload missing")
          return Bundle()
        }
        val size = source.length()
        val pending = File(context!!.filesDir, PENDING_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        pending
            .listFiles()
            ?.filter { now - it.lastModified() > PENDING_TTL_MS }
            ?.forEach { runCatching { it.delete() } }
        val token = "pending-$now-" + Integer.toHexString((0..0xffff).random())
        val target = File(pending, "$token.user.js")
        val ok =
            runCatching {
                  source.copyTo(target, overwrite = true)
                  true
                }
                .onFailure { Log.ex(it, "provider stageInstall: cannot copy ${source.name}") }
                .getOrDefault(false)
        source.delete()
        if (ok) {
          Log.d("provider stageInstall: token=$token size=$size")
          Bundle().apply { putString("token", token) }
        } else {
          Log.e("provider stageInstall: failed for ${target.name}")
          Bundle()
        }
      }
      "getResource" -> {
        val key = bundle.getString("key") ?: return Bundle()
        val file = ScriptFiles.privateResourceFile(context!!, key)
        val data = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return Bundle()
        Bundle().apply {
          putByteArray("data", data)
          putLong("modified", file.lastModified())
        }
      }
      "putResource" -> {
        val key = bundle.getString("key") ?: return Bundle().apply { putBoolean("ok", false) }
        val data = bundle.getByteArray("data") ?: return Bundle().apply { putBoolean("ok", false) }
        if (data.size > MAX_RESOURCE_BYTES) return Bundle().apply { putBoolean("ok", false) }
        val mime = bundle.getString("mime") ?: ""
        val ok = ScriptFiles.writePrivateResource(context!!, key, data)
        // Only mirror static-looking assets into the public directory; caching every API response
        // there would flood Download/ChromeXT/resources.
        if (ok && isMirrorable(key)) {
          runCatching { ScriptFiles.mirrorHttpResource(context!!, key, data, mime) }
        }
        Bundle().apply { putBoolean("ok", ok) }
      }
      "reportActivations" -> {
        val json = bundle.getString("activations") ?: return Bundle().apply { putBoolean("ok", false) }
        runCatching {
              val array = JSONArray(json)
              val rows = mutableListOf<ScriptActivation>()
              for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                rows.add(
                    ScriptActivation(
                        scriptId = item.getString("scriptId"),
                        packageName = item.getString("package"),
                        lastActive = item.optLong("lastActive"),
                        count = item.optLong("count"),
                    ))
              }
              db.reportActivations(rows)
            }
            .onFailure { return Bundle().apply { putBoolean("ok", false) } }
        Bundle().apply { putBoolean("ok", true) }
      }
      "queryActivations" -> {
        val array = JSONArray()
        db.activations().forEach { row ->
          array.put(
              JSONObject().apply {
                put("scriptId", row.scriptId)
                put("package", row.packageName)
                put("lastActive", row.lastActive)
                put("count", row.count)
              })
        }
        Bundle().apply { putString("activations", array.toString()) }
      }
      else -> null
    }
  }

  private companion object {
    const val MAX_RESOURCE_BYTES = 512 * 1024
    const val MAX_LOG_CHARS = 16 * 1024
    const val SNAPSHOT_PREFIX = "script-snapshot-"
    const val PENDING_DIR = "pending"
    const val PENDING_TTL_MS = 24 * 60 * 60 * 1000L

    private val STATIC_SUFFIXES =
        listOf(
            ".json", ".js", ".css", ".txt", ".xml", ".html", ".png", ".jpg", ".jpeg", ".gif",
            ".svg", ".webp", ".woff", ".woff2")

    fun isMirrorable(key: String): Boolean {
      val name = key.substringAfterLast('/').lowercase()
      return STATIC_SUFFIXES.any { name.endsWith(it) }
    }
  }

  private var lastCallerReport = 0L

  /** Engine switches only; deliberately readable before the caller check (see [call]). */
  private fun configBundle(): Bundle? {
    val db = store ?: return null
    val json = JSONObject()
    db.config().forEach { (key, value) -> json.put(key, value) }
    return Bundle().apply { putString("config", json.toString()) }
  }

  /**
   * Appends a batch of log lines from a hooked app. Also readable before the caller check: a host
   * whose script access is denied is exactly the host whose log is worth having. The batch size is
   * bounded and the caller package is resolved from the Binder UID, never from the payload.
   */
  private fun appendLog(bundle: Bundle): Bundle {
    val lines = bundle.getString("lines")
    val ctx = context
    if (lines.isNullOrBlank() || ctx == null) return Bundle().apply { putBoolean("ok", false) }
    val uid = Binder.getCallingUid()
    val caller = ctx.packageManager?.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
    val body = if (lines.length <= MAX_LOG_CHARS) lines else lines.take(MAX_LOG_CHARS)
    ModuleLog.write(
        ctx,
        listOf("# ${System.currentTimeMillis()} from $caller ($uid)") + body.split('\n'))
    return Bundle().apply { putBoolean("ok", true) }
  }

  /**
   * Any authorized browser that talks to the provider is, by definition, running the module. Record
   * it so the manager's app list reflects reality even when the explicit report was lost (provider
   * not started yet, process killed before the IO task ran).
   */
  private fun recordCaller() {
    val uid = Binder.getCallingUid()
    if (uid == Process.myUid()) return
    // getResource/putResource run for every GM_xmlhttpRequest; do not write to SQLite that often.
    val now = System.currentTimeMillis()
    if (now - lastCallerReport < 60_000) return
    lastCallerReport = now
    val packages = context?.packageManager?.getPackagesForUid(uid) ?: return
    val packageName =
        packages.firstOrNull { it in BrowserCatalog.xposedScope } ?: packages.firstOrNull() ?: return
    store?.reportHookedApp(packageName, "", "")
  }

  private fun reportHookedApp(bundle: Bundle): Bundle {
    val packageName =
        bundle.getString("package") ?: return Bundle().apply { putBoolean("ok", false) }
    val uid = Binder.getCallingUid()
    val owned = context?.packageManager?.getPackagesForUid(uid) ?: emptyArray()
    // Only the app itself may report that it was hooked; otherwise any process could pollute the
    // list with packages it does not own.
    if (packageName !in owned) return Bundle().apply { putBoolean("ok", false) }
    val label = bundle.getString("label") ?: ""
    val versionName = bundle.getString("version") ?: ""
    val ok = store?.reportHookedApp(packageName, label, versionName) ?: false
    if (ok) allowedPackages += packageName
    Log.d("provider reportHookedApp: $packageName ok=$ok")
    return Bundle().apply { putBoolean("ok", ok) }
  }

  private val allowedPackages: MutableSet<String> =
      java.util.Collections.newSetFromMap(
          java.util.concurrent.ConcurrentHashMap<String, Boolean>())

  private fun callerAllowed(method: String = "openFile"): Boolean {
    val uid = Binder.getCallingUid()
    if (uid == Process.myUid()) return true
    val packages = context?.packageManager?.getPackagesForUid(uid)
    if (packages.isNullOrEmpty()) {
      Log.e("provider rejected $method: uid $uid owns no package")
      return false
    }
    val catalog = BrowserCatalog.xposedScope.toSet()
    // Browsers are always allowed. Other scoped hosts (WebView apps the user hooked by hand, for
    // example a note-taking app) are allowed once the module has reported itself inside them;
    // reportHookedApp validates that the caller owns the package, so this cannot be claimed by an
    // unrelated app. Before 3.15 those apps relied on a browser-local database copy.
    val allowed =
        packages.any { packageName ->
          if (packageName in catalog || packageName in allowedPackages) return@any true
          val hooked = store?.isHookedApp(packageName) == true
          if (hooked) allowedPackages += packageName
          hooked
        }
    if (!allowed) {
      Log.e(
          "provider rejected $method: uid $uid packages ${packages.joinToString(",")} not hooked")
    }
    return allowed
  }

  /**
   * Serves the script snapshot and the resource cache as file descriptors. A Binder Bundle caps
   * out around 1 MB, which a single large userscript already exceeds, so large payloads never
   * travel through [call].
   */
  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    if (!callerAllowed("openFile")) {
      throw SecurityException("ChromeXt provider: caller UID is not a supported browser")
    }
    val segments = uri.pathSegments
    if (segments.size < 2) return null
    val file =
        when (segments[0]) {
          "file" -> {
            if (!segments[1].startsWith(SNAPSHOT_PREFIX)) return null
            File(context!!.filesDir, safeSegment(segments[1]))
          }
          "inbox" -> {
            if (!mode.contains('w')) return null
            File(File(context!!.filesDir, "inbox"), safeSegment(segments[1]))
          }
          "resource" ->
              ScriptFiles.privateResourceFile(
                  context!!, segments.drop(1).joinToString("/"))
          else -> return null
        }
    file.parentFile?.mkdirs()
    val flags =
        if (mode.contains('w')) {
          ParcelFileDescriptor.MODE_READ_WRITE or
              ParcelFileDescriptor.MODE_CREATE or
              ParcelFileDescriptor.MODE_TRUNCATE
        } else ParcelFileDescriptor.MODE_READ_ONLY
    return ParcelFileDescriptor.open(file, flags)
  }

  /** Reads and removes a file the browser uploaded before calling upsert/migrate. */
  private fun readInbox(bundle: Bundle): String? {
    val file = inboxFile(bundle) ?: return null
    return runCatching { file.readText() }
        .onFailure { Log.d("Cannot read inbox file ${file.name}: ${it.message}") }
        .getOrNull()
        .also { file.delete() }
  }

  /** The uploaded inbox file, without reading it (staging copies it instead). */
  private fun inboxFile(bundle: Bundle): File? {
    val name = bundle.getString("path") ?: return null
    val file = File(File(context!!.filesDir, "inbox"), safeSegment(name))
    return file.takeIf { it.isFile }
  }

  private fun safeSegment(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

  override fun query(
      uri: Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
      uri: Uri,
      values: ContentValues?,
      selection: String?,
      selectionArgs: Array<out String>?,
  ): Int = 0
}
