package org.matrix.chromext.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.TimeUnit
import org.matrix.chromext.utils.Log

/**
 * Best-effort reader for the framework's module scope.
 *
 * The module cannot ask LSPosed which packages it is enabled for, so it copies the manager's
 * config database with `su` and reads the scope table. Everything is optional: when root is not
 * available, or the database layout differs, [read] returns an empty set and the apps screen falls
 * back to "apps that actually reported in" plus the known browser catalog.
 */
object LsposedScope {

  private const val MODULE_PACKAGE = "org.matrix.chromext"

  private val CANDIDATES =
      listOf(
          "/data/adb/lspd/config/modules_config.db",
          "/data/adb/modules/lsposed/config/modules_config.db",
          "/data/adb/vector/config/modules_config.db",
          "/data/adb/vector/config/lspd/modules_config.db",
          "/data/adb/apatch/config/modules_config.db",
      )

  @Volatile private var cached: Set<String>? = null

  fun invalidate() {
    cached = null
  }

  /** Returns the scoped package names, or an empty set when the scope cannot be read. */
  fun read(context: Context): Set<String> {
    cached?.let { return it }
    val result = runCatching { readUncached(context) }.getOrDefault(emptySet())
    cached = result
    return result
  }

  private fun readUncached(context: Context): Set<String> {
    val directory = File(context.cacheDir, "scope").apply { mkdirs() }
    val copy = File(directory, "modules_config.db")
    for (path in CANDIDATES) {
      if (!copyFrom(path, copy)) continue
      val packages = queryScope(copy)
      if (packages.isNotEmpty()) return packages
    }
    return emptySet()
  }

  private fun copyFrom(path: String, target: File): Boolean =
      runCatching {
            val process = ProcessBuilder("su", "-c", "cat $path").redirectErrorStream(false).start()
            val bytes = process.inputStream.use { it.readBytes() }
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            if (!finished || process.exitValue() != 0 || bytes.size < 100) return@runCatching false
            target.writeBytes(bytes)
            true
          }
          .getOrDefault(false)

  private fun queryScope(database: File): Set<String> =
      runCatching {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
              db.rawQuery(
                      """
                      SELECT s.app_pkg_name
                      FROM scope s JOIN modules m ON s.mid = m.mid
                      WHERE m.module_pkg_name = ?
                      """
                          .trimIndent(),
                      arrayOf(MODULE_PACKAGE))
                  .use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                  }
            }
          }
          .onFailure { Log.d("LSPosed scope unavailable: ${it.message}") }
          .getOrDefault(emptySet())
}
