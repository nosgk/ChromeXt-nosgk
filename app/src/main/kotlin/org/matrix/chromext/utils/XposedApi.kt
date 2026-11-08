package org.matrix.chromext.utils

import io.github.libxposed.api.XposedInterface
import org.matrix.chromext.TAG

/**
 * Process-wide handle to the libxposed module instance.
 *
 * The Modern API is instance based, while the hook helpers are extension functions on
 * [java.lang.reflect.Method] and [java.lang.reflect.Constructor]. The entry point installs the
 * instance before any hook is attempted, and every helper goes through [require].
 */
object XposedApi {

  @Volatile private var instance: XposedInterface? = null

  fun install(api: XposedInterface) {
    instance = api
  }

  fun require(): XposedInterface =
      instance ?: throw IllegalStateException("libxposed API is not attached")

  fun log(priority: Int, msg: String) {
    instance?.log(priority, TAG, msg)
  }

  fun log(priority: Int, msg: String, thr: Throwable) {
    instance?.log(priority, TAG, msg, thr)
  }
}
