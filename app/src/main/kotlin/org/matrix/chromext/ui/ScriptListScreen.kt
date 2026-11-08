package org.matrix.chromext.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.chromext.R
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.parseScript
import org.matrix.chromext.utils.matching

private enum class ScriptFilter {
  ALL,
  ENABLED,
  DISABLED,
  THIS_PAGE,
}

private enum class ScriptSort {
  ORDER,
  NAME,
  UPDATED,
}

private const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptListScreen(
    modifier: Modifier,
    scripts: List<Script>,
    loading: Boolean,
    loadError: Boolean,
    activeUrl: String?,
    appFilter: String?,
    appLabel: String?,
    updates: Map<String, UpdateInfo>,
    checkingUpdates: Boolean,
    onClearAppFilter: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (Script) -> Unit,
    onEdit: (Script) -> Unit,
    onNew: (Script) -> Unit,
    onImport: (Script) -> Unit,
    onError: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onApplyUpdate: (Script, UpdateInfo) -> Unit,
    onRetry: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var query by rememberSaveable { mutableStateOf("") }
  var filter by rememberSaveable { mutableStateOf(ScriptFilter.ALL) }
  var sort by rememberSaveable { mutableStateOf(ScriptSort.ORDER) }
  var showFabMenu by remember { mutableStateOf(false) }
  var deleteTarget by remember { mutableStateOf<Script?>(null) }
  var pendingUpdate by remember { mutableStateOf<Pair<Script, UpdateInfo>?>(null) }
  var showUrlDialog by remember { mutableStateOf(false) }

  // 180 ms debounce keeps typing responsive while the code corpus is still scanned.
  val debouncedQuery by
      produceState(initialValue = "", key1 = query) {
        delay(180)
        value = query
      }

  val searchBlobs = remember(scripts) {
    scripts.associate { script ->
      script.id to
          buildString {
                append(script.id)
                append('\n')
                append(script.description)
                append('\n')
                append(script.match.joinToString("\n"))
                append('\n')
                append(script.code)
              }
              .lowercase()
    }
  }

  val visible = remember(scripts, searchBlobs, debouncedQuery, filter, sort, appFilter, activeUrl) {
    val needle = debouncedQuery.trim().lowercase()
    scripts
        .asSequence()
        .filter { appFilter == null || it.appliesTo(appFilter) }
        .filter { needle.isEmpty() || searchBlobs[it.id]?.contains(needle) == true }
        .filter {
          when (filter) {
            ScriptFilter.ALL -> true
            ScriptFilter.ENABLED -> it.enabled
            ScriptFilter.DISABLED -> !it.enabled
            ScriptFilter.THIS_PAGE -> activeUrl != null && matching(it, activeUrl)
          }
        }
        .sortedWith(
            when (sort) {
              ScriptSort.ORDER -> compareBy({ it.sortOrder }, { it.id })
              ScriptSort.NAME -> compareBy { it.id.lowercase() }
              ScriptSort.UPDATED -> compareByDescending { it.lastUpdated }
            })
        .toList()
  }

  val enabledCount = scripts.count { it.enabled }
  val title =
      if (appFilter != null && appLabel != null) {
        stringResource(R.string.apps_filter_title, appLabel)
      } else {
        stringResource(R.string.scripts_title, enabledCount, scripts.size)
      }

  val importLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
          val text =
              withContext(Dispatchers.IO) {
                runCatching {
                      context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        it.readText()
                      }
                    }
                    .getOrNull()
              }
          if (text == null) {
            onError(context.getString(R.string.error_read_file))
            return@launch
          }
          val parsed = parseScript(text, null, hydrateRequires = false)
          if (parsed == null) {
            onError(context.getString(R.string.error_invalid_userscript))
          } else {
            onImport(parsed.copy(lastUpdated = System.currentTimeMillis()))
          }
        }
      }

  Scaffold(
      modifier = modifier,
      topBar = {
        Column {
          TopAppBar(
              title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
              actions = {
                IconButton(onClick = onCheckUpdates, enabled = !checkingUpdates) {
                  if (checkingUpdates) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                  } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.check_updates))
                  }
                }
              },
          )
          if (appFilter != null && appLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              AssistChip(
                  onClick = onClearAppFilter,
                  label = { Text(appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                  trailingIcon = {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.empty_clear_filters),
                        modifier = Modifier.size(16.dp),
                    )
                  },
              )
            }
          }
          OutlinedTextField(
              value = query,
              onValueChange = { query = it },
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
              singleLine = true,
              leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
              placeholder = { Text(stringResource(R.string.search_hint)) },
          )
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .horizontalScroll(rememberScrollState())
                      .padding(horizontal = 12.dp, vertical = 4.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilterChip(
                selected = filter == ScriptFilter.ALL,
                onClick = { filter = ScriptFilter.ALL },
                label = { Text(stringResource(R.string.filter_all)) },
            )
            FilterChip(
                selected = filter == ScriptFilter.ENABLED,
                onClick = { filter = ScriptFilter.ENABLED },
                label = { Text(stringResource(R.string.filter_enabled)) },
            )
            FilterChip(
                selected = filter == ScriptFilter.DISABLED,
                onClick = { filter = ScriptFilter.DISABLED },
                label = { Text(stringResource(R.string.filter_disabled)) },
            )
            if (activeUrl != null) {
              FilterChip(
                  selected = filter == ScriptFilter.THIS_PAGE,
                  onClick = { filter = ScriptFilter.THIS_PAGE },
                  label = { Text(stringResource(R.string.filter_this_page)) },
              )
            }
          }
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .horizontalScroll(rememberScrollState())
                      .padding(horizontal = 12.dp, vertical = 2.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilterChip(
                selected = sort == ScriptSort.ORDER,
                onClick = { sort = ScriptSort.ORDER },
                label = { Text(stringResource(R.string.sort_order)) },
            )
            FilterChip(
                selected = sort == ScriptSort.NAME,
                onClick = { sort = ScriptSort.NAME },
                label = { Text(stringResource(R.string.sort_name)) },
            )
            FilterChip(
                selected = sort == ScriptSort.UPDATED,
                onClick = { sort = ScriptSort.UPDATED },
                label = { Text(stringResource(R.string.sort_updated)) },
            )
          }
        }
      },
      floatingActionButton = {
        Box {
          FloatingActionButton(onClick = { showFabMenu = true }) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.fab_new))
          }
          DropdownMenu(expanded = showFabMenu, onDismissRequest = { showFabMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.fab_new)) },
                onClick = {
                  showFabMenu = false
                  onNew(newScriptTemplate())
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.fab_import)) },
                onClick = {
                  showFabMenu = false
                  importLauncher.launch(
                      arrayOf(
                          "text/javascript",
                          "application/javascript",
                          "text/plain",
                          "*/*",
                      ))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.fab_install_url)) },
                onClick = {
                  showFabMenu = false
                  showUrlDialog = true
                },
            )
          }
        }
      },
  ) { padding ->
    Box(modifier = Modifier.padding(padding).fillMaxSize()) {
      when {
        loading && scripts.isEmpty() -> SkeletonGrid()
        loadError && scripts.isEmpty() -> ErrorState(onRetry)
        scripts.isEmpty() ->
            EmptyState(
                title = stringResource(R.string.empty_no_scripts),
                hint = stringResource(R.string.empty_no_scripts_hint),
            )
        visible.isEmpty() ->
            EmptyState(
                title = stringResource(R.string.empty_no_match),
                hint = stringResource(R.string.empty_no_match_hint),
                action = stringResource(R.string.empty_clear_filters),
                onAction = {
                  query = ""
                  filter = ScriptFilter.ALL
                  if (appFilter != null) onClearAppFilter()
                },
            )
        else ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                contentPadding =
                    PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items(visible, key = { it.id }) { script ->
                ScriptCard(
                    modifier = Modifier.animateItem(),
                    script = script,
                    update = updates[script.id]?.takeIf { it.hasUpdate },
                    onClick = { onEdit(script) },
                    onToggle = { onToggle(script.id, it) },
                    onDelete = { deleteTarget = script },
                    onUpdate = { update -> pendingUpdate = script to update },
                )
              }
            }
      }
    }
  }

  deleteTarget?.let { target ->
    AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text(stringResource(R.string.delete_script_title)) },
        text = { Text(stringResource(R.string.delete_script_body, target.id)) },
        confirmButton = {
          TextButton(
              onClick = {
                deleteTarget = null
                onDelete(target)
              }) {
                Text(stringResource(R.string.action_delete))
              }
        },
        dismissButton = {
          TextButton(onClick = { deleteTarget = null }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
  }

  pendingUpdate?.let { (script, update) ->
    AlertDialog(
        onDismissRequest = { pendingUpdate = null },
        title = { Text(stringResource(R.string.update_apply_title)) },
        text = {
          Text(
              stringResource(
                  R.string.update_apply_body,
                  script.id.substringAfterLast(':'),
                  update.remoteVersion,
              ))
        },
        confirmButton = {
          TextButton(
              onClick = {
                pendingUpdate = null
                onApplyUpdate(script, update)
              }) {
                Text(stringResource(R.string.action_update))
              }
        },
        dismissButton = {
          TextButton(onClick = { pendingUpdate = null }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
  }

  if (showUrlDialog) {
    UrlInstallDialog(
        onDismiss = { showUrlDialog = false },
        onInstall = {
          showUrlDialog = false
          onImport(it)
        },
        onError = onError,
    )
  }
}

@Composable
private fun ScriptCard(
    modifier: Modifier,
    script: Script,
    update: UpdateInfo?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onUpdate: (UpdateInfo) -> Unit,
) {
  Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = script.id.substringAfterLast(':'),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = script.enabled, onCheckedChange = onToggle)
      }
      if (script.description.isNotBlank()) {
        Text(
            text = script.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
      Row(
          modifier = Modifier.padding(top = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (script.version.isNotBlank()) {
          Text(
              text = stringResource(R.string.install_version, script.version),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (script.author.isNotBlank()) {
          Text(
              text = script.author,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
        if (script.customMatch.isNotBlank() || script.customInclude.isNotBlank()) {
          Badge(stringResource(R.string.badge_custom_match))
        }
        Badge(
            if (script.apps.isEmpty()) stringResource(R.string.badge_all_apps)
            else stringResource(R.string.badge_apps, script.apps.size))
        if (script.customRunAt.isNotBlank()) {
          Badge(script.customRunAt.removePrefix("document-"))
        }
      }
      if (update != null) {
        AssistChip(
            onClick = { onUpdate(update) },
            label = {
              Text(
                  stringResource(
                      R.string.update_available,
                      update.currentVersion.ifBlank { "?" },
                      update.remoteVersion))
            },
            modifier = Modifier.padding(top = 6.dp),
        )
      }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        IconButton(onClick = onDelete) {
          Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
        }
      }
    }
  }
}

@Composable
private fun Badge(text: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.primary,
  )
}

@Composable
private fun SkeletonGrid() {
  val transition = rememberInfiniteTransition(label = "skeleton")
  val alpha by
      transition.animateFloat(
          initialValue = 0.3f,
          targetValue = 0.7f,
          animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
          label = "alpha",
      )
  val placeholder = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.15f)
  LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 340.dp),
      contentPadding = PaddingValues(12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxSize(),
  ) {
    items(6) {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
          Spacer(
              modifier =
                  Modifier.fillMaxWidth(0.55f)
                      .height(18.dp)
                      .background(placeholder, RoundedCornerShape(4.dp)))
          Spacer(modifier = Modifier.height(10.dp))
          Spacer(
              modifier =
                  Modifier.fillMaxWidth()
                      .height(12.dp)
                      .background(placeholder, RoundedCornerShape(4.dp)))
          Spacer(modifier = Modifier.height(6.dp))
          Spacer(
              modifier =
                  Modifier.fillMaxWidth(0.8f)
                      .height(12.dp)
                      .background(placeholder, RoundedCornerShape(4.dp)))
        }
      }
    }
  }
}

@Composable
private fun EmptyState(
    title: String,
    hint: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(
          hint,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
      )
      if (action != null && onAction != null) {
        TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
      }
    }
  }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp),
    ) {
      Text(stringResource(R.string.list_error_hint), style = MaterialTheme.typography.titleMedium)
      TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.action_retry))
      }
    }
  }
}

@Composable
private fun UrlInstallDialog(
    onDismiss: () -> Unit,
    onInstall: (Script) -> Unit,
    onError: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var url by remember { mutableStateOf("") }
  var working by remember { mutableStateOf(false) }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.install_from_url_title)) },
      text = {
        Column {
          OutlinedTextField(
              value = url,
              onValueChange = { url = it },
              singleLine = true,
              placeholder = { Text(stringResource(R.string.install_from_url_hint)) },
          )
          if (working) {
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 12.dp).size(20.dp),
                strokeWidth = 2.dp,
            )
          }
        }
      },
      confirmButton = {
        TextButton(
            enabled = url.isNotBlank() && !working,
            onClick = {
              working = true
              scope.launch {
                val text = withContext(Dispatchers.IO) { readScriptFromUrl(url) }
                working = false
                if (text == null) {
                  onError(context.getString(R.string.error_download))
                  return@launch
                }
                val parsed = parseScript(text, null, hydrateRequires = false)
                if (parsed == null) {
                  onError(context.getString(R.string.error_invalid_userscript))
                } else {
                  onInstall(parsed.copy(lastUpdated = System.currentTimeMillis()))
                }
              }
            }) {
              Text(stringResource(R.string.action_install))
            }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
      },
  )
}

/** Downloads a script with the same scheme whitelist and size cap as the install screen. */
private fun readScriptFromUrl(address: String): String? {
  val url = runCatching { URL(address) }.getOrNull() ?: return null
  if (url.protocol != "http" && url.protocol != "https") return null
  val connection =
      runCatching { url.openConnection() as HttpURLConnection }.getOrNull() ?: return null
  connection.connectTimeout = 10_000
  connection.readTimeout = 10_000
  connection.instanceFollowRedirects = true
  return runCatching {
        if (connection.responseCode !in 200..299) return null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        connection.inputStream.use { input ->
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_SCRIPT_BYTES) return null
            output.write(buffer, 0, read)
          }
        }
        output.toString("UTF-8")
      }
      .getOrNull()
}

private fun newScriptTemplate(): Script {
  val stamp = System.currentTimeMillis()
  val name = "New Script $stamp"
  val meta =
      """
      // ==UserScript==
      // @name         $name
      // @namespace    ChromeXt
      // @version      1.0
      // @description  A new UserScript
      // @match        https://example.com/*
      // @grant        none
      // ==/UserScript==
      """
          .trimIndent()
  return Script(
      id = "ChromeXt:$name",
      match = arrayOf("https://example.com/*"),
      grant = arrayOf("none"),
      exclude = emptyArray(),
      meta = meta,
      code = "\n",
      storage = null,
      noframes = false,
      lastUpdated = stamp,
      version = "1.0",
      description = "A new UserScript",
  )
}
