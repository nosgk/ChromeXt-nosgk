package org.matrix.chromext.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.TimeUnit
import org.matrix.chromext.script.ScriptRecord
import org.matrix.chromext.script.parseScript
import org.matrix.chromext.utils.Log

/** One browser-local `userscript` database, as seen through `su`. */
data class LocalScriptDatabase(
    val packageName: String,
    val label: String,
    val path: String,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val checked: Boolean = false,
    val exists: Boolean = false,
)

data class ImportOutcome(
    val imported: Int = 0,
    val skipped: Int = 0,
    val invalid: Int = 0,
    val error: String? = null,
)

/**
 * Finds and imports the per-browser `userscript` databases.
 *
 * The upstream ChromeXt stores scripts in each browser's own SQLite file
 * (`/data/user/0/<pkg>/databases/userscript`). This fork keeps them in a central database in the
 * module, so scripts installed before switching builds are invisible until they are migrated. The
 * browser process migrates itself on first run; this reader lets the manager do the same thing
 * immediately with root, and also shows the real paths on the device.
 */
data class ScanOutcome(
    val databases: List<LocalScriptDatabase>,
    /** False when `su` is unavailable or the grant was denied. */
    val rootOk: Boolean,
    val message: String = "",
)

object ScriptStorage {

  private const val DB_NAME = "userscript"

  /** Expected database paths for the given packages, without touching the filesystem. */
  fun expected(packages: List<Pair<String, String>>): List<LocalScriptDatabase> =
      packages.map { (packageName, label) ->
        LocalScriptDatabase(
            packageName = packageName,
            label = label,
            path = "/data/user/0/$packageName/databases/$DB_NAME",
        )
      }

  /**
   * Probes every user profile (`/data/user/<id>`, `/data/user_de/<id>`) plus `/data/data` in one
   * `su` call. The previous version only checked user 0 and collapsed "su denied" into "not found",
   * which made a working setup look broken.
   */
  fun scan(databases: List<LocalScriptDatabase>): ScanOutcome {
    if (databases.isEmpty()) return ScanOutcome(databases, rootOk = true)
    val packages = databases.joinToString(" ") { it.packageName }
    val command =
        "users=\$(ls /data/user 2>/dev/null)\n" +
            "for u in \$users; do\n" +
            "  for p in $packages; do\n" +
            "    for f in \$(find /data/user/\$u/\$p /data/user_de/\$u/\$p -maxdepth 3 \\( -name $DB_NAME -o -name $DB_NAME.db \\) 2>/dev/null); do\n" +
            "      s=\$(stat -c %s \"\$f\" 2>/dev/null || echo 0)\n" +
            "      t=\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0)\n" +
            "      echo \"\$f|\$s|\$t\"\n" +
            "    done\n" +
            "  done\n" +
            "done\n" +
            "for p in $packages; do\n" +
            "  for f in \$(find /data/data/\$p -maxdepth 3 \\( -name $DB_NAME -o -name $DB_NAME.db \\) 2>/dev/null); do\n" +
            "    s=\$(stat -c %s \"\$f\" 2>/dev/null || echo 0)\n" +
            "    t=\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0)\n" +
            "    echo \"\$f|\$s|\$t\"\n" +
            "  done\n" +
            "done"

    val output =
        runSu(command, 12)
            ?: return ScanOutcome(
                databases.map { it.copy(checked = true, exists = false) },
                rootOk = false,
                message = "su unavailable",
            )
    if (output.contains("Permission denied", ignoreCase = true) ||
        output.contains("not allowed", ignoreCase = true) ||
        output.contains("uid=0", ignoreCase = true).not() && output.contains("su:", ignoreCase = true)) {
      return ScanOutcome(
          databases.map { it.copy(checked = true, exists = false) },
          rootOk = false,
          message = output.trim().take(200),
      )
    }

    val found = mutableMapOf<String, Triple<String, Long, Long>>()
    output.lineSequence().map { it.trim() }.filter { it.contains('|') }.forEach { line ->
      val parts = line.split('|')
      if (parts.size < 3) return@forEach
      val path = parts[0]
      val packageName =
          databases.firstOrNull { path.contains("/${it.packageName}/") }?.packageName
              ?: return@forEach
      val size = parts[1].toLongOrNull() ?: 0L
      val modified = parts[2].toLongOrNull() ?: 0L
      // Prefer the credential-encrypted user 0 path, but accept whatever exists.
      val current = found[packageName]
      val better =
          current == null ||
              (!current.first.contains("/data/user_de/") && path.contains("/data/user_de/")) ||
              (!current.first.contains("/data/user/0/") && path.contains("/data/user/0/"))
      if (better) found[packageName] = Triple(path, size, modified)
    }
    return ScanOutcome(
        databases.map { database ->
          val match = found[database.packageName]
          if (match == null) database.copy(checked = true, exists = false)
          else
              database.copy(
                  path = match.first,
                  size = match.second,
                  lastModified = match.third,
                  checked = true,
                  exists = true,
              )
        },
        rootOk = true,
    )
  }

  /**
   * Copies one browser database and inserts every row that is not in the central database yet.
   * [insert] returns true when the row was actually inserted (false means it already existed).
   */
  fun import(
      context: Context,
      database: LocalScriptDatabase,
      insert: (ScriptRecord) -> Boolean,
  ): ImportOutcome {
    if (!database.exists) return ImportOutcome(error = "not found")
    val directory = File(context.cacheDir, "import").apply { mkdirs() }
    directory.listFiles()?.forEach { runCatching { it.delete() } }
    val target = File(directory, DB_NAME)
    if (!copyFile(database.path, target, requireSqliteHeader = true)) {
      return ImportOutcome(error = "copy failed")
    }
    copyFile("${database.path}-wal", File(target.path + "-wal"), requireSqliteHeader = false)
    copyFile("${database.path}-shm", File(target.path + "-shm"), requireSqliteHeader = false)

    return runCatching {
          SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val columns = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(script)", null).use { cursor ->
              while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
              }
            }
            if ("id" !in columns || "meta" !in columns || "code" !in columns) {
              return ImportOutcome(error = "unknown schema")
            }
            var imported = 0
            var skipped = 0
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
                    parseScript(record.meta + record.code, record.storage, hydrateRequires = false) ==
                        null) {
                  invalid += 1
                } else if (insert(record)) {
                  imported += 1
                } else {
                  skipped += 1
                }
              }
            }
            ImportOutcome(imported = imported, skipped = skipped, invalid = invalid)
          }
        }
        .getOrElse {
          Log.d("Import from ${database.path} failed: ${it.message}")
          ImportOutcome(error = it.message ?: "read failed")
        }
  }

  private fun runSu(command: String, timeoutSeconds: Long): String? =
      runCatching {
            val process =
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val bytes = process.inputStream.use { it.readBytes() }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            if (!finished) return@runCatching null
            String(bytes, Charsets.UTF_8)
          }
          .getOrNull()

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
}
