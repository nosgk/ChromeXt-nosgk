package org.matrix.chromext.action

import android.app.Activity
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.proxy.UserScriptProxy
import org.matrix.chromext.utils.findMethod
import org.matrix.chromext.utils.findMethodOrNull
import org.matrix.chromext.utils.invokeMethod

object TabActions : ActionGroup {

  // TabModel#closeTab(Tab tab, Tab recommendedNextTab, boolean...[, int]), dropped by Chrome 131
  // but still shipped by older forks.
  private fun findLegacyCloseTab(model: Class<*>, tab: Class<*>): Method? {
    fun Method.closesTab() =
        parameterTypes.firstOrNull() == tab &&
            parameterTypes.drop(1).all {
              it == tab || it == Boolean::class.java || it == Int::class.java
            }
    return findMethodOrNull(model, true) { name == "closeTab" && closesTab() }
        ?: findMethodOrNull(model, true) {
          returnType == Boolean::class.java && parameterTypes.size >= 5 && closesTab()
        }
  }

  // TabClosureParams is the only class offering a static Tab factory whose result builds one back,
  // a shape that survives the renaming of all three classes involved.
  private fun isTabClosureParams(clazz: Class<*>, tab: Class<*>): Boolean =
      clazz.declaredMethods.any {
        Modifier.isStatic(it.modifiers) &&
            it.parameterTypes contentDeepEquals arrayOf(tab) &&
            it.returnType.declaredMethods.any { build ->
              build.parameterTypes.size == 0 && build.returnType == clazz
            }
      }

  // Since Chrome 131: TabModel#getTabRemover()#forceCloseTabs(TabClosureParams#closeTab(tab))
  private fun forceCloseTab(model: Any, tabModel: Class<*>, tab: Class<*>, currentTab: Any) {
    val getTabRemover =
        findMethod(tabModel) {
          parameterTypes.size == 0 &&
              returnType.declaredMethods.any {
                it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 1 &&
                    isTabClosureParams(it.parameterTypes[0], tab)
              }
        }
    val remover = getTabRemover.invoke(model)!!
    val forceCloseTabs =
        findMethod(getTabRemover.returnType) {
          returnType == Void.TYPE &&
              parameterTypes.size == 1 &&
              isTabClosureParams(parameterTypes[0], tab)
        }
    val closureParams = forceCloseTabs.parameterTypes[0]
    val builder =
        findMethod(closureParams) {
              Modifier.isStatic(modifiers) && parameterTypes contentDeepEquals arrayOf(tab)
            }
            .invoke(null, currentTab)!!
    val params =
        findMethod(builder::class.java) { parameterTypes.size == 0 && returnType == closureParams }
            .invoke(builder)
    forceCloseTabs.invoke(remover, params)
  }

  override val handlers =
      mapOf<String, ActionHandler>(
          "close" to
              ActionHandler { context ->
                val currentTab = context.tab
                val auxObject = context.auxObject
                val activity = Chrome.getContext()
                var callback: String? = null
                if (WebViewHook.isInit && currentTab != null && auxObject != null) {
                  auxObject.invokeMethod(currentTab) { name == "onCloseWindow" }
                } else if (currentTab != null &&
                    activity::class.java == UserScriptProxy.chromeTabbedActivity) {
                  val tab = Chrome.load("org.chromium.chrome.browser.tab.Tab")
                  val tabModel = Chrome.load("org.chromium.chrome.browser.tabmodel.TabModel")
                  val getCurrentTabModel =
                      findMethod(activity::class.java, true) {
                        parameterTypes.size == 0 && returnType == tabModel
                      }
                  val model = getCurrentTabModel.invoke(activity)!!
                  val legacyClose = findLegacyCloseTab(model::class.java, tab)
                  if (legacyClose != null) {
                    val args =
                        legacyClose.parameterTypes.mapIndexed { i, type ->
                          when {
                            i == 0 -> currentTab
                            type == tab -> null
                            type == Boolean::class.java -> false
                            else -> 0
                          }
                        }
                    legacyClose.invoke(model, *args.toTypedArray())
                  } else {
                    forceCloseTab(model, tabModel, tab, currentTab)
                  }
                } else {
                  val msg = "Closing tab ${currentTab} with context ${activity}"
                  callback =
                      "console.error(new TypeError('ChromeXt Action failure', {cause: '${msg}'}));"
                }
                callback
              },
          "focus" to
              ActionHandler { context ->
                Chrome.updateTab(context.tab)
                val detail = JSONObject(context.payload)
                val requestFocus = detail.getBoolean("requestFocus")
                val activity = Chrome.getContext()
                if (requestFocus &&
                    context.tab != null &&
                    activity::class.java == UserScriptProxy.chromeTabbedActivity) {
                  (activity as Activity).window.decorView.requestFocus()
                }
                null
              },
      )
}
