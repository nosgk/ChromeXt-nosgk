package org.matrix.chromext.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.browser.BrowserCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier,
    repository: ScriptRepository,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val prefs = remember { context.getSharedPreferences("ChromeXt", Context.MODE_PRIVATE) }
  var keepStorage by remember { mutableStateOf(prefs.getBoolean("keep_storage", true)) }
  var status by remember { mutableStateOf("Checking the central database...") }
  var message by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    status =
        withContext(Dispatchers.IO) {
          val available =
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
          if (available) "Central database reachable"
          else "Central database unavailable; browser processes use the local fallback"
        }
  }

  val exportLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
          uri ->
        if (uri != null) {
          scope.launch {
            val ok = withContext(Dispatchers.IO) { Backup.export(context, repository.records(), uri) }
            message = if (ok) "Backup exported" else "Export failed"
          }
        }
      }

  val importLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          scope.launch {
            val count = withContext(Dispatchers.IO) { Backup.import(context, uri) }
            message = if (count >= 0) "Imported $count scripts" else "Import failed"
            onChanged()
          }
        }
      }

  Scaffold(
      modifier = modifier,
      topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
              }
            },
        )
      },
  ) { padding ->
    Column(
        modifier =
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Card {
        Column(modifier = Modifier.padding(12.dp)) {
          Text("Database", style = MaterialTheme.typography.titleMedium)
          Text(status, style = MaterialTheme.typography.bodySmall)
        }
      }
      Card {
        Column(modifier = Modifier.padding(12.dp)) {
          Text("Browsers", style = MaterialTheme.typography.titleMedium)
          BrowserCatalog.supportedPackages.take(10).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall)
          }
          if (BrowserCatalog.supportedPackages.size > 10) {
            Text(
                "and ${BrowserCatalog.supportedPackages.size - 10} more",
                style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Persistent GM storage")
          Text(
              "Keep values when a script is updated",
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
        Text("Export backup (.zip)")
      }
      Button(
          onClick = {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
          },
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Import backup (.zip)")
      }
      OutlinedButton(
          onClick = {
            repository.delete(repository.records().map { it.id }.toTypedArray())
            onChanged()
            message = "All scripts deleted"
          },
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Delete all scripts")
      }
      Text(
          "ChromeXt ${BuildConfig.VERSION_NAME}",
          style = MaterialTheme.typography.bodySmall,
      )
      message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
  }
}
