package org.matrix.chromext.action

import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.script.Local
import org.matrix.chromext.script.ScriptDbHelper
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.script.parseScript
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.matching
import org.matrix.chromext.utils.parseOrigin

object ScriptActions : ActionGroup {
  override val handlers =
      mapOf<String, ActionHandler>(
          "installScript" to
              ActionHandler { context ->
                var callback: String? = null
                val script = parseScript(context.payload)
                if (script == null) {
                  callback = "alert('Invalid UserScript');"
                } else {
                  Log.i("Install script ${script.id}")
                  ScriptDbManager.apply {
                    insert(script)
                    scripts.removeAll(scripts.filter { it.id == script.id })
                    scripts.add(script)
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
                      ScriptDbManager.insert(newScript)
                      ScriptDbManager.scripts.remove(script)
                      ScriptDbManager.scripts.add(newScript)
                    } else {
                      callback = "alert('Fail to update script metadata');"
                    }
                  } else if (data.has("ids")) {
                    val jsonArray = data.getJSONArray("ids")
                    val ids = Array(jsonArray.length()) { jsonArray.getString(it) }
                    val scripts = ScriptDbManager.scripts.filter { ids.contains(it.id) }
                    if (data.optBoolean("delete")) {
                      val dbHelper = ScriptDbHelper(Chrome.getContext())
                      val db = dbHelper.writableDatabase
                      db.delete("script", "id = ?", ids)
                      ScriptDbManager.scripts.removeAll(scripts)
                      dbHelper.close()
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
