package org.matrix.chromext.action

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject
import org.matrix.chromext.Chrome

object ClipboardActions : ActionGroup {
  override val handlers =
      mapOf<String, ActionHandler>(
          "copy" to
              ActionHandler { context ->
                val data = JSONObject(context.payload)
                val type = data.getString("type")
                val text = data.getString("text")
                val label = data.optString("label")
                val clipData =
                    when (type) {
                      "html" -> ClipData.newHtmlText(label, text, data.optString("htmlText"))
                      else -> ClipData.newPlainText(label, text)
                    }
                val ctx = Chrome.getContext()
                val clipboard =
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(clipData)
                null
              },
      )
}
