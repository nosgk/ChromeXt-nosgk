package org.matrix.chromext.bridge

/**
 * Constants and helpers for the JavaScript <-> Kotlin bridge.
 *
 * The bridge itself is still the console-anchor mechanism: `scripts.js` emits a JSON line from
 * `ChromeXtTarget.dispatch`, and the hooks recognise it by source id and line number. Keeping the
 * identifiers here makes the two sides easier to change together.
 */
object JsBridge {
  const val PROTOCOL_VERSION = 1
  const val SOURCE_PREFIX = "local://ChromeXt/init"

  fun isInitSource(sourceId: String?): Boolean = sourceId?.startsWith(SOURCE_PREFIX) == true

  /** Appended to the injected init script; the anchor line itself stays where scripts.js puts it. */
  fun protocolDeclaration(): String = "const CHROMEXT_PROTOCOL = $PROTOCOL_VERSION;"
}
