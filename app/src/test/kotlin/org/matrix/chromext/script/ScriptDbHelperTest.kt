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

  @Test
  fun createsTheV10Schema() {
    context.deleteDatabase(ScriptDbHelper.DATABASE_NAME)
    val helper = ScriptDbHelper(context)
    val db = helper.writableDatabase
    val columns = mutableSetOf<String>()
    db.rawQuery("PRAGMA table_info(script)", null).use { cursor ->
      while (cursor.moveToNext()) {
        columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
      }
    }
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
            )))
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
    assertEquals(10, db.version)
    db.rawQuery("SELECT id, storage, enabled, sort_order FROM script ORDER BY id", null).use {
        cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("a:null", cursor.getString(0))
      assertNull(cursor.getString(1))
      assertEquals(1, cursor.getInt(2))
      assertEquals(0, cursor.getInt(3))
      assertTrue(cursor.moveToNext())
      assertEquals("b:stored", cursor.getString(0))
      assertEquals("{\"k\":1}", cursor.getString(1))
    }
    helper.close()
  }
}
