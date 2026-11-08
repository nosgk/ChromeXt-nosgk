package org.matrix.chromext.menu

import org.matrix.chromext.R
import org.matrix.chromext.utils.isChromeXtFrontEnd
import org.matrix.chromext.utils.isUserScript

/** The three page classes that decide which ChromeXt entry the browser menu shows. */
enum class PageKind {
  FRONT_END,
  USER_SCRIPT,
  REGULAR,
}

data class MenuEntry(val id: Int, val titleRes: Int, val iconRes: Int)

/**
 * Single source of truth for the "front end / UserScript / regular page" menu decision. The legacy
 * Menu path, the MVC model path, the context menu and the page-info row all used to duplicate this
 * mapping; they now ask this object instead.
 */
object MenuEntryPolicy {

  fun kindOf(url: String?): PageKind =
      when {
        isChromeXtFrontEnd(url) -> PageKind.FRONT_END
        isUserScript(url) -> PageKind.USER_SCRIPT
        else -> PageKind.REGULAR
      }

  fun titleRes(url: String?): Int =
      when (kindOf(url)) {
        PageKind.FRONT_END -> R.string.main_menu_developer_tools
        PageKind.USER_SCRIPT -> R.string.main_menu_install_script
        PageKind.REGULAR -> R.string.main_menu_eruda_console
      }

  fun entries(url: String?): List<MenuEntry> =
      when (kindOf(url)) {
        PageKind.FRONT_END ->
            listOf(
                MenuEntry(
                    R.id.developer_tools_id,
                    R.string.main_menu_developer_tools,
                    R.drawable.ic_devtools,
                ),
                MenuEntry(
                    R.id.extension_id,
                    R.string.main_menu_extension,
                    R.drawable.ic_extension,
                ),
            )
        PageKind.USER_SCRIPT ->
            listOf(
                MenuEntry(
                    R.id.install_script_id,
                    R.string.main_menu_install_script,
                    R.drawable.ic_install_script,
                ))
        PageKind.REGULAR ->
            listOf(
                MenuEntry(
                    R.id.eruda_console_id,
                    R.string.main_menu_eruda_console,
                    R.drawable.ic_devtools,
                ))
      }
}
