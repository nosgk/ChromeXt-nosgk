package org.matrix.chromext.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/** User-selectable appearance; [SYSTEM] keeps following the device setting. */
enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

object ThemePrefs {
  private const val FILE = "ChromeXt"
  private const val KEY = "theme_mode"

  fun load(context: Context): ThemeMode {
    val raw =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return ThemeMode.SYSTEM
    return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
  }

  fun save(context: Context, mode: ThemeMode) {
    context
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY, mode.name)
        .apply()
  }
}

fun isDarkMode(mode: ThemeMode, systemInDarkTheme: Boolean): Boolean =
    when (mode) {
      ThemeMode.SYSTEM -> systemInDarkTheme
      ThemeMode.LIGHT -> false
      ThemeMode.DARK -> true
    }

@Composable
fun ChromeXtTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
  val dark = isDarkMode(mode, isSystemInDarkTheme())
  val colors = if (dark) darkColorScheme() else lightColorScheme()
  MaterialTheme(colorScheme = colors, content = content)
}
