// The content script: reads a run's tab as the taf sees it, and does one act on it (docs/chrome.md, "Reading a page" and
// "Acting"). It runs in Chrome's isolated world, so the page can't see its refs or call it. It never focuses a field the
// owner is in, never submits a form by itself, and never reads a secret field's value. It reports an owner's own input in
// the tab, which pauses the run.
//
// A classic script (no import or export): the service worker injects mask.js, then this file, then calls
// `TafsPage.read()` or `TafsPage.act(act, readId)`.

(function (root) {
  if (root.TafsPage) return;
  const M = root.TafsMask;

  const MAX_TEXT = 20000;
  const MAX_LINKS = 200;
  const MAX_FIELDS = 200;
  const MAX_CONTROLS = 200;
  const MAX_OPTIONS = 50;

  // What the last read named: each ref's element, held only here.
  let refs = new Map();
  let current = null; // {read_id, href}
  let dialog = null; // the last dialog the page raised since the last read

  function newId() {
    const b = crypto.getRandomValues(new Uint8Array(12));
    return "rd_" + Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  }

  function clean(text, max = 200) {
    return String(text || "").replace(/\s+/g, " ").trim().slice(0, max);
  }

  function visible(el) {
    if (!el.isConnected) return false;
    const style = getComputedStyle(el);
    if (style.visibility === "hidden" || style.display === "none" || Number(style.opacity) === 0) return false;
    const rects = el.getClientRects();
    return rects.length > 0 && Array.from(rects).some((r) => r.width > 0 && r.height > 0);
  }

  function labelOf(el) {
    const aria = el.getAttribute("aria-label");
    if (aria) return clean(aria);
    const by = el.getAttribute("aria-labelledby");
    if (by) {
      const text = by.split(/\s+/).map((id) => document.getElementById(id)).filter(Boolean).map((n) => n.innerText).join(" ");
      if (clean(text)) return clean(text);
    }
    if (el.labels && el.labels.length) return clean(Array.from(el.labels).map((l) => l.innerText).join(" "));
    if (el.placeholder) return clean(el.placeholder);
    if (el.title) return clean(el.title);
    return clean(el.name || el.id || "");
  }

  const SECRET_NAMES = /(otp|one.?time|2fa|mfa|totp|verification.?code|security.?code|auth.?code|passcode|pin\b)/i;
  const CARD_NAMES = /(card.?number|cardnum|cc.?num|cc-?number|cvc|cvv|csc|card.?code|expir|exp.?date|cc-?exp)/i;

  /** A field's kind, as the extension tells fields apart: planque's set (`wireKind`), plus `username` and `radio`, which a
   * sealed value and a check need. */
  function kindOf(el) {
    const tag = el.tagName.toLowerCase();
    const type = (el.getAttribute("type") || "text").toLowerCase();
    const auto = (el.getAttribute("autocomplete") || "").toLowerCase();
    const named = (el.name || "") + " " + (el.id || "") + " " + (el.getAttribute("aria-label") || "");
    if (tag === "select") return "select";
    if (tag === "textarea") return "textarea";
    if (type === "password" || /current-password|new-password/.test(auto)) return "password";
    if (/cc-number|cc-csc|cc-exp/.test(auto) || CARD_NAMES.test(named)) return "card";
    if (auto.includes("one-time-code") || SECRET_NAMES.test(named)) return "code";
    if (auto.includes("username")) return "username";
    if (["email", "tel", "number", "search", "checkbox", "radio", "url", "date"].includes(type)) return type;
    if (type === "text") return "text";
    return "other";
  }

  /** A field's kind in planque's set: text, email, tel, number, search, select, checkbox, textarea, password, card, code
   * (a one-time code), or other. */
  function wireKind(kind) {
    if (kind === "username") return "text";
    if (kind === "radio") return "checkbox";
    if (kind === "url" || kind === "date") return "other";
    return kind;
  }

  const FIELD_SKIP = new Set(["hidden", "submit", "button", "image", "reset", "file"]);

  function fieldsOf() {
    const out = [];
    for (const el of document.querySelectorAll("input, textarea, select")) {
      if (out.length >= MAX_FIELDS) break;
      const type = (el.getAttribute("type") || "").toLowerCase();
      if (el.tagName === "INPUT" && FIELD_SKIP.has(type)) continue;
      if (!visible(el)) continue;
      const kind = kindOf(el);
      const ref = "f" + (out.length + 1);
      refs.set(ref, el);
      const field = { ref, label: labelOf(el), kind: wireKind(kind) };
      if (kind === "password" || kind === "card" || kind === "code") {
        field.masked = true;
      } else if (kind === "checkbox" || kind === "radio") {
        field.value = el.checked ? "checked" : "";
      } else if (kind === "select") {
        field.value = clean(el.selectedOptions[0] ? el.selectedOptions[0].text : "");
        field.options = Array.from(el.options).slice(0, MAX_OPTIONS).map((o) => clean(o.text, 100));
      } else {
        field.value = M.maskText(clean(el.value, 500));
      }
      if (el.disabled) field.disabled = true;
      out.push(field);
    }
    return out;
  }

  const CONTROLS = "button, input[type=submit], input[type=button], input[type=image], [role=button]";

  // A payment provider's own button, by the marks providers put on it: a fact in no language, as planque's `places_order`.
  const PROVIDER_BUTTON = /(paypal-button|apple-pay|applepay|gpay-button|google-pay|amazonpay|shop-pay|klarna-payment|stripe-payment|payment-request-button)/i;

  /** Whether a control places an order or pays, from the page's semantics: a payment provider's button, the submit of a
   * form with a card field, or a submit the page's structured data marks as an order's. The words are planque's fallback. */
  function placesOrder(el, submits) {
    const marks = (el.id || "") + " " + (typeof el.className === "string" ? el.className : "") + " " + (el.getAttribute("data-testid") || "");
    if (PROVIDER_BUTTON.test(marks)) return true;
    if (submits && el.form) {
      const card = Array.from(el.form.querySelectorAll("input")).some((i) => /cc-number|cc-csc|cc-exp/.test((i.getAttribute("autocomplete") || "").toLowerCase()));
      if (card) return true;
      if (el.form.closest('[itemtype*="schema.org/Order"], [itemtype*="schema.org/BuyAction"]')) return true;
    }
    return false;
  }

  function controlsOf() {
    const out = [];
    for (const el of document.querySelectorAll(CONTROLS)) {
      if (out.length >= MAX_CONTROLS) break;
      if (!visible(el)) continue;
      const ref = "c" + (out.length + 1);
      refs.set(ref, el);
      const label = clean(el.getAttribute("aria-label") || el.innerText || el.value || el.title || "");
      const submits = (el.tagName === "BUTTON" && (el.type || "submit") === "submit" && !!el.form) || (el.tagName === "INPUT" && el.type === "submit");
      const control = { ref, label: M.maskText(label), submits };
      if (placesOrder(el, submits)) control.places_order = true;
      out.push(control);
    }
    return out;
  }

  function linksOf() {
    const out = [];
    for (const el of document.querySelectorAll("a[href]")) {
      if (out.length >= MAX_LINKS) break;
      if (!visible(el)) continue;
      const url = M.maskURL(el.href);
      if (!url) continue;
      const ref = "l" + (out.length + 1);
      refs.set(ref, el);
      out.push({ ref, label: M.maskText(clean(el.innerText || el.getAttribute("aria-label") || el.title || "")), url });
    }
    return out;
  }

  function paymentFrame() {
    return Array.from(document.querySelectorAll("iframe[src]")).some((f) =>
      /(js\.stripe\.com|checkout\.stripe\.com|paypal\.com|adyen\.com|braintreegateway\.com|checkout\.com|klarna\.com|pay\.google\.com|apple\.com\/apple-pay)/i.test(f.src),
    );
  }

  function bare(href) {
    try {
      const u = new URL(href);
      return u.origin + u.pathname;
    } catch {
      return "";
    }
  }

  /** The page as the taf reads it, with fresh refs and a new read id. Earlier refs stop working. */
  function read() {
    refs = new Map();
    const text = M.maskText((document.body ? document.body.innerText : "").replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n").trim());
    const page = {
      read_id: newId(),
      url: bare(location.href),
      title: M.maskText(clean(document.title, 300)),
      text: text.length > MAX_TEXT ? text.slice(0, MAX_TEXT) + "\n(cut)" : text,
      fields: fieldsOf(),
      controls: controlsOf(),
      links: linksOf(),
    };
    if (paymentFrame()) page.payment_frame = true;
    if (dialog) page.dialog = dialog;
    dialog = null;
    current = { read_id: page.read_id, href: location.href };
    return page;
  }

  /** Whether `el` is what a person would hit at its centre: on screen and not under another element. */
  function onTop(el) {
    el.scrollIntoView({ block: "center", inline: "center", behavior: "instant" });
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    const x = r.left + r.width / 2;
    const y = r.top + r.height / 2;
    if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) return false;
    const hit = document.elementFromPoint(x, y);
    return !!hit && (hit === el || el.contains(hit) || (el.labels && Array.from(el.labels).some((l) => l.contains(hit))));
  }

  function setValue(el, value) {
    const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
    setter.call(el, value);
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function press(el) {
    const r = el.getBoundingClientRect();
    const at = { bubbles: true, cancelable: true, composed: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0 };
    el.dispatchEvent(new PointerEvent("pointerdown", at));
    el.dispatchEvent(new MouseEvent("mousedown", at));
    el.dispatchEvent(new PointerEvent("pointerup", at));
    el.dispatchEvent(new MouseEvent("mouseup", at));
    el.click();
  }

  /**
   * Does one act (`{kind, ref, value?}`) planned from read `readId`, or says why not: `{ok: true}` or `{ok: false, code}`.
   * The service worker reads the page back afterwards. A sealed value is filled by `fillSealed`, never here.
   */
  function act(a, readId) {
    if (!current || current.read_id !== readId || current.href !== location.href) return { ok: false, code: "page_changed" };
    if (dialog) return { ok: false, code: "dialog_open" };
    const el = refs.get(a.ref);
    if (!el || !el.isConnected) return { ok: false, code: "ref_unknown" };
    if (!visible(el) || !onTop(el)) return { ok: false, code: "not_visible" };
    if (el.disabled) return { ok: false, code: "disabled" };
    const kind = el.matches("input, textarea, select") ? kindOf(el) : null;
    if (kind === "password" || kind === "card" || kind === "code") return { ok: false, code: "secret_field" };
    switch (a.kind) {
      case "click":
        press(el);
        break;
      case "fill":
        if (!el.matches("input, textarea") || kind === "checkbox" || kind === "radio") return { ok: false, code: "bad_step" };
        setValue(el, String(a.value));
        break;
      case "select": {
        if (el.tagName !== "SELECT") return { ok: false, code: "bad_step" };
        const option = Array.from(el.options).find((o) => clean(o.text, 100) === a.value || o.value === a.value);
        if (!option) return { ok: false, code: "option_unknown" };
        el.value = option.value;
        el.dispatchEvent(new Event("input", { bubbles: true }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
        break;
      }
      case "check":
        if (kind !== "checkbox" && kind !== "radio") return { ok: false, code: "bad_step" };
        if (el.checked !== (a.value !== "unchecked")) press(el);
        break;
      default:
        return { ok: false, code: "bad_step" };
    }
    current = null; // the next act needs a new read
    return { ok: true };
  }

  /** Fills a sealed value into a password, username or code field on this exact origin (slice 5, after the phone's release). */
  function fillSealed(ref, readId, origin, value) {
    if (!current || current.read_id !== readId || current.href !== location.href) return { ok: false, code: "page_changed" };
    if (location.origin !== origin || window.top !== window) return { ok: false, code: "secret_field" };
    const el = refs.get(ref);
    if (!el || !el.isConnected || el.tagName !== "INPUT") return { ok: false, code: "ref_unknown" };
    const kind = kindOf(el);
    if (kind !== "password" && kind !== "username" && kind !== "code") return { ok: false, code: "secret_field" };
    if (!visible(el) || !onTop(el)) return { ok: false, code: "not_visible" };
    setValue(el, value);
    current = null;
    return { ok: true };
  }

  // The owner's own input in the run's tab pauses the run: a real keystroke, click or touch (`isTrusted`), never ours.
  let told = false;
  function ownerInput(e) {
    if (!e.isTrusted || told) return;
    told = true;
    try {
      chrome.runtime.sendMessage({ type: "owner_input" });
    } catch {
      // the extension is reloading: nothing to tell
    }
    setTimeout(() => (told = false), 2000);
  }
  for (const type of ["keydown", "pointerdown", "wheel"]) addEventListener(type, ownerInput, { capture: true, passive: true });

  // Dialogs the page raised (alert, confirm, prompt), reported by dialogs.js from the page's world under this tab's token.
  // None blocks: confirm answers no, prompt answers nothing. A read reports the last one; an act after one is refused.
  let listening = false;
  function listen(token) {
    if (listening || typeof token !== "string" || token.length < 16) return;
    listening = true;
    document.addEventListener("tafs-dialog-" + token, (e) => {
      const d = e.detail || {};
      dialog = { kind: clean(d.kind, 20), text: M.maskText(clean(d.text, 500)) };
    });
  }
  root.TafsPage = { read, act, fillSealed, kindOf, listen };
})(globalThis);
