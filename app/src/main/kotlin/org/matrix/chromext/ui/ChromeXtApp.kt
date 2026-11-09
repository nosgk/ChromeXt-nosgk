package org.matrix.chromext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import org.matrix.chromext.script.ScriptActivation

sealed interface Screen {
  data object Apps : Screen
  data object List : Screen
  data object Settings : Screen
  data object About : Screen
  data class Edit(val script: Script) : Screen
}

private data class Destination(val screen: Screen, val icon: ImageVector, val label: Int)

/** The apps entry stays highlighted while browsing its second-level script screens. */
private fun isSelected(destination: Screen, current: Screen): Boolean =
    when (destination) {
      Screen.Apps -> current is Screen.Apps || current is Screen.List || current is Screen.Edit
      Screen.List -> current is Screen.List
      Screen.Settings -> current is Screen.Settings
      Screen.About -> current is Screen.About
      is Screen.Edit -> current is Screen.Edit
    }

private data class Loaded(
    val scripts: List<Script>,
    val hooked: List<HookedApp>,
    val activations: List<ScriptActivation>,
    val apps: List<AppEntry>,
    val scope: ScopeResult,
)

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
  // Package icons/labels and the LSPosed scope are expensive (PackageManager + su) and do not
  // change between panel refreshes, so they are loaded once per panel session.
  val appsCache = remember { AppsCache() }

  var scripts by remember { mutableStateOf<List<Script>>(emptyList()) }
  var hookedApps by remember { mutableStateOf<List<HookedApp>>(emptyList()) }
  var activations by remember { mutableStateOf<Map<String, ScriptActivation>>(emptyMap()) }
  var activationPairs by remember { mutableStateOf<Set<Pair<String, String>>>(emptySet()) }
  var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
  var scopeResult by remember { mutableStateOf(ScopeResult()) }
  var includeAllApps by rememberSaveable { mutableStateOf(false) }
  var loading by remember { mutableStateOf(true) }
  var refreshing by remember { mutableStateOf(false) }
  var loadError by remember { mutableStateOf(false) }
  var screen by remember { mutableStateOf<Screen>(Screen.Apps) }
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
    val loaded =        withContext(Dispatchers.IO) {
          runCatching {
            val loadedScripts = repository.load()
            val loadedHooked = repository.hookedApps()
            val loadedActivations = repository.activations()
            val scopeRead = LsposedScope.read(context)
            Loaded(
                scripts = loadedScripts,
                hooked = loadedHooked,
                activations = loadedActivations,
                apps = loadApps(context, loadedHooked, loadedScripts, scopeRead.packages, appsCache),
                scope = scopeRead,
            )
          }
        }
    loaded
        .onSuccess {
          scripts = it.scripts
          hookedApps = it.hooked
          activations =
              it.activations
                  .groupBy { activation -> activation.scriptId }
                  .mapValues { entry -> entry.value.maxByOrNull { a -> a.lastActive }!! }
          activationPairs =
              it.activations.mapTo(mutableSetOf()) { a -> a.scriptId to a.packageName }
          apps = it.apps
          scopeResult = it.scope
        }
        .onFailure { loadError = true }
    loading = false
    refreshing = false
  }

  // Older installs have no hydrated @require payloads; fetch them once in the background.
  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) { runCatching { repository.hydrateMissing() } }
  }

  LaunchedEffect(editId, scripts) {
    if (editId != null && editId != openedEditId && scripts.any { it.id == editId }) {
      openedEditId = editId
      scripts.firstOrNull { it.id == editId }?.let { screen = Screen.Edit(it) }
    }
  }

  fun refresh() {
    refreshing = true
    reload += 1
  }

  fun toggle(id: String, enabled: Boolean) {
    scripts = scripts.map { if (it.id == id) it.copy(enabled = enabled) else it }
    scope.launch {
      val saved = withContext(Dispatchers.IO) { repository.setEnabled(id, enabled) }
      if (saved) {
        showMessage(context.getString(R.string.toggle_saved))
      } else {
        scripts = scripts.map { if (it.id == id) it.copy(enabled = !enabled) else it }
        showMessage(context.getString(R.string.toggle_failed))
      }
    }
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
            // A new script (or one whose id changed) gets its @require/@resource payloads
            // downloaded now; a plain metadata edit reuses what is already stored.
            if (originalId == null || originalId != script.id) repository.saveHydrated(script)
            else repository.save(script)
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
    scope.launch {
      val keepStorage = withContext(Dispatchers.IO) { repository.engineConfig().keepStorage }
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
      val saved = withContext(Dispatchers.IO) { repository.saveHydrated(merged) }
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

  // The selector only offers apps that are actually in the framework scope; when the scope cannot
  // be read, fall back to apps that reported in so the list is still usable.
  val knownApps = remember(apps, scopeResult) {
    val allowed =
        if (scopeResult.status == ScopeStatus.OK) scopeResult.packages
        else apps.filter { it.active }.map { it.packageName }.toSet()
    apps
        .filter { it.installed && (allowed.isEmpty() || it.packageName in allowed) }
        .map { AppEntry(it.packageName, it.label, it.versionName, null, false, true, false) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
  }

  val appLabel =
      appFilter?.let { packageName ->
        apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
      }

  val appLabels = remember(apps) { apps.associate { it.packageName to it.label } }

  BackHandler(enabled = screen !is Screen.Apps) {
    screen = if (screen is Screen.Edit) Screen.List else Screen.Apps
  }

  val destinations =
      listOf(
          Destination(Screen.Apps, Icons.Default.Build, R.string.nav_apps),
          Destination(Screen.Settings, Icons.Default.Settings, R.string.nav_settings),
          Destination(Screen.About, Icons.Default.Info, R.string.nav_about),
      )

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val wide = maxWidth >= 600.dp
    Row(modifier = Modifier.fillMaxSize()) {
      if (wide) {
        NavigationRail(modifier = Modifier.fillMaxHeight()) {
          destinations.forEach { destination ->
            NavigationRailItem(
                selected = isSelected(destination.screen, screen),
                onClick = { screen = destination.screen },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
          }
        }
      }
      Scaffold(
          modifier = Modifier.weight(1f),
          snackbarHost = { SnackbarHost(snackbar) },
          bottomBar = {
            if (!wide && screen !is Screen.Edit) {
              NavigationBar {
                destinations.forEach { destination ->
                  NavigationBarItem(
                      selected = isSelected(destination.screen, screen),
                      onClick = { screen = destination.screen },
                      icon = { Icon(destination.icon, contentDescription = null) },
                      label = { Text(stringResource(destination.label)) },
                  )
                }
              }
            }
          },
      ) { padding ->
        Crossfade(targetState = screen, animationSpec = tween(180), label = "screen") { current ->
          when (current) {
            Screen.Apps ->
                AppsScreen(
                    modifier = Modifier.padding(padding),
                    apps = apps,
                    scripts = scripts,
                    activationPairs = activationPairs,
                    loading = loading,
                    refreshing = refreshing,
                    includeAll = includeAllApps,
                    scopeResult = scopeResult,
                    onRefresh = { refresh() },
                    onIncludeAllChange = { includeAllApps = it },
                    onOpenApp = { packageName ->
                      appFilter = packageName
                      screen = Screen.List
                    },
                )
            Screen.List ->
                ScriptListScreen(
                    modifier = Modifier.padding(padding),
                    scripts = scripts,
                    activations = activations,
                    appLabels = appLabels,
                    loading = loading,
                    refreshing = refreshing,
                    loadError = loadError,
                    activeUrl = activeUrl,
                    appFilter = appFilter,
                    appLabel = appLabel,
                    defaultApps = appFilter?.let { listOf(it) } ?: emptyList(),
                    updates = updates,
                    checkingUpdates = checkingUpdates,
                    onBack = { screen = Screen.Apps },
                    onClearAppFilter = { appFilter = null },
                    onToggle = { id, enabled -> toggle(id, enabled) },
                    onDelete = { delete(it) },
                    onEdit = { screen = Screen.Edit(it) },
                    onNew = { screen = Screen.Edit(it) },
                    onImport = { save(null, it) },
                    onError = { showMessage(it) },
                    onCheckUpdates = { checkUpdates() },
                    onApplyUpdate = { script, info -> applyUpdate(script, info) },
                    onRetry = { refresh() },
                    onRefresh = { refresh() },
                )
            Screen.Settings ->
                SettingsScreen(
                    modifier = Modifier.padding(padding),
                    repository = repository,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onChanged = { reload += 1 },
                    onMessage = { showMessage(it) },
                    scopePackages = scopeResult.packages,
                )
            Screen.About -> AboutScreen(modifier = Modifier.padding(padding))
            is Screen.Edit ->
                ScriptEditorScreen(
                    modifier = Modifier.padding(padding),
                    script = current.script,
                    knownApps = knownApps,
                    scopeAvailable = scopeResult.status == ScopeStatus.OK,
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
  }
}
