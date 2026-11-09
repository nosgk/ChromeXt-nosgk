package org.matrix.chromext.ui

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import java.util.Locale
import org.matrix.chromext.Chrome
import org.matrix.chromext.R
import org.matrix.chromext.utils.Log

/**
 * Launches the manager from either the module process or a hooked browser process. The browser
 * process must use an explicit component: `Intent(context, MainActivity::class.java)` would
 * resolve against the browser package, where the class does not exist.
 */
object ManagerLauncher {
  private const val MODULE_PACKAGE = "org.matrix.chromext"
  private const val MAIN_ACTIVITY = "$MODULE_PACKAGE.ui.MainActivity"
  private const val INSTALL_ACTIVITY = "$MODULE_PACKAGE.ui.InstallScriptActivity"
  private const val CHANNEL = "xposed_notification"

  fun open(context: Context, activeUrl: String? = null) {
    val intent =
        Intent().apply {
          component = ComponentName(MODULE_PACKAGE, MAIN_ACTIVITY)
          if (activeUrl != null) putExtra(MainActivity.EXTRA_ACTIVE_URL, activeUrl)
        }
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
  }

  /**
   * Opens the native install confirmation screen for a staged payload. Only the token travels in
   * the Intent, so scripts larger than the Binder limit can be confirmed too.
   */
  fun installScript(context: Context, token: String): Boolean =
      runCatching {
            val intent =
                Intent().apply {
                  component = ComponentName(MODULE_PACKAGE, INSTALL_ACTIVITY)
                  putExtra(InstallScriptActivity.EXTRA_TOKEN, token)
                  // Installing from a hooked app scopes the script to that app by default.
                  Chrome.packageName?.let {
                    putExtra(InstallScriptActivity.EXTRA_PACKAGE, it)
                  }
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
            true
          }
          .getOrDefault(false)

  /**
   * Posts a notification that opens the install confirmation screen. OEM background-activity
   * limits can silently swallow a direct `startActivity`; a tap on the notification always starts
   * the activity from a user gesture.
   */
  fun notifyPendingInstall(context: Context, token: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    runCatching {
          val intent =
              Intent().apply {
                component = ComponentName(MODULE_PACKAGE, INSTALL_ACTIVITY)
                putExtra(InstallScriptActivity.EXTRA_TOKEN, token)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
          val flags =
              PendingIntent.FLAG_UPDATE_CURRENT or
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE
                  else 0
          val pending =
              PendingIntent.getActivity(context, token.hashCode(), intent, flags) ?: return
          val chinese = Locale.getDefault().language.lowercase().startsWith("zh")
          val builder =
              Notification.Builder(context, CHANNEL)
                  .setContentTitle("ChromeXt")
                  .setContentText(
                      if (chinese) "点此确认安装用户脚本"
                      else "Tap to confirm the UserScript install.")
                  .setSmallIcon(Icon.createWithResource(MODULE_PACKAGE, R.drawable.ic_extension))
                  .setAutoCancel(true)
                  .setContentIntent(pending)
          val manager =
              context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
          manager.notify(token.hashCode(), builder.build())
        }
        .onFailure { Log.d("Cannot post the install notification: ${it.message}") }
  }
}
