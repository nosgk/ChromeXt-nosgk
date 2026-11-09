package org.matrix.chromext.action

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.utils.Log

object ClipboardActions : ActionGroup {
  override val handlers =
      mapOf<String, ActionHandler>(
          "copy" to
              ActionHandler { context ->
                val data = JSONObject(context.payload)
                val type = data.optString("type", "text")
                val text = data.optString("text")
                val label = data.optString("label")
                val clipData =
                    if (type.contains("html", ignoreCase = true)) {
                      ClipData.newHtmlText(label, text, data.optString("htmlText", text))
                    } else {
                      ClipData.newPlainText(label, text)
                    }
                val ctx = Chrome.getContext()
                val clipboard =
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                // Android 10+ only allows the focused app to write the clipboard. A script may
                // call this while the page is not focused; report it instead of letting the
                // exception escape the dispatch path.
                runCatching { clipboard?.setPrimaryClip(clipData) }
                    .onFailure { Log.d("GM_setClipboard failed: ${it.message}") }
                null
              },
      )
}
