package org.matrix.chromext.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.browser.BrowserCatalog
import org.matrix.chromext.script.ScriptRecord
import org.matrix.chromext.script.ScriptStore

/**
 * Central script database exposed to hooked browser processes.
 *
 * The provider is exported because the browsers are different UIDs, but every call is checked
 * against the UID's installed package names. A caller that is not a supported browser is rejected
 * before it can read scripts or GM storage.
 */
class ScriptContentProvider : ContentProvider() {

  private var store: ScriptStore? = null

  override fun onCreate(): Boolean {
    store = ScriptStore(context!!)
    return true
  }

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
    // Reporting is open to any hooked app (including ones the user added to the Xposed scope by
    // hand), so it is validated by package ownership instead of the browser whitelist below.
    if (method == "reportHookedApp") return reportHookedApp(extras ?: Bundle())
    if (!callerAllowed()) {
      throw SecurityException("ChromeXt provider: caller UID is not a supported browser")
    }
    val db = store ?: return null
    val bundle = extras ?: Bundle()
    recordCaller()
    return when (method) {
      "ping" -> Bundle().apply { putBoolean("available", true) }
      "getConfig" -> {
        val json = JSONObject()
        db.config().forEach { (key, value) -> json.put(key, value) }
        Bundle().apply { putString("config", json.toString()) }
      }
      "queryScripts" -> {
        val array = JSONArray()
        db.records().forEach { array.put(it.toJson()) }
        Bundle().apply { putString("records", array.toString()) }
      }
      "upsertScript" -> {
        val record = bundle.getString("record") ?: return Bundle().apply { putBoolean("ok", false) }
        val ok =
            runCatching { db.upsert(ScriptRecord.fromJson(JSONObject(record))) }
                .getOrDefault(false)
        Bundle().apply { putBoolean("ok", ok) }
      }
      "toggleScript" -> {
        val id = bundle.getString("id") ?: return Bundle().apply { putBoolean("ok", false) }
        val enabled = bundle.getBoolean("enabled", true)
        Bundle().apply { putBoolean("ok", db.setEnabled(id, enabled)) }
      }
      "deleteScripts" -> {
        val ids = bundle.getStringArray("ids") ?: emptyArray<String>()
        db.delete(ids)
        Bundle().apply { putBoolean("ok", true) }
      }
      "updateStorage" -> {
        val id = bundle.getString("id") ?: return Bundle().apply { putBoolean("ok", false) }
        val storage =
            bundle.getString("storage") ?: return Bundle().apply { putBoolean("ok", false) }
        Bundle().apply { putBoolean("ok", db.updateStorage(id, storage)) }
      }
      "migrateLegacyScripts" -> {
        val records =
            bundle.getString("records") ?: return Bundle().apply { putInt("inserted", 0) }
        var inserted = 0
        runCatching {
          val array = JSONArray(records)
          for (i in 0 until array.length()) {
            if (db.insertIfAbsent(ScriptRecord.fromJson(array.getJSONObject(i)))) inserted += 1
          }
        }
        Bundle().apply { putInt("inserted", inserted) }
      }
      else -> null
    }
  }

  /**
   * Any authorized browser that talks to the provider is, by definition, running the module. Record
   * it so the manager's app list reflects reality even when the explicit report was lost (provider
   * not started yet, process killed before the IO task ran).
   */
  private fun recordCaller() {
    val uid = Binder.getCallingUid()
    if (uid == Process.myUid()) return
    val packages = context?.packageManager?.getPackagesForUid(uid) ?: return
    val packageName =
        packages.firstOrNull { it in BrowserCatalog.xposedScope } ?: packages.firstOrNull() ?: return
    store?.reportHookedApp(packageName, "", "")
  }

  private fun reportHookedApp(bundle: Bundle): Bundle {
    val packageName =
        bundle.getString("package") ?: return Bundle().apply { putBoolean("ok", false) }
    val uid = Binder.getCallingUid()
    val owned = context?.packageManager?.getPackagesForUid(uid) ?: emptyArray()
    // Only the app itself may report that it was hooked; otherwise any process could pollute the
    // list with packages it does not own.
    if (packageName !in owned) return Bundle().apply { putBoolean("ok", false) }
    val label = bundle.getString("label") ?: ""
    val versionName = bundle.getString("version") ?: ""
    val ok = store?.reportHookedApp(packageName, label, versionName) ?: false
    return Bundle().apply { putBoolean("ok", ok) }
  }

  private fun callerAllowed(): Boolean {
    val uid = Binder.getCallingUid()
    if (uid == Process.myUid()) return true
    val packages = context?.packageManager?.getPackagesForUid(uid) ?: return false
    val allowed = BrowserCatalog.xposedScope.toSet()
    return packages.any { it in allowed }
  }

  override fun query(
      uri: Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
      uri: Uri,
      values: ContentValues?,
      selection: String?,
      selectionArgs: Array<out String>?,
  ): Int = 0
}
