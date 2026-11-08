package org.matrix.chromext.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.matrix.chromext.R

/**
 * Full-screen editor for one script body.
 *
 * The script editor screen only shows a summary plus this dialog, so opening a large script no
 * longer builds a `TextField` over the whole code on every recomposition. The editor itself offers
 * monospace input, find/replace, a live line/column indicator and explicit save/discard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorDialog(initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
  var value by remember { mutableStateOf(TextFieldValue(initial)) }
  var showFind by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var replacement by remember { mutableStateOf("") }
  var confirmDiscard by remember { mutableStateOf(false) }

  val dirty = value.text != initial
  val lineCount = value.text.count { it == '\n' } + 1
  val position = remember(value) { lineColumn(value) }

  fun findNext() {
    if (query.isEmpty()) return
    val text = value.text
    val start = value.selection.end.coerceIn(0, text.length)
    var index = text.indexOf(query, start, ignoreCase = true)
    if (index < 0) index = text.indexOf(query, 0, ignoreCase = true)
    if (index >= 0) {
      value = value.copy(selection = TextRange(index, index + query.length))
    }
  }

  fun replaceCurrent() {
    val selection = value.selection
    if (selection.length > 0 &&
        value.text.substring(selection.min, selection.max).equals(query, ignoreCase = true)) {
      val text = value.text.replaceRange(selection.min, selection.max, replacement)
      value =
          TextFieldValue(
              text = text,
              selection = TextRange(selection.min + replacement.length),
          )
    } else {
      findNext()
    }
  }

  fun replaceAll() {
    if (query.isEmpty()) return
    value = TextFieldValue(value.text.replace(query, replacement, ignoreCase = true))
  }

  fun requestClose() {
    if (dirty) confirmDiscard = true else onDismiss()
  }

  Dialog(
      onDismissRequest = { requestClose() },
      properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize()) {
      Scaffold(
          topBar = {
            TopAppBar(
                title = {
                  Column {
                    Text(stringResource(R.string.code_editor_title))
                    Text(
                        stringResource(
                            R.string.code_editor_stats,
                            lineCount,
                            value.text.length,
                            position.first,
                            position.second),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                },
                navigationIcon = {
                  IconButton(onClick = { requestClose() }) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.action_back))
                  }
                },
                actions = {
                  IconButton(onClick = { showFind = !showFind }) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.code_editor_find))
                  }
                  TextButton(onClick = { onApply(value.text) }) {
                    Text(stringResource(R.string.action_save))
                  }
                },
            )
          },
      ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
          if (showFind) {
            FindReplaceBar(
                query = query,
                onQueryChange = { query = it },
                replacement = replacement,
                onReplacementChange = { replacement = it },
                onNext = { findNext() },
                onReplace = { replaceCurrent() },
                onReplaceAll = { replaceAll() },
                onClose = {
                  showFind = false
                  query = ""
                  replacement = ""
                },
            )
          }
          OutlinedTextField(
              value = value,
              onValueChange = { value = it },
              modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
              textStyle =
                  TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
          )
        }
      }
    }
  }

  if (confirmDiscard) {
    AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.code_editor_discard_title)) },
        text = { Text(stringResource(R.string.code_editor_discard_body)) },
        confirmButton = {
          TextButton(
              onClick = {
                confirmDiscard = false
                onDismiss()
              }) {
                Text(stringResource(R.string.code_editor_discard))
              }
        },
        dismissButton = {
          TextButton(onClick = { confirmDiscard = false }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
  }
}

@Composable
private fun FindReplaceBar(
    query: String,
    onQueryChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      OutlinedTextField(
          value = query,
          onValueChange = onQueryChange,
          modifier = Modifier.weight(1f),
          singleLine = true,
          placeholder = { Text(stringResource(R.string.code_editor_find)) },
      )
      TextButton(onClick = onNext) { Text(stringResource(R.string.code_editor_next)) }
      IconButton(onClick = onClose) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
      }
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      OutlinedTextField(
          value = replacement,
          onValueChange = onReplacementChange,
          modifier = Modifier.weight(1f),
          singleLine = true,
          placeholder = { Text(stringResource(R.string.code_editor_replace)) },
      )
      TextButton(onClick = onReplace) { Text(stringResource(R.string.code_editor_replace_one)) }
      TextButton(onClick = onReplaceAll) { Text(stringResource(R.string.code_editor_replace_all)) }
    }
  }
}

private fun lineColumn(value: TextFieldValue): Pair<Int, Int> {
  val text = value.text
  val offset = value.selection.start.coerceIn(0, text.length)
  val before = text.substring(0, offset)
  val line = before.count { it == '\n' } + 1
  val column = before.length - before.lastIndexOf('\n')
  return line to column
}
