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
class LegacyDatabaseTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun createLegacyDatabase(): String {
    val path = context.getDatabasePath("legacy-userscript-test")
    path.parentFile?.mkdirs()
    path.delete()
    SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
      db.execSQL(
          "CREATE TABLE script (id TEXT PRIMARY KEY NOT NULL, meta TEXT NOT NULL, code TEXT NOT NULL, storage TEXT);")
      db.execSQL(
          "INSERT INTO script VALUES ('ns:Good', '// ==UserScript==\n// @name Good\n// @match https://example.com/*\n// ==/UserScript==\n', 'code', NULL);")
      db.execSQL(
          "INSERT INTO script VALUES ('ns:Stored', '// ==UserScript==\n// @name Stored\n// @match https://example.com/*\n// ==/UserScript==\n', 'code', '{\"k\":1}');")
      db.execSQL(
          "INSERT INTO script VALUES ('ns:Broken', 'not a userscript', 'code', NULL);")
    }
    return path.path
  }

  @Test
  fun readsValidRowsAndCountsInvalidOnes() {
    val result = LegacyDatabase.read(createLegacyDatabase())
    assertNull(result.error)
    assertEquals(1, result.invalid)
    assertEquals(listOf("ns:Good", "ns:Stored"), result.records.map { it.id })
    assertEquals("{\"k\":1}", result.records[1].storage)
    assertTrue(result.records.all { it.lib == "[]" && it.resources == "[]" })
  }

  @Test
  fun reportsMissingFiles() {
    val result = LegacyDatabase.read("/definitely/not/here/userscript")
    assertEquals("not found", result.error)
    assertTrue(result.records.isEmpty())
  }
}
