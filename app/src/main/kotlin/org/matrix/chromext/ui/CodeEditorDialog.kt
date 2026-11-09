package org.matrix.chromext.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch
import org.matrix.chromext.R

/** Above this size the dialog opens in the virtualized read-only viewer instead of a text field. */
private const val LARGE_TEXT_THRESHOLD = 120_000

/**
 * Full-screen editor for one script body.
 *
 * Compose lays out the whole string for a text field, which is unusable for scripts like Pagetual
 * (~800 KB). Large scripts therefore open in a virtualized line viewer (`LazyColumn` of lines);
 * editing remains available behind an explicit confirmation. Small scripts keep the full editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorDialog(initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
  var value by remember { mutableStateOf(TextFieldValue(initial)) }
  var editing by remember { mutableStateOf(initial.length <= LARGE_TEXT_THRESHOLD) }
  var dirty by remember { mutableStateOf(false) }
  var showFind by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var replacement by remember { mutableStateOf("") }
  var confirmDiscard by remember { mutableStateOf(false) }
  var confirmLargeEdit by remember { mutableStateOf(false) }

  val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
  // Line/column needs a scan up to the cursor; only meaningful while the text field is usable.
  val position =
      remember(value.text, value.selection) {
        if (value.text.length <= LARGE_TEXT_THRESHOLD) lineColumn(value) else null
      }

  fun updateValue(next: TextFieldValue) {
    value = next
    dirty = true
  }

  fun findNext() {
    if (query.isEmpty()) return
    val text = value.text
    val start = value.selection.end.coerceIn(0, text.length)
    var index = text.indexOf(query, start, ignoreCase = true)
    if (index < 0) index = text.indexOf(query, 0, ignoreCase = true)
    if (index >= 0) {
      updateValue(value.copy(selection = TextRange(index, index + query.length)))
    }
  }

  fun replaceCurrent() {
    val selection = value.selection
    if (selection.length > 0 &&
        value.text.substring(selection.min, selection.max).equals(query, ignoreCase = true)) {
      val text = value.text.replaceRange(selection.min, selection.max, replacement)
      updateValue(
          TextFieldValue(
              text = text,
              selection = TextRange(selection.min + replacement.length),
          ))
    } else {
      findNext()
    }
  }

  fun replaceAll() {
    if (query.isEmpty()) return
    updateValue(TextFieldValue(value.text.replace(query, replacement, ignoreCase = true)))
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
                        if (editing) {
                          stringResource(
                              R.string.code_editor_stats,
                              lineCount,
                              value.text.length,
                              position?.first ?: 1,
                              position?.second ?: 1)
                        } else {
                          stringResource(
                              R.string.code_editor_viewer_stats, lineCount, value.text.length)
                        },
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
                  if (editing) {
                    TextButton(onClick = { onApply(value.text) }) {
                      Text(stringResource(R.string.action_save))
                    }
                  } else {
                    TextButton(
                        onClick = {
                          if (initial.length > LARGE_TEXT_THRESHOLD) confirmLargeEdit = true
                          else editing = true
                        }) {
                      Text(stringResource(R.string.code_editor_edit))
                    }
                  }
                },
            )
          },
      ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
          if (editing) {
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
                onValueChange = { updateValue(it) },
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                textStyle =
                    TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
          } else {
            LargeTextViewer(
                text = value.text,
                query = query,
                showFind = showFind,
                onQueryChange = { query = it },
                onCloseFind = {
                  showFind = false
                  query = ""
                },
            )
          }
        }
      }
    }
  }

  if (confirmLargeEdit) {
    AlertDialog(
        onDismissRequest = { confirmLargeEdit = false },
        title = { Text(stringResource(R.string.code_editor_large_edit_title)) },
        text = {
          Text(stringResource(R.string.code_editor_large_edit_body, value.text.length))
        },
        confirmButton = {
          TextButton(
              onClick = {
                confirmLargeEdit = false
                editing = true
              }) {
                Text(stringResource(R.string.code_editor_large_edit_confirm))
              }
        },
        dismissButton = {
          TextButton(onClick = { confirmLargeEdit = false }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
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

/**
 * Read-only viewer that only lays out the visible lines. Long minified lines still wrap, but a
 * 20 000-line script scrolls smoothly because `LazyColumn` never composes the whole body.
 */
@Composable
private fun LargeTextViewer(
    text: String,
    query: String,
    showFind: Boolean,
    onQueryChange: (String) -> Unit,
    onCloseFind: () -> Unit,
) {
  val lines = remember(text) { text.split('\n') }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  var highlight by remember { mutableStateOf(-1) }

  fun findNext() {
    if (query.isEmpty()) return
    val index = text.indexOf(query, ignoreCase = true)
    if (index < 0) {
      highlight = -1
      return
    }
    var line = 0
    var cursor = 0
    while (cursor < index) {
      if (text[cursor] == '\n') line += 1
      cursor += 1
    }
    highlight = line
    scope.launch { listState.animateScrollToItem(line) }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    if (showFind) {
      Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.code_editor_find)) },
        )
        TextButton(onClick = { findNext() }) {
          Text(stringResource(R.string.code_editor_next))
        }
        IconButton(onClick = onCloseFind) {
          Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
        }
      }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
      itemsIndexed(lines) { index, line ->
        Text(
            text = if (line.isEmpty()) " " else line,
            style =
                TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier.fillMaxWidth()
                    .background(
                        if (index == highlight) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 1.dp),
        )
      }
    }
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
