// Coach reads the page in front (docs/chrome.md, "Coach reads the page you're on"): when the person sends Coach a message
// from the side panel, the tab in front of the panel's window is read once, here, and goes with their words. Only this one
// read, on their send: nothing here listens, watches or stays behind.
//
// What it takes is the page's masked text, as a run's read masks it (mask.js): never a field's value or anything typed into an
// editable region (those are hidden for the moment the text is read), no secret shape (a key, a card number, a labelled
// secret), and an address with no query or fragment. At most MAX_BYTES of UTF-8, with `truncated` when the page goes on.

((root) => {
  const MAX_CHARS = 20000;
  const MAX_BYTES = 20000;
  const M = root.TafsMask;

  function bare(href) {
    try {
      const u = new URL(href);
      return u.origin + u.pathname;
    } catch {
      return "";
    }
  }

  /** The page's text with every editable region left out: what the person typed there is theirs, never Coach's. */
  function visibleText() {
    if (!document.body) return "";
    const hidden = [];
    for (const el of document.querySelectorAll('[contenteditable]:not([contenteditable="false"]), [role="textbox"], textarea')) {
      hidden.push([el, el.style.getPropertyValue("display"), el.style.getPropertyPriority("display")]);
      el.style.setProperty("display", "none", "important");
    }
    try {
      return document.body.innerText;
    } finally {
      for (const [el, display, priority] of hidden) {
        if (display) el.style.setProperty("display", display, priority);
        else el.style.removeProperty("display");
      }
    }
  }

  /** The page, once: `{url, title, text, truncated}`, all masked; text at most MAX_CHARS and MAX_BYTES. */
  function read() {
    const all = M.maskText(visibleText().replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n").trim());
    let end = Math.min(MAX_CHARS, all.length);
    const utf8 = new TextEncoder();
    for (let over = utf8.encode(all.slice(0, end)).length - MAX_BYTES; over > 0 && end > 0; over = utf8.encode(all.slice(0, end)).length - MAX_BYTES) {
      end -= Math.max(1, Math.ceil(over / 3));
    }
    if (end > 0 && end < all.length) {
      const last = all.charCodeAt(end - 1);
      if (last >= 0xd800 && last <= 0xdbff) end -= 1;
    }
    return {
      url: M.maskURL(bare(location.href)) || bare(location.href),
      title: M.maskText(String(document.title || "").replace(/\s+/g, " ").trim().slice(0, 300)),
      text: all.slice(0, end),
      truncated: end < all.length,
    };
  }

  root.TafsAside = { read };
})(globalThis);
