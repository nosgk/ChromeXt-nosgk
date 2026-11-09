package org.matrix.chromext.script

import android.database.sqlite.SQLiteDatabase
import java.io.File

data class LegacyReadResult(
    val records: List<ScriptRecord>,
    val invalid: Int = 0,
    val error: String? = null,
)

/**
 * Reads an upstream ChromeXt database (`/data/user/0/<browser>/databases/userscript`).
 *
 * The database is only ever opened read-only: this fork keeps the central database as the single
 * source of truth and must not create or modify a browser's copy.
 */
object LegacyDatabase {

  fun read(path: String): LegacyReadResult {
    if (!File(path).exists()) return LegacyReadResult(emptyList(), error = "not found")
    return runCatching {
          SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db -> read(db) }
        }
        .getOrElse { LegacyReadResult(emptyList(), error = it.message ?: "read failed") }
  }

  fun read(db: SQLiteDatabase): LegacyReadResult {
    val columns = mutableSetOf<String>()
    db.rawQuery("PRAGMA table_info(script)", null).use { cursor ->
      while (cursor.moveToNext()) {
        columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
      }
    }
    if ("id" !in columns || "meta" !in columns || "code" !in columns) {
      return LegacyReadResult(emptyList(), error = "unknown schema")
    }
    val records = mutableListOf<ScriptRecord>()
    var invalid = 0
    db.rawQuery("SELECT * FROM script", null).use { cursor ->
      while (cursor.moveToNext()) {
        fun text(name: String, default: String = ""): String {
          if (name !in columns) return default
          val index = cursor.getColumnIndex(name)
          return if (index < 0 || cursor.isNull(index)) default else cursor.getString(index)
        }
        fun number(name: String, default: Long): Long {
          if (name !in columns) return default
          val index = cursor.getColumnIndex(name)
          return if (index < 0 || cursor.isNull(index)) default else cursor.getLong(index)
        }
        val record =
            ScriptRecord(
                id = text("id"),
                meta = text("meta"),
                code = text("code"),
                storage = if ("storage" in columns) text("storage").ifBlank { null } else null,
                enabled = number("enabled", 1L) != 0L,
                sortOrder = number("sort_order", 0L).toInt(),
                customMatch = text("custom_match"),
                customInclude = text("custom_include"),
                customExclude = text("custom_exclude"),
                customRunAt = text("custom_run_at"),
                updateUrl = text("update_url"),
                iconUrl = text("icon_url"),
                lastUpdated = number("last_updated", 0L),
                apps = text("apps"),
            )
        if (record.id.isBlank() ||
            parseScript(record.meta + record.code, record.storage, hydrateRequires = false) == null) {
          invalid += 1
        } else {
          records.add(record)
        }
      }
    }
    return LegacyReadResult(records, invalid)
  }
}
