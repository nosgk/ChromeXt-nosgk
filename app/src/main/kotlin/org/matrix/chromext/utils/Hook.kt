package org.matrix.chromext.utils

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/** Handle returned by every helper below; [unhook] is idempotent in the Modern API. */
typealias Unhook = XposedInterface.HookHandle

/** Matches the legacy `XCallback.PRIORITY_DEFAULT` so hook ordering stays the same. */
const val PRIORITY_DEFAULT = 50

typealias Hooker = (param: HookParam) -> Unit

/**
 * Adapter that keeps the hook call sites written against the legacy `MethodHookParam` shape.
 *
 * The Modern API hands an immutable argument list and returns the result from the interceptor, so
 * this class records argument edits and an explicit result override. [hookBefore] and [hookAfter]
 * translate those back into `chain.proceed(...)` and a return value.
 */
class HookParam internal constructor(private val chain: XposedInterface.Chain) {

  /** Mutable copy: assignments here are replayed through `proceed(args)` by the helper. */
  val args: MutableList<Any?> = ArrayList<Any?>(chain.args)

  /** The receiver. All hooks installed by this module are instance methods or constructors. */
  val thisObject: Any = chain.thisObject!!

  val executable: Executable = chain.executable

  var result: Any? = null
    set(value) {
      field = value
      resultSet = true
    }

  private var resultSet = false

  internal fun hasResult(): Boolean = resultSet

  internal fun proceedWithArgs(): Any? = chain.proceed(args.toTypedArray())
}

fun Method.hookBefore(priority: Int = PRIORITY_DEFAULT, hook: Hooker): Unhook =
    XposedApi.require().hook(this).setPriority(priority).intercept { chain ->
      val param = HookParam(chain)
      try {
        hook(param)
      } catch (thr: Throwable) {
        Log.ex(thr)
      }
      // Setting a result in a "before" hook skips the original call, like the legacy API did.
      if (param.hasResult()) param.result else param.proceedWithArgs()
    }

fun Method.hookAfter(priority: Int = PRIORITY_DEFAULT, hooker: Hooker): Unhook =
    XposedApi.require().hook(this).setPriority(priority).intercept { chain ->
      val param = HookParam(chain)
      param.result = chain.proceed(param.args.toTypedArray())
      try {
        hooker(param)
      } catch (thr: Throwable) {
        Log.ex(thr)
      }
      param.result
    }

fun Constructor<*>.hookAfter(priority: Int = PRIORITY_DEFAULT, hooker: Hooker): Unhook =
    XposedApi.require().hook(this).setPriority(priority).intercept { chain ->
      val param = HookParam(chain)
      chain.proceed(param.args.toTypedArray())
      try {
        hooker(param)
      } catch (thr: Throwable) {
        Log.ex(thr)
      }
      // Constructors ignore the return value.
      null
    }

/** Keeps the `before { } / after { }` DSL used by the preference hook. */
class XposedHookFactory(priority: Int = PRIORITY_DEFAULT) : XposedInterface.Hooker {
  private var beforeMethod: Hooker? = null
  private var afterMethod: Hooker? = null

  fun before(before: Hooker) {
    beforeMethod = before
  }

  fun after(after: Hooker) {
    afterMethod = after
  }

  override fun intercept(chain: XposedInterface.Chain): Any? {
    val param = HookParam(chain)
    beforeMethod?.let {
      try {
        it(param)
      } catch (thr: Throwable) {
        Log.ex(thr)
      }
    }
    param.result =
        if (param.hasResult()) param.result else chain.proceed(param.args.toTypedArray())
    afterMethod?.let {
      try {
        it(param)
      } catch (thr: Throwable) {
        Log.ex(thr)
      }
    }
    return param.result
  }
}

fun Method.hookMethod(
    priority: Int = PRIORITY_DEFAULT,
    hook: XposedHookFactory.() -> Unit,
): Unhook {
  val factory = XposedHookFactory(priority).apply(hook)
  return XposedApi.require().hook(this).setPriority(priority).intercept(factory)
}
