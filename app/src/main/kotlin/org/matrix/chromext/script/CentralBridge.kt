package org.matrix.chromext.script

import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.matrix.chromext.Chrome

/**
 * Browser-side client for [org.matrix.chromext.provider.ScriptContentProvider]. Every call is
 * best-effort: when the module process is unavailable (OEM background limits, force-stop) the
 * caller falls back to the local database.
 */
object CentralBridge {
  private val uri = Uri.parse("content://org.matrix.chromext.scripts")

  private fun call(method: String, extras: Bundle? = null): Bundle? =
      runCatching { Chrome.getContext().contentResolver.call(uri, method, null, extras) }
          .getOrNull()

  fun isAvailable(): Boolean = call("ping")?.getBoolean("available") == true

  fun queryRecords(): List<ScriptRecord>? {
    val bundle = call("queryScripts") ?: return null
    val json = bundle.getString("records") ?: return null
    return runCatching {
      val array = JSONArray(json)
      List(array.length()) { ScriptRecord.fromJson(array.getJSONObject(it)) }
    }
        .getOrNull()
  }

  fun upsert(record: ScriptRecord): Boolean {
    val extras = Bundle().apply { putString("record", record.toJson().toString()) }
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

  fun migrate(records: List<ScriptRecord>): Int {
    val array = JSONArray()
    records.forEach { array.put(it.toJson()) }
    val extras = Bundle().apply { putString("records", array.toString()) }
    return call("migrateLegacyScripts", extras)?.getInt("inserted", 0) ?: 0
  }
}
