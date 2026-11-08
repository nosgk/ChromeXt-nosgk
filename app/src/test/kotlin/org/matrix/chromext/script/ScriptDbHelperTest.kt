package org.matrix.chromext.script

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScriptDbHelperTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun columnsOf(db: SQLiteDatabase, table: String): Set<String> {
    val columns = mutableSetOf<String>()
    db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
      while (cursor.moveToNext()) {
        columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
      }
    }
    return columns
  }

  @Test
  fun createsTheV11Schema() {
    context.deleteDatabase(ScriptDbHelper.DATABASE_NAME)
    val helper = ScriptDbHelper(context)
    val db = helper.writableDatabase
    val columns = columnsOf(db, "script")
    val appColumns = columnsOf(db, "hooked_app")
    assertEquals(ScriptDbHelper.DATABASE_VERSION, db.version)
    helper.close()
    assertTrue(
        columns.containsAll(
            setOf(
                "id",
                "meta",
                "code",
                "storage",
                "enabled",
                "sort_order",
                "custom_match",
                "custom_include",
                "custom_exclude",
                "custom_run_at",
                "update_url",
                "icon_url",
                "last_updated",
                "apps",
            )))
    assertTrue(
        appColumns.containsAll(setOf("package_name", "label", "version_name", "last_seen")))
  }

  @Test
  fun migratesV9WithoutLosingNullStorageRows() {
    val path = context.getDatabasePath(ScriptDbHelper.DATABASE_NAME)
    path.parentFile?.mkdirs()
    path.delete()
    SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
      db.execSQL(
          "CREATE TABLE script (id TEXT PRIMARY KEY NOT NULL, meta TEXT NOT NULL, code TEXT NOT NULL, storage TEXT);")
      db.execSQL(
          "INSERT INTO script VALUES ('a:null', '// ==UserScript==\n// ==/UserScript==\n', 'code', NULL);")
      db.execSQL(
          "INSERT INTO script VALUES ('b:stored', '// ==UserScript==\n// ==/UserScript==\n', 'code', '{\"k\":1}');")
      db.version = 9
    }

    val helper = ScriptDbHelper(context)
    val db = helper.readableDatabase
    assertEquals(11, db.version)
    db.rawQuery("SELECT id, storage, enabled, sort_order, apps FROM script ORDER BY id", null).use {
        cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("a:null", cursor.getString(0))
      assertNull(cursor.getString(1))
      assertEquals(1, cursor.getInt(2))
      assertEquals(0, cursor.getInt(3))
      assertEquals("", cursor.getString(4))
      assertTrue(cursor.moveToNext())
      assertEquals("b:stored", cursor.getString(0))
      assertEquals("{\"k\":1}", cursor.getString(1))
    }
    helper.close()
  }

  @Test
  fun migratesV10ByAddingAppsAndTheHookedAppTable() {
    val path = context.getDatabasePath(ScriptDbHelper.DATABASE_NAME)
    path.parentFile?.mkdirs()
    path.delete()
    SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
      db.execSQL(
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
            last_updated INTEGER NOT NULL DEFAULT 0
          );
          """)
      db.execSQL(
          "INSERT INTO script (id, meta, code) VALUES ('a:kept', '// ==UserScript==\n// ==/UserScript==\n', 'code');")
      db.version = 10
    }

    val helper = ScriptDbHelper(context)
    val db = helper.readableDatabase
    assertEquals(11, db.version)
    assertTrue(columnsOf(db, "script").contains("apps"))
    assertTrue(columnsOf(db, "hooked_app").contains("package_name"))
    db.rawQuery("SELECT apps FROM script WHERE id = 'a:kept'", null).use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("", cursor.getString(0))
    }
    helper.close()
  }
}
