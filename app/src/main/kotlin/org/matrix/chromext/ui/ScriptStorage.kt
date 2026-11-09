package org.matrix.chromext.ui

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import org.matrix.chromext.script.LegacyDatabase
import org.matrix.chromext.script.LegacyReadResult
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.Su

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

/**
 * Finds and reads the per-browser `userscript` databases.
 *
 * The upstream ChromeXt stores scripts in each browser's own SQLite file
 * (`/data/user/0/<pkg>/databases/userscript`). This fork keeps them in a central database in the
 * module, so scripts installed before switching builds are invisible until they are migrated. The
 * browser process imports its own file once (read-only) on first run; this reader lets the manager
 * do the same immediately with root, and also shows the real paths on the device.
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
    // Only touch each app's `databases/` directory. Walking the whole data directory (Chrome's
    // app_chrome tree alone has tens of thousands of entries) made this command exceed the timeout
    // and look like "not found" on every browser.
    val command =
        "users=\$(ls /data/user 2>/dev/null)\n" +
            "for u in \$users; do\n" +
            "  for p in $packages; do\n" +
            "    for base in /data/user/\$u/\$p /data/user_de/\$u/\$p; do\n" +
            "      for f in \$base/databases/$DB_NAME \$base/databases/$DB_NAME.db; do\n" +
            "        if [ -f \"\$f\" ]; then\n" +
            "          s=\$(stat -c %s \"\$f\" 2>/dev/null || echo 0)\n" +
            "          t=\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0)\n" +
            "          echo \"\$f|\$s|\$t\"\n" +
            "        fi\n" +
            "      done\n" +
            "      for f in \$(find \$base/databases -maxdepth 1 -name '$DB_NAME*' 2>/dev/null); do\n" +
            "        s=\$(stat -c %s \"\$f\" 2>/dev/null || echo 0)\n" +
            "        t=\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0)\n" +
            "        echo \"\$f|\$s|\$t\"\n" +
            "      done\n" +
            "    done\n" +
            "  done\n" +
            "done\n" +
            "for p in $packages; do\n" +
            "  for f in /data/data/\$p/databases/$DB_NAME /data/data/\$p/databases/$DB_NAME.db; do\n" +
            "    if [ -f \"\$f\" ]; then\n" +
            "      s=\$(stat -c %s \"\$f\" 2>/dev/null || echo 0)\n" +
            "      t=\$(stat -c %Y \"\$f\" 2>/dev/null || echo 0)\n" +
            "      echo \"\$f|\$s|\$t\"\n" +
            "    fi\n" +
            "  done\n" +
            "done\n" +
            "exit 0"

    val result = runSu(command, 15)
    if (result.timedOut || result.exitCode != 0) {
      return ScanOutcome(
          databases.map { it.copy(checked = true, exists = false) },
          rootOk = false,
          message =
              result.output.trim().ifBlank {
                if (result.timedOut) "su timed out" else "su exit ${result.exitCode}"
              },
      )
    }
    val output = result.output
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
   * Copies one browser database to the app cache and parses its rows. Nothing is written back to
   * the browser; the caller decides which scripts to import.
   */
  fun read(context: Context, database: LocalScriptDatabase): LegacyReadResult {
    if (!database.exists) return LegacyReadResult(emptyList(), error = "not found")
    val directory = File(context.cacheDir, "import").apply { mkdirs() }
    directory.listFiles()?.forEach { runCatching { it.delete() } }
    val target = File(directory, DB_NAME)
    if (!copyFile(database.path, target, requireSqliteHeader = true)) {
      return LegacyReadResult(emptyList(), error = "copy failed")
    }
    copyFile("${database.path}-wal", File(target.path + "-wal"), requireSqliteHeader = false)
    copyFile("${database.path}-shm", File(target.path + "-shm"), requireSqliteHeader = false)
    return LegacyDatabase.read(target.path)
  }

  /** Reads a database the user picked with the system file picker. */
  fun read(context: Context, uri: android.net.Uri): LegacyReadResult =
      runCatching {
            val directory = File(context.cacheDir, "import").apply { mkdirs() }
            directory.listFiles()?.forEach { runCatching { it.delete() } }
            val target = File(directory, DB_NAME)
            context.contentResolver.openInputStream(uri)?.use { input ->
              target.outputStream().use { output -> input.copyTo(output) }
            } ?: return LegacyReadResult(emptyList(), error = "cannot open file")
            LegacyDatabase.read(target.path)
          }
          .getOrElse {
            Log.d("Reading the imported database failed: ${it.message}")
            LegacyReadResult(emptyList(), error = it.message ?: "read failed")
          }

  private data class SuResult(
      val output: String,
      val exitCode: Int,
      val timedOut: Boolean,
  )

  private fun runSu(command: String, timeoutSeconds: Long): SuResult {
    val su = Su.path() ?: return SuResult("su not found", -1, timedOut = false)
    return runCatching {
          val process = ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
          val bytes = process.inputStream.use { it.readBytes() }
          val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
          if (!finished) {
            process.destroy()
            SuResult(String(bytes, Charsets.UTF_8), -1, timedOut = true)
          } else {
            SuResult(String(bytes, Charsets.UTF_8), process.exitValue(), timedOut = false)
          }
        }
        .getOrElse { SuResult(it.message ?: "su failed", -1, timedOut = false) }
  }

  private fun copyFile(path: String, target: File, requireSqliteHeader: Boolean): Boolean {
    val su = Su.path() ?: return false
    return runCatching {
          val process =
              ProcessBuilder(su, "-c", "cat $path 2>/dev/null")
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
  }

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
