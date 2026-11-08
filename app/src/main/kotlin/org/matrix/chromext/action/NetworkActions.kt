package org.matrix.chromext.action

import android.content.Context
import android.os.Handler
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.devtools.DevToolClient
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.script.Local
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.utils.XMLHttpRequest

object NetworkActions : ActionGroup {

  private fun syncSharedPreference(
      payload: String,
      item: String,
      cache: MutableMap<String, String>,
  ): String? {
    val result = JSONObject(payload)
    val origin = result.getString("origin")
    val sharedPref = Chrome.getContext().getSharedPreferences(item, Context.MODE_PRIVATE)
    with(sharedPref.edit()) {
      if (result.has("data") && result.optString("data").length > 0) {
        val data = result.getString("data")
        putString(origin, data)
        cache.put(origin, data)
      } else if (cache.containsKey(origin)) {
        remove(origin)
        cache.remove(origin)
      }
      apply()
    }
    return null
  }

  override val handlers =
      mapOf<String, ActionHandler>(
          "xmlhttpRequest" to
              ActionHandler { context ->
                val detail = JSONObject(context.payload)
                val uuid = detail.getDouble("uuid")
                if (detail.optBoolean("abort")) {
                  ActionState.xmlhttpRequests.get(uuid)?.abort()
                } else {
                  val request =
                      XMLHttpRequest(
                          detail.getString("id"),
                          detail.getJSONObject("request"),
                          uuid,
                          context.tab,
                          context.frameId,
                      )
                  ActionState.xmlhttpRequests.put(uuid, request)
                  Chrome.IO.submit { request.send() }
                }
                null
              },
          "cookie" to
              ActionHandler { context ->
                if (WebViewHook.isInit) WebView.setWebContentsDebuggingEnabled(true)
                val detail = JSONObject(context.payload)
                val method = detail.getString("method")
                val params = detail.optJSONObject("params")
                val data = JSONArray()
                fun checkResult(result: JSONObject): Boolean {
                  data.put(result)
                  if (result.has("id") && result.getInt("id") == 2) {
                    detail.put("response", data)
                    detail.remove("params")
                    val code =
                        "Symbol.${Local.name}.unlock(${Local.key}).post('cookie', ${detail});"
                    Handler(Chrome.getContext().mainLooper).post {
                      Chrome.evaluateJavascript(listOf(code), context.tab, context.frameId)
                    }
                    return false
                  }
                  return true
                }
                val url = Chrome.getUrl(context.tab)
                Chrome.IO.submit {
                  val tabId = Chrome.getTabId(context.tab, url)
                  val client = DevToolClient(tabId, "cookie")
                  Chrome.IO.submit { client.listen { if (!checkResult(it)) client.close() } }
                  client.command(null, "Network.enable", JSONObject())
                  client.command(null, method, params)
                }
                null
              },
          "syncData" to
              ActionHandler { context ->
                val type = JSONObject(context.payload).getString("name")
                when (type) {
                  "filters" ->
                      syncSharedPreference(
                          context.payload, "CosmeticFilter", ScriptDbManager.cosmeticFilters)
                  "userAgent" ->
                      syncSharedPreference(context.payload, "UserAgent", ScriptDbManager.userAgents)
                  "cspRules" ->
                      syncSharedPreference(context.payload, "CSPRule", ScriptDbManager.cspRules)
                  else -> null
                }
              },
      )
}
