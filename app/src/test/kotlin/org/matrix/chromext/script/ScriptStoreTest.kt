package org.matrix.chromext.script

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScriptStoreTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun record(id: String): ScriptRecord =
      ScriptRecord(
          id = id,
          meta =
              "// ==UserScript==\n// @name Demo\n// @match https://example.com/*\n// ==/UserScript==\n",
          code = "console.log(1);",
          storage = null,
          enabled = true,
          sortOrder = 0,
          customMatch = "",
          customInclude = "",
          customExclude = "",
          customRunAt = "",
          updateUrl = "",
          iconUrl = "",
          lastUpdated = 1L,
      )

  private fun freshStore(): ScriptStore {
    context.deleteDatabase(ScriptDbHelper.DATABASE_NAME)
    return ScriptStore(context)
  }

  @Test
  fun bumpsRevisionOnEveryWrite() {
    val store = freshStore()
    val before = store.revision()
    assertTrue(store.upsert(record("a:one")))
    val afterInsert = store.revision()
    assertTrue(afterInsert > before)
    assertTrue(store.setEnabled("a:one", false))
    assertTrue(store.revision() > afterInsert)
    assertFalse(store.records().single().enabled)
    store.close()
  }

  @Test
  fun accumulatesActivationCountsAndCascadesOnDelete() {
    val store = freshStore()
    assertTrue(store.upsert(record("a:one")))
    store.reportActivations(listOf(ScriptActivation("a:one", "com.android.chrome", 100, 1)))
    store.reportActivations(listOf(ScriptActivation("a:one", "com.android.chrome", 200, 1)))
    val activation = store.activations().single()
    assertEquals(200L, activation.lastActive)
    assertEquals(2L, activation.count)
    store.delete(arrayOf("a:one"))
    assertTrue(store.activations().isEmpty())
    store.close()
  }

  @Test
  fun roundTripsHydratedLibAndResources() {
    val store = freshStore()
    val record =
        record("a:two").copy(
            lib = encodeLib(listOf("lib-code")),
            resources =
                encodeResources(
                    listOf(ScriptResource("logo", "https://example.com/logo.png", "image/png", "QUJD"))),
        )
    assertTrue(store.upsert(record))
    val script = store.record("a:two")!!.toScript()!!
    assertEquals(listOf("lib-code"), script.lib)
    assertEquals("QUJD", script.resources.single().data)
    store.close()
  }
}
