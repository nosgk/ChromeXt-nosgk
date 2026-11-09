package org.matrix.chromext

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.matrix.chromext.browser.BrowserCatalog
import org.matrix.chromext.hook.BaseHook
import org.matrix.chromext.hook.ContextMenuHook
import org.matrix.chromext.hook.PageInfoHook
import org.matrix.chromext.hook.PageMenuHook
import org.matrix.chromext.hook.PreferenceHook
import org.matrix.chromext.hook.UserScriptHook
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.ModuleLog
import org.matrix.chromext.utils.XposedApi
import org.matrix.chromext.utils.findMethodOrNull
import org.matrix.chromext.utils.hookAfter

/**
 * libxposed Modern API entry point (API 102).
 *
 * The legacy `IXposedHookLoadPackage` / `IXposedHookZygoteInit` pair is gone: module path and
 * process information now come from the module instance, and hooks are installed from
 * [onPackageReady], which fires once the package class loader exists and before the app starts.
 */
class MainHook : XposedModule() {

  /** Packages already reported by this process; a process can host more than one package. */
  private val reported = java.util.Collections.synchronizedSet(mutableSetOf<String>())

  /** A WebView host may build many clients before its chrome client appears; warn only once. */
  private var missingClientReported = false

  /** Installed once per process; `onPackageReady` may fire for several loaded packages. */
  private var applicationHookInstalled = false
  private var webViewConstructorsHooked = false

  override fun onModuleLoaded(param: ModuleLoadedParam) {
    XposedApi.install(this)
    Resource.init(getModuleApplicationInfo().sourceDir)
    if (param.processName != "?") ModuleLog.setProcessLabel(param.processName.substringBefore(':'))
    Log.d(param.processName + " module loaded")
  }

  override fun onPackageReady(param: PackageReadyParam) {
    val process = processName()
    // The process name identifies the host; param.packageName can also be a loaded-code package
    // such as com.google.android.webview, which must not become the log tag.
    if (process != "?") ModuleLog.setProcessLabel(process.substringBefore(':'))
    Log.d("package ready: ${param.packageName} in $process")
    if (param.packageName == "org.matrix.chromext") return
    // Neither the Application nor the system context exists yet. Attach on Application.onCreate;
    // ModuleLog keeps the lines queued until then instead of dropping them.
    installApplicationHook()
    attachEarlyContext()
    if (param.packageName != "android") {
      reportHookedApp(param.packageName, param.applicationInfo)
      detectUnsupportedKernel(param.packageName, param.classLoader)
    }
    val classLoader = param.classLoader
    if (BrowserCatalog.supportedPackages.contains(param.packageName)) {
      // Every constructor taking a Context, rather than a fixed index into an order the JLS leaves
      // unspecified: browsers do not agree on how many constructors WindowAndroid has, and Whale in
      // particular has no one-argument overload, so index 1 there is a test-only constructor the
      // browser never calls and the whole module stays inert. The overloads delegate into one
      // another, so hooking all of them just means the callback can run twice, which Chrome.init
      // and initHooks both already absorb.
      val windowAndroid = classLoader.loadClass("org.chromium.ui.base.WindowAndroid")
      val constructors =
          windowAndroid.declaredConstructors.filter {
            val first = it.parameterTypes.firstOrNull()
            first != null && Context::class.java.isAssignableFrom(first)
          }
      if (constructors.isEmpty()) Log.e("No WindowAndroid constructor takes a Context")
      Log.d("WindowAndroid constructors: ${constructors.size}")
      constructors.forEach { constructor ->
        constructor.hookAfter {
          Chrome.init(it.args[0] as Context, param.packageName)
          // Keep the menu hooks reachable even when the UserScript hook cannot be installed
          runCatching { initHooks(UserScriptHook) }
              .onFailure { Log.ex(it, "UserScriptHook init failed") }
          if (ContextMenuHook.isInit) return@hookAfter
          // The settings screen and the browser menu are independent features, kept in separate
          // runCatching blocks so a browser that cannot host our preferences still gets its menu
          // entries.
          if (!Chrome.isVivaldi) {
            runCatching { initHooks(PreferenceHook) }
                .onFailure { Log.ex(it, "PreferenceHook init failed") }
          }
          runCatching {
            initHooks(
                when {
                  // Herond (Compose) and Whale (custom views) replace the app menu with a surface
                  // we cannot inject into, so fall back to the selection action mode like the
                  // WebView based browsers do.
                  Chrome.isHerond || Chrome.isWhale -> ContextMenuHook
                  Chrome.isCocCoc -> PageInfoHook
                  else -> PageMenuHook
                })
          }
              .onFailure {
                initHooks(ContextMenuHook)
                Log.ex(it, "Menu hook init failed")
              }
        }
      }
    } else {
      val ctx = currentApplication()
      Log.d("WebView host ${param.packageName}: application=${ctx != null}")

      Chrome.isMi =
          Chrome.isMi ||
              param.packageName == "com.mi.globalbrowser" ||
              param.packageName == "com.android.browser"
      Chrome.isQihoo = param.packageName == "com.qihoo.contents"

      if (ctx == null && Chrome.isMi) return
      // Wait to get the browser context of Mi Browser

      if (ctx != null && param.packageName != "android") {
        Chrome.init(ctx, ctx.packageName)
      }

      if (Chrome.isMi) {
        WebViewHook.WebView = Chrome.load("com.miui.webkit.WebView")
        runCatching {
          WebViewHook.ViewClient = Chrome.load("com.android.browser.tab.TabWebViewClient")
          WebViewHook.ChromeClient = Chrome.load("com.android.browser.tab.TabWebChromeClient")
        }
            .onFailure {
              val miuiAutologinBar = Chrome.load("com.android.browser.MiuiAutologinBar")
              // Use MiuiAutologinBar to find `com.android.browser.tab.Tab`, which can located by
              // searching the string "X-MiOrigin"
              val fields = miuiAutologinBar.declaredFields.map { it.type }
              val tab =
                  miuiAutologinBar.declaredMethods
                      .find {
                        it.parameterCount == 2 &&
                            it.parameterTypes[1] == Boolean::class.java &&
                            !fields.contains(it.parameterTypes[0])
                      }!!
                      .run { parameterTypes[0] }
              tab.declaredFields.forEach {
                if (findMethodOrNull(it.type) {
                  // Found by searching the string "Console: "
                  it.name == "onGeolocationPermissionsHidePrompt"
                } != null)
                    WebViewHook.ChromeClient = it.type
                if (findMethodOrNull(it.type) {
                  // Found by searching the string "Tab.MainWebViewClient"
                  it.name == "onReceivedHttpAuthRequest"
                } != null)
                    WebViewHook.ViewClient = it.type
              }
            }

        hookWebView()
        return
      }

      if (Chrome.isQihoo) {
        WebViewHook.WebView = Chrome.load("com.qihoo.webkit.WebView")
        WebViewHook.ViewClient = Chrome.load("com.qihoo.webkit.WebViewClient")
        WebViewHook.ChromeClient = Chrome.load("com.qihoo.webkit.WebChromeClient")
        hookWebView()
        return
      }

      if (!webViewConstructorsHooked) {
        webViewConstructorsHooked = true
        WebViewClient::class.java.declaredConstructors[0].hookAfter {
          if (it.thisObject::class != WebViewClient::class) {
            Log.d("WebViewClient subclass: ${it.thisObject::class.java.name}")
            WebViewHook.ViewClient = it.thisObject::class.java
            hookWebView()
          }
        }

        WebChromeClient::class.java.declaredConstructors[0].hookAfter {
          if (it.thisObject::class != WebChromeClient::class) {
            Log.d("WebChromeClient subclass: ${it.thisObject::class.java.name}")
            WebViewHook.ChromeClient = it.thisObject::class.java
            hookWebView()
          }
        }
      }
    }
  }

  /**
   * `onPackageReady` runs before the Application exists, so both the log sink and the hook report
   * wait for `Application.onCreate`. The framework class is shared by the host app, so the hook
   * fires with the app instance even when it was installed for a loaded-code package.
   */
  private fun installApplicationHook() {
    if (applicationHookInstalled) return
    applicationHookInstalled = true
    runCatching {
          Application::class.java.getDeclaredMethod("onCreate").hookAfter {
            val app = it.thisObject as? Application ?: return@hookAfter
            ModuleLog.attach(app, app.packageName)
            Log.d("application created: ${app.packageName} in ${processName()}")
            reportHookedApp(app.packageName, app.applicationInfo, app)
          }
        }
        .onFailure { Log.ex(it, "Cannot hook Application.onCreate") }
  }

  /**
   * Some frameworks still expose the system context before the app starts; when they do, attach
   * right away so even the earliest probes reach the file.
   */
  private fun attachEarlyContext() {
    val ctx = systemContext() ?: currentApplication() ?: return
    val label = currentApplication()?.packageName ?: processName().substringBefore(':')
    ModuleLog.attach(ctx, label)
    Log.d("diagnostics attached early for $label")
  }

  /** Android 9+ exposes the process name directly; older releases need the hidden accessor. */
  private fun processName(): String =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { Application.getProcessName() }.getOrNull() ?: "?"
      } else {
        runCatching {
              Class.forName("android.app.ActivityThread")
                  .getDeclaredMethod("currentProcessName")
                  .apply { isAccessible = true }
                  .invoke(null) as? String
            }
            .getOrNull() ?: "?"
      }

  /**
   * Some apps render their pages with the Tencent X5/TBS kernel instead of `android.webkit.WebView`.
   * ChromeXt only hooks the framework classes, so those pages are never injected; say so explicitly
   * instead of leaving a silent no-op behind.
   */
  private fun detectUnsupportedKernel(packageName: String, classLoader: ClassLoader) {
    val known =
        listOf(
                "com.tencent.smtt.sdk.WebView",
                "com.tencent.smtt.sdk.WebViewClient",
                "com.tencent.smtt.sdk.WebChromeClient",
                "com.tencent.tbs.core.webkit.WebView",
            )
            .filter { runCatching { classLoader.loadClass(it) }.isSuccess }
    if (known.isNotEmpty()) {
      Log.w(
          "$packageName ships a Tencent X5/TBS kernel (${known.joinToString()}); ChromeXt only " +
              "hooks android.webkit.WebView, so pages rendered by X5 are not injected")
    }
  }

  /**
   * The legacy API jar shipped a stub for `android.app.AndroidAppHelper`. libxposed does not, and
   * adding a stub in the `android.app` namespace would end up packaged in the APK, so call the
   * hidden method the same way the stub did. Hooked processes run without hidden-API enforcement.
   */
  private fun currentApplication(): Application? =
      runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val method = activityThread.getDeclaredMethod("currentApplication")
            method.isAccessible = true
            method.invoke(null) as? Application
          }
          .getOrNull()

  private fun reportHookedApp(
      packageName: String,
      info: ApplicationInfo,
      knownContext: Context? = null,
  ) {
    if (!reported.add(packageName)) return
    // The system context and the Application do not exist at onPackageReady; the
    // Application.onCreate hook calls this again with a real Context.
    val context = knownContext ?: systemContext() ?: currentApplication()
    if (context == null) {
      reported.remove(packageName)
      return
    }
    val packageManager = context.packageManager
    val label =
        runCatching { info.loadLabel(packageManager).toString() }.getOrDefault("")
    val versionName =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }
            .getOrDefault("")
    Chrome.IO.submit {
      val ok =
          runCatching {
                val extras =
                    Bundle().apply {
                      putString("package", packageName)
                      putString("label", label)
                      putString("version", versionName)
                    }
                val result =
                    context.contentResolver.call(
                        Uri.parse("content://org.matrix.chromext.scripts"),
                        "reportHookedApp",
                        null,
                        extras,
                    )
                result?.getBoolean("ok") == true
              }
              .onFailure { Log.d("reportHookedApp($packageName) failed: ${it.message}") }
              .getOrDefault(false)
      Log.d("reportHookedApp($packageName) -> $ok")
    }
  }

  private fun systemContext(): Context? =
      runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current =
                activityThread.getDeclaredMethod("currentActivityThread").apply {
                  isAccessible = true
                }
                .invoke(null)
            activityThread.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                .invoke(current) as? Context
          }
          .getOrNull()

  private fun hookWebView() {
    if (WebViewHook.ChromeClient == null || WebViewHook.ViewClient == null) {
      if (!missingClientReported) {
        missingClientReported = true
        Log.w(
            "hookWebView: missing classes (viewClient=${WebViewHook.ViewClient}, " +
                "chromeClient=${WebViewHook.ChromeClient})")
      }
      return
    }
    if (WebViewHook.WebView == null) {
      runCatching {
            WebViewHook.WebView = WebView::class.java
            WebView.setWebContentsDebuggingEnabled(true)
          }
          .onFailure { Log.ex(it, "Cannot hook android.webkit.WebView") }
    }
    Log.d(
        "hookWebView: webView=${WebViewHook.WebView?.name}, " +
            "viewClient=${WebViewHook.ViewClient?.name}, chromeClient=${WebViewHook.ChromeClient?.name}")
    initHooks(WebViewHook, ContextMenuHook)
  }

  private fun initHooks(vararg hook: BaseHook) {
    hook.forEach { current ->
      if (current.isInit) return@forEach
      current.init()
      if (current.isInit) Log.d("${current.javaClass.simpleName} hooked")
      else Log.w("${current.javaClass.simpleName} did not initialize")
    }
  }
}
