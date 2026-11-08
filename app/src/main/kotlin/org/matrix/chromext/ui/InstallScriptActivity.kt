package org.matrix.chromext.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.parseScript

class InstallScriptActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val uri = intent?.data
    val provided = intent?.getStringExtra(EXTRA_SCRIPT)
    setContent {
      ChromeXtTheme {
        Surface {
          InstallScriptScreen(
              uri = uri,
              provided = provided,
              onCancel = { finish() },
              onInstalled = { finish() },
              onEdit = { script ->
                val repository = ScriptRepository(this)
                repository.save(script)
                repository.close()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_EDIT_ID, script.id))
                finish()
              },
          )
        }
      }
    }
  }

  companion object {
    const val ACTION_INSTALL_SCRIPT = "org.matrix.chromext.ACTION_INSTALL_SCRIPT"
    const val EXTRA_SCRIPT = "script"
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallScriptScreen(
    uri: Uri?,
    provided: String?,
    onCancel: () -> Unit,
    onInstalled: () -> Unit,
    onEdit: (Script) -> Unit,
) {
  val context = LocalContext.current
  val repository = remember { ScriptRepository(context) }
  DisposableEffect(Unit) { onDispose { repository.close() } }

  var text by remember { mutableStateOf(provided) }
  var script by remember { mutableStateOf<Script?>(null) }
  var existingVersion by remember { mutableStateOf<String?>(null) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(uri, provided) {
    if (text == null && uri != null) {
      text = withContext(Dispatchers.IO) { readText(context, uri) }
    }
    if (text == null) error = "Cannot read the script"
  }
  LaunchedEffect(text) {
    val source = text ?: return@LaunchedEffect
    val parsed = parseScript(source, null, hydrateRequires = false)
    if (parsed == null) {
      error = "Invalid UserScript"
    } else {
      script = parsed
      existingVersion =
          withContext(Dispatchers.IO) { repository.load().find { it.id == parsed.id }?.version }
    }
  }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Install UserScript") },
            navigationIcon = {
              IconButton(onClick = onCancel) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Cancel")
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
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      val current = script
      if (current != null) {
        Text(current.id.substringAfterLast(':'), style = MaterialTheme.typography.headlineSmall)
        if (current.version.isNotBlank()) {
          val previous = existingVersion
          Text(
              if (previous.isNullOrBlank()) "v${current.version}"
              else "v$previous -> v${current.version}")
        }
        if (current.author.isNotBlank()) {
          Text("Author: ${current.author}", style = MaterialTheme.typography.bodySmall)
        }
        if (current.description.isNotBlank()) Text(current.description)
        Text("Target sites", style = MaterialTheme.typography.titleSmall)
        current.match.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Permissions", style = MaterialTheme.typography.titleSmall)
        current.grant.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Code preview", style = MaterialTheme.typography.titleSmall)
        Text(
            current.code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 20,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
          OutlinedButton(
              onClick = { onEdit(current.copy(lastUpdated = System.currentTimeMillis())) },
              modifier = Modifier.weight(1f),
          ) {
            Text("Edit")
          }
          Button(
              onClick = {
                repository.save(current.copy(lastUpdated = System.currentTimeMillis()))
                onInstalled()
              },
              modifier = Modifier.weight(1f),
          ) {
            Text("Install")
          }
        }
      } else if (error == null) {
        Text("Reading the script...")
      }
    }
  }
}

private const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024

private fun readText(context: Context, uri: Uri): String? =
    runCatching {
          val stream =
              when (uri.scheme) {
                "content", "file" -> context.contentResolver.openInputStream(uri)
                "http", "https" -> URL(uri.toString()).openStream()
                else -> null
              } ?: return@runCatching null
          stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              total += read
              if (total > MAX_SCRIPT_BYTES) return@runCatching null
              output.write(buffer, 0, read)
            }
            output.toString("UTF-8")
          }
        }
        .getOrNull()
