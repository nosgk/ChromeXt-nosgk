package org.matrix.chromext.action

/** Everything an action handler needs, captured once by [org.matrix.chromext.Listener]. */
data class ActionContext(
    val action: String,
    val payload: String,
    val tab: Any?,
    val auxObject: Any?,
    val frameId: String?,
)
