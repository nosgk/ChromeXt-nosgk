package org.matrix.chromext.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.matrix.chromext.R
import org.matrix.chromext.script.LegacyReadResult
import org.matrix.chromext.script.ScriptRecord
import org.matrix.chromext.script.parseScript
import org.matrix.chromext.script.toRecord

/** One row of the import preview. */
data class ImportCandidate(
    val record: ScriptRecord,
    val valid: Boolean = true,
    /** The same id already exists in the central database. */
    val existing: Boolean = false,
    val version: String = "",
    val matchCount: Int = 0,
)

data class ImportPreview(
    val candidates: List<ImportCandidate> = emptyList(),
    val invalid: Int = 0,
    val error: String? = null,
) {
  val valid: List<ImportCandidate>
    get() = candidates.filter { it.valid }
}

fun previewFromText(text: String, existingIds: Set<String>): ImportPreview {
  val parsed = parseScript(text, null, hydrateRequires = false)
      ?: return ImportPreview(error = "invalid")
  val record = parsed.copy(lastUpdated = System.currentTimeMillis()).toRecord()
  return ImportPreview(
      candidates =
          listOf(
              ImportCandidate(
                  record,
                  valid = true,
                  existing = record.id in existingIds,
                  version = parsed.version,
                  matchCount = parsed.match.size,
              )))
}

fun previewFromRecords(result: LegacyReadResult, existingIds: Set<String>): ImportPreview {
  if (result.error != null) return ImportPreview(error = result.error)
  return ImportPreview(
      candidates =
          result.records.map { record ->
            val parsed = parseScript(record.meta + record.code, null, false)
            ImportCandidate(
                record,
                valid = true,
                existing = record.id in existingIds,
                version = versionOf(record.meta),
                matchCount = parsed?.match?.size ?: 0,
            )
          },
      invalid = result.invalid,
  )
}

/**
 * Shows what a database or `.user.js` file contains before anything is written. Rows start
 * selected; the user can deselect individual scripts and choose whether existing ids are replaced.
 */
@Composable
fun ImportPreviewDialog(
    preview: ImportPreview,
    onDismiss: () -> Unit,
    onConfirm: (List<ScriptRecord>, Boolean) -> Unit,
) {
  var selected by remember(preview) {
    mutableStateOf(preview.valid.map { it.record.id }.toSet())
  }
  var overwrite by remember(preview) { mutableStateOf(false) }
  val allSelected = selected.size == preview.valid.size && preview.valid.isNotEmpty()

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.import_preview_title)) },
      text = {
        Column {
          Text(
              stringResource(
                  R.string.import_preview_summary,
                  preview.valid.size,
                  preview.invalid),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
              modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            TextButton(
                onClick = {
                  selected =
                      if (allSelected) emptySet() else preview.valid.map { it.record.id }.toSet()
                }) {
                  Text(
                      stringResource(
                          if (allSelected) R.string.import_select_none
                          else R.string.import_select_all))
                }
            Row(
                modifier =
                    Modifier.clickable { overwrite = !overwrite }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
              Text(
                  stringResource(R.string.import_overwrite),
                  style = MaterialTheme.typography.labelMedium,
              )
            }
          }
          HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
          LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            items(preview.valid, key = { it.record.id }) { candidate ->
              val checked = candidate.record.id in selected
              Row(
                  modifier =
                      Modifier.fillMaxWidth()
                          .clickable {
                            selected =
                                if (checked) selected - candidate.record.id
                                else selected + candidate.record.id
                          }
                          .padding(vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = {
                      selected =
                          if (checked) selected - candidate.record.id
                          else selected + candidate.record.id
                    })
                Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                  Text(
                      candidate.record.id.substringAfterLast(':'),
                      style = MaterialTheme.typography.bodyMedium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                  )
                  val matchLabel = stringResource(R.string.import_match_count, candidate.matchCount)
                  val existingLabel = stringResource(R.string.import_existing)
                  val details =
                      buildList {
                            if (candidate.version.isNotBlank()) add("v${candidate.version}")
                            add(matchLabel)
                            if (candidate.existing) add(existingLabel)
                          }
                          .joinToString(" · ")
                  Text(
                      details,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
          if (preview.invalid > 0) {
            Text(
                stringResource(R.string.import_invalid_hint, preview.invalid),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
          }
        }
      },
      confirmButton = {
        TextButton(
            enabled = selected.isNotEmpty(),
            onClick = {
              onConfirm(
                  preview.valid.filter { it.record.id in selected }.map { it.record }, overwrite)
            }) {
              Text(stringResource(R.string.import_confirm, selected.size))
            }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
      },
  )
}

private fun versionOf(meta: String): String =
    meta.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("// @version") }
        ?.substringAfter("@version")
        ?.trim()
        .orEmpty()
