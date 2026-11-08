package org.matrix.chromext.menu

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.chromext.R

class MenuEntryPolicyTest {

  @Test
  fun classifiesPages() {
    assertEquals(
        PageKind.FRONT_END,
        MenuEntryPolicy.kindOf("https://jing.matrix-transformation.dev/ChromeXt/"),
    )
    assertEquals(PageKind.USER_SCRIPT, MenuEntryPolicy.kindOf("https://example.com/demo.user.js"))
    assertEquals(PageKind.REGULAR, MenuEntryPolicy.kindOf("https://example.com/"))
  }

  @Test
  fun frontEndHasDeveloperToolsAndManagerEntries() {
    val entries = MenuEntryPolicy.entries("https://jing.matrix-transformation.dev/ChromeXt/")
    assertEquals(2, entries.size)
    assertEquals(R.id.developer_tools_id, entries[0].id)
    assertEquals(R.string.main_menu_script_manager, entries[1].titleRes)
  }

  @Test
  fun regularPagesHideErudaWhenTheEngineSwitchIsOff() {
    val previous = MenuEntryPolicy.erudaEnabled
    try {
      MenuEntryPolicy.erudaEnabled = false
      assertEquals(0, MenuEntryPolicy.entries("https://example.com/").size)
      MenuEntryPolicy.erudaEnabled = true
      assertEquals(1, MenuEntryPolicy.entries("https://example.com/").size)
    } finally {
      MenuEntryPolicy.erudaEnabled = previous
    }
  }
}
