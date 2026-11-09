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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.chromext.R
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.parseScript

class InstallScriptActivity : ComponentActivity() {

  private var stagedToken: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val uri = intent?.data
    val provided = intent?.getStringExtra(EXTRA_SCRIPT)
    val token = intent?.getStringExtra(EXTRA_TOKEN)
    val sourcePackage = intent?.getStringExtra(EXTRA_PACKAGE)
    stagedToken = token
    setContent {
      ChromeXtTheme(ThemePrefs.load(this)) {
        Surface {
          InstallScriptScreen(
              uri = uri,
              provided = provided,
              token = token,
              sourcePackage = sourcePackage,
              onCancel = {
                discardStaged(this, token)
                finish()
              },
              onInstalled = {
                discardStaged(this, token)
                finish()
              },
              onEdit = { script ->
                discardStaged(this, token)
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

  override fun onDestroy() {
    super.onDestroy()
    // A back press finishes without going through the buttons; drop the staged payload so it does
    // not linger (the provider also cleans files older than a day).
    if (isFinishing) discardStaged(this, stagedToken)
  }

  companion object {
    const val ACTION_INSTALL_SCRIPT = "org.matrix.chromext.ACTION_INSTALL_SCRIPT"
    const val EXTRA_SCRIPT = "script"
    const val EXTRA_PACKAGE = "source_package"
    /** Token for a payload staged by the ContentProvider (used for large scripts). */
    const val EXTRA_TOKEN = "install_token"
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallScriptScreen(
    uri: Uri?,
    provided: String?,
    token: String?,
    sourcePackage: String?,
    onCancel: () -> Unit,
    onInstalled: () -> Unit,
    onEdit: (Script) -> Unit,
) {
  val context = LocalContext.current
  val repository = remember { ScriptRepository(context) }
  DisposableEffect(Unit) { onDispose { repository.close() } }
  val scope = rememberCoroutineScope()

  var text by remember { mutableStateOf(provided) }
  var script by remember { mutableStateOf<Script?>(null) }
  var existingVersion by remember { mutableStateOf<String?>(null) }
  var existingApps by remember { mutableStateOf<List<String>>(emptyList()) }
  var existingFound by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<Int?>(null) }

  // A fresh install from a hooked app is scoped to that app. Re-installing keeps the chosen scope
  // and adds the current app; a script that was global stays global.
  val scopedApps =
      when {
        !existingFound -> listOfNotNull(sourcePackage?.takeIf { it.isNotBlank() })
        existingApps.isEmpty() -> emptyList()
        sourcePackage != null && sourcePackage !in existingApps -> existingApps + sourcePackage
        else -> existingApps
      }

  LaunchedEffect(uri, provided, token) {
    if (text == null && token != null) {
      text = withContext(Dispatchers.IO) { readStaged(context, token) }
    }
    if (text == null && uri != null) {
      text = withContext(Dispatchers.IO) { readText(context, uri) }
    }
    if (text == null) error = R.string.install_error_read
  }
  LaunchedEffect(text) {
    val source = text ?: return@LaunchedEffect
    val parsed = parseScript(source, null, hydrateRequires = false)
    if (parsed == null) {
      error = R.string.install_error_invalid
    } else {
      script = parsed
      val existing = withContext(Dispatchers.IO) { repository.load().find { it.id == parsed.id } }
      existingFound = existing != null
      existingVersion = existing?.version
      existingApps = existing?.apps ?: emptyList()
    }
  }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.install_title)) },
            navigationIcon = {
              IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.action_cancel))
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
      error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
      val current = script
      if (current != null) {
        Text(current.id.substringAfterLast(':'), style = MaterialTheme.typography.headlineSmall)
        if (current.version.isNotBlank()) {
          val previous = existingVersion
          Text(
              if (previous.isNullOrBlank()) stringResource(R.string.install_version, current.version)
              else stringResource(R.string.install_version_diff, previous, current.version))
        }
        if (current.author.isNotBlank()) {
          Text(
              stringResource(R.string.install_author, current.author),
              style = MaterialTheme.typography.bodySmall,
          )
        }
        if (current.description.isNotBlank()) Text(current.description)
        if (existingFound) {
          Text(
              stringResource(R.string.install_already_saved),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
          )
        }
        Text(
            if (scopedApps.isEmpty()) stringResource(R.string.install_scope_all)
            else stringResource(R.string.install_scope, scopedApps.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.install_target_sites), style = MaterialTheme.typography.titleSmall)
        current.match.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(R.string.install_permissions), style = MaterialTheme.typography.titleSmall)
        current.grant.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            stringResource(R.string.install_code_preview),
            style = MaterialTheme.typography.titleSmall,
        )
        // Laying out an 800 KB script just to show 20 lines freezes the page; show a bounded
        // prefix instead.
        val preview =
            remember(current.id, current.code) {
              if (current.code.length <= MAX_PREVIEW_CHARS) current.code
              else current.code.substring(0, MAX_PREVIEW_CHARS)
            }
        Text(
            preview,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 20,
            overflow = TextOverflow.Ellipsis,
        )
        if (current.code.length > MAX_PREVIEW_CHARS) {
          Text(
              stringResource(R.string.install_code_truncated, current.code.length),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_cancel))
          }
          OutlinedButton(
              onClick = {
                val updated =
                    current.copy(apps = scopedApps, lastUpdated = System.currentTimeMillis())
                scope.launch {
                  withContext(Dispatchers.IO) { repository.saveHydrated(updated) }
                  onEdit(updated)
                }
              },
              modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.action_edit))
          }
          Button(
              onClick = {
                val updated =
                    current.copy(apps = scopedApps, lastUpdated = System.currentTimeMillis())
                scope.launch {
                  withContext(Dispatchers.IO) { repository.saveHydrated(updated) }
                  onInstalled()
                }
              },
              modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.action_install))
          }
        }
      } else if (error == null) {
        Text(stringResource(R.string.install_reading))
      }
    }
  }
}

private const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024
private const val MAX_PREVIEW_CHARS = 4000

/** The provider keeps staged install payloads here; the Activity reads them by token. */
private fun stagedFile(context: Context, token: String): File =
    File(File(context.filesDir, "pending"), token.replace(Regex("[^A-Za-z0-9._-]"), "_"))

private fun readStaged(context: Context, token: String): String? =
    runCatching { stagedFile(context, token).takeIf { it.isFile }?.readText() }.getOrNull()

private fun discardStaged(context: Context, token: String?) {
  if (token == null) return
  runCatching { stagedFile(context, token).delete() }
}

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
