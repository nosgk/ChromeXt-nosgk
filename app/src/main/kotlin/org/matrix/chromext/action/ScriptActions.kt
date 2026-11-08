package org.matrix.chromext.action

import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.Chrome
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
                  callback = "alert('Invalid UserScript');"
                } else {
                  Log.i("Install script ${script.id}")
                  // Prefer the native confirmation screen; fall back to the old direct install
                  // when the module Activity cannot be started (for example, a restricted ROM).
                  if (!ManagerLauncher.installScript(Chrome.getContext(), context.payload)) {
                    val existing = ScriptDbManager.scripts.find { it.id == script.id }
                    val toInsert =
                        if (existing == null) {
                          // Installed from this app: scope it here instead of enabling it globally.
                          script.copy(apps = listOfNotNull(Chrome.packageName))
                        } else {
                          // Re-installing keeps the user's enable state, ordering and overrides.
                          script.copy(
                              enabled = existing.enabled,
                              sortOrder = existing.sortOrder,
                              customMatch = existing.customMatch,
                              customInclude = existing.customInclude,
                              customExclude = existing.customExclude,
                              customRunAt = existing.customRunAt,
                              apps = existing.apps,
                              lib = existing.lib,
                          )
                        }
                    ScriptDbManager.insert(toInsert)
                  }
                }
                callback
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
}
