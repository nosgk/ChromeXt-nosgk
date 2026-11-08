package org.matrix.chromext.action

import java.util.concurrent.ConcurrentHashMap
import org.matrix.chromext.utils.XMLHttpRequest

/**
 * State shared between action handlers. Kept here rather than in Listener so handlers do not have
 * to reach back into the dispatcher.
 */
object ActionState {
  val xmlhttpRequests: MutableMap<Double, XMLHttpRequest> = ConcurrentHashMap()
  val tabNotification: MutableMap<Int, Any> = ConcurrentHashMap()
}
