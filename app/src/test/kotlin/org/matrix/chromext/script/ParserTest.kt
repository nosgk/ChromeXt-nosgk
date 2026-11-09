package org.matrix.chromext.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

  @Test
  fun parsesMetadata() {
    val input =
        """
        // ==UserScript==
        // @name Demo
        // @namespace test
        // @version 1.2.3
        // @author Alice
        // @description A demo
        // @match https://example.com/*
        // @include https://example.org/*
        // @exclude https://example.com/private/*
        // @grant GM_setValue
        // @run-at document-start
        // @icon https://example.com/icon.png
        // @updateURL https://example.com/demo.user.js
        // ==/UserScript==
        console.log("hi");
        """
            .trimIndent()
    val script = parseScript(input)!!
    assertEquals("test:Demo", script.id)
    assertEquals("1.2.3", script.version)
    assertEquals("Alice", script.author)
    assertEquals("A demo", script.description)
    assertEquals("document-start", script.runAt)
    assertEquals("https://example.com/icon.png", script.iconUrl)
    assertEquals("https://example.com/demo.user.js", script.updateUrl)
    assertTrue(script.match.contains("https://example.com/*"))
    assertTrue(script.match.contains("https://example.org/*"))
    assertTrue(script.exclude.contains("https://example.com/private/*"))
    assertTrue(script.grant.contains("GM_setValue"))
  }

  @Test
  fun rejectsMissingMatch() {
    val input = "// ==UserScript==\n// @name NoMatch\n// ==/UserScript==\n"
    assertNull(parseScript(input))
  }

  @Test
  fun grantDownloadImpliesXmlhttpRequest() {
    val input =
        """
        // ==UserScript==
        // @name Download
        // @match https://example.com/*
        // @grant GM_download
        // ==/UserScript==
        """
            .trimIndent()
    assertTrue(parseScript(input)!!.grant.contains("GM_xmlhttpRequest"))
  }

  @Test
  fun parsesStorage() {
    val input =
        """
        // ==UserScript==
        // @name Storage
        // @match https://example.com/*
        // @grant GM_getValue
        // ==/UserScript==
        """
            .trimIndent()
    val script = parseScript(input, """{"key":"value"}""")
    assertEquals("value", script!!.storage!!.getString("key"))
  }

  @Test
  fun parsesScriptWithoutTrailingNewline() {
    val input =
        javaClass.classLoader!!
            .getResource("userscripts/edge/no-trailing-newline.user.js")!!
            .readText()
    assertEquals("ChromeXt:NoTrailingNewline", parseScript(input)!!.id)
  }

  @Test
  fun dropsUnknownRunAtAndNonHttpUpdateUrl() {
    val input =
        """
        // ==UserScript==
        // @name Params
        // @match https://example.com/*
        // @run-at nonsense
        // @updateURL file:///tmp/demo.user.js
        // ==/UserScript==
        """
            .trimIndent()
    val script = parseScript(input)!!
    assertEquals("", script.runAt)
    assertEquals("", script.updateUrl)
  }

  @Test
  fun deduplicatesGrantAndMatchEntries() {
    val input =
        """
        // ==UserScript==
        // @name Duplicates
        // @match https://example.com/*
        // @match https://example.com/*
        // @grant GM_setValue
        // @grant GM_setValue
        // ==/UserScript==
        """
            .trimIndent()
    val script = parseScript(input)!!
    assertEquals(1, script.match.size)
    assertEquals(1, script.grant.count { it == "GM_setValue" })
  }

  @Test
  fun parsesResourcesAndStripsIntegrityHashes() {
    val input =
        """
        // ==UserScript==
        // @name Resources
        // @match https://example.com/*
        // @resource style https://example.com/style.css
        // @resource logo https://example.com/logo.png#sha256-abc
        // ==/UserScript==
        """
            .trimIndent()
    val script = parseScript(input)!!
    assertEquals(2, script.resources.size)
    assertEquals("style", script.resources[0].name)
    assertEquals("https://example.com/style.css", script.resources[0].url)
    assertEquals("text/css", script.resources[0].mime)
    assertEquals("https://example.com/logo.png", script.resources[1].url)
    assertEquals("image/png", script.resources[1].mime)
    assertNull(script.resources[0].data)
  }
}
