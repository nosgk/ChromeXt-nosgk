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
    /** JSON array of `@require` sources, hydrated at install time. */
    val lib: String = "[]",
    /** JSON array of `@resource` entries, hydrated at install time. */
    val resources: String = "[]",
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
        put("lib", lib)
        put("resources", resources)
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
            lib = json.optString("lib", "[]"),
            resources = json.optString("resources", "[]"),
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
        lib = encodeLib(lib),
        resources = encodeResources(resources),
    )

/** Rebuilds the injection model, keeping every manager-side override stored in the row. */
fun Script.withRecord(record: ScriptRecord): Script {
  val storageJson =
      if (storage != null) {
        record.storage?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
      } else null
  return copy(
      enabled = record.enabled,
      sortOrder = record.sortOrder,
      customMatch = record.customMatch,
      customInclude = record.customInclude,
      customExclude = record.customExclude,
      customRunAt = record.customRunAt,
      updateUrl = record.updateUrl,
      iconUrl = record.iconUrl,
      lastUpdated = record.lastUpdated,
      apps = parseApps(record.apps),
      lib = parseLib(record.lib),
      resources = parseResources(record.resources),
      storage = storageJson,
  )
}

fun ScriptRecord.toScript(): Script? {
  val parsed = parseScript(meta + code, storage, hydrateRequires = false) ?: return null
  if (parsed.id != id) return null
  return parsed.withRecord(this)
}

/** Mirrored `.user.js` document in the public Download directory. */
data class MirrorEntry(val scriptId: String, val uri: String, val path: String)

/** Aggregate numbers for the settings telemetry, without reading every script body. */
data class ScriptStats(val total: Int, val enabled: Int, val storageBytes: Long)

/** Thin CRUD over the `script` table, usable from both the browser and the provider process. */
class ScriptStore(context: Context) {

  private val appContext = context.applicationContext

  init {
    // Must run before the helper opens the file. The helper only renames the module's own database,
    // never a browser's.
    ScriptDbHelper.migrateLegacyFileName(appContext)
  }

  private val helper = ScriptDbHelper(appContext)

  fun records(): List<ScriptRecord> {    val db = helper.readableDatabase
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
                lib = cursor.string("lib", "[]"),
                resources = cursor.string("resources", "[]"),
            ))
      }
    } finally {
      cursor.close()
    }
    return result.sortedWith(compareBy({ it.sortOrder }, { it.id }))
  }

  fun record(id: String): ScriptRecord? = records().firstOrNull { it.id == id }

  fun stats(): ScriptStats {
    var total = 0
    var enabled = 0
    var storageBytes = 0L
    helper.readableDatabase
        .query("script", arrayOf("enabled", "storage"), null, null, null, null, null)
        .use { cursor ->
          while (cursor.moveToNext()) {
            total += 1
            if (cursor.getInt(0) != 0) enabled += 1
            if (!cursor.isNull(1)) {
              storageBytes += cursor.getString(1).toByteArray(Charsets.UTF_8).size
            }
          }
        }
    return ScriptStats(total, enabled, storageBytes)
  }

  fun upsert(record: ScriptRecord): Boolean {
    val db = helper.writableDatabase
    val values = contentValues(record)
    val ok = db.insertWithOnConflict("script", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L
    if (ok) bumpRevision(db)
    return ok
  }

  /** Migration helper: never overwrite an existing row. */
  fun insertIfAbsent(record: ScriptRecord): Boolean {
    val db = helper.writableDatabase
    val values = contentValues(record)
    val ok = db.insertWithOnConflict("script", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    if (ok) bumpRevision(db)
    return ok
  }

  fun setEnabled(id: String, enabled: Boolean): Boolean {
    val db = helper.writableDatabase
    val values = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
    val ok = db.update("script", values, "id = ?", arrayOf(id)) > 0
    if (ok) bumpRevision(db)
    return ok
  }

  fun updateStorage(id: String, storage: String): Boolean {
    val values = ContentValues().apply { put("storage", storage) }
    return helper.writableDatabase.update("script", values, "id = ?", arrayOf(id)) > 0
  }

  fun delete(id: String): Boolean = delete(arrayOf(id)) > 0

  fun delete(ids: Array<String>): Int {
    if (ids.isEmpty()) return 0
    val db = helper.writableDatabase
    val placeholders = ids.joinToString(",") { "?" }
    val deleted = db.delete("script", "id IN ($placeholders)", ids)
    if (deleted > 0) {
      runCatching {
        db.delete("script_activation", "script_id IN ($placeholders)", ids)
        db.delete("file_mirror", "script_id IN ($placeholders)", ids)
      }
      bumpRevision(db)
    }
    return deleted
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

  /**
   * Whether the module has been injected into [packageName] at least once. Used by the provider to
   * let WebView hosts outside the browser catalog (for example a scoped note-taking app) read the
   * central scripts, which is required now that browsers no longer keep a local copy.
   */
  fun isHookedApp(packageName: String): Boolean {
    if (packageName.isBlank()) return false
    val cursor =
        helper.readableDatabase.query(
            "hooked_app", arrayOf("package_name"), "package_name = ?", arrayOf(packageName), null,
            null, null)
    return cursor.use { it.moveToFirst() }
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
      // Engine switches are pulled by the browser on a revision change as well, so a toggle
      // applies on the next page load instead of waiting for onResume.
      bumpRevision(db)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /** Monotonic counter bumped on every script write; browsers refresh only when it changes. */
  fun revision(): Long {
    val cursor =
        helper.readableDatabase.query(
            "config", arrayOf("value"), "key = ?", arrayOf(KEY_REVISION), null, null, null)
    return cursor.use { if (it.moveToFirst()) it.getString(0)?.toLongOrNull() ?: 0L else 0L }
  }

  private fun bumpRevision(db: SQLiteDatabase) {
    val next = revision() + 1
    val values = ContentValues().apply { put("key", KEY_REVISION); put("value", next.toString()) }
    db.insertWithOnConflict("config", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun activations(): List<ScriptActivation> {
    val cursor =
        helper.readableDatabase.query(
            "script_activation", null, null, null, null, null, null)
    val result = mutableListOf<ScriptActivation>()
    try {
      while (cursor.moveToNext()) {
        result.add(
            ScriptActivation(
                scriptId = cursor.string("script_id"),
                packageName = cursor.string("package_name"),
                lastActive = cursor.long("last_active", 0L),
                count = cursor.long("count", 0L),
            ))
      }
    } finally {
      cursor.close()
    }
    return result
  }

  /** Upserts a batch of activation reports sent by a hooked browser process. */
  fun reportActivations(rows: List<ScriptActivation>) {
    if (rows.isEmpty()) return
    val db = helper.writableDatabase
    db.beginTransaction()
    try {
      rows.forEach { row ->
        // ON CONFLICT ... DO UPDATE needs SQLite 3.24 (Android 11); read-modify-write works on
        // every supported API level.
        var lastActive = row.lastActive
        var count = row.count
        db.rawQuery(
                "SELECT last_active, count FROM script_activation " +
                    "WHERE script_id = ? AND package_name = ?",
                arrayOf(row.scriptId, row.packageName))
            .use { cursor ->
              if (cursor.moveToFirst()) {
                lastActive = maxOf(cursor.getLong(0), lastActive)
                count += cursor.getLong(1)
              }
            }
        val values =
            ContentValues().apply {
              put("script_id", row.scriptId)
              put("package_name", row.packageName)
              put("last_active", lastActive)
              put("count", count)
            }
        db.insertWithOnConflict(
            "script_activation", null, values, SQLiteDatabase.CONFLICT_REPLACE)
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun mirrorEntry(scriptId: String): MirrorEntry? {
    val cursor =
        helper.readableDatabase.query(
            "file_mirror", null, "script_id = ?", arrayOf(scriptId), null, null, null)
    return cursor.use {
      if (!it.moveToFirst()) null
      else
          MirrorEntry(
              scriptId = it.string("script_id"),
              uri = it.string("uri"),
              path = it.string("path"),
          )
    }
  }

  fun putMirrorEntry(entry: MirrorEntry) {
    val values =
        ContentValues().apply {
          put("script_id", entry.scriptId)
          put("uri", entry.uri)
          put("path", entry.path)
        }
    helper.writableDatabase.insertWithOnConflict(
        "file_mirror", null, values, SQLiteDatabase.CONFLICT_REPLACE)
  }

  fun deleteMirrorEntry(scriptId: String) {
    helper.writableDatabase.delete("file_mirror", "script_id = ?", arrayOf(scriptId))
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

  private fun contentValues(record: ScriptRecord): ContentValues =
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
        put("lib", record.lib)
        put("resources", record.resources)
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

  companion object {
    const val KEY_REVISION = "scripts_revision"
  }
}
