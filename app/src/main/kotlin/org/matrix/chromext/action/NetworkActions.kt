package org.matrix.chromext.action

import android.content.Context
import android.os.Handler
import android.util.Base64
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.devtools.DevToolClient
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.script.CentralBridge
import org.matrix.chromext.script.Local
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.script.ScriptFiles
import org.matrix.chromext.script.guessMime
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
                  val request = detail.getJSONObject("request")
                  val method = request.optString("method", "GET").uppercase()
                  val cacheKey =
                      if (ScriptDbManager.engine.resourceCache &&
                          (method == "GET" || method == "HEAD")) {
                        ScriptFiles.resourceKeyForUrl(request.optString("url"))
                      } else null
                  if (cacheKey == null) {
                    sendRequest(context, detail, uuid, null)
                  } else {
                    // Look the local mirror up on the IO pool; a fresh hit answers without the
                    // network. A stale hit still answers immediately, then refreshes in the
                    // background so scripts never block on a slow CDN and never stay stale.
                    Chrome.IO.submit {
                      val cached = CentralBridge.getResource(cacheKey)
                      val fresh =
                          cached != null &&
                              System.currentTimeMillis() - cached.modified <= CACHE_TTL_MS
                      when {
                        cached != null && fresh ->
                            respondFromCache(context, detail, cached.bytes, request)
                        cached != null -> {
                          respondFromCache(context, detail, cached.bytes, request)
                          sendRequest(context, detail, Math.random(), cacheKey, silent = true)
                        }
                        else -> sendRequest(context, detail, uuid, cacheKey)
                      }
                    }
                  }
                }
                null
              },
          "cookie" to
              ActionHandler { context ->
                if (WebViewHook.isInit && ScriptDbManager.engine.webViewDebug) {
                  WebView.setWebContentsDebuggingEnabled(true)
                }
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

  private fun sendRequest(
      context: ActionContext,
      detail: JSONObject,
      uuid: Double,
      cacheKey: String?,
      silent: Boolean = false,
  ) {
    val request =
        XMLHttpRequest(
            detail.getString("id"),
            detail.getJSONObject("request"),
            uuid,
            context.tab,
            context.frameId,
            cacheKey,
            silent,
        )
    ActionState.xmlhttpRequests.put(uuid, request)
    Chrome.IO.submit { request.send() }
  }

  /**
   * Answers a GET from `Download/ChromeXT/resources` with the same message shape the native
   * request uses, so GM.js cannot tell the difference.
   */
  private fun respondFromCache(
      context: ActionContext,
      detail: JSONObject,
      bytes: ByteArray,
      request: JSONObject,
  ) {
    val name = request.optString("url").substringBefore('?').substringAfterLast('/')
    val mime = guessMime(name)
    val unknown = mime == "application/octet-stream"
    val text = isTextMime(mime) || (unknown && looksLikeText(bytes))
    val response =
        JSONObject(
            mapOf("id" to detail.getString("id"), "uuid" to detail.getDouble("uuid")))
    val data =
        JSONObject().apply {
          put("status", 200)
          put("statusText", "OK")
          put("headers", JSONObject().put("Content-Type", JSONArray(listOf(mime))))
          put("binary", !text)
          put(
              "chunk",
              if (text) String(bytes, Charsets.UTF_8)
              else Base64.encodeToString(bytes, Base64.NO_WRAP))
          put("bytes", bytes.size)
        }
    response.put("type", "progress")
    response.put("data", data)
    postResponse(context, response)
    data.remove("headers")
    response.put("type", "load")
    postResponse(context, response)
  }

  private fun postResponse(context: ActionContext, response: JSONObject) {
    val code = "Symbol.${Local.name}.unlock(${Local.key}).post('xmlhttpRequest', ${response});"
    Handler(Chrome.getContext().mainLooper).post {
      Chrome.evaluateJavascript(listOf(code), context.tab, context.frameId)
    }
  }

  private fun isTextMime(mime: String): Boolean {
    val lower = mime.lowercase()
    return lower.isEmpty() ||
        lower.startsWith("text/") ||
        lower.contains("json") ||
        lower.contains("javascript") ||
        lower.contains("xml") ||
        lower.contains("html") ||
        lower.contains("css")
  }

  private fun looksLikeText(bytes: ByteArray): Boolean =
      bytes.take(1024).none { it == 0.toByte() }
}

/** Runtime GET cache freshness; older entries are still served while a refresh runs. */
private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
