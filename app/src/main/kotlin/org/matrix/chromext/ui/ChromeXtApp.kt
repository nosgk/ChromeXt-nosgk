package org.matrix.chromext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.chromext.script.Script

sealed interface Screen {
  data object List : Screen
  data object Settings : Screen
  data class Edit(val script: Script) : Screen
}

@Composable
fun ChromeXtApp(activeUrl: String?, editId: String?) {
  val context = LocalContext.current
  val repository = remember { ScriptRepository(context) }
  DisposableEffect(Unit) { onDispose { repository.close() } }

  var scripts by remember { mutableStateOf<List<Script>>(emptyList()) }
  var screen by remember { mutableStateOf<Screen>(Screen.List) }
  var reload by remember { mutableIntStateOf(0) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(reload) {
    scripts = withContext(Dispatchers.IO) { repository.load() }
  }
  LaunchedEffect(editId) {
    if (editId != null) {
      val script = withContext(Dispatchers.IO) { repository.load().find { it.id == editId } }
      if (script != null) screen = Screen.Edit(script)
    }
  }

  BackHandler(enabled = screen != Screen.List) { screen = Screen.List }

  Scaffold(
      bottomBar = {
        if (screen is Screen.List || screen is Screen.Settings) {
          NavigationBar {
            NavigationBarItem(
                selected = screen is Screen.List,
                onClick = { screen = Screen.List },
                icon = { Icon(Icons.Default.List, contentDescription = "Scripts") },
                label = { Text("Scripts") },
            )
            NavigationBarItem(
                selected = screen is Screen.Settings,
                onClick = { screen = Screen.Settings },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label = { Text("Settings") },
            )
          }
        }
      },
  ) { padding ->
    when (val current = screen) {
      Screen.List ->
          ScriptListScreen(
              modifier = Modifier.padding(padding),
              scripts = scripts,
              activeUrl = activeUrl,
              onToggle = { id, enabled ->
                repository.setEnabled(id, enabled)
                scripts = scripts.map { if (it.id == id) it.copy(enabled = enabled) else it }
              },
              onDelete = { script ->
                repository.delete(arrayOf(script.id))
                scripts = scripts.filterNot { it.id == script.id }
              },
              onEdit = { screen = Screen.Edit(it) },
              onNew = { script ->
                repository.save(script)
                scripts = scripts.filterNot { it.id == script.id } + script
                screen = Screen.Edit(script)
              },
              onError = { error = it },
          )
      is Screen.Edit ->
          ScriptEditorScreen(
              modifier = Modifier.padding(padding),
              script = current.script,
              onCancel = { screen = Screen.List },
              onSave = { updated ->
                if (updated.id != current.script.id) {
                  repository.delete(arrayOf(current.script.id))
                }
                repository.save(updated)
                scripts =
                    scripts.filterNot { it.id == current.script.id || it.id == updated.id } +
                        updated
                screen = Screen.List
              },
          )
      Screen.Settings ->
          SettingsScreen(
              modifier = Modifier.padding(padding),
              repository = repository,
              onBack = { screen = Screen.List },
              onChanged = { reload += 1 },
          )
    }
  }

  error?.let { message ->
    AlertDialog(
        onDismissRequest = { error = null },
        title = { Text("Error") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
    )
  }
}
