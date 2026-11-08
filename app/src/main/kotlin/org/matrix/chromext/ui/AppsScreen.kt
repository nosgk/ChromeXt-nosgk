package org.matrix.chromext.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.matrix.chromext.R
import org.matrix.chromext.browser.BrowserCatalog
import org.matrix.chromext.script.HookedApp
import org.matrix.chromext.script.Script

/** One row of the apps screen. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val icon: ImageBitmap?,
    /** A hooked process reported in (or used the central database) at least once. */
    val active: Boolean,
    /** The package is installed on this device. */
    val installed: Boolean,
    /** The framework's module scope contains this package (root-assisted read, best effort). */
    val scoped: Boolean,
)

/**
 * Collects everything the apps screen and the script editor need: every launchable app (for
 * scoping), plus packages that reported in, are in the framework scope, or are referenced by a
 * script.
 */
fun loadApps(
    context: Context,
    hooked: List<HookedApp>,
    scripts: List<Script>,
    scoped: Set<String>,
): List<AppEntry> {
  val packageManager = context.packageManager
  val active = hooked.associateBy { it.packageName }
  val scriptScopes = scripts.flatMap { it.apps }.toSet()

  val packages = LinkedHashSet<String>()
  packages += active.keys
  packages += scoped
  packages += scriptScopes
  packages += BrowserCatalog.xposedScope
  runCatching { packageManager.getInstalledApplications(0) }
      .getOrDefault(emptyList())
      .forEach { info ->
        if (packageManager.getLaunchIntentForPackage(info.packageName) != null) {
          packages += info.packageName
        }
      }
  packages -= context.packageName
  packages -= "android"

  return packages
      .map { packageName ->
        val info = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        val label =
            info?.let { runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull() }
        val version =
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val icon =
            info?.let {
              runCatching {
                    packageManager.getApplicationIcon(it).toIconBitmap(64).asImageBitmap()
                  }
                  .getOrNull()
            }
        AppEntry(
            packageName = packageName,
            label = label?.takeIf { it.isNotBlank() } ?: packageName,
            versionName = version.orEmpty(),
            icon = icon,
            active = active.containsKey(packageName),
            installed = info != null,
            scoped = packageName in scoped,
        )
      }
      .sortedWith(
          compareByDescending<AppEntry> { it.active }
              .thenByDescending { it.scoped }
              .thenByDescending { it.installed }
              .thenBy { it.label.lowercase() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    modifier: Modifier,
    apps: List<AppEntry>,
    scripts: List<Script>,
    loading: Boolean,
    refreshing: Boolean,
    includeAll: Boolean,
    scopeResult: ScopeResult,
    onRefresh: () -> Unit,
    onIncludeAllChange: (Boolean) -> Unit,
    onOpenApp: (String?) -> Unit,
) {
  var query by rememberSaveable { mutableStateOf("") }

  val catalog = remember { BrowserCatalog.xposedScope.toSet() }
  val scriptScopes = remember(scripts) { scripts.flatMap { it.apps }.toSet() }
  val visible = remember(apps, includeAll, query, scriptScopes) {
    val needle = query.trim().lowercase()
    apps
        .asSequence()
        .filter {
          includeAll ||
              it.active ||
              it.scoped ||
              it.packageName in scriptScopes ||
              (it.installed && it.packageName in catalog)
        }
        .filter {
          needle.isEmpty() ||
              it.label.lowercase().contains(needle) ||
              it.packageName.lowercase().contains(needle)
        }
        .toList()
  }

  val globalScripts = remember(scripts) { scripts }
  val globalEnabled = globalScripts.count { it.enabled }

  Scaffold(
      modifier = modifier,
      topBar = {
        Column {
          TopAppBar(
              title = { Text(stringResource(R.string.apps_title)) },
              actions = {
                IconButton(onClick = onRefresh) {
                  Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
              },
          )
          OutlinedTextField(
              value = query,
              onValueChange = { query = it },
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
              singleLine = true,
              leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
              placeholder = { Text(stringResource(R.string.apps_search_hint)) },
          )
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .horizontalScroll(rememberScrollState())
                      .padding(horizontal = 12.dp, vertical = 4.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilterChip(
                selected = !includeAll,
                onClick = { onIncludeAllChange(false) },
                label = { Text(stringResource(R.string.apps_filter_hooked)) },
            )
            FilterChip(
                selected = includeAll,
                onClick = { onIncludeAllChange(true) },
                label = { Text(stringResource(R.string.apps_filter_all)) },
            )
          }
        }
      },
  ) { padding ->
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.padding(padding).fillMaxSize(),
    ) {
      when {
        loading && apps.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
        visible.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  modifier = Modifier.padding(32.dp),
              ) {
                Text(stringResource(R.string.apps_empty), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.apps_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
              }
            }
        else ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              item(key = "global") {
                GlobalRow(
                    scripts = globalScripts,
                    enabled = globalEnabled,
                    onClick = { onOpenApp(null) },
                )
              }
              if (scopeResult.status != ScopeStatus.OK) {
                item(key = "scope-hint") {
                  Text(
                      scopeHint(scopeResult),
                      style = MaterialTheme.typography.bodySmall,
                      color =
                          if (scopeResult.status == ScopeStatus.EMPTY)
                              MaterialTheme.colorScheme.onSurfaceVariant
                          else MaterialTheme.colorScheme.error,
                      modifier = Modifier.padding(horizontal = 4.dp),
                  )
                }
              }
              items(visible, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    total = scripts.count { it.appliesTo(app.packageName) },
                    enabled = scripts.count { it.appliesTo(app.packageName) && it.enabled },
                    onClick = { onOpenApp(app.packageName) },
                )
              }
            }
      }
    }
  }
}

@Composable
private fun GlobalRow(scripts: List<Script>, enabled: Int, onClick: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(
          modifier =
              Modifier.size(40.dp)
                  .background(
                      MaterialTheme.colorScheme.primaryContainer,
                      RoundedCornerShape(10.dp),
                  ),
          contentAlignment = Alignment.Center,
      ) {
        Text(
            text = "ALL",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
        Text(stringResource(R.string.apps_global), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.apps_global_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
          stringResource(R.string.apps_script_counts, scripts.size, enabled),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun AppRow(app: AppEntry, total: Int, enabled: Int, onClick: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      if (app.icon != null) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
      } else {
        Box(
            modifier =
                Modifier.size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
          Text(
              app.label.take(1).uppercase(),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
        Text(
            text = app.label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = app.packageName,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text =
                when {
                  app.active -> stringResource(R.string.apps_status_active)
                  app.scoped -> stringResource(R.string.apps_status_scoped)
                  !app.installed -> stringResource(R.string.apps_status_missing)
                  else -> stringResource(R.string.apps_status_out_of_scope)
                },
            style = MaterialTheme.typography.labelSmall,
            color =
                if (app.active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(
            stringResource(R.string.apps_script_counts, total, enabled),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (app.active) {
          Icon(
              Icons.Default.CheckCircle,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 4.dp).size(18.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun scopeHint(result: ScopeResult): String =
    when (result.status) {
      ScopeStatus.OK -> ""
      ScopeStatus.EMPTY -> stringResource(R.string.apps_scope_empty)
      ScopeStatus.NO_ROOT -> stringResource(R.string.apps_scope_no_root)
      ScopeStatus.NO_CONFIG -> stringResource(R.string.apps_scope_no_config)
      ScopeStatus.DB_ERROR ->
          stringResource(
              R.string.apps_scope_db_error,
              result.detail.ifBlank { stringResource(R.string.apps_unknown) })
    }

private fun Drawable.toIconBitmap(size: Int): Bitmap {
  if (this is BitmapDrawable && bitmap != null) {
    return Bitmap.createScaledBitmap(bitmap, size, size, true)
  }
  val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(output)
  setBounds(0, 0, size, size)
  draw(canvas)
  return output
}
