package org.matrix.chromext.script

import android.content.ContentValues
import android.content.Context
import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.CursorWindow
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import org.json.JSONObject

/** Grants that imply the script keeps data in GM_storage. */
private val STORAGE_GRANTS =
    setOf(
        "GM_setValue",
        "GM_getValue",
        "GM_listValues",
        "GM_addValueChangeListener",
        "GM_removeValueChangeListener",
        "GM_cookie",
        "GM.setValue",
        "GM.getValue",
        "GM.listValues",
        "GM.addValueChangeListener",
        "GM.removeValueChangeListener",
        "GM.cookie",
    )

/** Raw database row. Parsing stays in the browser process, so the provider never downloads @require. */
data class ScriptRecord(
    val id: String,
    val meta: String,
    val code: String,
    val storage: String?,
    val enabled: Boolean,
    val sortOrder: Int,
    val customMatch: String,
    val customInclude: String,
    val customExclude: String,
    val customRunAt: String,
    val updateUrl: String,
    val iconUrl: String,
    val lastUpdated: Long,
    /** Comma separated package names; empty means every hooked app. */
    val apps: String = "",
) {
  fun toJson(): JSONObject =
      JSONObject().apply {
        put("id", id)
        put("meta", meta)
        put("code", code)
        put("storage", storage ?: JSONObject.NULL)
        put("enabled", enabled)
        put("sortOrder", sortOrder)
        put("customMatch", customMatch)
        put("customInclude", customInclude)
        put("customExclude", customExclude)
        put("customRunAt", customRunAt)
        put("updateUrl", updateUrl)
        put("iconUrl", iconUrl)
        put("lastUpdated", lastUpdated)
        put("apps", apps)
      }

  companion object {
    fun fromJson(json: JSONObject): ScriptRecord =
        ScriptRecord(
            id = json.getString("id"),
            meta = json.getString("meta"),
            code = json.getString("code"),
            storage = if (json.isNull("storage")) null else json.optString("storage"),
            enabled = json.optBoolean("enabled", true),
            sortOrder = json.optInt("sortOrder", 0),
            customMatch = json.optString("customMatch"),
            customInclude = json.optString("customInclude"),
            customExclude = json.optString("customExclude"),
            customRunAt = json.optString("customRunAt"),
            updateUrl = json.optString("updateUrl"),
            iconUrl = json.optString("iconUrl"),
            lastUpdated = json.optLong("lastUpdated", 0L),
            apps = json.optString("apps"),
        )
  }
}

fun Script.toRecord(): ScriptRecord =
    ScriptRecord(
        id = id,
        meta = meta,
        code = code,
        storage = storage?.toString(),
        enabled = enabled,
        sortOrder = sortOrder,
        customMatch = customMatch,
        customInclude = customInclude,
        customExclude = customExclude,
        customRunAt = customRunAt,
        updateUrl = updateUrl,
        iconUrl = iconUrl,
        lastUpdated = lastUpdated,
        apps = encodeApps(apps),
    )

/** Thin CRUD over the `script` table, usable from both the browser and the provider process. */
class ScriptStore(context: Context) {

  private val appContext = context.applicationContext
  private val helper = ScriptDbHelper(context)

  fun records(): List<ScriptRecord> {
    val db = helper.readableDatabase
    // Do not rely on ORDER BY: an interrupted v9 -> v10 migration may leave the column missing,
    // while the reader can still fall back to defaults. Sort after reading instead.
    val cursor = db.query("script", null, null, null, null, null, null)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      (cursor as AbstractWindowedCursor).setWindow(CursorWindow("max", 1024 * 1024 * 10L))
    }
    val result = mutableListOf<ScriptRecord>()
    try {
      while (cursor.moveToNext()) {
        result.add(
            ScriptRecord(
                id = cursor.string("id"),
                meta = cursor.string("meta"),
                code = cursor.string("code"),
                storage = cursor.stringOrNull("storage"),
                enabled = cursor.int("enabled", 1) != 0,
                sortOrder = cursor.int("sort_order", 0),
                customMatch = cursor.string("custom_match"),
                customInclude = cursor.string("custom_include"),
                customExclude = cursor.string("custom_exclude"),
                customRunAt = cursor.string("custom_run_at"),
                updateUrl = cursor.string("update_url"),
                iconUrl = cursor.string("icon_url"),
                lastUpdated = cursor.long("last_updated", 0L),
                apps = cursor.string("apps"),
            ))
      }
    } finally {
      cursor.close()
    }
    return result.sortedWith(compareBy({ it.sortOrder }, { it.id }))
  }

  fun upsert(record: ScriptRecord): Boolean {
    val db = helper.writableDatabase
    val values =
        ContentValues().apply {
          put("id", record.id)
          put("meta", record.meta)
          put("code", record.code)
          put("storage", record.storage)
          put("enabled", if (record.enabled) 1 else 0)
          put("sort_order", record.sortOrder)
          put("custom_match", record.customMatch)
          put("custom_include", record.customInclude)
          put("custom_exclude", record.customExclude)
          put("custom_run_at", record.customRunAt)
          put("update_url", record.updateUrl)
          put("icon_url", record.iconUrl)
          put("last_updated", record.lastUpdated)
          put("apps", record.apps)
        }
    return db.insertWithOnConflict("script", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L
  }

  /** Migration helper: never overwrite an existing row. */
  fun insertIfAbsent(record: ScriptRecord): Boolean {
    val db = helper.writableDatabase
    val values =
        ContentValues().apply {
          put("id", record.id)
          put("meta", record.meta)
          put("code", record.code)
          put("storage", record.storage)
          put("enabled", if (record.enabled) 1 else 0)
          put("sort_order", record.sortOrder)
          put("custom_match", record.customMatch)
          put("custom_include", record.customInclude)
          put("custom_exclude", record.customExclude)
          put("custom_run_at", record.customRunAt)
          put("update_url", record.updateUrl)
          put("icon_url", record.iconUrl)
          put("last_updated", record.lastUpdated)
          put("apps", record.apps)
        }
    return db.insertWithOnConflict("script", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
  }

  fun setEnabled(id: String, enabled: Boolean): Boolean {
    val values = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
    return helper.writableDatabase.update("script", values, "id = ?", arrayOf(id)) > 0
  }

  fun updateStorage(id: String, storage: String): Boolean {
    val values = ContentValues().apply { put("storage", storage) }
    return helper.writableDatabase.update("script", values, "id = ?", arrayOf(id)) > 0
  }

  fun delete(id: String): Boolean =
      helper.writableDatabase.delete("script", "id = ?", arrayOf(id)) > 0

  fun delete(ids: Array<String>): Int {
    if (ids.isEmpty()) return 0
    val placeholders = ids.joinToString(",") { "?" }
    return helper.writableDatabase.delete("script", "id IN ($placeholders)", ids)
  }

  fun close() {
    helper.close()
  }

  /** Apps the module has actually been injected into, most recently seen first. */
  fun hookedApps(): List<HookedApp> {
    val cursor = helper.readableDatabase.query("hooked_app", null, null, null, null, null, null)
    val result = mutableListOf<HookedApp>()
    try {
      while (cursor.moveToNext()) {
        result.add(
            HookedApp(
                packageName = cursor.string("package_name"),
                label = cursor.string("label"),
                versionName = cursor.string("version_name"),
                lastSeen = cursor.long("last_seen", 0L),
            ))
      }
    } finally {
      cursor.close()
    }
    return result.sortedByDescending { it.lastSeen }
  }

  fun reportHookedApp(
      packageName: String,
      label: String,
      versionName: String,
      lastSeen: Long = System.currentTimeMillis(),
  ): Boolean {
    if (packageName.isBlank()) return false
    val db = helper.writableDatabase
    val values =
        ContentValues().apply {
          put("package_name", packageName)
          put("label", label)
          put("version_name", versionName)
          put("last_seen", lastSeen)
        }
    db.insertWithOnConflict("hooked_app", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    // A plain provider call only refreshes last_seen; the explicit report from the hooked process
    // fills in label/version, so never overwrite a known label with an empty one.
    val update =
        ContentValues().apply {
          put("last_seen", lastSeen)
          if (label.isNotBlank()) put("label", label)
          if (versionName.isNotBlank()) put("version_name", versionName)
        }
    return db.update("hooked_app", update, "package_name = ?", arrayOf(packageName)) > 0
  }

  fun config(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    helper.readableDatabase
        .query("config", arrayOf("key", "value"), null, null, null, null, null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            result[cursor.getString(0)] = cursor.getString(1)
          }
        }
    return result
  }

  fun putConfig(key: String, value: String): Boolean {
    val values = ContentValues().apply { put("key", key); put("value", value) }
    return helper.writableDatabase
        .insertWithOnConflict("config", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L
  }

  fun putConfigs(values: Map<String, String>) {
    val db = helper.writableDatabase
    db.beginTransaction()
    try {
      values.forEach { (key, value) ->
        val row = ContentValues().apply { put("key", key); put("value", value) }
        db.insertWithOnConflict("config", null, row, SQLiteDatabase.CONFLICT_REPLACE)
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /** Physical size of the SQLite file, used by the settings telemetry card. */
  fun databaseSize(): Long = appContext.getDatabasePath(ScriptDbHelper.DATABASE_NAME).length()

  fun databasePath(): String =
      appContext.getDatabasePath(ScriptDbHelper.DATABASE_NAME).absolutePath

  /** Runs SQLite VACUUM and returns the number of bytes reclaimed (negative if it grew). */
  fun vacuum(): Long {
    val before = databaseSize()
    helper.writableDatabase.execSQL("VACUUM")
    return before - databaseSize()
  }

  fun clearStorage(id: String): Boolean {
    val values = ContentValues().apply { putNull("storage") }
    return helper.writableDatabase.update("script", values, "id = ?", arrayOf(id)) > 0
  }

  /**
   * Removes GM_storage rows whose script no longer declares any storage API. Returns how many rows
   * were cleared.
   */
  fun clearOrphanStorage(): Int {
    var cleared = 0
    records().forEach { record ->
      if (record.storage == null) return@forEach
      val parsed = parseScript(record.meta + record.code, null, hydrateRequires = false)
      val usesStorage = parsed?.grant?.any { it in STORAGE_GRANTS } == true
      if (!usesStorage && clearStorage(record.id)) cleared += 1
    }
    return cleared
  }

  private fun Cursor.string(name: String, default: String = ""): String {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) default else getString(index)
  }

  private fun Cursor.stringOrNull(name: String): String? {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) null else getString(index)
  }

  private fun Cursor.int(name: String, default: Int): Int {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) default else getInt(index)
  }

  private fun Cursor.long(name: String, default: Long): Long {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) default else getLong(index)
  }
}
