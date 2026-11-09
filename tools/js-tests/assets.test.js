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

test("the install editor is localized and resilient", () => {
  const text = read("app/src/main/assets/editor.js");
  assert.match(text, /确认安装/, "the confirm button must be localized");
  assert.match(text, /findCodeNode/, "the code element lookup must not be a single selector");
  assert.doesNotMatch(
    text,
    /return prepareDOM\(\);/,
    "sandboxed pages must not recurse synchronously",
  );
});

test("GM.js reports real activation and consumes local resources", () => {
  const text = read("app/src/main/assets/GM.js");
  assert.match(text, /dispatch\("scriptActive"/);
  assert.match(text, /resourceData/);
  assert.match(text, /URL\.createObjectURL/);
});
