package org.matrix.chromext.ui

import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.matrix.chromext.R
import org.matrix.chromext.browser.BrowserCatalog
import org.matrix.chromext.script.HookedApp
import org.matrix.chromext.script.Script

/** An installed app that the module supports, whether or not it has been hooked yet. */
data class AppEntry(val packageName: String, val label: String)

/**
 * Reads the installed browsers. The manifest `<queries>` block limits the result to the catalog on
 * Android 11+, so this does not need QUERY_ALL_PACKAGES.
 */
fun loadInstalledBrowsers(context: Context): List<AppEntry> {
  val packageManager = context.packageManager
  return BrowserCatalog.xposedScope
      .mapNotNull { packageName ->
        runCatching {
              val info = packageManager.getApplicationInfo(packageName, 0)
              AppEntry(packageName, packageManager.getApplicationLabel(info).toString())
            }
            .getOrNull()
      }
      .sortedBy { it.label.lowercase() }
}

@Composable
fun AppsScreen(
    modifier: Modifier,
    active: List<HookedApp>,
    installed: List<AppEntry>,
    scripts: List<Script>,
    loading: Boolean,
    onOpenApp: (String) -> Unit,
) {
  val activePackages = active.map { it.packageName }.toSet()
  val inactive = installed.filter { it.packageName !in activePackages }

  if (loading && active.isEmpty() && installed.isEmpty()) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  if (active.isEmpty() && inactive.isEmpty()) {
    EmptyApps(modifier)
    return
  }

  LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (active.isNotEmpty()) {
      item(key = "header-active") { SectionHeader(stringResource(R.string.apps_active)) }
      items(active, key = { "active:${it.packageName}" }) { app ->
        val seen = formatTime(app.lastSeen)
        val count = countScripts(scripts, app.packageName)
        val detail =
            if (seen.isEmpty()) stringResource(R.string.apps_script_count, count)
            else
                stringResource(R.string.apps_last_seen, seen) +
                    " · " +
                    stringResource(R.string.apps_script_count, count)
        AppCard(
            label = app.label.ifBlank { stringResource(R.string.apps_unknown) },
            packageName = app.packageName,
            detail = detail,
            active = true,
            onClick = { onOpenApp(app.packageName) },
        )
      }
    }
    if (inactive.isNotEmpty()) {
      item(key = "header-inactive") {
        SectionHeader(stringResource(R.string.apps_inactive))
      }
      items(inactive, key = { "inactive:${it.packageName}" }) { app ->
        AppCard(
            label = app.label.ifBlank { stringResource(R.string.apps_unknown) },
            packageName = app.packageName,
            detail = stringResource(R.string.apps_script_count, countScripts(scripts, app.packageName)),
            active = false,
            onClick = { onOpenApp(app.packageName) },
        )
      }
    }
  }
}

private fun countScripts(scripts: List<Script>, packageName: String): Int =
    scripts.count { it.appliesTo(packageName) }

@Composable
private fun SectionHeader(text: String) {
  Text(
      text = text,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
  )
}

@Composable
private fun AppCard(
    label: String,
    packageName: String,
    detail: String,
    active: Boolean,
    onClick: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
      if (active) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp),
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun EmptyApps(modifier: Modifier) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
      Text(stringResource(R.string.apps_empty), style = MaterialTheme.typography.titleMedium)
      Text(
          stringResource(R.string.apps_empty_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

private fun formatTime(value: Long): String {
  if (value <= 0L) return ""
  return runCatching {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
      }
      .getOrDefault("")
}
