package org.matrix.chromext.script

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject

data class Script(
    val id: String,
    val match: Array<String>,
    val grant: Array<String>,
    val exclude: Array<String>,
    var meta: String,
    val code: String,
    var storage: JSONObject?,
    val lib: List<String> = mutableListOf<String>(),
    val noframes: Boolean,
    val enabled: Boolean = true,
    /** Empty means every hooked app; otherwise the script is only injected into these packages. */
    val apps: List<String> = emptyList(),
    val sortOrder: Int = 0,
    val customMatch: String = "",
    val customInclude: String = "",
    val customExclude: String = "",
    val customRunAt: String = "",
    val updateUrl: String = "",
    val iconUrl: String = "",
    val lastUpdated: Long = 0L,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val runAt: String = "",
) {
  /** User overrides win over the metadata block; empty overrides keep the original rules. */
  fun effectiveMatchRules(): List<String> {
    val custom = (customMatch + "\n" + customInclude).lines().filter { it.isNotBlank() }
    return custom.ifEmpty { match.toList() }
  }

  fun effectiveExcludeRules(): List<String> {
    val custom = customExclude.lines().filter { it.isNotBlank() }
    return custom.ifEmpty { exclude.toList() }
  }

  /**
   * Whether this script runs inside [packageName]. An empty app list keeps the pre-3.10 behavior
   * (every hooked app), and an unknown package name never filters scripts out.
   */
  fun appliesTo(packageName: String?): Boolean =
      apps.isEmpty() || packageName == null || apps.contains(packageName)
}

/** Stored as a comma separated list; empty means "all hooked apps". */
fun parseApps(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

fun encodeApps(apps: List<String>): String =
    apps.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

data class HookedApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val lastSeen: Long,
)

private const val SQL_CREATE_HOOKED_APPS =
    """
    CREATE TABLE IF NOT EXISTS hooked_app (
      package_name TEXT PRIMARY KEY NOT NULL,
      label TEXT NOT NULL DEFAULT '',
      version_name TEXT NOT NULL DEFAULT '',
      last_seen INTEGER NOT NULL DEFAULT 0
    );
    """

/** Central key/value store for engine switches shared by the manager and the browser processes. */
private const val SQL_CREATE_CONFIG =
    """
    CREATE TABLE IF NOT EXISTS config (
      key TEXT PRIMARY KEY NOT NULL,
      value TEXT NOT NULL DEFAULT ''
    );
    """

private const val SQL_CREATE_ENTRIES =
    """
    CREATE TABLE script (
      id TEXT PRIMARY KEY NOT NULL,
      meta TEXT NOT NULL,
      code TEXT NOT NULL,
      storage TEXT,
      enabled INTEGER NOT NULL DEFAULT 1,
      sort_order INTEGER NOT NULL DEFAULT 0,
      custom_match TEXT NOT NULL DEFAULT '',
      custom_include TEXT NOT NULL DEFAULT '',
      custom_exclude TEXT NOT NULL DEFAULT '',
      custom_run_at TEXT NOT NULL DEFAULT '',
      update_url TEXT NOT NULL DEFAULT '',
      icon_url TEXT NOT NULL DEFAULT '',
      last_updated INTEGER NOT NULL DEFAULT 0,
      apps TEXT NOT NULL DEFAULT ''
    );
    """

class ScriptDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(SQL_CREATE_ENTRIES)
    db.execSQL(SQL_CREATE_HOOKED_APPS)
    db.execSQL(SQL_CREATE_CONFIG)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 7) {
      db.execSQL("DROP TABLE IF EXISTS script;")
      onCreate(db)
      return
    }

    if (oldVersion < 8) {
      db.execSQL("CREATE TABLE tmp AS SELECT id,meta,code,storage FROM script;")
      db.execSQL("DROP TABLE script;")
      db.execSQL("ALTER TABLE tmp RENAME TO script;")
    }

    if (oldVersion < 9) {
      db.execSQL("CREATE TABLE tmp AS SELECT id,meta,code,storage FROM script;")
      db.execSQL("DROP TABLE script;")
      db.execSQL(SQL_CREATE_ENTRIES)
      db.execSQL(
          "INSERT INTO script (id,meta,code) SELECT id,meta,code FROM tmp WHERE storage IS NULL OR storage = '';")
      db.execSQL(
          "INSERT INTO script (id,meta,code,storage) SELECT id,meta,code,storage FROM tmp WHERE storage IS NOT NULL AND storage != '';")
      db.execSQL("DROP TABLE tmp;")
    }

    if (oldVersion < 10) {
      addColumn(db, "enabled", "INTEGER NOT NULL DEFAULT 1")
      addColumn(db, "sort_order", "INTEGER NOT NULL DEFAULT 0")
      addColumn(db, "custom_match", "TEXT NOT NULL DEFAULT ''")
      addColumn(db, "custom_include", "TEXT NOT NULL DEFAULT ''")
      addColumn(db, "custom_exclude", "TEXT NOT NULL DEFAULT ''")
      addColumn(db, "custom_run_at", "TEXT NOT NULL DEFAULT ''")
      addColumn(db, "update_url", "TEXT NOT NULL DEFAULT ''")
      addColumn(db, "icon_url", "TEXT NOT NULL DEFAULT ''")
      addColumn(db, "last_updated", "INTEGER NOT NULL DEFAULT 0")
    }

    if (oldVersion < 11) {
      addColumn(db, "apps", "TEXT NOT NULL DEFAULT ''")
      db.execSQL(SQL_CREATE_HOOKED_APPS)
    }

    if (oldVersion < 12) {
      db.execSQL(SQL_CREATE_CONFIG)
    }
  }

  private fun addColumn(db: SQLiteDatabase, name: String, definition: String) {
    runCatching { db.execSQL("ALTER TABLE script ADD COLUMN $name $definition") }
        .onFailure { Log.w(DATABASE_NAME, "Cannot add column $name: ${it.message}") }
  }

  override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Older code only reads id/meta/code/storage, so extra columns are harmless. Keep the data
    // instead of recreating the table; a future migration can still run.
    Log.w(DATABASE_NAME, "Database downgrade from $oldVersion to $newVersion; keeping data")
  }

  companion object {
    const val DATABASE_VERSION = 12
    const val DATABASE_NAME = "userscript"
  }
}
