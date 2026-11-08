package org.matrix.chromext.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    requestNotificationPermission()
    val activeUrl = intent?.getStringExtra(EXTRA_ACTIVE_URL)
    val editId = intent?.getStringExtra(EXTRA_EDIT_ID)
    setContent {
      ChromeXtTheme {
        Surface { ChromeXtApp(activeUrl = activeUrl, editId = editId) }
      }
    }
  }

  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
  }

  companion object {
    const val EXTRA_ACTIVE_URL = "active_url"
    const val EXTRA_EDIT_ID = "edit_id"
  }
}
