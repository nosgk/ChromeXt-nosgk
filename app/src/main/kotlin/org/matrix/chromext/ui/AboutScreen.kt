package org.matrix.chromext.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.R

private const val FORK_URL = "https://github.com/nosgk/ChromeXt-nosgk"
private const val UPSTREAM_URL = "https://github.com/JingMatrix/ChromeXt"
private const val LICENSE_NAME = "GNU General Public License v3.0"

@Composable
fun AboutScreen(modifier: Modifier) {
  val context = LocalContext.current
  Column(
      modifier =
          modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text("ChromeXt", style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
      Column {
        LinkRow(stringResource(R.string.about_fork), FORK_URL) { openUrl(context, it) }
        HorizontalDivider()
        LinkRow(stringResource(R.string.about_upstream), UPSTREAM_URL) { openUrl(context, it) }
        HorizontalDivider()
        LinkRow(stringResource(R.string.about_license), LICENSE_NAME) { openUrl(context, it) }
      }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.about_disclaimer_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.about_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun LinkRow(title: String, value: String, onClick: (String) -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().clickable { onClick(value) }.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(
          value,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
      )
    }
    Icon(
        imageVector = Icons.Default.ArrowForward,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private fun openUrl(context: Context, url: String) {
  runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}
