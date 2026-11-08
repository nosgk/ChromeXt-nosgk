package org.matrix.chromext.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.R
import org.matrix.chromext.browser.BrowserCatalog
import org.matrix.chromext.script.EngineConfig
import org.matrix.chromext.script.ScriptDbHelper

private data class Telemetry(
    val providerReady: Boolean,
    val latencyMs: Double,
    val schemaVersion: Int,
    val databasePath: String,
    val databaseSize: Long,
    val totalScripts: Int,
    val enabledScripts: Int,
    val storageBytes: Long,
    val hookedApps: Int,
)

private data class BrowserInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val icon: ImageBitmap?,
    val installed: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier,
    repository: ScriptRepository,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onChanged: () -> Unit,
    onMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var telemetry by remember { mutableStateOf<Telemetry?>(null) }
  var engine by remember { mutableStateOf(EngineConfig()) }
  var browsers by remember { mutableStateOf<List<BrowserInfo>>(emptyList()) }
  var showAllTargets by remember { mutableStateOf(false) }
  var confirmDeleteAll by remember { mutableStateOf(false) }
  var confirmClearStorage by remember { mutableStateOf(false) }
  var pendingStorageCount by remember { mutableStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  var storageDatabases by remember { mutableStateOf<List<LocalScriptDatabase>>(emptyList()) }
  var scanningStorage by remember { mutableStateOf(false) }
  var importingPackage by remember { mutableStateOf<String?>(null) }
  var scanError by remember { mutableStateOf<String?>(null) }
  var showManualImport by remember { mutableStateOf(false) }
  var manualPath by remember { mutableStateOf("") }

  fun loadTelemetry() {
    scope.launch {
      val loaded =
          withContext(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtimeNanos()
            val ready =
                runCatching {
                      context.contentResolver
                          .call(
                              Uri.parse("content://org.matrix.chromext.scripts"),
                              "ping",
                              null,
                              null,
                          )
                          ?.getBoolean("available") == true
                    }
                    .getOrDefault(false)
            val latency = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            val records = repository.records()
            val storageBytes =
                records.sumOf { (it.storage?.toByteArray(Charsets.UTF_8)?.size ?: 0).toLong() }
            Telemetry(
                providerReady = ready,
                latencyMs = latency,
                schemaVersion = ScriptDbHelper.DATABASE_VERSION,
                databasePath = repository.databasePath(),
                databaseSize = repository.databaseSize(),
                totalScripts = records.size,
                enabledScripts = records.count { it.enabled },
                storageBytes = storageBytes,
                hookedApps = repository.hookedApps().size,
            )
          }
      telemetry = loaded
      engine = withContext(Dispatchers.IO) { repository.engineConfig() }
    }
  }

  LaunchedEffect(Unit) { loadTelemetry() }

  LaunchedEffect(showAllTargets) {
    browsers = withContext(Dispatchers.IO) { loadBrowsers(context, showAllTargets) }
  }

  LaunchedEffect(browsers) {
    if (storageDatabases.isEmpty()) {
      storageDatabases =
          ScriptStorage.expected(
              browsers.filter { it.installed }.map { it.packageName to it.label })
    }
  }

  fun applyScan(outcome: ScanOutcome) {
    storageDatabases = outcome.databases
    scanError =
        if (outcome.rootOk) null
        else
            outcome.message.ifBlank { context.getString(R.string.settings_storage_no_root) }
  }

  fun scanStorage() {
    if (scanningStorage) return
    scanningStorage = true
    scope.launch {
      applyScan(withContext(Dispatchers.IO) { ScriptStorage.scan(storageDatabases) })
      scanningStorage = false
    }
  }

  fun importDatabase(database: LocalScriptDatabase, onDone: () -> Unit = {}) {
    importingPackage = database.packageName
    scope.launch {
      val outcome =
          withContext(Dispatchers.IO) {
            ScriptStorage.import(context, database, repository::insertIfAbsent)
          }
      importingPackage = null
      if (outcome.error != null) {
        onMessage(context.getString(R.string.settings_storage_import_failed, outcome.error))
      } else {
        onMessage(
            context.getString(
                R.string.settings_storage_imported,
                outcome.imported,
                outcome.skipped,
                outcome.invalid))
        onChanged()
        loadTelemetry()
      }
      onDone()
    }
  }

  fun importAll() {
    if (importingPackage != null) return
    if (storageDatabases.none { it.checked }) {
      // Nothing has been probed yet; scan once and continue.
      scanningStorage = true
      scope.launch {
        applyScan(withContext(Dispatchers.IO) { ScriptStorage.scan(storageDatabases) })
        scanningStorage = false
        importAll()
      }
      return
    }
    val pending = storageDatabases.filter { it.exists }
    if (pending.isEmpty()) {
      onMessage(context.getString(R.string.settings_storage_none))
      return
    }
    val iterator = pending.iterator()
    fun next() {
      if (!iterator.hasNext()) return
      importDatabase(iterator.next(), onDone = { next() })
    }
    next()
  }

  fun updateEngine(updated: EngineConfig) {
    engine = updated
    scope.launch { withContext(Dispatchers.IO) { repository.saveEngineConfig(updated) } }
  }

  val exportLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
          uri ->
        if (uri != null) {
          scope.launch {
            val ok = withContext(Dispatchers.IO) { Backup.export(context, repository.records(), uri) }
            onMessage(
                context.getString(
                    if (ok) R.string.settings_backup_exported else R.string.settings_backup_failed))
          }
        }
      }

  val importLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          scope.launch {
            val count = withContext(Dispatchers.IO) { Backup.import(context, uri) }
            onMessage(
                if (count >= 0) context.getString(R.string.settings_backup_imported, count)
                else context.getString(R.string.settings_backup_import_failed))
            if (count >= 0) {
              onChanged()
              loadTelemetry()
            }
          }
        }
      }

  Scaffold(
      modifier = modifier,
      topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
  ) { padding ->
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = 340.dp),
        modifier = Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
    ) {
      item(key = "telemetry") {
        TelemetryCard(telemetry = telemetry, onPing = { loadTelemetry() })
      }
      item(key = "browsers") {
        BrowsersCard(
            browsers = browsers,
            showAllTargets = showAllTargets,
            onToggleAll = { showAllTargets = it },
            onRestart = { packageName ->
              scope.launch {
                val restarted = restartBrowser(context, packageName)
                if (!restarted) openAppInfo(context, packageName)
                onMessage(
                    context.getString(
                        if (restarted) R.string.settings_restart_sent
                        else R.string.settings_restart_failed,
                        packageName))
              }
            },
        )
      }
      item(key = "engine") {
        EngineCard(engine = engine, onChange = { updateEngine(it) })
      }
      item(key = "storage") {
        StorageCard(
            centralPath = telemetry?.databasePath ?: "",
            centralSize = telemetry?.databaseSize ?: 0L,
            centralCount = telemetry?.totalScripts ?: 0,
            databases = storageDatabases,
            scanning = scanningStorage,
            importingPackage = importingPackage,
            scanError = scanError,
            onScan = { scanStorage() },
            onImport = { importDatabase(it) },
            onImportAll = { importAll() },
            onManual = {
              manualPath = ""
              showManualImport = true
            },
        )
      }
      item(key = "appearance") {
        AppearanceCard(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
      }
      item(key = "data") {
        DataCard(
            telemetry = telemetry,
            busy = busy,
            onExport = { exportLauncher.launch("ChromeXt-backup.zip") },
            onImport = {
              importLauncher.launch(
                  arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
            onVacuum = {
              busy = true
              scope.launch {
                val freed = withContext(Dispatchers.IO) { repository.vacuum() }
                busy = false
                loadTelemetry()
                onMessage(context.getString(R.string.settings_vacuum_done, formatBytes(freed)))
              }
            },
            onClearStorage = {
              pendingStorageCount = 0
              scope.launch {
                val count = withContext(Dispatchers.IO) { countOrphanStorage(repository) }
                pendingStorageCount = count
                confirmClearStorage = true
              }
            },
            onDeleteAll = { confirmDeleteAll = true },
        )
      }
      item(key = "about-version") {
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
      }
    }
  }

  if (showManualImport) {
    AlertDialog(
        onDismissRequest = { showManualImport = false },
        title = { Text(stringResource(R.string.settings_storage_manual_title)) },
        text = {
          Column {
            Text(
                stringResource(R.string.settings_storage_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = manualPath,
                onValueChange = { manualPath = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_storage_manual_path)) },
            )
          }
        },
        confirmButton = {
          TextButton(
              enabled = manualPath.isNotBlank() && importingPackage == null,
              onClick = {
                val database =
                    LocalScriptDatabase(
                        packageName = "manual",
                        label = context.getString(R.string.settings_storage_manual_label),
                        path = manualPath.trim(),
                        checked = true,
                        exists = true,
                    )
                showManualImport = false
                importDatabase(database)
              }) {
                Text(stringResource(R.string.settings_storage_import))
              }
        },
        dismissButton = {
          TextButton(onClick = { showManualImport = false }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
  }

  if (confirmDeleteAll) {
    val count = telemetry?.totalScripts ?: 0
    AlertDialog(
        onDismissRequest = { confirmDeleteAll = false },
        title = { Text(stringResource(R.string.settings_delete_all_title)) },
        text = { Text(stringResource(R.string.settings_delete_all_count, count)) },
        confirmButton = {
          TextButton(
              onClick = {
                confirmDeleteAll = false
                scope.launch {
                  withContext(Dispatchers.IO) {
                    repository.delete(repository.records().map { it.id }.toTypedArray())
                  }
                  onChanged()
                  loadTelemetry()
                  onMessage(context.getString(R.string.settings_deleted_all))
                }
              }) {
                Text(stringResource(R.string.action_delete))
              }
        },
        dismissButton = {
          TextButton(onClick = { confirmDeleteAll = false }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
  }

  if (confirmClearStorage) {
    AlertDialog(
        onDismissRequest = { confirmClearStorage = false },
        title = { Text(stringResource(R.string.settings_clear_storage_title)) },
        text = { Text(stringResource(R.string.settings_clear_storage_body, pendingStorageCount)) },
        confirmButton = {
          TextButton(
              onClick = {
                confirmClearStorage = false
                scope.launch {
                  val cleared =
                      withContext(Dispatchers.IO) { repository.clearOrphanStorage() }
                  onChanged()
                  loadTelemetry()
                  onMessage(context.getString(R.string.settings_storage_cleared, cleared))
                }
              }) {
                Text(stringResource(R.string.action_delete))
              }
        },
        dismissButton = {
          TextButton(onClick = { confirmClearStorage = false }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
  }
}

@Composable
private fun TelemetryCard(telemetry: Telemetry?, onPing: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.settings_telemetry),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        StatusPill(
            ready = telemetry?.providerReady == true,
            text =
                stringResource(
                    if (telemetry?.providerReady == true) R.string.settings_status_active
                    else R.string.settings_status_fallback),
        )
      }
      val value = telemetry
      if (value == null) {
        Text(
            stringResource(R.string.loading),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
      } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Metric(
              title = stringResource(R.string.settings_metric_ipc),
              value =
                  stringResource(
                      if (value.providerReady) R.string.settings_ipc_ready
                      else R.string.settings_ipc_fallback),
              detail = String.format(Locale.US, "%.1f ms", value.latencyMs),
              modifier = Modifier.weight(1f),
              action = {
                TextButton(onClick = onPing) { Text(stringResource(R.string.settings_ping)) }
              },
          )
          Metric(
              title = stringResource(R.string.settings_metric_schema),
              value = "v${value.schemaVersion}",
              detail = formatBytes(value.databaseSize),
              modifier = Modifier.weight(1f),
          )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Metric(
              title = stringResource(R.string.settings_metric_payload),
              value = "${value.enabledScripts}/${value.totalScripts}",
              detail = stringResource(R.string.settings_storage_size, formatBytes(value.storageBytes)),
              modifier = Modifier.weight(1f),
          )
          Metric(
              title = stringResource(R.string.settings_metric_module),
              value = BuildConfig.VERSION_NAME,
              detail = stringResource(R.string.settings_hooked_apps, value.hookedApps),
              modifier = Modifier.weight(1f),
          )
        }
        Text(
            value.databasePath,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
      }
    }
  }
}

@Composable
private fun StatusPill(ready: Boolean, text: String) {
  Box(
      modifier =
          Modifier.background(
                  if (ready) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.errorContainer,
                  RoundedCornerShape(50),
              )
              .padding(horizontal = 10.dp, vertical = 4.dp)
  ) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color =
            if (ready) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onErrorContainer,
    )
  }
}

@Composable
private fun Metric(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
  Column(modifier = modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        detail,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    action?.invoke()
  }
}

@Composable
private fun BrowsersCard(
    browsers: List<BrowserInfo>,
    showAllTargets: Boolean,
    onToggleAll: (Boolean) -> Unit,
    onRestart: (String) -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.settings_browsers_installed),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onToggleAll(!showAllTargets) }) {
          Text(
              stringResource(
                  if (showAllTargets) R.string.settings_browsers_show_installed
                  else R.string.settings_browsers_show_all))
        }
      }
      if (browsers.isEmpty()) {
        Text(
            stringResource(R.string.settings_browsers_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      browsers.forEach { browser ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          if (browser.icon != null) {
            Image(
                bitmap = browser.icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
          } else {
            Box(
                modifier =
                    Modifier.size(32.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
              Text(
                  browser.label.take(1).uppercase(),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                browser.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                browser.packageName,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
          }
          if (browser.installed) {
            Text(
                if (browser.versionName.isBlank()) ""
                else stringResource(R.string.settings_browser_version, browser.versionName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onRestart(browser.packageName) }) {
              Icon(
                  Icons.Default.Refresh,
                  contentDescription = stringResource(R.string.settings_browser_restart),
                  tint = MaterialTheme.colorScheme.primary,
              )
            }
          } else {
            Text(
                stringResource(R.string.settings_browser_not_installed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EngineCard(engine: EngineConfig, onChange: (EngineConfig) -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
          stringResource(R.string.settings_engine),
          style = MaterialTheme.typography.titleMedium,
      )
      EngineSwitch(
          title = stringResource(R.string.settings_engine_injection),
          hint = stringResource(R.string.settings_engine_injection_hint),
          checked = engine.injectionEnabled,
          onCheckedChange = { onChange(engine.copy(injectionEnabled = it)) },
      )
      EngineSwitch(
          title = stringResource(R.string.settings_engine_keep_storage),
          hint = stringResource(R.string.settings_engine_keep_storage_hint),
          checked = engine.keepStorage,
          onCheckedChange = { onChange(engine.copy(keepStorage = it)) },
      )
      EngineSwitch(
          title = stringResource(R.string.settings_engine_csp),
          hint = stringResource(R.string.settings_engine_csp_hint),
          checked = engine.cspRules,
          onCheckedChange = { onChange(engine.copy(cspRules = it)) },
      )
      EngineSwitch(
          title = stringResource(R.string.settings_engine_webview),
          hint = stringResource(R.string.settings_engine_webview_hint),
          checked = engine.webViewDebug,
          onCheckedChange = { onChange(engine.copy(webViewDebug = it)) },
      )
      EngineSwitch(
          title = stringResource(R.string.settings_engine_eruda),
          hint = stringResource(R.string.settings_engine_eruda_hint),
          checked = engine.erudaEnabled,
          onCheckedChange = { onChange(engine.copy(erudaEnabled = it)) },
      )
      EngineSwitch(
          title = stringResource(R.string.settings_engine_eruda_auto),
          hint = stringResource(R.string.settings_engine_eruda_auto_hint),
          checked = engine.erudaAuto,
          onCheckedChange = { onChange(engine.copy(erudaAuto = it)) },
      )
      EngineSwitch(
          title = stringResource(R.string.settings_engine_privileged),
          hint = stringResource(R.string.settings_engine_privileged_hint),
          checked = engine.allowPrivileged,
          onCheckedChange = { onChange(engine.copy(allowPrivileged = it)) },
      )
      EngineSwitch(
          title = stringResource(R.string.settings_engine_install_confirm),
          hint = stringResource(R.string.settings_engine_install_confirm_hint),
          checked = engine.installConfirm,
          onCheckedChange = { onChange(engine.copy(installConfirm = it)) },
      )
      Text(
          stringResource(R.string.settings_engine_pending),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun EngineSwitch(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyMedium)
      Text(
          hint,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun AppearanceCard(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
          stringResource(R.string.settings_appearance),
          style = MaterialTheme.typography.titleMedium,
      )
      Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ThemeMode.entries.forEach { mode ->
          val selected = themeMode == mode
          OutlinedButton(
              onClick = { onThemeModeChange(mode) },
              modifier = Modifier.weight(1f),
          ) {
            Text(
                stringResource(
                    when (mode) {
                      ThemeMode.SYSTEM -> R.string.theme_system
                      ThemeMode.LIGHT -> R.string.theme_light
                      ThemeMode.DARK -> R.string.theme_dark
                    }),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style =
                    if (selected) MaterialTheme.typography.labelLarge
                    else MaterialTheme.typography.labelMedium,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DataCard(
    telemetry: Telemetry?,
    busy: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onVacuum: () -> Unit,
    onClearStorage: () -> Unit,
    onDeleteAll: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
          stringResource(R.string.settings_data),
          style = MaterialTheme.typography.titleMedium,
      )
      Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(onClick = onExport, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.settings_export_short), maxLines = 1)
        }
        Button(onClick = onImport, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.settings_import_short), maxLines = 1)
        }
      }
      OutlinedButton(
          onClick = onVacuum,
          enabled = !busy,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      ) {
        Text(stringResource(R.string.settings_vacuum))
      }
      Text(
          stringResource(R.string.settings_vacuum_hint),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedButton(
          onClick = onClearStorage,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      ) {
        Text(stringResource(R.string.settings_clear_storage))
      }
      Text(
          stringResource(R.string.settings_clear_storage_hint),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
      Text(
          stringResource(R.string.settings_danger),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.error,
      )
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(
            onClick = onDeleteAll,
            colors =
                androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error),
        ) {
          Text(stringResource(R.string.settings_delete_all))
        }
      }
      Text(
          stringResource(R.string.settings_delete_all_count, telemetry?.totalScripts ?: 0),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun loadBrowsers(context: Context, includeMissing: Boolean): List<BrowserInfo> {
  val packageManager = context.packageManager
  return BrowserCatalog.supportedPackages
      .mapNotNull { packageName ->
        val info = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        if (info == null && !includeMissing) return@mapNotNull null
        val label =
            info?.let {
              runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull()
            } ?: packageName
        val version =
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
                .getOrNull()
                .orEmpty()
        val icon =
            info?.let {
              runCatching {
                    packageManager.getApplicationIcon(it).toBrowserIconBitmap(64).asImageBitmap()
                  }
                  .getOrNull()
            }
        BrowserInfo(
            packageName = packageName,
            label = label,
            versionName = version,
            icon = icon,
            installed = info != null,
        )
      }
      .sortedWith(
          compareByDescending<BrowserInfo> { it.installed }.thenBy { it.label.lowercase() })
}

private fun countOrphanStorage(repository: ScriptRepository): Int {
  var count = 0
  repository.records().forEach { record ->
    if (record.storage == null) return@forEach
    val parsed =
        org.matrix.chromext.script.parseScript(
            record.meta + record.code, null, hydrateRequires = false)
    val usesStorage =
        parsed?.grant?.any {
          it.startsWith("GM_setValue") ||
              it.startsWith("GM_getValue") ||
              it.startsWith("GM_listValues") ||
              it.startsWith("GM_addValueChangeListener") ||
              it.startsWith("GM_removeValueChangeListener") ||
              it == "GM_cookie" ||
              it == "GM.setValue" ||
              it == "GM.getValue" ||
              it == "GM.listValues" ||
              it == "GM.cookie"
        } == true
    if (!usesStorage) count += 1
  }
  return count
}

private suspend fun restartBrowser(context: Context, packageName: String): Boolean =
    withContext(Dispatchers.IO) {
      runCatching {
            val process =
                ProcessBuilder(
                        "su",
                        "-c",
                        "am force-stop $packageName; monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                    .redirectErrorStream(true)
                    .start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            finished && process.exitValue() == 0
          }
          .getOrDefault(false)
    }

private fun openAppInfo(context: Context, packageName: String) {
  runCatching {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}

@Composable
private fun StorageCard(
    centralPath: String,
    centralSize: Long,
    centralCount: Int,
    databases: List<LocalScriptDatabase>,
    scanning: Boolean,
    importingPackage: String?,
    scanError: String?,
    onScan: () -> Unit,
    onImport: (LocalScriptDatabase) -> Unit,
    onImportAll: () -> Unit,
    onManual: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
          stringResource(R.string.settings_storage_title),
          style = MaterialTheme.typography.titleMedium,
      )
      Text(
          stringResource(R.string.settings_storage_central),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
      )
      SelectionContainer {
        Text(
            centralPath,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        )
      }
      Text(
          formatBytes(centralSize) +
              " · " +
              stringResource(R.string.settings_storage_scripts, centralCount),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.settings_storage_local),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onScan, enabled = !scanning) {
          Text(
              stringResource(
                  if (scanning) R.string.settings_storage_scanning
                  else R.string.settings_storage_scan))
        }
        TextButton(
            onClick = onImportAll,
            enabled = importingPackage == null && databases.any { it.exists },
        ) {
          Text(stringResource(R.string.settings_storage_import_all))
        }
        TextButton(onClick = onManual) {
          Text(stringResource(R.string.settings_storage_manual))
        }
      }
      scanError?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
      }
      if (databases.isEmpty()) {
        Text(
            stringResource(R.string.settings_storage_no_browser),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      databases.forEach { database ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text(database.label, style = MaterialTheme.typography.bodyMedium)
              Text(
                  database.packageName,
                  style =
                      MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (database.checked && !database.exists) {
              Text(
                  stringResource(R.string.settings_storage_missing),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              TextButton(
                  onClick = { onImport(database) },
                  enabled = database.exists && importingPackage == null,
              ) {
                Text(
                    stringResource(
                        if (importingPackage == database.packageName)
                            R.string.settings_storage_importing
                        else R.string.settings_storage_import))
              }
            }
          }
          SelectionContainer {
            Text(
                database.path,
                style =
                    MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Text(
              if (!database.checked) stringResource(R.string.settings_storage_unchecked)
              else if (!database.exists) stringResource(R.string.settings_storage_missing)
              else
                  formatBytes(database.size) +
                      " · " +
                      stringResource(
                          R.string.settings_storage_modified,
                          formatTime(database.lastModified)),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Text(
          stringResource(R.string.settings_storage_hint),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 6.dp),
      )
      if (databases.isNotEmpty() &&
          databases.all { it.checked } &&
          databases.none { it.exists }) {
        Text(
            stringResource(R.string.settings_storage_not_found_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}

private fun formatTime(value: Long): String =
    if (value <= 0L) ""
    else
        runCatching {
              java.text.DateFormat.getDateTimeInstance(
                      java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                  .format(java.util.Date(value))
            }
            .getOrDefault("")

private fun formatBytes(bytes: Long): String =
    when {
      bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
      bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
      else -> "$bytes B"
    }

private fun Drawable.toBrowserIconBitmap(size: Int): Bitmap {
  if (this is BitmapDrawable && bitmap != null) {
    return Bitmap.createScaledBitmap(bitmap, size, size, true)
  }
  val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(output)
  setBounds(0, 0, size, size)
  draw(canvas)
  return output
}
