package org.matrix.chromext.utils

import android.content.Context
import android.util.Log as AndroidLog
import android.widget.Toast
import java.lang.ref.WeakReference
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.TAG

object Log {
  private var lastToast: WeakReference<Toast>? = null

  fun i(msg: String) {
    AndroidLog.i(TAG, msg)
    // Info messages are frequent (every page navigation); only mirror them to the Xposed log on
    // debug builds so release devices do not pay for the IPC and do not leak URLs into the log.
    if (BuildConfig.DEBUG) XposedApi.log(AndroidLog.INFO, "ChromeXt logging: " + msg)
  }

  fun d(msg: String, full: Boolean = false) {
    if (BuildConfig.DEBUG) {
      if (!full && msg.length > 300) {
        AndroidLog.d(TAG, msg.take(300) + " ...")
      } else {
        AndroidLog.d(TAG, msg)
      }
    }
  }

  fun w(msg: String) {
    AndroidLog.w(TAG, msg)
  }

  fun e(msg: String) {
    AndroidLog.e(TAG, msg)
    XposedApi.log(AndroidLog.ERROR, "ChromeXt error: " + msg)
  }

  fun ex(thr: Throwable, msg: String = "") {
    AndroidLog.e(TAG, msg, thr)
    XposedApi.log(AndroidLog.ERROR, "ChromeXt exception caught: [$msg] " + thr.toString(), thr)
  }

  fun toast(context: Context, msg: String) {
    this.lastToast?.get()?.cancel()
    val duration = Toast.LENGTH_SHORT
    val toast = Toast.makeText(context, msg, duration)
    toast.show()
    this.lastToast = WeakReference(toast)
  }
}
