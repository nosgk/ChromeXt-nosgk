package org.matrix.chromext.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.matrix.chromext.R
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.parseScript

private val MONOSPACE = TextStyle(fontFamily = FontFamily.Monospace)

private val RUN_AT_VALUES =
    listOf(
        "" to R.string.run_at_default,
        "document-start" to R.string.run_at_start,
        "document-end" to R.string.run_at_end,
        "document-idle" to R.string.run_at_idle,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    modifier: Modifier,
    script: Script,
    knownApps: List<AppEntry>,
    scopeAvailable: Boolean,
    onCancel: () -> Unit,
    onSave: (Script) -> Unit,
) {
  val scope = rememberCoroutineScope()

  // Keyed on the script id so switching between scripts never reuses stale edits.
  var tab by remember(script.id) { mutableIntStateOf(0) }
  var meta by remember(script.id) { mutableStateOf(script.meta) }
  var code by remember(script.id) { mutableStateOf(script.code) }
  var enabled by remember(script.id) { mutableStateOf(script.enabled) }
  var customMatch by remember(script.id) { mutableStateOf(script.customMatch) }
  var customInclude by remember(script.id) { mutableStateOf(script.customInclude) }
  var customExclude by remember(script.id) { mutableStateOf(script.customExclude) }
  var customRunAt by remember(script.id) { mutableStateOf(script.customRunAt) }
  var updateUrl by remember(script.id) { mutableStateOf(script.updateUrl) }
  var apps by remember(script.id) { mutableStateOf(script.apps) }
  var storage by remember(script.id) {
    mutableStateOf(script.storage?.toString(2) ?: "")
  }
  var error by remember(script.id) { mutableStateOf<String?>(null) }
  var showAppsDialog by remember(script.id) { mutableStateOf(false) }
  var showCodeEditor by remember(script.id) { mutableStateOf(false) }
  var appQuery by remember(script.id) { mutableStateOf("") }
  var checkingUpdate by remember(script.id) { mutableStateOf(false) }
  var updateInfo by remember(script.id) { mutableStateOf<UpdateInfo?>(null) }
  var pendingRemote by remember(script.id) { mutableStateOf<Script?>(null) }

  fun save() {
    val storageJson =
        if (storage.isBlank()) {
          null
        } else {
          runCatching { JSONObject(storage) }.getOrNull()
              ?: run {
                error = "storage"
                return
              }
        }
    val parsed =
        parseScript(meta + code, storageJson?.toString(), hydrateRequires = false)
            ?: run {
              error = "metadata"
              return
            }
    if (parsed.version.isBlank()) {
      error = "version"
      return
    }
    if (customRunAt.isNotBlank() && RUN_AT_VALUES.none { it.first == customRunAt }) {
      error = "run_at"
      return
    }
    if (updateUrl.isNotBlank() &&
        !updateUrl.trim().startsWith("http://") &&
        !updateUrl.trim().startsWith("https://")) {
      error = "update_url"
      return
    }
    onSave(
        parsed.copy(
            enabled = enabled,
            apps = apps,
            sortOrder = script.sortOrder,
            customMatch = customMatch,
            customInclude = customInclude,
            customExclude = customExclude,
            customRunAt = customRunAt,
            updateUrl = updateUrl.trim(),
            iconUrl = script.iconUrl,
            lastUpdated = System.currentTimeMillis(),
            lib = script.lib,
        ))
  }

  Scaffold(
      modifier = modifier,
      topBar = {
        TopAppBar(
            title = { Text(script.id.substringAfterLast(':')) },
            navigationIcon = {
              IconButton(onClick = onCancel) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
              }
            },
            actions = {
              TextButton(onClick = { save() }) { Text(stringResource(R.string.action_save)) }
            },
        )
      },
  ) { padding ->
    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
      TabRow(selectedTabIndex = tab) {
        Tab(
            selected = tab == 0,
            onClick = { tab = 0 },
            text = { Text(stringResource(R.string.editor_tab_code)) },
        )
        Tab(
            selected = tab == 1,
            onClick = { tab = 1 },
            text = { Text(stringResource(R.string.editor_tab_settings)) },
        )
        Tab(
            selected = tab == 2,
            onClick = { tab = 2 },
            text = { Text(stringResource(R.string.editor_tab_values)) },
        )
      }
      when (tab) {
        0 ->
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
              OutlinedTextField(
                  value = meta,
                  onValueChange = { meta = it },
                  label = { Text(stringResource(R.string.editor_metadata)) },
                  textStyle = MONOSPACE,
                  modifier = Modifier.fillMaxWidth().weight(1f),
              )
              Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Text(
                      stringResource(R.string.editor_code),
                      style = MaterialTheme.typography.titleSmall,
                  )
                  Text(
                      stringResource(
                          R.string.editor_code_summary,
                          code.count { it == '\n' } + 1,
                          code.length),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                      stringResource(R.string.editor_code_hint),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.padding(top = 4.dp),
                  )
                  Button(
                      onClick = { showCodeEditor = true },
                      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                  ) {
                    Text(stringResource(R.string.editor_open_code))
                  }
                }
              }
            }
        1 ->
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.editor_enabled),
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = { enabled = it })
              }
              Text(
                  stringResource(R.string.editor_run_at),
                  style = MaterialTheme.typography.titleSmall,
              )
              Row(
                  modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                RUN_AT_VALUES.forEach { (value, label) ->
                  FilterChip(
                      selected = customRunAt == value,
                      onClick = { customRunAt = value },
                      label = { Text(stringResource(label)) },
                  )
                }
              }
              OutlinedTextField(
                  value = customMatch,
                  onValueChange = { customMatch = it },
                  label = { Text(stringResource(R.string.editor_custom_match)) },
                  textStyle = MONOSPACE,
                  modifier = Modifier.fillMaxWidth(),
              )
              OutlinedTextField(
                  value = customInclude,
                  onValueChange = { customInclude = it },
                  label = { Text(stringResource(R.string.editor_custom_include)) },
                  textStyle = MONOSPACE,
                  modifier = Modifier.fillMaxWidth(),
              )
              OutlinedTextField(
                  value = customExclude,
                  onValueChange = { customExclude = it },
                  label = { Text(stringResource(R.string.editor_custom_exclude)) },
                  textStyle = MONOSPACE,
                  modifier = Modifier.fillMaxWidth(),
              )
              OutlinedTextField(
                  value = updateUrl,
                  onValueChange = { updateUrl = it },
                  label = { Text(stringResource(R.string.editor_update_url)) },
                  textStyle = MONOSPACE,
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth(),
              )
              Text(
                  stringResource(R.string.editor_apps),
                  style = MaterialTheme.typography.titleSmall,
              )
              Text(
                  if (apps.isEmpty()) stringResource(R.string.editor_apps_all)
                  else apps.joinToString(", ") { packageName ->
                    knownApps.firstOrNull { it.packageName == packageName }?.label ?: packageName
                  },
                  style = MaterialTheme.typography.bodyMedium,
              )
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                      appQuery = ""
                      showAppsDialog = true
                    }) {
                  Text(stringResource(R.string.editor_apps_select))
                }
                TextButton(onClick = { apps = emptyList() }) {
                  Text(stringResource(R.string.editor_apps_all))
                }
              }
              Text(
                  stringResource(
                      if (scopeAvailable) R.string.editor_apps_hint
                      else R.string.editor_apps_scope_unavailable),
                  style = MaterialTheme.typography.bodySmall,
                  color =
                      if (scopeAvailable) MaterialTheme.colorScheme.onSurfaceVariant
                      else MaterialTheme.colorScheme.error,
              )
              TextButton(
                  enabled = !checkingUpdate,
                  onClick = {
                    checkingUpdate = true
                    updateInfo = null
                    scope.launch {
                      updateInfo = UpdateChecker.check(script.copy(updateUrl = updateUrl.trim()))
                      checkingUpdate = false
                    }
                  }) {
                    Text(stringResource(R.string.editor_check_update))
                  }
              updateInfo?.let { info ->
                when {
                  info.error != null -> Text(updateErrorText(info.error))
                  info.hasUpdate ->
                      AssistChip(
                          onClick = { pendingRemote = info.remote },
                          label = {
                            Text(
                                stringResource(
                                    R.string.update_available,
                                    info.currentVersion.ifBlank { "?" },
                                    info.remoteVersion))
                          },
                      )
                  else -> Text(stringResource(R.string.update_none))
                }
              }
            }
        else ->
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
              OutlinedTextField(
                  value = storage,
                  onValueChange = { storage = it },
                  label = { Text(stringResource(R.string.editor_storage)) },
                  textStyle = MONOSPACE,
                  modifier = Modifier.fillMaxSize(),
              )
            }
      }
    }
  }

  if (showAppsDialog) {
    val needle = appQuery.trim().lowercase()
    val candidates =
        (
                apps
                    .filter { packageName ->
                      knownApps.none { it.packageName == packageName }
                    }
                    .map { AppEntry(it, it, "", null, false, false, false) } + knownApps)
            .distinctBy { it.packageName }
            .filter {
              needle.isEmpty() ||
                  it.label.lowercase().contains(needle) ||
                  it.packageName.lowercase().contains(needle)
            }
    AlertDialog(
        onDismissRequest = { showAppsDialog = false },
        title = { Text(stringResource(R.string.editor_apps_select)) },
        text = {
          Column {
            OutlinedTextField(
                value = appQuery,
                onValueChange = { appQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.editor_apps_search)) },
            )
            if (!scopeAvailable) {
              Text(
                  stringResource(R.string.editor_apps_scope_unavailable),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.error,
                  modifier = Modifier.padding(top = 4.dp),
              )
            }
            Column(
                modifier =
                    Modifier.heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp),
            ) {
              if (candidates.isEmpty()) {
                Text(
                    stringResource(R.string.editor_apps_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              candidates.forEach { app ->
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable {
                              apps =
                                  if (apps.contains(app.packageName)) {
                                    apps - app.packageName
                                  } else {
                                    apps + app.packageName
                                  }
                            }
                            .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  Checkbox(
                      checked = apps.contains(app.packageName),
                      onCheckedChange = {
                        apps =
                            if (apps.contains(app.packageName)) apps - app.packageName
                            else apps + app.packageName
                      },
                  )
                  Column {
                    Text(app.label)
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { showAppsDialog = false }) {
            Text(stringResource(R.string.action_ok))
          }
        },
        dismissButton = {
          TextButton(
              onClick = {
                apps = emptyList()
                showAppsDialog = false
              }) {
                Text(stringResource(R.string.editor_apps_all))
              }
        },
    )
  }

  pendingRemote?.let { remote ->
    AlertDialog(
        onDismissRequest = { pendingRemote = null },
        title = { Text(stringResource(R.string.update_apply_title)) },
        text = {
          Text(
              stringResource(
                  R.string.update_apply_body,
                  script.id.substringAfterLast(':'),
                  remote.version,
              ))
        },
        confirmButton = {
          TextButton(
              onClick = {
                meta = remote.meta
                code = remote.code
                if (updateUrl.isBlank()) updateUrl = remote.updateUrl
                pendingRemote = null
                updateInfo = null
              }) {
                Text(stringResource(R.string.action_update))
              }
        },
        dismissButton = {
          TextButton(onClick = { pendingRemote = null }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
  }

  if (showCodeEditor) {
    CodeEditorDialog(
        initial = code,
        onDismiss = { showCodeEditor = false },
        onApply = {
          code = it
          showCodeEditor = false
        },
    )
  }

  error?.let { reason ->
    val message =
        when (reason) {
          "storage" -> stringResource(R.string.editor_error_storage)
          "metadata" -> stringResource(R.string.editor_error_metadata)
          "version" -> stringResource(R.string.editor_error_version)
          "run_at" -> stringResource(R.string.editor_error_run_at)
          "update_url" -> stringResource(R.string.editor_error_update_url)
          else -> stringResource(R.string.editor_error_metadata)
        }
    AlertDialog(
        onDismissRequest = { error = null },
        title = { Text(stringResource(R.string.error_title)) },
        text = { Text(message) },
        confirmButton = {
          TextButton(onClick = { error = null }) { Text(stringResource(R.string.action_ok)) }
        },
    )
  }
}

@Composable
private fun updateErrorText(error: UpdateError): String =
    when (error) {
      UpdateError.NO_URL -> stringResource(R.string.update_error_no_url)
      UpdateError.BAD_URL -> stringResource(R.string.update_error_bad_url)
      UpdateError.NETWORK -> stringResource(R.string.update_error_network)
      UpdateError.HTTP -> stringResource(R.string.update_error_http)
      UpdateError.TOO_LARGE -> stringResource(R.string.update_error_too_large)
      UpdateError.INVALID_SCRIPT -> stringResource(R.string.update_error_invalid)
      UpdateError.ID_MISMATCH -> stringResource(R.string.update_error_id_mismatch)
    }
