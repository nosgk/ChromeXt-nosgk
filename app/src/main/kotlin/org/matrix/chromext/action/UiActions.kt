package org.matrix.chromext.action

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.webkit.WebView
import java.io.File
import java.io.FileReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.R
import org.matrix.chromext.devtools.getInspectPages
import org.matrix.chromext.hook.UserScriptHook
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.proxy.UserScriptProxy
import org.matrix.chromext.script.Local
import org.matrix.chromext.script.ScriptDbManager
import org.matrix.chromext.utils.ERUD_URL
import org.matrix.chromext.utils.Log

object UiActions : ActionGroup {

  private fun checkErudaVersion(ctx: Context, callback: (String?) -> Unit) {
    val url = URL(ERUD_URL + "@latest/eruda.js")
    val connection = url.openConnection() as HttpURLConnection
    runCatching {
      connection.inputStream.bufferedReader().use {
        val firstLine = it.readLine()
        val newVersion = Local.getErudaVersion(ctx, firstLine)
        if (newVersion == null) {
          callback(null)
        } else if (newVersion != Local.eruda_version) {
          Local.eruda_version = newVersion
          callback(firstLine + "\n" + it.readText())
        } else {
          callback("latest")
        }
      }
    }
        .onFailure { callback(null) }
  }

  override val handlers =
      mapOf<String, ActionHandler>(
          "notification" to
              ActionHandler { context ->
                var callback: String? = null
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                  callback = "console.error('notification API requires Android Oreo')"
                } else {
                  val detail = JSONObject(context.payload)
                  val id = detail.optString("id")
                  val uuid = detail.optInt("uuid")
                  // Tampermonkey allows a missing title or text; the previous getString() threw
                  // and the notification silently disappeared.
                  val title = detail.optString("title", "")
                  val text = detail.optString("text", "")
                  val timeout = detail.optLong("timeout", 10_000L)
                  val ctx = Chrome.getContext()
                  var channel = "xposed_notification"
                  if (detail.optBoolean("silent")) channel += "_slient"
                  val builder =
                      Notification.Builder(ctx, channel)
                          .setContentTitle(title)
                          .setContentText(text)
                          .setSmallIcon(
                              Icon.createWithResource(
                                  "org.matrix.chromext", R.drawable.ic_extension))
                          .setTimeoutAfter(timeout)
                          .setCategory(Notification.CATEGORY_MESSAGE)
                          .setAutoCancel(true)
                          .setLocalOnly(true)
                          .setOnlyAlertOnce(true)
                  if (detail.optBoolean("onclick")) {
                    builder.setContentIntent(
                        ScriptNotification.newIntent(id, uuid, context.frameId))
                    context.tab?.let { ActionState.tabNotification.put(uuid, it) }
                  }
                  val notificationManager =
                      ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                  if (detail.has("image")) {
                    Chrome.IO.submit {
                      runCatching {
                        val url = URL(detail.getString("image"))
                        val connection = url.openConnection() as HttpURLConnection
                        val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                        builder.setLargeIcon(Icon.createWithBitmap(bitmap))
                      }
                          .onFailure { Log.d("Fail to set notification image: ${it.message}") }
                      runCatching { notificationManager.notify(id, uuid, builder.build()) }
                          .onFailure { Log.d("GM_notification failed: ${it.message}") }
                    }
                  } else {
                    runCatching { notificationManager.notify(id, uuid, builder.build()) }
                        .onFailure { Log.d("GM_notification failed: ${it.message}") }
                  }
                  if (detail.optBoolean("highlight")) {
                    (ctx as? Activity)?.window?.decorView?.requestFocus()
                  }
                }
                callback
              },
          "loadEruda" to
              ActionHandler { context ->
                val ctx = Chrome.getContext()
                val eruda = File(ctx.filesDir, "Eruda.js")
                if (eruda.exists()) {
                  val codes =
                      mutableListOf(
                          FileReader(eruda).use { it.readText() } +
                              "\n//# sourceURL=${ERUD_URL}@${Local.eruda_version}/eruda.js")
                  codes.add("{${Local.eruda}}\n//# sourceURL=local://ChromeXt/eruda")
                  Chrome.evaluateJavascript(codes, context.tab, context.frameId)
                } else {
                  ActionRegistry.handle(
                      ActionContext(
                          "updateEruda", JSONObject().put("load", true).toString(), null, null, null))
                }
                null
              },
          "updateEruda" to
              ActionHandler { context ->
                val ctx = Chrome.getContext()
                Handler(ctx.mainLooper).post { Log.toast(ctx, "Updating Eruda...") }
                Chrome.IO.submit {
                  checkErudaVersion(ctx) {
                    val msg =
                        if (it == "latest") {
                          "Eruda is already the latest"
                        } else if (it != null) {
                          "Updated to eruda v" + Local.eruda_version
                        } else {
                          "Failed to download Eruda.js from ${ERUD_URL}"
                        }
                    Handler(ctx.mainLooper).post { Log.toast(ctx, msg) }
                    if (it != null) {
                      if (it != "latest") {
                        val file = File(ctx.filesDir, "Eruda.js")
                        file.outputStream().write(it.toByteArray())
                      }
                      if (context.payload != "" && JSONObject(context.payload).optBoolean("load"))
                          ActionRegistry.handle(
                              ActionContext("loadEruda", "", null, null, null))
                    }
                  }
                }
                null
              },
          "inspectPages" to
              ActionHandler { context ->
                if (WebViewHook.isInit && ScriptDbManager.engine.webViewDebug) {
                  WebView.setWebContentsDebuggingEnabled(true)
                }
                Chrome.IO.submit {
                  val code = "ChromeXt.post('inspect_pages', ${getInspectPages()});"
                  Handler(Chrome.getContext().mainLooper).post {
                    Chrome.evaluateJavascript(listOf(code), context.tab, context.frameId)
                  }
                }
                null
              },
          "userAgentSpoof" to
              ActionHandler { context ->
                var callback: String? = null
                if (UserScriptHook.isInit) {
                  val loadUrlParams = UserScriptProxy.newLoadUrlParams(context.payload)
                  if (UserScriptProxy.userAgentHook(context.payload, loadUrlParams)) {
                    UserScriptProxy.loadUrl.invoke(Chrome.getTab(), loadUrlParams)
                    callback = "console.log('User-Agent spoofed');"
                  }
                }
                callback
              },
      )
}

private class ScriptNotification(detail: JSONObject, frameId: String?) : BroadcastReceiver() {
  private val detail = detail
  private val frameId = frameId

  companion object {
    const val ACTION_USERSCRIPT = "ChromeXt"
    const val UUID = "GM_notification"

    fun newIntent(id: String, uuid: Int, frameId: String?): PendingIntent {
      val ctx = Chrome.getContext()
      val detail = JSONObject(mapOf("id" to id, "uuid" to uuid))
      val receiver = ScriptNotification(detail, frameId)
      val filter = IntentFilter(ACTION_USERSCRIPT)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // The broadcast is sent through our own PendingIntent; it must not be reachable by other
        // apps. targetSdk 35 makes the flag mandatory for dynamically registered receivers.
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        ctx.registerReceiver(receiver, filter)
      }
      val intent =
          Intent().apply {
            setAction(ACTION_USERSCRIPT)
            putExtra(UUID, uuid)
          }
      return PendingIntent.getBroadcast(ctx, uuid, intent, PendingIntent.FLAG_IMMUTABLE)
    }
  }

  override fun onReceive(ctx: Context, intent: Intent) {
    if (intent.getAction() == ACTION_USERSCRIPT) {
      val uuid = intent.getIntExtra(UUID, 0)
      if (uuid == detail.getInt("uuid")) {
        val tab = ActionState.tabNotification.get(detail.getInt("uuid"))!!
        val code = "Symbol.${Local.name}.unlock(${Local.key}).post('notification', ${detail});"
        Chrome.evaluateJavascript(listOf(code), tab, frameId)
        ctx.unregisterReceiver(this)
        ActionState.tabNotification.remove(uuid)
      }
    }
  }
}
