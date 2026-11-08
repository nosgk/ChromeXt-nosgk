package org.matrix.chromext.ui

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.script.ScriptRecord
import org.matrix.chromext.script.ScriptStore
import org.matrix.chromext.script.parseScript
import org.matrix.chromext.script.toRecord

/**
 * Backup format:
 *   chromext.json          - version + every row (including enable state and GM storage)
 *   scripts/<id>.user.js   - readable copy of each script
 *
 * Import also accepts a plain Violentmonkey-style ZIP of `.user.js` files; the JSON file is used
 * when present because it preserves the manager state.
 */
object Backup {

  fun export(context: Context, records: List<ScriptRecord>, uri: Uri): Boolean =
      runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
              ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                val json =
                    JSONObject().apply {
                      put("version", 1)
                      put("scripts", JSONArray(records.map { it.toJson() }))
                    }
                zip.putNextEntry(ZipEntry("chromext.json"))
                zip.write(json.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                records.forEach { record ->
                  zip.putNextEntry(ZipEntry("scripts/${safeName(record.id)}.user.js"))
                  zip.write((record.meta + record.code).toByteArray(Charsets.UTF_8))
                  zip.closeEntry()
                }
              }
              true
            } ?: false
          }
          .getOrDefault(false)

  fun import(context: Context, uri: Uri): Int =
      runCatching {
            var count = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
              val store = ScriptStore(context.applicationContext)
              try {
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                  var entry = zip.nextEntry
                  while (entry != null) {
                    val name = entry.name
                    if (name == "chromext.json") {
                      val text = zip.readBytes().toString(Charsets.UTF_8)
                      val array = JSONObject(text).optJSONArray("scripts") ?: JSONArray()
                      for (i in 0 until array.length()) {
                        val record = ScriptRecord.fromJson(array.getJSONObject(i))
                        if (store.upsert(record)) count += 1
                      }
                    } else if (name.endsWith(".user.js")) {
                      val text = zip.readBytes().toString(Charsets.UTF_8)
                      val parsed = parseScript(text, null, hydrateRequires = false)
                      if (parsed != null &&
                          store.upsert(
                              parsed.copy(lastUpdated = System.currentTimeMillis()).toRecord())) {
                        count += 1
                      }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                  }
                }
              } finally {
                store.close()
              }
            }
            count
          }
          .getOrDefault(-1)

  private fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
