package org.matrix.chromext.action

/** Handles one `ChromeXt.dispatch` action and returns the JavaScript callback, if any. */
fun interface ActionHandler {
  fun handle(context: ActionContext): String?
}

/** A named group of handlers, flattened into the registry at startup. */
interface ActionGroup {
  val handlers: Map<String, ActionHandler>
}
