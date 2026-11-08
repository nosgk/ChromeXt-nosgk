package org.matrix.chromext.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.parseScript

enum class UpdateError {
  NO_URL,
  BAD_URL,
  NETWORK,
  HTTP,
  TOO_LARGE,
  INVALID_SCRIPT,
  ID_MISMATCH,
}

data class UpdateInfo(
    val scriptId: String,
    val currentVersion: String,
    val remoteVersion: String = "",
    val remote: Script? = null,
    val error: UpdateError? = null,
) {
  val hasUpdate: Boolean
    get() = remote != null && UpdateChecker.compareVersions(remoteVersion, currentVersion) > 0
}

/**
 * Fetches a script's `@updateURL`/`@downloadURL` and compares versions.
 *
 * Runs in the module process, so the manifest needs its own INTERNET permission. The fetch is
 * deliberately conservative: http/https only, 10 s timeouts, 4 MiB cap.
 */
object UpdateChecker {

  private const val MAX_BYTES = 4 * 1024 * 1024
  private const val TIMEOUT_MS = 10_000

  suspend fun check(script: Script): UpdateInfo = withContext(Dispatchers.IO) { checkBlocking(script) }

  private fun checkBlocking(script: Script): UpdateInfo {
    val address = script.updateUrl.trim()
    if (address.isEmpty()) {
      return UpdateInfo(script.id, script.version, error = UpdateError.NO_URL)
    }
    val url =
        runCatching { URL(address) }.getOrNull()
            ?: return UpdateInfo(script.id, script.version, error = UpdateError.BAD_URL)
    if (url.protocol != "http" && url.protocol != "https") {
      return UpdateInfo(script.id, script.version, error = UpdateError.BAD_URL)
    }

    val connection =
        runCatching { url.openConnection() as HttpURLConnection }
            .getOrNull()
            ?: return UpdateInfo(script.id, script.version, error = UpdateError.NETWORK)
    connection.connectTimeout = TIMEOUT_MS
    connection.readTimeout = TIMEOUT_MS
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "ChromeXt/${BuildConfig.VERSION_NAME}")
    connection.setRequestProperty("Accept", "text/plain, application/javascript, */*")

    return try {
      val code = connection.responseCode
      if (code !in 200..299) {
        return UpdateInfo(script.id, script.version, error = UpdateError.HTTP)
      }
      val text =
          connection.inputStream.use { readLimited(it) }
              ?: return UpdateInfo(script.id, script.version, error = UpdateError.TOO_LARGE)
      val parsed =
          parseScript(text, null, hydrateRequires = false)
              ?: return UpdateInfo(script.id, script.version, error = UpdateError.INVALID_SCRIPT)
      if (parsed.id != script.id) {
        return UpdateInfo(
            script.id,
            script.version,
            remoteVersion = parsed.version,
            remote = parsed,
            error = UpdateError.ID_MISMATCH,
        )
      }
      UpdateInfo(
          script.id,
          script.version,
          remoteVersion = parsed.version,
          remote = parsed,
      )
    } catch (thr: Throwable) {
      UpdateInfo(script.id, script.version, error = UpdateError.NETWORK)
    } finally {
      runCatching { connection.disconnect() }
    }
  }

  private fun readLimited(input: InputStream): String? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      total += read
      if (total > MAX_BYTES) return null
      output.write(buffer, 0, read)
    }
    return output.toString("UTF-8")
  }

  /**
   * Version comparison that tolerates the shapes userscripts actually use (`1.2`, `v1.2.3`,
   * `2024.05.01`, `1.0-beta`). Numeric segments compare numerically; a release wins over a
   * pre-release suffix; text segments compare lexically.
   */
  fun compareVersions(left: String, right: String): Int {
    val a = segments(left)
    val b = segments(right)
    val size = maxOf(a.size, b.size)
    for (index in 0 until size) {
      val x = a.getOrNull(index)
      val y = b.getOrNull(index)
      if (x == null) {
        // The left side ended: it is the release, so it wins over a pre-release suffix, but extra
        // numeric revisions on the right make the right side newer.
        val number = y?.toLongOrNull()
        if (number == null) return 1
        if (number == 0L) continue
        return -1
      }
      if (y == null) {
        val number = x.toLongOrNull()
        if (number == null) return -1
        if (number == 0L) continue
        return 1
      }
      val xn = x.toLongOrNull()
      val yn = y.toLongOrNull()
      val comparison =
          when {
            xn != null && yn != null -> xn.compareTo(yn)
            xn != null -> 1
            yn != null -> -1
            else -> x.compareTo(y, ignoreCase = true)
          }
      if (comparison != 0) return comparison
    }
    return 0
  }

  private fun segments(version: String): List<String> =
      version
          .trim()
          .removePrefix("v")
          .removePrefix("V")
          .split(Regex("[^A-Za-z0-9]+"))
          .filter { it.isNotEmpty() }
}
