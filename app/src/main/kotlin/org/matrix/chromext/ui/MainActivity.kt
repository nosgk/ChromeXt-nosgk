package org.matrix.chromext.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.matrix.chromext.utils.ModuleLog

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The manager writes the shared log file directly; hooked apps upload their lines through the
    // provider. Attaching here keeps the manager's own entries working even before the provider
    // has been created.
    ModuleLog.attach(applicationContext)
    requestNotificationPermission()
    requestLegacyStoragePermission()
    val activeUrl = intent?.getStringExtra(EXTRA_ACTIVE_URL)
    val editId = intent?.getStringExtra(EXTRA_EDIT_ID)
    setContent {
      val context = LocalContext.current
      var mode by remember { mutableStateOf(ThemePrefs.load(context)) }
      val dark = isDarkMode(mode, isSystemInDarkTheme())
      // The XML theme cannot follow a manual override, so paint the window to match Compose and
      // avoid a white flash before the first frame (and a light border while resizing).
      SideEffect {
        window.setBackgroundDrawable(
            ColorDrawable(if (dark) DARK_WINDOW else LIGHT_WINDOW))
      }
      ChromeXtTheme(mode) {
        Surface(modifier = Modifier.fillMaxSize()) {
          ChromeXtApp(
              activeUrl = activeUrl,
              editId = editId,
              themeMode = mode,
              onThemeModeChange = {
                mode = it
                ThemePrefs.save(context, it)
              },
          )
        }
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

  /** Android 9 and older write the Download/ChromeXT mirror with a plain File. */
  private fun requestLegacyStoragePermission() {
    if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED) {
      requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
    }
  }

  companion object {
    const val EXTRA_ACTIVE_URL = "active_url"
    const val EXTRA_EDIT_ID = "edit_id"

    private const val DARK_WINDOW = 0xFF1C1B1F.toInt()
    private const val LIGHT_WINDOW = 0xFFFEF7FF.toInt()
  }
}
