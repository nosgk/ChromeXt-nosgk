package org.matrix.chromext.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptListScreen(
    modifier: Modifier,
    scripts: List<Script>,
    activeUrl: String?,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (Script) -> Unit,
    onEdit: (Script) -> Unit,
    onNew: (Script) -> Unit,
    onError: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var query by remember { mutableStateOf("") }
  var filter by remember { mutableStateOf(ScriptFilter.ALL) }
  var sort by remember { mutableStateOf(ScriptSort.ORDER) }
  var showFabMenu by remember { mutableStateOf(false) }
  var showUrlDialog by remember { mutableStateOf(false) }
  var deleteTarget by remember { mutableStateOf<Script?>(null) }

  val importLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          scope.launch {
            val text =
                withContext(Dispatchers.IO) {
                  context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    it.readText()
                  }
                }
            if (text == null) {
              onError("Cannot read the selected file")
            } else {
              val parsed = parseScript(text, null, hydrateRequires = false)
              if (parsed == null) onError("Invalid UserScript")
              else onNew(parsed.copy(lastUpdated = System.currentTimeMillis()))
            }
          }
        }
      }

  val filtered =
      scripts
          .filter { script ->
            val matchesQuery =
                query.isBlank() ||
                    script.id.contains(query, true) ||
                    script.description.contains(query, true) ||
                    script.match.any { it.contains(query, true) } ||
                    script.code.contains(query, true)
            val matchesFilter =
                when (filter) {
                  ScriptFilter.ALL -> true
                  ScriptFilter.ENABLED -> script.enabled
                  ScriptFilter.DISABLED -> !script.enabled
                  ScriptFilter.THIS_PAGE -> activeUrl != null && matching(script, activeUrl)
                }
            matchesQuery && matchesFilter
          }
          .sortedWith(
              when (sort) {
                ScriptSort.ORDER -> compareBy({ it.sortOrder }, { it.id })
                ScriptSort.NAME -> compareBy { it.id.lowercase() }
                ScriptSort.UPDATED -> compareByDescending { it.lastUpdated }
              })

  val enabledCount = scripts.count { it.enabled }
  Scaffold(
      modifier = modifier,
      topBar = {
        Column {
          TopAppBar(title = { Text("ChromeXt ($enabledCount/${scripts.size} enabled)") })
          OutlinedTextField(
              value = query,
              onValueChange = { query = it },
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
              placeholder = { Text("Search name, match or code") },
              leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
              singleLine = true,
          )
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .horizontalScroll(rememberScrollState())
                      .padding(horizontal = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilterChip(
                selected = filter == ScriptFilter.ALL,
                onClick = { filter = ScriptFilter.ALL },
                label = { Text("All") })
            FilterChip(
                selected = filter == ScriptFilter.ENABLED,
                onClick = { filter = ScriptFilter.ENABLED },
                label = { Text("Enabled") })
            FilterChip(
                selected = filter == ScriptFilter.DISABLED,
                onClick = { filter = ScriptFilter.DISABLED },
                label = { Text("Disabled") })
            if (activeUrl != null) {
              FilterChip(
                  selected = filter == ScriptFilter.THIS_PAGE,
                  onClick = { filter = ScriptFilter.THIS_PAGE },
                  label = { Text("This page") })
            }
          }
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .horizontalScroll(rememberScrollState())
                      .padding(horizontal = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilterChip(
                selected = sort == ScriptSort.ORDER,
                onClick = { sort = ScriptSort.ORDER },
                label = { Text("Order") })
            FilterChip(
                selected = sort == ScriptSort.NAME,
                onClick = { sort = ScriptSort.NAME },
                label = { Text("Name") })
            FilterChip(
                selected = sort == ScriptSort.UPDATED,
                onClick = { sort = ScriptSort.UPDATED },
                label = { Text("Updated") })
          }
        }
      },
      floatingActionButton = {
        Box {
          FloatingActionButton(onClick = { showFabMenu = true }) {
            Icon(Icons.Default.Add, contentDescription = "Add")
          }
          DropdownMenu(expanded = showFabMenu, onDismissRequest = { showFabMenu = false }) {
            DropdownMenuItem(
                text = { Text("New script") },
                onClick = {
                  showFabMenu = false
                  onNew(newScriptTemplate())
                })
            DropdownMenuItem(
                text = { Text("Import .user.js") },
                onClick = {
                  showFabMenu = false
                  importLauncher.launch(
                      arrayOf("text/javascript", "application/javascript", "text/plain", "*/*"))
                })
            DropdownMenuItem(
                text = { Text("Install from URL") },
                onClick = {
                  showFabMenu = false
                  showUrlDialog = true
                })
          }
        }
      },
  ) { padding ->
    LazyColumn(
        modifier = Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(filtered, key = { it.id }) { script ->
        ScriptCard(
            script = script,
            onToggle = onToggle,
            onEdit = onEdit,
            onDelete = { deleteTarget = script },
        )
      }
    }
  }

  if (showUrlDialog) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { showUrlDialog = false },
        title = { Text("Install from URL") },
        text = {
          OutlinedTextField(
              value = url,
              onValueChange = { url = it },
              label = { Text("https://example.com/script.user.js") },
              singleLine = true,
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                showUrlDialog = false
                scope.launch {
                  val text =
                      withContext(Dispatchers.IO) {
                        runCatching {
                          URL(url).openStream().bufferedReader().use { it.readText() }
                        }
                            .getOrNull()
                      }
                  if (text == null) {
                    onError("Cannot download the script")
                  } else {
                    val parsed = parseScript(text, null, hydrateRequires = false)
                    if (parsed == null) onError("Invalid UserScript")
                    else onNew(parsed.copy(lastUpdated = System.currentTimeMillis()))
                  }
                }
              }) { Text("Install") }
        },
        dismissButton = {
          TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") }
        },
    )
  }

  deleteTarget?.let { target ->
    AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text("Delete script?") },
        text = { Text(target.id) },
        confirmButton = {
          TextButton(
              onClick = {
                deleteTarget = null
                onDelete(target)
              }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun ScriptCard(
    script: Script,
    onToggle: (String, Boolean) -> Unit,
    onEdit: (Script) -> Unit,
    onDelete: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth().clickable { onEdit(script) }) {
    Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
            script.id.substringAfterLast(':'),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (script.description.isNotBlank()) {
          Text(
              script.description,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (script.version.isNotBlank()) {
            Text("v${script.version}", style = MaterialTheme.typography.labelSmall)
          }
          if (script.author.isNotBlank()) {
            Text(script.author, style = MaterialTheme.typography.labelSmall)
          }
          if (script.customMatch.isNotBlank() || script.customInclude.isNotBlank()) {
            Text("custom match", style = MaterialTheme.typography.labelSmall)
          }
          if (script.customRunAt.isNotBlank()) {
            Text(script.customRunAt, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
      Switch(checked = script.enabled, onCheckedChange = { onToggle(script.id, it) })
      IconButton(onClick = onDelete) {
        Icon(Icons.Default.Delete, contentDescription = "Delete")
      }
    }
  }
}

private fun newScriptTemplate(): Script {
  val meta =
      """
      // ==UserScript==
      // @name New Script
      // @namespace ChromeXt
      // @version 1.0
      // @description 
      // @match https://example.com/*
      // @grant none
      // ==/UserScript==
      """
          .trimIndent() + "\n"
  return Script(
      id = "ChromeXt:New Script",
      match = arrayOf("https://example.com/*"),
      grant = arrayOf("none"),
      exclude = emptyArray(),
      meta = meta,
      code = "\n",
      storage = null,
      noframes = false,
      lastUpdated = System.currentTimeMillis(),
      version = "1.0",
  )
}
