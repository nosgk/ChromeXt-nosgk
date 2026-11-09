package org.matrix.chromext.script

import android.content.Context
import android.util.Base64
import org.matrix.chromext.utils.Log

/**
 * Downloads `@require` and `@resource` payloads while the manager process is installing or
 * importing a script.
 *
 * Parsing itself stays pure (no network), so a database read or a page injection never waits for a
 * remote file. Everything fetched here is persisted in the central database and mirrored to
 * `Download/ChromeXt/resources/`, which also makes the script work offline afterwards.
 */
object ScriptHydrator {

  private val requireReg = Regex("""(?m)^//\s+@require\s+(\S+)""")

  fun hydrate(context: Context, script: Script): Script {
    val lib = mutableListOf<String>()
    requireReg.findAll(script.meta).forEach { match ->
      runCatching { lib.add(downloadLib(match.groupValues[1])) }
          .onFailure { Log.d("Cannot fetch @require ${match.groupValues[1]}: ${it.message}") }
    }
    val resources =
        script.resources.map { resource ->
          if (resource.url.isBlank()) return@map resource
          runCatching {
                val bytes = downloadBytes(resource.url)
                val key = resourceKey(script.id, resource.name)
                ScriptFiles.writePrivateResource(context, key, bytes)
                ScriptFiles.mirrorResource(
                    context, script.id, resource.name, bytes, resource.mime)
                resource.copy(
                    data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    mime = resource.mime.ifBlank { guessMime(resource.name) },
                )
              }
              .onFailure {
                Log.d("Cannot fetch @resource ${resource.name}: ${it.message}")
              }
              .getOrDefault(resource)
        }
    return script.copy(lib = lib, resources = resources)
  }

  fun resourceKey(scriptId: String, name: String): String =
      "script/" + ScriptFiles.safeName(scriptId) + "/" + ScriptFiles.safeName(name)
}
