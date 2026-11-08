package org.matrix.chromext.action

import android.os.Handler
import org.json.JSONObject
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.Chrome
import org.matrix.chromext.devtools.DevSessions
import org.matrix.chromext.devtools.DevToolClient
import org.matrix.chromext.devtools.hitDevTools
import org.matrix.chromext.extension.LocalFiles
import org.matrix.chromext.utils.Log

object DevToolsActions : ActionGroup {
  override val handlers =
      mapOf<String, ActionHandler>(
          "extension" to
              ActionHandler { context ->
                var callback: String? = null
                if (context.payload == "") {
                  if (BuildConfig.DEBUG) {
                    callback = "ChromeXt.post('extension', ${LocalFiles.start()});"
                  } else {
                    Log.toast(
                        Chrome.getContext(), "Work in progress, might be ready in the future :)")
                  }
                }
                callback
              },
          "websocket" to
              ActionHandler { context ->
                val detail = JSONObject(context.payload)
                val targetTabId = detail.getString("targetTabId")
                var target = DevSessions.get { it.tabId == targetTabId && it.tag == "transit" }
                if (detail.has("message")) {
                  val message = JSONObject(detail.getString("message"))
                  target?.command(
                      message.getInt("id"),
                      message.getString("method"),
                      message.optJSONObject("params"),
                  )
                } else {
                  fun response(res: JSONObject) {
                    if (Chrome.checkTab(context.tab)) {
                      Handler(Chrome.getContext().mainLooper).post {
                        Chrome.evaluateJavascript(
                            listOf("ChromeXt.post('websocket', ${res})"),
                            context.tab,
                            context.frameId,
                        )
                      }
                    } else {
                      target?.close()
                    }
                  }
                  Chrome.IO.submit {
                    target?.close()
                    hitDevTools().close()
                    target = DevToolClient(targetTabId, "transit")
                    if (!target!!.isClosed()) {
                      DevSessions.add(target!!)
                      response(JSONObject(mapOf("open" to true)))
                      target!!.listen { response(JSONObject(mapOf("message" to it))) }
                    }
                    response(JSONObject(mapOf("error" to "Remote session closed")))
                  }
                }
                null
              },
      )
}
