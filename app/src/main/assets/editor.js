const isSandboxed = [
  "raw.githubusercontent.com",
  "gist.githubusercontent.com",
].includes(location.hostname);

const useChinese = /^zh/i.test((navigator.language || "").toLowerCase());
const t = (cn, en) => (useChinese ? cn : en);

/** Finds the element that holds the script source. Browsers do not agree on its shape. */
function findCodeNode() {
  return (
    document.querySelector("body > pre") ||
    document.querySelector("pre") ||
    document.querySelector("textarea") ||
    null
  );
}

async function installScript(force = false) {
  const dialog = document.querySelector("dialog#confirm");
  if (!force) {
    if (dialog && !dialog.open) dialog.showModal();
    return;
  }
  if (dialog) dialog.close();
  const script = document.body.innerText;
  Symbol.ChromeXt.dispatch("installScript", script);
}

/** Renders the metadata block above the code. Returns false when the page is not a script. */
function renderMeta(code) {
  const separator = "==/UserScript==\n";
  const parts = code.innerHTML.split(separator);
  if (parts.length < 2) return false;
  let html = (parts.shift() + separator).replace(
    "GM.ChromeXt",
    "<em>GM.ChromeXt</em>"
  );
  for (const api of ["GM_notification", "GM_setClipboard", "GM_cookie"]) {
    html = html.replace(api, `<span>${api}</span>`);
  }
  const scriptMeta = document.createElement("pre");
  scriptMeta.innerHTML = html;
  code.innerHTML = parts.join(separator);
  code.id = "code";
  code.removeAttribute("style");
  scriptMeta.id = "meta";
  document.body.prepend(scriptMeta);
  scriptMeta.setAttribute("contenteditable", true);
  code.setAttribute("contenteditable", true);
  scriptMeta.setAttribute("spellcheck", false);
  code.setAttribute("spellcheck", false);
  return true;
}

function injectStyle() {
  if (document.querySelector("style#chromext-editor-style")) return;
  const style = document.createElement("style");
  style.id = "chromext-editor-style";
  style.setAttribute("type", "text/css");
  style.textContent = _editor_style;
  document.head.appendChild(style);
  const meta = document.createElement("meta");
  meta.setAttribute("name", "viewport");
  meta.setAttribute(
    "content",
    "width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"
  );
  document.head.appendChild(meta);
}

/**
 * The confirmation dialog is the one thing that must always work: it is created even when the
 * editor cannot render (no `pre`/`textarea`, CSP restrictions, sandboxed raw pages).
 */
function createConfirmDialog(alertEncoding) {
  const dialog = document.createElement("dialog");
  dialog.id = "confirm";

  const question = document.createElement("p");
  question.textContent = t(
    "确认安装此用户脚本？",
    "Confirm ChromeXt to install this UserScript?"
  );

  const interaction = document.createElement("div");
  interaction.id = "interaction";
  const yes = document.createElement("button");
  yes.textContent = t("确认安装", "Confirm");
  yes.addEventListener("click", () => installScript(true));
  const no = document.createElement("button");
  no.textContent = t("30 秒后再问", "Ask 30s later");
  no.addEventListener("click", () => {
    dialog.close();
    try {
      setTimeout(() => dialog.showModal(), 30000);
    } catch (e) {}
  });
  interaction.append(yes, no);

  dialog.append(question);
  if (alertEncoding) {
    const warn = document.createElement("p");
    warn.textContent = t(
      "当前脚本可能存在编码错误。建议下载后在本地打开以修复。",
      "Current script may contain badly encoded text. Download it and open it locally to fix it."
    );
    dialog.append(warn);
  }
  if (document.querySelector("#meta > em") != undefined) {
    const alert = document.createElement("p");
    alert.id = "alert";
    alert.textContent = t(
      "注意：脚本声明了 GM.ChromeXt",
      "ATTENTION: GM.ChromeXt is declared"
    );
    dialog.append(alert);
  }
  dialog.append(interaction);
  document.body.prepend(dialog);
  try {
    dialog.showModal();
  } catch (e) {
    dialog.show();
  }
  Symbol.installScript = installScript;
}

function renderEditor(code, alertEncoding) {
  if (document.querySelector("dialog#confirm")) return;
  if (code) {
    try {
      renderMeta(code);
    } catch (e) {
      console.warn("ChromeXt: cannot render the script editor", e);
    }
  }
  createConfirmDialog(alertEncoding);
}

async function prepareDOM(attempt = 0) {
  if (typeof Symbol === "undefined" || Symbol.ChromeXt == undefined) return;
  if (document.querySelector("dialog#confirm")) return;
  if (document.readyState === "loading") {
    // Sandboxed pages may not run setTimeout; schedule through every mechanism that exists and
    // keep the retry bounded instead of the unbounded synchronous recursion used before.
    if (attempt < 120) {
      const retry = () => prepareDOM(attempt + 1);
      try {
        setTimeout(retry, 50);
      } catch (e) {}
      try {
        requestAnimationFrame(retry);
      } catch (e) {}
      try {
        queueMicrotask(retry);
      } catch (e) {}
    }
    return;
  }
  const body = document.body;
  if (!body) return;

  const code = findCodeNode();
  const type = (document.contentType || "").toLowerCase();
  // Raw text/javascript documents are safe to take over. For HTML pages, only a nearly empty
  // document (a single <pre>/<textarea> wrapper) qualifies; this keeps the editor off normal
  // install pages such as Greasyfork's HTML page, which also ends in .user.js.
  const plain =
    type.startsWith("text/plain") ||
    type.includes("javascript") ||
    (code != null && body.children.length <= 2 && !document.querySelector("p, div, script"));
  if (!plain) return;

  try {
    injectStyle();
  } catch (e) {}

  let alertEncoding = false;
  if (code && typeof fixEncoding === "function") {
    try {
      alertEncoding = !(await fixEncoding(true, true, code));
    } catch (e) {
      console.warn("ChromeXt: encoding detection failed", e);
    }
  }
  renderEditor(code, alertEncoding);
}

if (document.readyState === "loading") {
  try {
    document.addEventListener("DOMContentLoaded", () => prepareDOM(0));
    window.addEventListener("load", () => prepareDOM(0));
  } catch (e) {}
}
prepareDOM();
