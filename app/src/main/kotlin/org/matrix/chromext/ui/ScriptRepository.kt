package org.matrix.chromext.ui

import android.content.Context
import org.matrix.chromext.script.EngineConfig
import org.matrix.chromext.script.HookedApp
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.ScriptRecord
import org.matrix.chromext.script.ScriptStore
import org.matrix.chromext.script.parseApps
import org.matrix.chromext.script.parseScript
import org.matrix.chromext.script.toRecord

/**
 * Manager-side access to the central database. The manager runs in the module process, so it uses
 * [ScriptStore] directly; the ContentProvider is only needed by the browser processes.
 */
class ScriptRepository(context: Context) {

  private val store = ScriptStore(context.applicationContext)

  fun load(): List<Script> = store.records().mapNotNull { toScript(it) }

  fun records(): List<ScriptRecord> = store.records()

  fun save(script: Script): Boolean = store.upsert(script.toRecord())

  fun setEnabled(id: String, enabled: Boolean): Boolean = store.setEnabled(id, enabled)

  fun delete(ids: Array<String>) {
    store.delete(ids)
  }

  fun updateStorage(id: String, storage: String): Boolean = store.updateStorage(id, storage)

  fun hookedApps(): List<HookedApp> = store.hookedApps()

  fun engineConfig(): EngineConfig = EngineConfig.from(store.config())

  fun saveEngineConfig(config: EngineConfig) = store.putConfigs(config.toMap())

  fun databasePath(): String = store.databasePath()

  fun databaseSize(): Long = store.databaseSize()

  fun vacuum(): Long = store.vacuum()

  fun clearOrphanStorage(): Int = store.clearOrphanStorage()

  /** Migration helper used by the browser-database importer: never overwrite an existing row. */
  fun insertIfAbsent(record: ScriptRecord): Boolean = store.insertIfAbsent(record)

  fun close() {
    store.close()
  }

  private fun toScript(record: ScriptRecord): Script? {
    val parsed =
        parseScript(record.meta + record.code, record.storage, hydrateRequires = false) ?: return null
    return parsed.copy(
        enabled = record.enabled,
        sortOrder = record.sortOrder,
        customMatch = record.customMatch,
        customInclude = record.customInclude,
        customExclude = record.customExclude,
        customRunAt = record.customRunAt,
        updateUrl = record.updateUrl,
        iconUrl = record.iconUrl,
        lastUpdated = record.lastUpdated,
        apps = parseApps(record.apps),
    )
  }
}
