package org.matrix.chromext.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.matrix.chromext.R
import org.matrix.chromext.script.HookedApp
import org.matrix.chromext.script.Script

sealed interface Screen {
  data object List : Screen
  data object Apps : Screen
  data object Settings : Screen
  data object About : Screen
  data class Edit(val script: Script) : Screen
}

@Composable
fun ChromeXtApp(
    activeUrl: String?,
    editId: String?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
  val context = LocalContext.current
  val repository = remember { ScriptRepository(context) }
  DisposableEffect(Unit) { onDispose { repository.close() } }
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }

  var scripts by remember { mutableStateOf<List<Script>>(emptyList()) }
  var hookedApps by remember { mutableStateOf<List<HookedApp>>(emptyList()) }
  var installedApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var loadError by remember { mutableStateOf(false) }
  var screen by remember { mutableStateOf<Screen>(Screen.List) }
  var appFilter by rememberSaveable { mutableStateOf<String?>(null) }
  var reload by remember { mutableIntStateOf(0) }
  var updates by remember { mutableStateOf<Map<String, UpdateInfo>>(emptyMap()) }
  var checkingUpdates by remember { mutableStateOf(false) }
  var openedEditId by remember { mutableStateOf<String?>(null) }

  fun showMessage(text: String) {
    scope.launch { snackbar.showSnackbar(text) }
  }

  LaunchedEffect(reload) {
    loading = true
    loadError = false
    val loaded =
        withContext(Dispatchers.IO) {
          runCatching {
            Triple(repository.load(), repository.hookedApps(), loadInstalledBrowsers(context))
          }
        }
    loaded
        .onSuccess { (loadedScripts, loadedApps, loadedInstalled) ->
          scripts = loadedScripts
          hookedApps = loadedApps
          installedApps = loadedInstalled
        }
        .onFailure { loadError = true }
    loading = false
  }

  LaunchedEffect(editId, scripts) {
    if (editId != null && editId != openedEditId && scripts.any { it.id == editId }) {
      openedEditId = editId
      scripts.firstOrNull { it.id == editId }?.let { screen = Screen.Edit(it) }
    }
  }

  fun toggle(id: String, enabled: Boolean) {
    scripts = scripts.map { if (it.id == id) it.copy(enabled = enabled) else it }
    scope.launch { withContext(Dispatchers.IO) { repository.setEnabled(id, enabled) } }
  }

  fun delete(script: Script) {
    scripts = scripts.filterNot { it.id == script.id }
    scope.launch { withContext(Dispatchers.IO) { repository.delete(arrayOf(script.id)) } }
  }

  fun save(originalId: String?, script: Script) {
    scope.launch {
      val saved =
          withContext(Dispatchers.IO) {
            if (originalId != null && originalId != script.id) {
              repository.delete(arrayOf(originalId))
            }
            repository.save(script)
          }
      if (saved) {
        scripts = scripts.filterNot { it.id == originalId || it.id == script.id } + script
        screen = Screen.List
      } else {
        showMessage(context.getString(R.string.error_title))
      }
    }
  }

  fun checkUpdates() {
    if (checkingUpdates) return
    val targets = scripts.filter { it.updateUrl.isNotBlank() }
    if (targets.isEmpty()) {
      showMessage(context.getString(R.string.update_error_no_url))
      return
    }
    checkingUpdates = true
    scope.launch {
      val results = coroutineScope {
        val semaphore = Semaphore(3)
        targets
            .map { script ->
              async(Dispatchers.IO) { semaphore.withPermit { UpdateChecker.check(script) } }
            }
            .awaitAll()
      }
      updates = results.associateBy { it.scriptId }
      checkingUpdates = false
      val count = results.count { it.hasUpdate }
      showMessage(
          if (count == 0) context.getString(R.string.update_none)
          else context.getString(R.string.update_available_count, count))
    }
  }

  fun applyUpdate(script: Script, info: UpdateInfo) {
    val remote = info.remote ?: return
    val keepStorage =
        context
            .getSharedPreferences("ChromeXt", Context.MODE_PRIVATE)
            .getBoolean("keep_storage", true)
    // Preserve everything the user configured locally; only meta/code/version come from the
    // downloaded script.
    val merged =
        remote.copy(
            enabled = script.enabled,
            apps = script.apps,
            sortOrder = script.sortOrder,
            customMatch = script.customMatch,
            customInclude = script.customInclude,
            customExclude = script.customExclude,
            customRunAt = script.customRunAt,
            updateUrl = script.updateUrl.ifBlank { remote.updateUrl },
            iconUrl = script.iconUrl.ifBlank { remote.iconUrl },
            storage = if (keepStorage) script.storage else null,
            lastUpdated = System.currentTimeMillis(),
            lib = script.lib,
        )
    scope.launch {
      val saved = withContext(Dispatchers.IO) { repository.save(merged) }
      if (saved) {
        scripts = scripts.map { if (it.id == script.id) merged else it }
        updates = updates - script.id
        showMessage(
            context.getString(R.string.update_applied, merged.id.substringAfterLast(':')))
      } else {
        showMessage(context.getString(R.string.error_title))
      }
    }
  }

  val knownApps = remember(installedApps, hookedApps) {
    (installedApps + hookedApps.map { AppEntry(it.packageName, it.label) })
        .filter { it.packageName.isNotBlank() }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
  }

  val appLabel =
      appFilter?.let { packageName ->
        knownApps.firstOrNull { it.packageName == packageName }?.label?.ifBlank { null }
            ?: packageName
      }

  BackHandler(enabled = screen !is Screen.List) { screen = Screen.List }

  Scaffold(
      snackbarHost = { SnackbarHost(snackbar) },
      bottomBar = {
        if (screen !is Screen.Edit) {
          NavigationBar {
            NavigationBarItem(
                selected = screen is Screen.List,
                onClick = { screen = Screen.List },
                icon = { Icon(Icons.Default.List, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_scripts)) },
            )
            NavigationBarItem(
                selected = screen is Screen.Apps,
                onClick = { screen = Screen.Apps },
                icon = { Icon(Icons.Default.Build, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_apps)) },
            )
            NavigationBarItem(
                selected = screen is Screen.Settings,
                onClick = { screen = Screen.Settings },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_settings)) },
            )
            NavigationBarItem(
                selected = screen is Screen.About,
                onClick = { screen = Screen.About },
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_about)) },
            )
          }
        }
      },
  ) { padding ->
    Crossfade(targetState = screen, animationSpec = tween(180), label = "screen") { current ->
      when (current) {
        Screen.List ->
            ScriptListScreen(
                modifier = Modifier.padding(padding),
                scripts = scripts,
                loading = loading,
                loadError = loadError,
                activeUrl = activeUrl,
                appFilter = appFilter,
                appLabel = appLabel,
                updates = updates,
                checkingUpdates = checkingUpdates,
                onClearAppFilter = { appFilter = null },
                onToggle = { id, enabled -> toggle(id, enabled) },
                onDelete = { delete(it) },
                onEdit = { screen = Screen.Edit(it) },
                onNew = { screen = Screen.Edit(it) },
                onImport = { save(null, it) },
                onError = { showMessage(it) },
                onCheckUpdates = { checkUpdates() },
                onApplyUpdate = { script, info -> applyUpdate(script, info) },
                onRetry = { reload += 1 },
            )
        Screen.Apps ->
            AppsScreen(
                modifier = Modifier.padding(padding),
                active = hookedApps,
                installed = installedApps,
                scripts = scripts,
                loading = loading,
                onOpenApp = {
                  appFilter = it
                  screen = Screen.List
                },
            )
        Screen.Settings ->
            SettingsScreen(
                modifier = Modifier.padding(padding),
                repository = repository,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onChanged = { reload += 1 },
                onMessage = { showMessage(it) },
            )
        Screen.About -> AboutScreen(modifier = Modifier.padding(padding))
        is Screen.Edit ->
            ScriptEditorScreen(
                modifier = Modifier.padding(padding),
                script = current.script,
                knownApps = knownApps,
                onCancel = { screen = Screen.List },
                onSave = { updated ->
                  val originalId = current.script.id.takeIf { id -> scripts.any { it.id == id } }
                  save(originalId, updated)
                },
            )
      }
    }
  }
}
