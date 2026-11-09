package org.matrix.chromext.hook

import android.app.Activity
import android.os.Build
import android.os.Handler
import java.lang.ref.WeakReference
import org.matrix.chromext.Chrome
import org.matrix.chromext.Listener
import org.matrix.chromext.bridge.JsBridge
import org.matrix.chromext.script.Local
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.findField
import org.matrix.chromext.utils.findMethod
import org.matrix.chromext.utils.hookAfter
import org.matrix.chromext.utils.hookBefore
import org.matrix.chromext.utils.invokeMethod

object WebViewHook : BaseHook() {

  var ViewClient: Class<*>? = null
  var ChromeClient: Class<*>? = null
  var WebView: Class<*>? = null
  val records = mutableListOf<WeakReference<Any>>()

  fun evaluateJavascript(code: String?, view: Any?) {
    val webView = (view ?: Chrome.getTab())
    if (code == null || code.length == 0) return
    if (webView == null) {
      Log.d("evaluateJavascript skipped: no WebView")
      return
    }
    val webSettings = webView.invokeMethod { name == "getSettings" }
    if (webSettings?.invokeMethod { name == "getJavaScriptEnabled" } != true) {
      Log.d("evaluateJavascript skipped: JavaScript is disabled in this WebView")
      return
    }
    Handler(Chrome.getContext().mainLooper).post {
      webView.invokeMethod(code, null) { name == "evaluateJavascript" }
    }
  }

  override fun init() {

    findMethod(ChromeClient!!, true) { name == "onConsoleMessage" && parameterCount == 1 }
        // public boolean onConsoleMessage (ConsoleMessage consoleMessage)
        .hookAfter {
          // Don't use ConsoleMessage to specify this method since Mi Browser uses its own
          // implementation
          // This should be the way to communicate with the front-end of ChromeXt
          val chromeClient = it.thisObject
          val consoleMessage = it.args[0]
          val messageLevel = consoleMessage.invokeMethod { name == "messageLevel" }
          val sourceId = consoleMessage.invokeMethod { name == "sourceId" } as String
          val lineNumber = consoleMessage.invokeMethod { name == "lineNumber" }
          val message = consoleMessage.invokeMethod { name == "message" } as String
          if (messageLevel.toString() == "TIP" &&
              JsBridge.isInitSource(sourceId) &&
              lineNumber == Local.anchorInChromeXt) {
            val webView =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  records
                      .find {
                        if (Chrome.isQihoo) {
                              val mProvider = findField(WebView!!) { name == "mProvider" }
                              mProvider.get(it.get())
                            } else {
                              it.get()
                            }
                            ?.invokeMethod { name == "getWebChromeClient" } == chromeClient
                      }
                      ?.get()
                } else Chrome.getTab()
            Listener.startAction(message, webView, chromeClient, sourceId)
          } else {
            Log.d(messageLevel.toString() + ": [${sourceId}@${lineNumber}] ${message}")
          }
        }

    fun onUpdateUrl(url: String, view: Any?) {
      if (url.startsWith("javascript") || view == null) return
      Chrome.updateTab(view)
      ScriptDbManager.invokeScript(url, view)
    }

    findMethod(WebView!!) { name == "setWebChromeClient" }
        .hookAfter {
          val webView = it.thisObject
          records.removeAll(records.filter { it.get() == null || it.get() == webView })
          if (it.args[0] != null) records.add(WeakReference(webView))
          Log.d(
              "setWebChromeClient: ${webView.javaClass.name} " +
                  "client=${it.args[0]?.javaClass?.name}")
        }

    findMethod(WebView!!) { name == "onAttachedToWindow" }
        .hookAfter {
          Log.d("WebView attached: ${it.thisObject.javaClass.name}")
          Chrome.updateTab(it.thisObject)
        }

    findMethod(ViewClient!!, true) { name == "onPageStarted" }
        // public void onPageStarted (WebView view, String url, Bitmap favicon)
        .hookAfter {
          if (Chrome.isQihoo && it.thisObject::class.java.declaredMethods.size > 1) return@hookAfter
          val url = it.args[1] as String
          Log.d("onPageStarted: ${it.thisObject.javaClass.name} $url")
          onUpdateUrl(url, it.args[0])
        }

    findMethod(Activity::class.java) { name == "onStop" }
        .hookBefore { ScriptDbManager.updateScriptStorage() }
    isInit = true
  }
}
