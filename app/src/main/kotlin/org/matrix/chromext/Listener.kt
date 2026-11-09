package org.matrix.chromext

import org.json.JSONObject
import org.matrix.chromext.action.ActionContext
import org.matrix.chromext.action.ActionRegistry
import org.matrix.chromext.script.Local
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.isChromeXtFrontEnd
import org.matrix.chromext.utils.isDevToolsFrontEnd
import org.matrix.chromext.utils.isUserScript

/**
 * Entry point for `ChromeXt.dispatch` calls coming from injected JavaScript. This object only
 * authenticates the request and forwards it to [ActionRegistry]; the actions themselves live in
 * `org.matrix.chromext.action`.
 */
object Listener {

  val allowedActions =
      mapOf(
          "userscript" to listOf("block", "installScript"),
          "front-end" to listOf("inspect_pages", "userscript", "extension"),
          "devtools" to listOf("websocket"),
      )

  private fun checkPermisson(action: String, key: Double, tab: Any?): Boolean {
    if (key != Local.key) return false
    // At document-start the tab URL can still be empty; a null URL must not throw here or the
    // whole dispatch (activation reports, GM storage, installs) is silently lost.
    val url = Chrome.getUrl(tab) ?: return false
    if (url.endsWith(".txt")) return false
    if (isUserScript(url) && !allowedActions.get("userscript")!!.contains(action)) return false
    if (allowedActions.get("front-end")!!.contains(action) && !isChromeXtFrontEnd(url)) return false
    if (allowedActions.get("devtools")!!.contains(action) && !isDevToolsFrontEnd(url)) return false
    return true
  }

  fun startAction(text: String, currentTab: Any? = null, auxObject: Any? = null, sourceId: String) {
    var frameId: String? = null
    val sourceParts = sourceId.split("/init/")
    if (sourceParts.size > 1) frameId = sourceParts.last()
    runCatching {
      val data = JSONObject(text)
      val action = data.getString("action")
      val key = data.getDouble("key")
      val payload = data.optString("payload")
      if (checkPermisson(action, key, currentTab)) {
        val callback = on(action, payload, currentTab, auxObject, frameId)
        if (callback != null) Chrome.evaluateJavascript(listOf(callback), currentTab, frameId)
      }
    }
        .onFailure { Log.i("${it::class.java.name}: startAction fails with " + text) }
  }

  fun on(
      action: String,
      payload: String = "",
      currentTab: Any? = null,
      auxObject: Any? = null,
      frameId: String? = null,
  ): String? = ActionRegistry.handle(ActionContext(action, payload, currentTab, auxObject, frameId))
}
