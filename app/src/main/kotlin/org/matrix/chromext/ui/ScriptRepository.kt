package org.matrix.chromext.ui

import android.content.Context
import org.json.JSONObject
import org.matrix.chromext.script.EngineConfig
import org.matrix.chromext.script.HookedApp
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.ScriptActivation
import org.matrix.chromext.script.ScriptFiles
import org.matrix.chromext.script.ScriptHydrator
import org.matrix.chromext.script.ScriptRecord
import org.matrix.chromext.script.ScriptStats
import org.matrix.chromext.script.ScriptStore
import org.matrix.chromext.script.parseApps
import org.matrix.chromext.script.parseLib
import org.matrix.chromext.script.parseResources
import org.matrix.chromext.script.parseScript
import org.matrix.chromext.script.toRecord

/**
 * Manager-side access to the central database. The manager runs in the module process, so it uses
 * [ScriptStore] directly; the ContentProvider is only needed by the browser processes.
 */
data class ImportResult(val imported: Int, val skipped: Int, val invalid: Int)

class ScriptRepository(context: Context) {

  private val appContext = context.applicationContext
  private val store = ScriptStore(appContext)

  /**
   * Parsed scripts keyed by id, with the source text they were parsed from. Re-parsing every
   * 800 KB script on each panel refresh made the manager visibly slow; only edited rows are
   * parsed again.
   */
  private val parseCache =
      java.util.concurrent.ConcurrentHashMap<String, Pair<String, Script>>()

  fun load(): List<Script> = store.records().mapNotNull { toScript(it) }

  fun records(): List<ScriptRecord> = store.records()

  fun stats(): ScriptStats = store.stats()

  fun save(script: Script): Boolean {
    val record = script.toRecord()
    val saved = store.upsert(record)
    if (saved) {
      runCatching { ScriptFiles.mirrorScript(appContext, store, record) }
      runCatching { ScriptFiles.writeManifest(appContext, store) }
    }
    return saved
  }

  /** Saves a script after downloading its `@require`/`@resource` payloads (install/update paths). */
  fun saveHydrated(script: Script): Boolean = save(ScriptHydrator.hydrate(appContext, script))

  /**
   * Writes the rows the user selected in the import preview. Existing ids are skipped unless
   * [overwrite] is set; `@require`/`@resource` payloads are hydrated for every written row.
   */
  fun importRecords(records: List<ScriptRecord>, overwrite: Boolean): ImportResult {
    val existing = records().mapTo(mutableSetOf()) { it.id }
    var imported = 0
    var skipped = 0
    var invalid = 0
    records.forEach { record ->
      if (record.id in existing && !overwrite) {
        skipped += 1
        return@forEach
      }
      val script = toScript(record)
      if (script == null) {
        invalid += 1
        return@forEach
      }
      val hydrated = ScriptHydrator.hydrate(appContext, script)
      if (store.upsert(hydrated.toRecord())) imported += 1 else invalid += 1
    }
    if (imported > 0) {
      runCatching { ScriptFiles.writeManifest(appContext, store) }
      mirrorAll()
    }
    return ImportResult(imported, skipped, invalid)
  }

  fun setEnabled(id: String, enabled: Boolean): Boolean {
    val ok = store.setEnabled(id, enabled)
    if (ok) runCatching { ScriptFiles.writeManifest(appContext, store) }
    return ok
  }

  fun delete(ids: Array<String>) {
    ids.forEach { runCatching { ScriptFiles.deleteScriptMirror(appContext, store, it) } }
    store.delete(ids)
    runCatching { ScriptFiles.writeManifest(appContext, store) }
  }

  fun updateStorage(id: String, storage: String): Boolean = store.updateStorage(id, storage)

  fun hookedApps(): List<HookedApp> = store.hookedApps()

  fun activations(): List<ScriptActivation> = store.activations()

  fun engineConfig(): EngineConfig = EngineConfig.from(store.config())

  fun saveEngineConfig(config: EngineConfig) = store.putConfigs(config.toMap())

  fun databasePath(): String = store.databasePath()

  fun databaseSize(): Long = store.databaseSize()

  fun revision(): Long = store.revision()

  fun vacuum(): Long = store.vacuum()

  fun clearOrphanStorage(): Int = store.clearOrphanStorage()

  /** Migration helper used by the browser-database importer: never overwrite an existing row. */
  fun insertIfAbsent(record: ScriptRecord): Boolean = store.insertIfAbsent(record)

  fun mirrorAll() {
    records().forEach { runCatching { ScriptFiles.mirrorScript(appContext, store, it) } }
    runCatching { ScriptFiles.writeManifest(appContext, store) }
  }

  /**
   * Scripts installed by older builds have no hydrated `@require` payloads. Fetch them once in the
   * background; a failed download leaves `lib` empty and is retried on the next manager start.
   */
  fun hydrateMissing() {
    val requireReg = Regex("""(?m)^//\s+@require\s+\S+""")
    records().forEach { record ->
      if (record.lib != "[]" || !requireReg.containsMatchIn(record.meta)) return@forEach
      val script = toScript(record) ?: return@forEach
      val hydrated = ScriptHydrator.hydrate(appContext, script)
      if (hydrated.lib.isNotEmpty()) {
        runCatching { store.upsert(hydrated.toRecord()) }
      }
    }
  }

  fun close() {
    store.close()
  }

  private fun toScript(record: ScriptRecord): Script? {
    val source = record.meta + record.code
    val cached = parseCache[record.id]
    val parsed =
        if (cached != null && cached.first == source) cached.second
        else
            parseScript(source, record.storage, hydrateRequires = false)?.also {
              parseCache[record.id] = source to it
            }
    if (parsed == null) return null
    // Fields that can change without the source text still come from the row.
    val storage =
        if (parsed.storage != null) {
          record.storage?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        } else null
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
        lib = parseLib(record.lib),
        resources = parseResources(record.resources),
        storage = storage,
    )
  }
}
