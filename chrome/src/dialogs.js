// Runs in the page's own world of a run's tab, as early as the extension can inject it (docs/chrome.md, "Dialogs"): the
// page's alert, confirm and prompt stop blocking and are reported to the content script under this tab's token; a confirm
// is never accepted and a prompt never answered. A "leave this page?" prompt can't hold a run's tab: beforeunload handlers
// are dropped. Only the run's own tabs get this, never the owner's.
//
// `installDialogGuard` is handed to chrome.scripting.executeScript as a function, so it must not close over anything.

export function installDialogGuard(token) {
  if (window.__tafsDialogGuard) return;
  Object.defineProperty(window, "__tafsDialogGuard", { value: true });
  const name = "tafs-dialog-" + token;
  const tell = (kind, text) => {
    try {
      document.dispatchEvent(new CustomEvent(name, { detail: { kind, text: String(text == null ? "" : text).slice(0, 1000) } }));
    } catch {
      // nothing to tell before the document exists
    }
  };
  window.alert = function (text) {
    tell("alert", text);
  };
  window.confirm = function (text) {
    tell("confirm", text);
    return false;
  };
  window.prompt = function (text) {
    tell("prompt", text);
    return null;
  };
  const add = EventTarget.prototype.addEventListener;
  EventTarget.prototype.addEventListener = function (type, listener, options) {
    if (this === window && String(type).toLowerCase() === "beforeunload") return;
    return add.call(this, type, listener, options);
  };
  Object.defineProperty(window, "onbeforeunload", { configurable: false, get: () => null, set: () => {} });
}
