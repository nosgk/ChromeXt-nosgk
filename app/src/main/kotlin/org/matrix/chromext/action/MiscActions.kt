package org.matrix.chromext.action

import org.matrix.chromext.Chrome
import org.matrix.chromext.script.Local
import org.matrix.chromext.utils.invalidUserScriptUrls
import org.matrix.chromext.utils.isUserScript

object MiscActions : ActionGroup {
  override val handlers =
      mapOf<String, ActionHandler>(
          "block" to
              ActionHandler { context ->
                val url = Chrome.getUrl(context.tab)
                if (isUserScript(url)) {
                  if (invalidUserScriptUrls.size > 256) invalidUserScriptUrls.removeAt(0)
                  invalidUserScriptUrls.add(url!!)
                }
                "if (Symbol.ChromeXt) Symbol.ChromeXt.lock(${Local.key},'${Local.name}');"
              },
      )
}
