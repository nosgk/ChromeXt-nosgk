package org.matrix.chromext.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.R
import org.matrix.chromext.browser.BrowserCatalog

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
  val prefs = remember { context.getSharedPreferences("ChromeXt", Context.MODE_PRIVATE) }
  var keepStorage by remember { mutableStateOf(prefs.getBoolean("keep_storage", true)) }
  var status by remember { mutableStateOf<Boolean?>(null) }
  var confirmDeleteAll by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    status =
        withContext(Dispatchers.IO) {
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
        }
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
            if (count >= 0) onChanged()
          }
        }
      }

  Scaffold(
      modifier = modifier,
      topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
  ) { padding ->
    Column(
        modifier =
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Card {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(stringResource(R.string.settings_database), style = MaterialTheme.typography.titleMedium)
          Text(
              when (status) {
                null -> stringResource(R.string.settings_db_checking)
                true -> stringResource(R.string.settings_db_available)
                false -> stringResource(R.string.settings_db_unavailable)
              },
              style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      Card {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
              stringResource(R.string.settings_appearance),
              style = MaterialTheme.typography.titleMedium,
          )
          Text(
              stringResource(R.string.settings_theme),
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(top = 6.dp),
          )
          Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            ThemeMode.entries.forEach { mode ->
              FilterChip(
                  selected = themeMode == mode,
                  onClick = { onThemeModeChange(mode) },
                  label = {
                    Text(
                        stringResource(
                            when (mode) {
                              ThemeMode.SYSTEM -> R.string.theme_system
                              ThemeMode.LIGHT -> R.string.theme_light
                              ThemeMode.DARK -> R.string.theme_dark
                            }))
                  },
              )
            }
          }
        }
      }
      Card {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
              stringResource(R.string.settings_browsers),
              style = MaterialTheme.typography.titleMedium,
          )
          BrowserCatalog.supportedPackages.take(10).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall)
          }
          if (BrowserCatalog.supportedPackages.size > 10) {
            Text(
                stringResource(
                    R.string.settings_browsers_more,
                    BrowserCatalog.supportedPackages.size - 10),
                style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.settings_keep_storage))
          Text(
              stringResource(R.string.settings_keep_storage_hint),
              style = MaterialTheme.typography.bodySmall,
          )
        }
        Switch(
            checked = keepStorage,
            onCheckedChange = {
              keepStorage = it
              prefs.edit().putBoolean("keep_storage", it).apply()
            },
        )
      }
      Button(
          onClick = { exportLauncher.launch("ChromeXt-backup.zip") },
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.settings_export))
      }
      Button(
          onClick = {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
          },
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.settings_import))
      }
      OutlinedButton(
          onClick = { confirmDeleteAll = true },
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.settings_delete_all))
      }
      Text(
          stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }

  if (confirmDeleteAll) {
    AlertDialog(
        onDismissRequest = { confirmDeleteAll = false },
        title = { Text(stringResource(R.string.settings_delete_all_title)) },
        text = { Text(stringResource(R.string.settings_delete_all_body)) },
        confirmButton = {
          TextButton(
              onClick = {
                confirmDeleteAll = false
                scope.launch {
                  withContext(Dispatchers.IO) {
                    repository.delete(repository.records().map { it.id }.toTypedArray())
                  }
                  onChanged()
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
}
