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
    return true
  }

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
    // Reporting is open to any hooked app (including ones the user added to the Xposed scope by
    // hand), so it is validated by package ownership instead of the browser whitelist below.
    if (method == "reportHookedApp") return reportHookedApp(extras ?: Bundle())
    if (!callerAllowed()) {
      throw SecurityException("ChromeXt provider: caller UID is not a supported browser")
    }
    val db = store ?: return null
    val bundle = extras ?: Bundle()
    recordCaller()
    return when (method) {
      "ping" -> Bundle().apply { putBoolean("available", true) }
      "getConfig" -> {
        val json = JSONObject()
        db.config().forEach { (key, value) -> json.put(key, value) }
        Bundle().apply { putString("config", json.toString()) }
      }
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
        Bundle().apply { putBoolean("ok", ok) }
      }
      "toggleScript" -> {
        val id = bundle.getString("id") ?: return Bundle().apply { putBoolean("ok", false) }
        val enabled = bundle.getBoolean("enabled", true)
        val ok = db.setEnabled(id, enabled)
        if (ok) runCatching { ScriptFiles.writeManifest(context!!, db) }
        Bundle().apply { putBoolean("ok", ok) }
      }
      "deleteScripts" -> {
        val ids = bundle.getStringArray("ids") ?: emptyArray<String>()
        ids.forEach { runCatching { ScriptFiles.deleteScriptMirror(context!!, db, it) } }
        db.delete(ids)
        runCatching { ScriptFiles.writeManifest(context!!, db) }
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
        Bundle().apply { putBoolean("ok", ok) }
      }
      "getResource" -> {
        val key = bundle.getString("key") ?: return Bundle()
        val data = ScriptFiles.readPrivateResource(context!!, key) ?: return Bundle()
        Bundle().apply { putByteArray("data", data) }
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
    const val SNAPSHOT_PREFIX = "script-snapshot-"

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
    return Bundle().apply { putBoolean("ok", ok) }
  }

  private fun callerAllowed(): Boolean {
    val uid = Binder.getCallingUid()
    if (uid == Process.myUid()) return true
    val packages = context?.packageManager?.getPackagesForUid(uid) ?: return false
    val allowed = BrowserCatalog.xposedScope.toSet()
    return packages.any { it in allowed }
  }

  /**
   * Serves the script snapshot and the resource cache as file descriptors. A Binder Bundle caps
   * out around 1 MB, which a single large userscript already exceeds, so large payloads never
   * travel through [call].
   */
  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    if (!callerAllowed()) {
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
    val name = bundle.getString("path") ?: return null
    val file = File(File(context!!.filesDir, "inbox"), safeSegment(name))
    return runCatching { file.readText() }
        .onFailure { Log.d("Cannot read inbox file ${file.name}: ${it.message}") }
        .getOrNull()
        .also { file.delete() }
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
