// The floating bar (docs/chrome.md, "While a taf works"): while a taf works in this Chrome, the tab in front of each window
// shows a small bar of the extension's own: on the run's own tab, at the top, who is working there ("Rae is working here"),
// Pause or Resume, and Stop; on any other tab, at the foot, "Rae is working", Go to its tab, Pause and Stop. It's put in only
// while a run lives, and taken away when the last one ends.
//
// It sits in a closed shadow root on a plain element with no name, class or attribute of ours, so the page can't read its words,
// change what they say or press it (it can hide it, as anything on a page; the side panel is the trusted Stop); a read never includes it, and an act's "is it on top" check looks through it (page.js). Only a real press (`isTrusted`) counts, and what it sends names the run: the service
// worker checks it came from the extension's own content script, and the runner acts only on a run it holds. The page can
// remove the element: while the bar should show, it's put back.

((root) => {
  if (root.TafsBar) return;

  let host = null;
  let watch = null;
  let parts = null;
  let state = null;

  function send(act) {
    if (!state || typeof state.run !== "string") return;
    try {
      chrome.runtime.sendMessage({ type: "bar", act, run: state.run });
    } catch {
      // the extension is reloading: nothing to tell
    }
  }

  function button(act) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = act;
    b.addEventListener("click", (e) => {
      if (!e.isTrusted) return;
      send(b.dataset.act || act);
    });
    return b;
  }

  function make() {
    host = document.createElement("div");
    const shadow = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent =
      ":host{all:initial;position:fixed;inset:0;z-index:2147483647;pointer-events:none;display:block}" +
      ".pill{position:absolute;left:50%;bottom:16px;transform:translateX(-50%);pointer-events:auto;display:flex;align-items:center;gap:8px;max-width:min(92vw,560px);padding:6px 6px 6px 12px;" +
      "border-radius:999px;background:#1b1b1f;color:#fff;font:600 13px/1.2 system-ui,sans-serif;border:1.5px solid #F29A2E;" +
      "box-shadow:0 6px 24px rgba(0,0,0,.35)}" +
      ".pill.top{top:12px;bottom:auto}" +
      ".dot{flex:none;width:9px;height:9px;border-radius:50%;background:#F29A2E;animation:p 1.2s ease-in-out infinite}" +
      ".paused .dot{background:#9a9aa3;animation:none}" +
      "@keyframes p{50%{opacity:.35}}" +
      ".words{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}" +
      "button{flex:none;font:600 12px/1 system-ui,sans-serif;padding:6px 10px;border-radius:999px;border:0;cursor:pointer;" +
      "background:#2c2c33;color:#fff}" +
      "button.stop{background:#F29A2E;color:#1b1b1f}" +
      "button[hidden]{display:none}" +
      "@media (prefers-reduced-motion:reduce){.dot{animation:none}}";
    const pill = document.createElement("div");
    pill.className = "pill";
    pill.setAttribute("role", "status");
    const dot = document.createElement("span");
    dot.className = "dot";
    const words = document.createElement("span");
    words.className = "words";
    const go = button("go");
    const toggle = button("pause");
    const stop = button("stop");
    pill.append(dot, words, go, toggle, stop);
    shadow.append(style, pill);
    parts = { pill, words, go, toggle, stop };
  }

  /**
   * Shows the bar for run `s.run`: `s.words` (the extension's words, "Rae is working here", "Rae is working" or "Rae is paused"),
   * `s.here` (this is the run's own tab: no Go to its tab, and the bar sits at the top), `s.paused`, and `s.labels` {go, pause,
   * resume, stop}. Anything else isn't a bar.
   */
  function show(s) {
    const l = s && s.labels;
    if (!s || typeof s.run !== "string" || typeof s.words !== "string" || !l || ["go", "pause", "resume", "stop"].some((k) => typeof l[k] !== "string")) {
      return { ok: false };
    }
    state = { run: s.run };
    if (!host) make();
    parts.words.textContent = s.words;
    parts.pill.classList.toggle("paused", !!s.paused);
    parts.pill.classList.toggle("top", !!s.here);
    parts.go.textContent = l.go;
    parts.go.hidden = !!s.here;
    parts.toggle.textContent = s.paused ? l.resume : l.pause;
    parts.toggle.dataset.act = s.paused ? "resume" : "pause";
    // Resume gives power back, so it's offered only on the run's own tab: on another page, which could lay a lure over it,
    // the bar says the taf is paused, with Go to its tab (the runner refuses a Resume from elsewhere too).
    parts.toggle.hidden = !!s.paused && !s.here;
    parts.stop.textContent = l.stop;
    if (!host.isConnected) document.documentElement.appendChild(host);
    if (!watch) {
      watch = new MutationObserver(() => {
        if (host && state && !host.isConnected) document.documentElement.appendChild(host);
      });
      watch.observe(document.documentElement, { childList: true });
    }
    return { ok: true };
  }

  function hide() {
    state = null;
    if (watch) watch.disconnect();
    watch = null;
    if (host) host.remove();
    return true;
  }

  /** Whether [node] is this bar's own element: by identity, never by its name, which any page can give an element of its own. */
  function isOurs(node) {
    return !!host && node === host;
  }

  root.TafsBar = { show, hide, isOurs };
})(globalThis);
