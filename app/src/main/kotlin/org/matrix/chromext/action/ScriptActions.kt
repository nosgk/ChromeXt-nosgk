package org.matrix.chromext.action

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.script.CentralBridge
import org.matrix.chromext.script.Local
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.script.parseScript
import org.matrix.chromext.ui.ManagerLauncher
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.matching
import org.matrix.chromext.utils.parseOrigin

object ScriptActions : ActionGroup {
  override val handlers =
      mapOf<String, ActionHandler>(
          "installScript" to
              ActionHandler { context ->
                var callback: String? = null
                val script = parseScript(context.payload, hydrateRequires = false)
                if (script == null) {
                  callback = "alert('${invalidScriptMessage()}');"
                } else {
                  Log.i("Install script ${script.id}")
                  val existing = ScriptDbManager.scripts.find { it.id == script.id }
                  val currentPackage = Chrome.packageName
                  val toInsert =
                      if (existing == null) {
                        // Installed from this app: scope it here instead of enabling it globally.
                        script.copy(apps = listOfNotNull(currentPackage))
                      } else {
                        // Re-installing keeps the user's enable state, ordering and overrides, and
                        // adds the app it was installed from to a non-global scope.
                        val apps =
                            if (existing.apps.isNotEmpty() &&
                                currentPackage != null &&
                                currentPackage !in existing.apps) {
                              existing.apps + currentPackage
                            } else {
                              existing.apps
                            }
                        script.copy(
                            enabled = existing.enabled,
                            sortOrder = existing.sortOrder,
                            customMatch = existing.customMatch,
                            customInclude = existing.customInclude,
                            customExclude = existing.customExclude,
                            customRunAt = existing.customRunAt,
                            apps = apps,
                            lib = existing.lib,
                            resources = existing.resources,
                        )
                      }
                  // Install first. A blocked background activity start (ColorOS and other OEM
                  // restrictions) must not lose the script; the native screen is only a review
                  // step now.
                  if (!ScriptDbManager.insert(toInsert)) {
                    callback = "alert('${installFailedMessage()}');"
                  } else {
                    // @require/@resource payloads are downloaded in the module process, which owns
                    // the network permission and the resources directory.
                    Chrome.IO.submit { CentralBridge.hydrate(script.id) }
                    if (ScriptDbManager.engine.installConfirm) {
                      val ctx = runCatching { Chrome.getContext() }.getOrNull()
                      if (ctx != null && !ManagerLauncher.installScript(ctx, context.payload)) {
                        ManagerLauncher.notifyInstalled(ctx, toInsert.id)
                      }
                    }
                  }
                }
                callback
              },
          "scriptActive" to
              ActionHandler { context ->
                ScriptDbManager.reportActivation(context.payload)
                null
              },
          "scriptStorage" to
              ActionHandler { context ->
                var callback: String? = null
                val detail = JSONObject(context.payload)
                val id = detail.getString("id")
                val script = ScriptDbManager.scripts.find { it.id == id }
                if (script?.storage != null) {
                  if (detail.optBoolean("broadcast")) {
                    detail.remove("broadcast")
                    Chrome.broadcast("scriptStorage", detail) {
                      it != null && matching(script, it) && parseOrigin(it) != null
                    }
                  }
                  val data = detail.getJSONObject("data")
                  val key = data.getString("key")
                  if (data.has("value")) {
                    script.storage!!.put(key, data.get("value"))
                  } else if (data.has("id")) {
                    if (script.storage!!.has(key)) {
                      data.put("value", script.storage!!.get(key))
                    }
                    detail.put("data", data)
                    callback =
                        "Symbol.${Local.name}.unlock(${Local.key}).post('scriptSyncValue', ${detail});"
                  } else {
                    script.storage!!.remove(key)
                  }
                }
                callback
              },
          "userscript" to
              ActionHandler { context ->
                var callback: String? = null
                if (context.payload == "") {
                  val detail = JSONObject(mapOf("type" to "init"))
                  detail.put("ids", JSONArray(ScriptDbManager.scripts.map { it.id }))
                  callback = "ChromeXt.post('userscript', ${detail});"
                } else {
                  val data = JSONObject(context.payload)
                  if (data.has("meta")) {
                    val script =
                        ScriptDbManager.scripts.filter { it.id == data.getString("id") }.first()
                    val newScript =
                        parseScript(
                            data.getString("meta") + script.code, script.storage?.toString())
                    if (newScript != null) {
                      val updated =
                          newScript.copy(
                              enabled = script.enabled,
                              sortOrder = script.sortOrder,
                              customMatch = script.customMatch,
                              customInclude = script.customInclude,
                              customExclude = script.customExclude,
                              customRunAt = script.customRunAt,
                              updateUrl = script.updateUrl,
                              iconUrl = script.iconUrl,
                              apps = script.apps,
                              lib = script.lib,
                              lastUpdated = System.currentTimeMillis(),
                          )
                      ScriptDbManager.insert(updated)
                    } else {
                      callback = "alert('Fail to update script metadata');"
                    }
                  } else if (data.has("ids")) {
                    val jsonArray = data.getJSONArray("ids")
                    val ids = Array(jsonArray.length()) { jsonArray.getString(it) }
                    val scripts = ScriptDbManager.scripts.filter { ids.contains(it.id) }
                    if (data.optBoolean("delete")) {
                      ScriptDbManager.delete(ids)
                    } else {
                      val result = JSONArray(scripts.map { it.meta })
                      callback = "ChromeXt.post('script_meta', ${result});"
                    }
                  }
                }
                callback
              },
      )

  private fun chinese(): Boolean = Locale.getDefault().language.lowercase().startsWith("zh")

  private fun invalidScriptMessage(): String =
      if (chinese()) "无效的用户脚本" else "Invalid UserScript"

  private fun installFailedMessage(): String =
      if (chinese()) "安装失败：无法写入脚本库" else "Install failed: cannot write to the script library"
}
