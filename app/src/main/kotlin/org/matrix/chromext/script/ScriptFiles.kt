package org.matrix.chromext.script

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.utils.Log

/**
 * Mirrors the central database into the public `Download/ChromeXT/` directory.
 *
 * The SQLite database stays private to the module: several browser processes with different UIDs
 * cannot safely share one file over FUSE. The mirror is what users see and manage:
 *
 * ```
 * Download/ChromeXT/
 *   manifest.json
 *   userscript/<name>.user.js
 *   resources/<script>/<@resource name>
 *   resources/http/<host>/<path>        (runtime GM_xmlhttpRequest cache)
 * ```
 *
 * Writing is done by the module process only (manager UI and ContentProvider), never by a browser.
 */
object ScriptFiles {

  const val ROOT_PATH = "/storage/emulated/0/Download/ChromeXt"
  const val SCRIPTS_DIR = "userscript"
  const val RESOURCES_DIR = "resources"

  private const val MAX_SEGMENT = 80

  fun rootPath(): String = ROOT_PATH

  fun scriptFileName(id: String): String = safeName(id) + ".user.js"

  fun safeName(raw: String): String {
    val cleaned =
        raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimEnd('.', ' ').ifBlank {
          "script"
        }
    if (cleaned.length <= MAX_SEGMENT) return cleaned
    return cleaned.take(MAX_SEGMENT - 9) + "-" + Integer.toHexString(raw.hashCode())
  }

  /** Rewrites one script into the public directory and remembers the document. */
  fun mirrorScript(context: Context, store: ScriptStore, record: ScriptRecord): MirrorEntry? {
    val bytes = (record.meta + record.code).toByteArray(Charsets.UTF_8)
    val entry = upsert(context, SCRIPTS_DIR, scriptFileName(record.id), "text/javascript", bytes)
        ?: return null
    val mirror = MirrorEntry(record.id, entry.first, entry.second)
    store.putMirrorEntry(mirror)
    return mirror
  }

  fun deleteScriptMirror(context: Context, store: ScriptStore, scriptId: String) {
    val entry = store.mirrorEntry(scriptId)
    store.deleteMirrorEntry(scriptId)
    if (entry == null) return
    runCatching {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && entry.uri.startsWith("content://")) {
            context.contentResolver.delete(Uri.parse(entry.uri), null, null)
          } else {
            File(entry.path).delete()
          }
        }
        .onFailure { Log.d("Cannot remove mirrored script ${entry.path}: ${it.message}") }
  }

  /** Writes an `@resource` payload under `resources/<scriptKey>/<name>`. */
  fun mirrorResource(
      context: Context,
      scriptKey: String,
      name: String,
      bytes: ByteArray,
      mime: String = "",
  ): Boolean {
    val relative = "$RESOURCES_DIR/${safeName(scriptKey)}/${safeName(name)}"
    return upsert(context, relative, safeName(name), mime.ifBlank { "application/octet-stream" }, bytes) !=
        null
  }

  /** Writes a cached HTTP response under `resources/http/<host>/<path>`. */
  fun mirrorHttpResource(context: Context, key: String, bytes: ByteArray, mime: String = ""): Boolean {
    val parts = key.split('/').filter { it.isNotEmpty() }
    if (parts.size < 2 || parts[0] != "http") return false
    val name = parts.last()
    val relative = "$RESOURCES_DIR/" + parts.dropLast(1).joinToString("/")
    return upsert(context, relative, name, mime.ifBlank { "application/octet-stream" }, bytes) != null
  }

  fun writeManifest(context: Context, store: ScriptStore) {
    val records = store.records()
    val scripts = JSONArray()
    records.forEach { record ->
      scripts.put(
          JSONObject().apply {
            put("id", record.id)
            put("file", "$SCRIPTS_DIR/${scriptFileName(record.id)}")
            put("version", parseVersion(record.meta))
            put("enabled", record.enabled)
          })
    }
    val manifest =
        JSONObject().apply {
          put("generator", "ChromeXt")
          put("generatedAt", System.currentTimeMillis())
          put("scripts", scripts)
        }
    upsert(
        context,
        "",
        "manifest.json",
        "application/json",
        manifest.toString(2).toByteArray(Charsets.UTF_8))
  }

  private fun parseVersion(meta: String): String =
      meta.lineSequence()
          .map { it.trim() }
          .firstOrNull { it.startsWith("// @version") }
          ?.substringAfter("@version")
          ?.trim()
          .orEmpty()

  // ---------------------------------------------------------------------------------------------
  // Private resource store used by the ContentProvider (browser processes cannot read files owned
  // by the module, and public storage needs All-files access on Android 13+).
  // ---------------------------------------------------------------------------------------------

  fun privateResourceFile(context: Context, key: String): File =
      File(File(context.filesDir, "resources"), sanitizeKey(key))

  fun writePrivateResource(context: Context, key: String, bytes: ByteArray): Boolean =
      runCatching {
            val target = privateResourceFile(context, key)
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
              target.writeBytes(bytes)
              tmp.delete()
            }
            true
          }
          .getOrDefault(false)

  fun readPrivateResource(context: Context, key: String): ByteArray? =
      runCatching { privateResourceFile(context, key).takeIf { it.isFile }?.readBytes() }.getOrNull()

  fun deletePrivateResource(context: Context, key: String) {
    runCatching { privateResourceFile(context, key).delete() }
  }

  /** Maps an HTTP(S) URL to a stable, traversal-safe cache key: `http/<host>/<path>[.<hash>]`. */
  fun resourceKeyForUrl(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val path = (uri.path ?: "/").split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
    val base =
        "http/" +
            safeName(host) +
            (if (path.isEmpty()) "" else "/" + path.joinToString("/") { safeName(it) })
    val query = uri.query
    return if (query.isNullOrBlank()) base
    else base.take(base.length.coerceAtMost(180)) + "." + Integer.toHexString(query.hashCode())
  }

  fun sanitizeKey(key: String): String =
      key.split('/', '\\')
          .filter { it.isNotEmpty() && it != "." && it != ".." }
          .joinToString("/")
          .ifBlank { "misc" }

  // ---------------------------------------------------------------------------------------------

  private fun upsert(
      context: Context,
      relativeDir: String,
      displayName: String,
      mime: String,
      bytes: ByteArray,
  ): Pair<String, String>? {
    val dir =
        if (relativeDir.isBlank()) "ChromeXt"
        else "ChromeXt/" + sanitizeKey(relativeDir)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      upsertMediaStore(context, dir, displayName, mime, bytes)
    } else {
      upsertFile(dir, displayName, bytes)
    }
  }

  private fun upsertMediaStore(
      context: Context,
      relativeDir: String,
      displayName: String,
      mime: String,
      bytes: ByteArray,
  ): Pair<String, String>? = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + relativeDir
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        var uri =
            resolver
                .query(
                    collection,
                    projection,
                    selection,
                    arrayOf("$relativePath/", displayName),
                    null)
                ?.use { cursor ->
                  if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                  } else null
                }
        if (uri == null) {
          val values =
              ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
              }
          uri = resolver.insert(collection, values)
        }
        val target = uri ?: return@runCatching null
        resolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: return@runCatching null
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(target, done, null, null)
        val displayPath =
            if (relativeDir.isBlank()) "$ROOT_PATH/$displayName"
            else "$ROOT_PATH/$relativeDir/$displayName"
        target.toString() to displayPath
      }
      .onFailure { Log.d("Cannot mirror $relativeDir/$displayName: ${it.message}") }
      .getOrNull()

  private fun upsertFile(relativeDir: String, displayName: String, bytes: ByteArray): Pair<String, String>? =
      runCatching {
            val directory =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    relativeDir)
            directory.mkdirs()
            val target = File(directory, displayName)
            target.writeBytes(bytes)
            "file://${target.absolutePath}" to target.absolutePath
          }
          .onFailure { Log.d("Cannot mirror $relativeDir/$displayName: ${it.message}") }
          .getOrNull()
}
