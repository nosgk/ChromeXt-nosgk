"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const read = (relative) => fs.readFileSync(path.join(root, relative), "utf8");

test("scripts.js has exactly one Kotlin anchor", () => {
  const text = read("app/src/main/assets/scripts.js");
  const matches = text.match(/\/\/ Kotlin anchor/g) || [];
  assert.strictEqual(matches.length, 1, "the anchor line must be unique");
});

test("scripts.js defines the dispatch bridge", () => {
  const text = read("app/src/main/assets/scripts.js");
  assert.match(text, /class ChromeXtTarget/);
  assert.match(text, /dispatch\(action, payload, key\)/);
  assert.match(text, /backup\.stringify\(\{ action, payload, key: initKey \}\)/);
});

test("GM.js implements the documented APIs", () => {
  const text = read("app/src/main/assets/GM.js");
  const names = [
    "GM_addStyle",
    "GM_xmlhttpRequest",
    "GM_setValue",
    "GM_getValue",
    "GM_notification",
    "GM_cookie",
    "GM_download",
    "GM_openInTab",
    "GM_registerMenuCommand",
  ];
  for (const name of names) {
    assert.match(
      text,
      new RegExp(`(function|const|let) ${name}\\b`),
      `missing ${name}`,
    );
  }
});

test("the in-page install editor stays self-contained", () => {
  const text = read("app/src/main/assets/editor.js");
  assert.doesNotMatch(text, /unpkg\.com/, "editor.js must not depend on a CDN");
});
