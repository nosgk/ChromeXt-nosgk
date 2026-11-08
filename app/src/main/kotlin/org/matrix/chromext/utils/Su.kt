package org.matrix.chromext.utils

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resolves the root shell binary.
 *
 * App processes do not always have a PATH that contains the root manager's `su`: Magisk exposes
 * it at `/debug_ramdisk/su` (and `/system/bin/su`), KernelSU at `/data/adb/ksu/bin/su`, APatch at
 * `/data/adb/ap/bin/su`. `ProcessBuilder("su", ...)` then fails with
 * `Cannot run program "su": error=2, No such file or directory`, which is what broke the storage
 * scan and the LSPosed scope reader on those devices.
 */
object Su {

  private val CANDIDATES =
      listOf(
          "/system/bin/su",
          "/system/xbin/su",
          "/sbin/su",
          "/debug_ramdisk/su",
          "/data/adb/ksu/bin/su",
          "/data/adb/ap/bin/su",
          "/data/adb/magisk/su",
          "/su/bin/su",
          "/vendor/bin/su",
      )

  @Volatile private var cached: String? = null

  /** Returns the resolved root shell, or null. A miss is not cached, so a later grant can be picked up. */
  fun path(): String? {
    cached?.let { return it }
    synchronized(this) {
      cached?.let { return it }
      val found = CANDIDATES.firstOrNull { File(it).canExecute() } ?: fromPath() ?: fromShell()
      if (found != null) cached = found
      return found
    }
  }

  private fun fromPath(): String? {
    val path = System.getenv("PATH").orEmpty()
    return path
        .split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .map { File(it, "su") }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
  }

  private fun fromShell(): String? =
      runCatching {
            val process =
                ProcessBuilder("/system/bin/sh", "-c", "command -v su")
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            if (finished && process.exitValue() == 0 && output.startsWith("/")) output else null
          }
          .getOrNull()
}
