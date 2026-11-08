package org.matrix.chromext.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.parseScript

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    modifier: Modifier,
    script: Script,
    onCancel: () -> Unit,
    onSave: (Script) -> Unit,
) {
  var tab by remember { mutableIntStateOf(0) }
  var meta by remember { mutableStateOf(script.meta) }
  var code by remember { mutableStateOf(script.code) }
  var enabled by remember { mutableStateOf(script.enabled) }
  var customMatch by remember { mutableStateOf(script.customMatch) }
  var customInclude by remember { mutableStateOf(script.customInclude) }
  var customExclude by remember { mutableStateOf(script.customExclude) }
  var customRunAt by remember { mutableStateOf(script.customRunAt) }
  var updateUrl by remember { mutableStateOf(script.updateUrl) }
  var storage by remember { mutableStateOf(script.storage?.toString(2) ?: "") }
  var error by remember { mutableStateOf<String?>(null) }

  fun save() {
    val storageJson =
        if (storage.isBlank()) {
          null
        } else {
          runCatching { JSONObject(storage) }
              .getOrElse {
                error = "GM_storage is not valid JSON"
                return
              }
        }
    val parsed = parseScript(meta + code, storageJson?.toString(), hydrateRequires = false)
    if (parsed == null) {
      error = "The metadata block is invalid"
      return
    }
    onSave(
        parsed.copy(
            enabled = enabled,
            customMatch = customMatch,
            customInclude = customInclude,
            customExclude = customExclude,
            customRunAt = customRunAt,
            updateUrl = updateUrl,
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
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
              }
            },
            actions = { TextButton(onClick = { save() }) { Text("Save") } },
        )
      },
  ) { padding ->
    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
      TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Code") })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings") })
        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Values") })
      }
      when (tab) {
        0 ->
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedTextField(
                  value = meta,
                  onValueChange = { meta = it },
                  modifier = Modifier.fillMaxWidth().weight(1f),
                  label = { Text("Metadata") },
                  textStyle =
                      MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              )
              OutlinedTextField(
                  value = code,
                  onValueChange = { code = it },
                  modifier = Modifier.fillMaxWidth().weight(2f),
                  label = { Text("Code") },
                  textStyle =
                      MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              )
            }
        1 ->
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
              }
              Text("Run at", style = MaterialTheme.typography.labelLarge)
              Row(
                  modifier = Modifier.horizontalScroll(rememberScrollState()),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                listOf(
                        "" to "Default",
                        "document-start" to "start",
                        "document-end" to "end",
                        "document-idle" to "idle",
                    )
                    .forEach { (value, label) ->
                      FilterChip(
                          selected = customRunAt == value,
                          onClick = { customRunAt = value },
                          label = { Text(label) })
                    }
              }
              OutlinedTextField(
                  value = customMatch,
                  onValueChange = { customMatch = it },
                  label = { Text("Custom @match (one per line)") },
                  modifier = Modifier.fillMaxWidth(),
                  minLines = 3,
              )
              OutlinedTextField(
                  value = customInclude,
                  onValueChange = { customInclude = it },
                  label = { Text("Custom @include (one per line)") },
                  modifier = Modifier.fillMaxWidth(),
                  minLines = 2,
              )
              OutlinedTextField(
                  value = customExclude,
                  onValueChange = { customExclude = it },
                  label = { Text("Custom @exclude (one per line)") },
                  modifier = Modifier.fillMaxWidth(),
                  minLines = 2,
              )
              OutlinedTextField(
                  value = updateUrl,
                  onValueChange = { updateUrl = it },
                  label = { Text("Update URL") },
                  modifier = Modifier.fillMaxWidth(),
                  singleLine = true,
              )
            }
        else ->
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
              OutlinedTextField(
                  value = storage,
                  onValueChange = { storage = it },
                  modifier = Modifier.fillMaxSize(),
                  label = { Text("GM_storage JSON") },
                  textStyle =
                      MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              )
            }
      }
    }
  }

  error?.let { message ->
    AlertDialog(
        onDismissRequest = { error = null },
        title = { Text("Cannot save") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
    )
  }
}
