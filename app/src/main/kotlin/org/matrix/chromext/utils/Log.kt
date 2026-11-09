package org.matrix.chromext.utils

import android.content.Context
import android.util.Log as AndroidLog
import android.widget.Toast
import java.lang.ref.WeakReference
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.TAG

object Log {
  private var lastToast: WeakReference<Toast>? = null

  /**
   * Detailed diagnostics, driven by the manager's "detailed debug log" switch. It starts on so
   * hook and provider failures that happen before the first config sync are always captured, and
   * then follows the user's choice.
   */
  @Volatile private var verbose = true

  fun setVerbose(enabled: Boolean) {
    if (verbose == enabled) return
    // Record the transition with the value that lets the line through: disabling is logged just
    // before the gate closes, enabling just after it opens.
    if (!enabled) d("diagnostics disabled")
    verbose = enabled
    if (enabled) d("diagnostics enabled")
  }

  val isVerbose: Boolean
    get() = verbose

  private fun diagnosticsEnabled(): Boolean = BuildConfig.DEBUG || verbose

  private fun truncate(msg: String): String = if (msg.length > 300) msg.take(300) + " ..." else msg

  fun i(msg: String) {
    AndroidLog.i(TAG, msg)
    // Info messages are frequent (every page navigation); only mirror them while diagnostics are
    // on, so a release device does not pay for the IPC and does not leak URLs into the log.
    if (diagnosticsEnabled()) {
      XposedApi.log(AndroidLog.INFO, "ChromeXt logging: " + msg)
      ModuleLog.append(msg)
    }
  }

  fun d(msg: String, full: Boolean = false) {
    if (!diagnosticsEnabled()) return
    val line = if (full) msg else truncate(msg)
    AndroidLog.d(TAG, line)
    XposedApi.log(AndroidLog.DEBUG, line)
    ModuleLog.append(line)
  }

  /**
   * Diagnostics about the provider bridge itself. Never forwarded to [ModuleLog]: a failed
   * `appendLog` call must not schedule another `appendLog` call.
   */
  fun dLocal(msg: String) {
    if (!diagnosticsEnabled()) return
    val line = truncate(msg)
    AndroidLog.d(TAG, line)
    XposedApi.log(AndroidLog.DEBUG, line)
  }

  fun w(msg: String) {
    AndroidLog.w(TAG, msg)
    XposedApi.log(AndroidLog.WARN, msg)
    ModuleLog.append("W " + msg)
  }

  fun e(msg: String) {
    AndroidLog.e(TAG, msg)
    XposedApi.log(AndroidLog.ERROR, "ChromeXt error: " + msg)
    ModuleLog.append("E " + msg)
  }

  fun ex(thr: Throwable, msg: String = "") {
    AndroidLog.e(TAG, msg, thr)
    XposedApi.log(AndroidLog.ERROR, "ChromeXt exception caught: [$msg] " + thr.toString(), thr)
    ModuleLog.append("E " + msg + " " + thr)
  }

  fun toast(context: Context, msg: String) {
    this.lastToast?.get()?.cancel()
    val duration = Toast.LENGTH_SHORT
    val toast = Toast.makeText(context, msg, duration)
    toast.show()
    this.lastToast = WeakReference(toast)
  }
}
