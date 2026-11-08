package org.matrix.chromext.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.TimeUnit
import org.matrix.chromext.utils.Log

enum class ScopeStatus {
  /** The scope was read successfully. */
  OK,
  /** `su` is unavailable or the grant was denied. */
  NO_ROOT,
  /** No `modules_config.db` was found under /data/adb. */
  NO_CONFIG,
  /** A config was found but could not be copied or queried. */
  DB_ERROR,
  /** The config exists but the module has an empty scope. */
  EMPTY,
}

data class ScopeResult(
    val packages: Set<String> = emptySet(),
    val status: ScopeStatus = ScopeStatus.NO_ROOT,
    val detail: String = "",
)

/**
 * Best-effort reader for the framework's module scope.
 *
 * LSPosed (and Vector, which is the same codebase renamed) keeps the module/scope database at
 * `/data/adb/lspd/config/modules_config.db`:
 *
 * ```
 * modules(mid INTEGER PRIMARY KEY, module_pkg_name TEXT UNIQUE, apk_path TEXT, enabled, auto_include)
 * scope(mid INTEGER, app_pkg_name TEXT, user_id INTEGER)
 * ```
 *
 * The database is copied with `su` (including the `-wal`/`-shm` sidecars, because the live file may
 * be in WAL mode and the main file alone can be stale) and queried read-write from the app cache.
 * Paths are discovered with `find`, so a renamed framework directory still works. Every failure is
 * reported through [ScopeResult.status] instead of being silently treated as an empty scope.
 */
object LsposedScope {

  private const val MODULE_PACKAGE = "org.matrix.chromext"
  private const val DB_NAME = "modules_config.db"

  private val CANDIDATES =
      listOf(
          "/data/adb/lspd/config/$DB_NAME",
          "/data/adb/modules/lsposed/config/$DB_NAME",
          "/data/adb/vector/config/$DB_NAME",
          "/data/adb/vector/config/lspd/$DB_NAME",
          "/data/adb/apatch/config/$DB_NAME",
      )

  @Volatile private var cached: ScopeResult? = null

  fun invalidate() {
    cached = null
  }

  fun read(context: Context): ScopeResult {
    cached?.let { return it }
    val result =
        runCatching { readUncached(context) }
            .getOrElse {
              Log.d("LSPosed scope read failed: ${it.message}")
              ScopeResult(status = ScopeStatus.DB_ERROR, detail = it.message.orEmpty())
            }
    cached = result
    return result
  }

  private fun readUncached(context: Context): ScopeResult {
    if (!hasRoot()) return ScopeResult(status = ScopeStatus.NO_ROOT)

    val paths = discoverPaths()
    if (paths.isEmpty()) return ScopeResult(status = ScopeStatus.NO_CONFIG)

    var lastDetail = ""
    for (path in paths) {
      val directory = File(context.cacheDir, "scope").apply { mkdirs() }
      directory.listFiles()?.forEach { runCatching { it.delete() } }
      val database = File(directory, DB_NAME)
      if (!copyDatabase(path, database)) {
        lastDetail = "cannot copy $path"
        continue
      }
      val packages = queryScope(database)
      if (packages == null) {
        lastDetail = "cannot query $path"
        continue
      }
      return if (packages.isEmpty()) ScopeResult(status = ScopeStatus.EMPTY)
      else ScopeResult(packages = packages, status = ScopeStatus.OK)
    }
    return ScopeResult(status = ScopeStatus.DB_ERROR, detail = lastDetail)
  }

  private fun hasRoot(): Boolean =
      runCatching {
            val process =
                ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
              process.destroy()
              return@runCatching false
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.exitValue() == 0 && output.contains("uid=0")
          }
          .getOrDefault(false)

  private fun discoverPaths(): List<String> {
    val found = linkedSetOf<String>()
    runCatching {
      val process =
          ProcessBuilder("su", "-c", "find /data/adb -name $DB_NAME 2>/dev/null")
              .redirectErrorStream(true)
              .start()
      if (process.waitFor(6, TimeUnit.SECONDS)) {
        process.inputStream.bufferedReader().useLines { lines ->
          lines
              .map { it.trim() }
              .filter { it.startsWith("/") && it.endsWith(DB_NAME) }
              .forEach { found += it }
        }
      } else {
        process.destroy()
      }
    }
    if (found.isEmpty()) found += CANDIDATES
    return found.toList()
  }

  private fun copyDatabase(path: String, target: File): Boolean {
    if (!copyFile(path, target, requireSqliteHeader = true)) return false
    // WAL sidecars are optional; a missing one just means the main file already has the data.
    copyFile("$path-wal", File(target.path + "-wal"), requireSqliteHeader = false)
    copyFile("$path-shm", File(target.path + "-shm"), requireSqliteHeader = false)
    return true
  }

  private fun copyFile(path: String, target: File, requireSqliteHeader: Boolean): Boolean =
      runCatching {
            val process =
                ProcessBuilder("su", "-c", "cat $path 2>/dev/null")
                    .redirectErrorStream(true)
                    .start()
            val bytes = process.inputStream.use { it.readBytes() }
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            if (bytes.size < 16) return@runCatching false
            val payload =
                if (requireSqliteHeader) {
                  val header = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
                  val offset = indexOf(bytes, header)
                  if (offset < 0) return@runCatching false
                  bytes.copyOfRange(offset, bytes.size)
                } else {
                  bytes
                }
            target.writeBytes(payload)
            true
          }
          .getOrDefault(false)

  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    outer@ for (index in 0..haystack.size - needle.size) {
      for (offset in needle.indices) {
        if (haystack[index + offset] != needle[offset]) continue@outer
      }
      return index
    }
    return -1
  }

  private fun queryScope(database: File): Set<String>? {
    val queries =
        listOf(
            """
            SELECT s.app_pkg_name
            FROM scope s JOIN modules m ON s.mid = m.mid
            WHERE m.module_pkg_name = ?
            """
                .trimIndent(),
            "SELECT app_pkg_name FROM scope WHERE mid IN (SELECT mid FROM modules WHERE module_pkg_name = ?)",
        )
    for (sql in queries) {
      val result =
          runCatching {
                // Read-write so SQLite can replay the copied WAL sidecar.
                SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
                    .use { db ->
                      db.rawQuery(sql, arrayOf(MODULE_PACKAGE)).use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                      }
                    }
              }
              .getOrNull()
      if (result != null) return result
    }
    return null
  }
}
