package org.matrix.chromext.ui

import android.app.Activity
import android.content.Context
import android.content.Intent

object ManagerLauncher {
  fun open(context: Context, activeUrl: String? = null) {
    val intent =
        Intent(context, MainActivity::class.java).apply {
          if (activeUrl != null) putExtra(MainActivity.EXTRA_ACTIVE_URL, activeUrl)
        }
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
  }

  /** Returns false when the manager Activity cannot be started, so the caller can fall back. */
  fun installScript(context: Context, script: String): Boolean =
      runCatching {
            val intent =
                Intent(context, InstallScriptActivity::class.java).apply {
                  putExtra(InstallScriptActivity.EXTRA_SCRIPT, script)
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
            true
          }
          .getOrDefault(false)
}
