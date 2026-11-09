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

  /** Returns false when the manager Activity cannot be started, so the caller can fall back. */
  fun installScript(context: Context, script: String): Boolean =
      runCatching {
            val intent =
                Intent().apply {
                  component = ComponentName(MODULE_PACKAGE, INSTALL_ACTIVITY)
                  putExtra(InstallScriptActivity.EXTRA_SCRIPT, script)
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
   * Posts a notification that opens the manager for [scriptId]. OEM background-activity limits can
   * silently swallow a direct `startActivity` from a browser process; the notification gives the
   * user a path that always works because it starts the activity from a tap.
   */
  fun notifyInstalled(context: Context, scriptId: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    runCatching {
          val intent =
              Intent().apply {
                component = ComponentName(MODULE_PACKAGE, MAIN_ACTIVITY)
                putExtra(MainActivity.EXTRA_EDIT_ID, scriptId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
          val flags =
              PendingIntent.FLAG_UPDATE_CURRENT or
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE
                  else 0
          val pending =
              PendingIntent.getActivity(context, scriptId.hashCode(), intent, flags) ?: return
          val chinese = Locale.getDefault().language.lowercase().startsWith("zh")
          val builder =
              Notification.Builder(context, CHANNEL)
                  .setContentTitle("ChromeXt")
                  .setContentText(
                      if (chinese) "脚本已安装，点此打开管理面板"
                      else "Script installed. Tap to open the manager.")
                  .setSmallIcon(Icon.createWithResource(MODULE_PACKAGE, R.drawable.ic_extension))
                  .setAutoCancel(true)
                  .setContentIntent(pending)
          val manager =
              context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
          manager.notify(scriptId.hashCode(), builder.build())
        }
        .onFailure { Log.d("Cannot post the install notification: ${it.message}") }
  }
}
