package org.matrix.chromext.action

object ActionRegistry {

  private val handlers: Map<String, ActionHandler> =
      listOf(
              ClipboardActions,
              TabActions,
              ScriptActions,
              NetworkActions,
              UiActions,
              DevToolsActions,
              MiscActions,
          )
          .flatMap { it.handlers.entries }
          .associate { it.key to it.value }

  fun handle(context: ActionContext): String? = handlers[context.action]?.handle(context)

  fun actionNames(): Set<String> = handlers.keys
}
