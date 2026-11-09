package org.matrix.chromext.script

import android.net.Uri
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import kotlin.text.Regex
import org.json.JSONObject

private val blocksReg =
    Regex(
        """(?<metablock>[\S\s]*?// ==UserScript==\r?\n[\S\s]*?\r?\n// ==/UserScript==\s*)(?<code>[\S\s]*)""")
private val metaReg = Regex("""^//\s+@(?<key>[\w-]+)(\s+(?<value>.+))?""")

fun parseScript(
    input: String,
    storage: String? = null,
    @Suppress("UNUSED_PARAMETER") hydrateRequires: Boolean = false,
): Script? {
  val blockMatchGroup = blocksReg.matchEntire(input)?.groups
  if (blockMatchGroup == null) {
    return null
  }

  val script =
      object {
        var name = "sample"
        var namespace = "ChromeXt"
        var match = mutableListOf<String>()
        var grant = mutableListOf<String>()
        var exclude = mutableListOf<String>()
        var require = mutableListOf<String>()
        var resources = mutableListOf<ScriptResource>()
        var version = ""
        var author = ""
        var description = ""
        var iconUrl = ""
        var updateUrl = ""
        var runAt = ""
        val meta = (blockMatchGroup[1]?.value as String)
        val code = blockMatchGroup[2]?.value as String
        var storage: JSONObject? = null
        var noframes = false
      }
  script.meta.split("\n").forEach {
    val metaMatchGroup = metaReg.matchEntire(it)?.groups
    if (metaMatchGroup != null) {
      val key = metaMatchGroup[1]?.value as String
      if (metaMatchGroup[2] != null) {
        val value = metaMatchGroup[3]?.value as String
        when (key) {
          "name" -> script.name = value.replace(":", "")
          "namespace" -> script.namespace = value
          "match" -> script.match.add(value)
          "include" -> script.match.add(value)
          "grant" -> script.grant.add(value)
          "exclude" -> script.exclude.add(value)
          "require" -> script.require.add(value)
          "resource" -> {
            val parts = value.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (parts.isNotEmpty()) {
              val url = parts.getOrNull(1).orEmpty().substringBefore("#")
              script.resources.add(
                  ScriptResource(
                      name = parts[0],
                      url = url,
                      mime = guessMime(url.substringAfterLast('/'))))
            }
          }
          "version" -> script.version = value
          "author" -> script.author = value
          "description" -> script.description = value
          "icon", "iconURL" -> script.iconUrl = value
          "updateURL", "downloadURL" -> script.updateUrl = value
          "run-at" -> script.runAt = value
          "noframes" -> script.noframes = true
        }
      } else {
        when (key) {
          "noframes" -> script.noframes = true
        }
      }
    }
  }

  if (!script.grant.contains("GM_xmlhttpRequest") &&
      (script.grant.contains("GM_download") ||
          script.grant.contains("GM.xmlHttpRequest") ||
          script.grant.contains("GM_getResourceText"))) {
    script.grant.add("GM_xmlhttpRequest")
  }

  if (script.grant.contains("GM.getValue") ||
      script.grant.contains("GM_getValue") ||
      script.grant.contains("GM_cookie")) {
    runCatching { script.storage = JSONObject(storage!!) }
        .onFailure { script.storage = JSONObject() }
  }

  if (script.match.size == 0) {
    return null
  } else {
    // `@require` and `@resource` payloads are hydrated at install time by ScriptHydrator; parsing
    // stays pure so a database read never blocks on the network.
    val lib = mutableListOf<String>()
    // Keep the stored parameters inside the values the injector understands: an unknown @run-at
    // would otherwise silently fall back in GM.js, and a non-http update URL can never be fetched.
    val runAt =
        script.runAt.trim().takeIf {
          it == "document-start" || it == "document-end" || it == "document-idle"
        } ?: ""
    val updateUrl =
        script.updateUrl.trim().takeIf {
          it.startsWith("http://") || it.startsWith("https://")
        } ?: ""
    val parsed =
        Script(
            script.namespace + ":" + script.name,
            script.match.distinct().toTypedArray(),
            script.grant.distinct().toTypedArray(),
            script.exclude.distinct().toTypedArray(),
            script.meta,
            script.code,
            script.storage,
            lib,
            script.noframes,
            updateUrl = updateUrl,
            iconUrl = script.iconUrl,
            lastUpdated = System.currentTimeMillis(),
            version = script.version,
            author = script.author,
            description = script.description,
            runAt = runAt,
            resources = script.resources.distinctBy { it.name },
        )
    return parsed
  }
}

/** Minimal MIME hint for `@resource` entries; used for blob URLs and the Download mirror. */
fun guessMime(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
      "js" -> "text/javascript"
      "css" -> "text/css"
      "json" -> "application/json"
      "html", "htm" -> "text/html"
      "txt" -> "text/plain"
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "gif" -> "image/gif"
      "svg" -> "image/svg+xml"
      "webp" -> "image/webp"
      "woff" -> "font/woff"
      "woff2" -> "font/woff2"
      else -> "application/octet-stream"
    }

internal const val MAX_HYDRATE_BYTES = 4 * 1024 * 1024

internal fun downloadLib(libUrl: String): String {
  if (libUrl.startsWith("data:")) {
    val chunks = libUrl.split(",").toMutableList()
    val type = chunks.removeAt(0)
    val data = Uri.decode(chunks.joinToString(""))
    if (type.endsWith("base64")) {
      return Base64.decode(data, Base64.DEFAULT).toString()
    } else {
      return data
    }
  }
  return String(downloadBytes(libUrl), Charsets.UTF_8)
}

/** Downloads a `@require`/`@resource` payload with a timeout and a hard size cap. */
internal fun downloadBytes(url: String): ByteArray {
  if (url.startsWith("data:")) {
    val chunks = url.split(",").toMutableList()
    val type = chunks.removeAt(0)
    val data = Uri.decode(chunks.joinToString(""))
    return if (type.endsWith("base64")) Base64.decode(data, Base64.DEFAULT)
    else data.toByteArray(Charsets.UTF_8)
  }
  val parsed = URL(url)
  val protocol = parsed.protocol.lowercase()
  require(protocol == "http" || protocol == "https") {
    "Unsupported scheme: $protocol"
  }
  val connection = parsed.openConnection() as HttpURLConnection
  connection.connectTimeout = 15_000
  connection.readTimeout = 15_000
  return connection.inputStream.use { input ->
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      total += read
      require(total <= MAX_HYDRATE_BYTES) { "Resource is larger than 4 MiB" }
      output.write(buffer, 0, read)
    }
    output.toByteArray()
  }
}
