package org.matrix.chromext.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Launches the manager from either the module process or a hooked browser process. The browser
 * process must use an explicit component: `Intent(context, MainActivity::class.java)` would
 * resolve against the browser package, where the class does not exist.
 */
object ManagerLauncher {
  private const val MODULE_PACKAGE = "org.matrix.chromext"
  private const val MAIN_ACTIVITY = "$MODULE_PACKAGE.ui.MainActivity"
  private const val INSTALL_ACTIVITY = "$MODULE_PACKAGE.ui.InstallScriptActivity"

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
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
            true
          }
          .getOrDefault(false)
}
