package org.matrix.chromext.script

import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.matrix.chromext.Chrome

/**
 * Browser-side client for [org.matrix.chromext.provider.ScriptContentProvider]. Every call is
 * best-effort: when the module process is unavailable (OEM background limits, force-stop) the
 * caller falls back to the cached script list.
 *
 * Large payloads (the whole script list, a single large script) never travel through a Binder
 * Bundle: the provider exposes files through `openFile()` and the browser reads/writes them
 * directly.
 */
data class CachedResource(val bytes: ByteArray, val modified: Long)

object CentralBridge {
  private const val AUTHORITY = "content://org.matrix.chromext.scripts"
  private val uri = Uri.parse(AUTHORITY)

  private fun call(method: String, extras: Bundle? = null): Bundle? =
      runCatching { Chrome.getContext().contentResolver.call(uri, method, null, extras) }
          .getOrNull()

  private fun inboxUri(name: String): Uri =
      Uri.parse(AUTHORITY).buildUpon().appendPath("inbox").appendPath(name).build()

  /** Writes [json] into the provider's inbox; the returned name is passed as `path`. */
  private fun writeInbox(json: String): String? {
    val name = "upload-" + System.nanoTime() + ".json"
    return runCatching {
          Chrome.getContext().contentResolver.openOutputStream(inboxUri(name))?.use {
            it.write(json.toByteArray(Charsets.UTF_8))
          } ?: return@runCatching null
          name
        }
        .getOrNull()
  }

  fun isAvailable(): Boolean = call("ping")?.getBoolean("available") == true

  fun getConfig(): Map<String, String>? {
    val json = call("getConfig")?.getString("config") ?: return null
    return runCatching {
      val objectValue = org.json.JSONObject(json)
      objectValue.keys().asSequence().associateWith { objectValue.optString(it) }
    }
        .getOrNull()
  }

  fun queryRecords(): List<ScriptRecord>? {
    val target = call("queryScripts")?.getString("uri") ?: return null
    return runCatching {
          Chrome.getContext().contentResolver.openInputStream(Uri.parse(target))?.use { input ->
            val array = JSONArray(input.bufferedReader().readText())
            List(array.length()) { ScriptRecord.fromJson(array.getJSONObject(it)) }
          }
        }
        .getOrNull()
  }

  fun upsert(record: ScriptRecord): Boolean {
    val name = writeInbox(record.toJson().toString()) ?: return false
    val extras = Bundle().apply { putString("path", name) }
    return call("upsertScript", extras)?.getBoolean("ok") == true
  }

  fun delete(ids: Array<String>): Boolean {
    val extras = Bundle().apply { putStringArray("ids", ids) }
    return call("deleteScripts", extras)?.getBoolean("ok") == true
  }

  fun setEnabled(id: String, enabled: Boolean): Boolean {
    val extras =
        Bundle().apply {
          putString("id", id)
          putBoolean("enabled", enabled)
        }
    return call("toggleScript", extras)?.getBoolean("ok") == true
  }

  fun updateStorage(id: String, storage: String): Boolean {
    val extras =
        Bundle().apply {
          putString("id", id)
          putString("storage", storage)
        }
    return call("updateStorage", extras)?.getBoolean("ok") == true
  }

  /** Cheap change counter; browsers only pull the full list when it differs from their copy. */
  fun revision(): Long? = call("revision")?.getLong("revision")

  /** Downloads `@require`/`@resource` payloads in the module process after a page install. */
  fun hydrate(id: String): Boolean {
    val extras = Bundle().apply { putString("id", id) }
    return call("hydrateScript", extras)?.getBoolean("ok") == true
  }

  fun getResource(key: String): CachedResource? {
    val bundle = call("getResource", Bundle().apply { putString("key", key) }) ?: return null
    val data = bundle.getByteArray("data") ?: return null
    return CachedResource(data, bundle.getLong("modified"))
  }

  fun putResource(key: String, bytes: ByteArray, mime: String = ""): Boolean {
    val extras =
        Bundle().apply {
          putString("key", key)
          putByteArray("data", bytes)
          putString("mime", mime)
        }
    return call("putResource", extras)?.getBoolean("ok") == true
  }

  fun reportActivations(rows: List<ScriptActivation>): Boolean {
    if (rows.isEmpty()) return true
    val array = JSONArray()
    rows.forEach { row ->
      array.put(
          org.json.JSONObject().apply {
            put("scriptId", row.scriptId)
            put("package", row.packageName)
            put("lastActive", row.lastActive)
            put("count", row.count)
          })
    }
    val extras = Bundle().apply { putString("activations", array.toString()) }
    return call("reportActivations", extras)?.getBoolean("ok") == true
  }

  fun queryActivations(): List<ScriptActivation>? {
    val json = call("queryActivations")?.getString("activations") ?: return null
    return runCatching {
      val array = JSONArray(json)
      List(array.length()) { index ->
        val item = array.getJSONObject(index)
        ScriptActivation(
            scriptId = item.getString("scriptId"),
            packageName = item.getString("package"),
            lastActive = item.optLong("lastActive"),
            count = item.optLong("count"),
        )
      }
    }
        .getOrNull()
  }

  /** Returns the number of inserted rows, or null when the provider is unreachable. */
  fun migrate(records: List<ScriptRecord>): Int? {
    if (records.isEmpty()) return 0
    val array = JSONArray()
    records.forEach { array.put(it.toJson()) }
    val name = writeInbox(array.toString()) ?: return null
    val extras = Bundle().apply { putString("path", name) }
    return call("migrateLegacyScripts", extras)?.getInt("inserted")
  }

  /**
   * Records that this process was hooked. The provider validates that the caller owns the package,
   * so a script cannot claim an app it does not belong to.
   */
  fun reportHookedApp(packageName: String, label: String, versionName: String): Boolean {
    val extras =
        Bundle().apply {
          putString("package", packageName)
          putString("label", label)
          putString("version", versionName)
        }
    return call("reportHookedApp", extras)?.getBoolean("ok") == true
  }
}
