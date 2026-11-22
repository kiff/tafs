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
  // What each ref's element was when it was read (its tag, kind, words and target): an act on a ref whose element has since
  // changed into something else is refused, not done.
  let prints = new Map();
  let current = null; // {read_id, href}
  let dialog = null; // the last dialog the page raised since the last read

  /** What identifies an element in a read: its tag, type, name, label, words and link target. A fill or a tick changes a
   * field's value, never these, so they hold from the read to the act. */
  function printOf(el) {
    const words = el.matches("input, textarea, select") ? "" : clean(el.innerText || el.getAttribute("aria-label") || el.title || "", 120);
    // The row it sits in (a list item, a table row, a card): a re-sorted list of identical "Add" buttons must not send an act
    // meant for one row to another. The row's words, short and hashed, so the print stays small.
    const row = el.closest("li, tr, article, [role=row], [role=listitem]");
    return [el.tagName, el.getAttribute("type") || "", el.getAttribute("name") || el.id || "", labelOf(el), words, el.getAttribute("href") || "", row ? digest(clean(row.innerText, 400)) : ""].join("\u0001");
  }

  /** A short fingerprint of a text (FNV-1a, 32 bits): a change detector, never a secret. */
  function digest(text) {
    let h = 0x811c9dc5;
    for (let i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i);
      h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h.toString(16);
  }

  /** Names `el` in this read. */
  function bind(ref, el) {
    refs.set(ref, el);
    prints.set(ref, printOf(el));
  }

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

  // The fields a sealed value went into: read as masked from then on, whatever their kind, so a username filled into a
  // text or email field never goes back up in a read (qa-kiff's gate on sealed sign-ins).
  const sealedFilled = new WeakSet();

  function fieldsOf() {
    const out = [];
    for (const el of document.querySelectorAll("input, textarea, select")) {
      if (out.length >= MAX_FIELDS) break;
      const type = (el.getAttribute("type") || "").toLowerCase();
      if (el.tagName === "INPUT" && FIELD_SKIP.has(type)) continue;
      if (!visible(el)) continue;
      const kind = kindOf(el);
      const ref = "f" + (out.length + 1);
      bind(ref, el);
      const field = { ref, label: labelOf(el), kind: wireKind(kind) };
      if (kind === "password" || kind === "card" || kind === "code" || sealedFilled.has(el)) {
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
      bind(ref, el);
      const label = clean(el.getAttribute("aria-label") || el.innerText || el.value || el.title || "");
      const submits = (el.tagName === "BUTTON" && (el.type || "submit") === "submit" && !!el.form) || (el.tagName === "INPUT" && el.type === "submit");
      const control = { ref, label: M.maskText(label), submits };
      if (placesOrder(el, submits)) control.places_order = true;
      out.push(control);
    }
    return out;
  }

  /**
   * A link that acts rather than goes somewhere: a script handler, a button's role, or a URL that is this page plus a
   * fragment. It's offered as a control, so the pay and checkout rules apply to it as to a button.
   */
  function actsAsControl(el) {
    if (el.matches("[role=button], [onclick]")) return true;
    try {
      const to = new URL(el.href);
      const here = new URL(location.href);
      return to.origin === here.origin && to.pathname === here.pathname && to.search === here.search;
    } catch {
      return true;
    }
  }

  function linksOf(controls) {
    const out = [];
    for (const el of document.querySelectorAll("a[href]")) {
      if (out.length >= MAX_LINKS) break;
      if (!visible(el)) continue;
      if (el.matches(CONTROLS)) continue; // already a control
      if (actsAsControl(el)) {
        if (controls.length >= MAX_CONTROLS) continue;
        const ref = "c" + (controls.length + 1);
        bind(ref, el);
        const control = { ref, label: M.maskText(clean(el.innerText || el.getAttribute("aria-label") || el.title || "")), submits: false };
        if (placesOrder(el, false)) control.places_order = true;
        controls.push(control);
        continue;
      }
      const url = M.maskURL(el.href);
      if (!url) continue;
      const ref = "l" + (out.length + 1);
      bind(ref, el);
      out.push({ ref, label: M.maskText(clean(el.innerText || el.getAttribute("aria-label") || el.title || "")), url });
    }
    return out;
  }

  function paymentFrame() {
    return Array.from(document.querySelectorAll("iframe[src]")).some((f) =>
      /(js\.stripe\.com|checkout\.stripe\.com|paypal\.com|adyen\.com|braintreegateway\.com|checkout\.com|klarna\.com|pay\.google\.com|apple\.com\/apple-pay)/i.test(f.src),
    );
  }

  // ---- A checkout, as the page shows it: its lines, its total and the total's currency (docs/chrome.md, "Steps"). The
  // facts planque pays and holds on, so they must be the same ones planque's own browser reads: one amount next to a
  // "total" word in one currency (two different candidates, or none, is no total), and the rows with an amount in that
  // currency that aren't a total, a subtotal, shipping, tax or a discount. Numbers are decimal strings ("12.50").
  const CODES = "EUR|USD|GBP|CHF|SEK|NOK|DKK|PLN|CZK|HUF|JPY|CAD|AUD|BRL|MXN";
  const NUM = "\\d{1,3}(?:[.,\\s]\\d{3})*(?:[.,]\\d{2})?";
  const MONEY = new RegExp("(?:(" + CODES + ")|([€$£]))\\s?(" + NUM + ")|(" + NUM + ")\\s?(?:(" + CODES + ")|([€$£]))", "g");
  const SYMBOL = { "€": "EUR", $: "USD", "£": "GBP" };
  const TOTAL_WORD = /\b(total|totale|gesamt(?:betrag|summe|preis)?|summe|totaal|montant total|importe total|a pagar|à payer|zu zahlen|da pagare|te betalen|to pay|amount due)\b/i;
  const STRONG_TOTAL = /grand total|order total|total to pay|to pay|a pagar|à payer|zu zahlen|da pagare|te betalen|importe total|montant total|gesamtbetrag|amount due/i;
  const NOT_A_LINE = /\b(sub.?total|total|totale|gesamt|summe|totaal|montant|importe|shipping|delivery|livraison|versand|envío|envio|spedizione|verzend|tax|vat|tva|mwst|iva|btw|discount|coupon|rabatt|remise|descuento|sconto|korting|desconto)\b/i;
  const QTY = /(?:×|x|qty\.?|quantity|menge|cantidad|quantité|quantità|aantal|qtd)\s*:?\s*(\d{1,4})|(\d{1,4})\s*(?:×|x)\b/i;

  /** "1.234,50" and "1,234.50" are 1234.50: the last separator before two digits is the decimal one. */
  function normalizeAmount(text) {
    const t = text.replace(/\s/g, "");
    const m = t.match(/^(.*?)([.,])(\d{2})$/);
    const whole = (m ? m[1] : t).replace(/[.,]/g, "");
    return whole + "." + (m ? m[3] : "00");
  }

  function amountsIn(text) {
    const out = [];
    for (const m of text.matchAll(MONEY)) {
      const currency = m[1] || SYMBOL[m[2]] || m[5] || SYMBOL[m[6]];
      const amount = m[3] || m[4];
      if (currency && amount) out.push({ amount: normalizeAmount(amount), currency });
    }
    return out;
  }

  function checkoutOf() {
    if (!document.body) return null;
    const found = [];
    let seen = 0;
    for (const el of document.body.querySelectorAll("*")) {
      if (++seen > 6000) break;
      if (["SCRIPT", "STYLE", "INPUT", "TEXTAREA", "SELECT", "OPTION"].includes(el.tagName) || !visible(el)) continue;
      const text = clean(el.textContent, 400);
      if (text.length > 160 || !TOTAL_WORD.test(text)) continue;
      let candidates = amountsIn(text);
      if (!candidates.length && el.parentElement) candidates = amountsIn(clean(el.parentElement.textContent, 240));
      if (!candidates.length && el.nextElementSibling) candidates = amountsIn(clean(el.nextElementSibling.textContent, 160));
      if (!candidates.length) continue;
      // A row naming several amounts (a subtotal and a total) gives its last.
      found.push({ ...candidates[candidates.length - 1], strong: STRONG_TOTAL.test(text) });
    }
    const distinct = (list) => [...new Set(list.map((a) => a.amount + " " + a.currency))];
    const strong = found.filter((a) => a.strong);
    const total = distinct(strong).length === 1 ? strong[0] : distinct(found).length === 1 ? found[0] : null;
    if (!total) return null;
    const lines = [];
    const had = new Set();
    for (const row of document.querySelectorAll("tr, li, [class*=line], [class*=item]")) {
      if (lines.length >= 50) break;
      if (!visible(row) || row.querySelector("tr, li")) continue;
      // Cells joined with a separator: a row's text runs them together ("Blue pen × 2" and "2.50" would read as 22.50).
      const text = row.children.length > 1 ? Array.from(row.children).map((c) => clean(c.textContent, 200)).filter(Boolean).join(" | ") : clean(row.textContent, 200);
      if (!text || text.length > 200 || NOT_A_LINE.test(text)) continue;
      const priced = amountsIn(text).filter((a) => a.currency === total.currency);
      if (!priced.length) continue;
      const price = priced[priced.length - 1];
      const q = text.match(QTY);
      const quantity = q ? parseInt(q[1] || q[2], 10) : 1;
      const item = clean(text.replace(MONEY, "").replace(QTY, "").replace(/^[\s—–\-:|,]+|[\s—–\-:|,]+$/g, ""), 120);
      if (!item) continue;
      const key = item + "|" + quantity + "|" + price.amount;
      if (had.has(key)) continue;
      had.add(key);
      lines.push({ item: M.maskText(item), quantity, price: price.amount });
    }
    return { lines, total: total.amount, currency: total.currency };
  }

  function bare(href) {
    try {
      const u = new URL(href);
      return u.origin + u.pathname;
    } catch {
      return "";
    }
  }

  /**
   * The page as the taf reads it, with fresh refs and a new read id. Earlier refs stop working. A page's text comes in
   * windows of MAX_TEXT characters of its masked text: `offset` asks for a later one, and a page that goes on says so
   * (`more`, and the `next_offset` to ask for). Fields, controls and links are the page's whole, in every window.
   */
  function read(offset) {
    refs = new Map();
    prints = new Map();
    const text = M.maskText((document.body ? document.body.innerText : "").replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n").trim());
    const start = Number.isSafeInteger(offset) && offset > 0 ? Math.min(offset, text.length) : 0;
    // A window never ends inside a surrogate pair (an emoji): it stops one short, and the next starts with the whole character.
    let end = Math.min(start + MAX_TEXT, text.length);
    if (end < text.length) {
      const last = text.charCodeAt(end - 1);
      if (last >= 0xd800 && last <= 0xdbff) end -= 1;
    }
    const page = {
      read_id: newId(),
      url: bare(location.href),
      title: M.maskText(clean(document.title, 300)),
      text: text.slice(start, end),
      fields: fieldsOf(),
      controls: controlsOf(),
    };
    if (start > 0) page.offset = start;
    if (end < text.length) {
      page.more = true;
      page.next_offset = end;
    }
    page.links = linksOf(page.controls);
    if (paymentFrame()) page.payment_frame = true;
    const checkout = checkoutOf();
    if (checkout) page.checkout = checkout;
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
    if (!el || !el.isConnected || prints.get(a.ref) !== printOf(el)) return { ok: false, code: "ref_unknown" };
    if (!visible(el) || !onTop(el)) return { ok: false, code: "not_visible" };
    if (el.disabled) return { ok: false, code: "disabled" };
    const kind = el.matches("input, textarea, select") ? kindOf(el) : null;
    if (kind === "password" || kind === "card" || kind === "code") return { ok: false, code: "secret_field" };
    switch (a.kind) {
      case "click":
        // A link or form that would open a new tab goes in this one, inside the run's fence.
        if (el.closest("a[target], form[target]")) el.closest("a[target], form[target]").target = "_self";
        if (el.form && el.form.target) el.form.target = "_self";
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
  function fillSealed(ref, readId, origin, value, part) {
    if (!current || current.read_id !== readId || current.href !== location.href) return { ok: false, code: "page_changed" };
    if (location.origin !== origin || window.top !== window) return { ok: false, code: "secret_field" };
    const el = refs.get(ref);
    if (!el || !el.isConnected || el.tagName !== "INPUT" || prints.get(ref) !== printOf(el)) return { ok: false, code: "ref_unknown" };
    const kind = kindOf(el);
    const fits = part === "password" ? kind === "password" : part === "username" ? ["username", "text", "email"].includes(kind) : false;
    if (!fits) return { ok: false, code: "secret_field" };
    if (!visible(el) || !onTop(el)) return { ok: false, code: "not_visible" };
    sealedFilled.add(el);
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
  // The boxes put over secret fields while a screenshot is taken (docs/chrome.md, "Screenshots of a run's acts").
  let shotBoxes = [];

  /** Every field whose value a picture must not show: a password, card or one-time-code field, one the extension filled from a
   * sealed sign-in, and a text field whose value is a secret by its shape (a key, a card number). The same rules as a read's. */
  function secretFields() {
    const out = [];
    for (const el of document.querySelectorAll("input, textarea, select")) {
      if (el.tagName === "INPUT" && FIELD_SKIP.has((el.getAttribute("type") || "").toLowerCase())) continue;
      if (!visible(el)) continue;
      const kind = kindOf(el);
      const value = el.tagName === "SELECT" ? "" : String(el.value || "");
      if (kind === "password" || kind === "card" || kind === "code" || sealedFilled.has(el) || (value && M.maskText(value) !== value)) out.push(el);
    }
    return out;
  }

  /**
   * Puts an opaque box over each secret field, and says whether every one is covered: `{ok, masked}`. `ok` is false when a
   * field's middle shows something that isn't its box (the picture would show the field), and then nothing is captured.
   */
  function maskForShot() {
    unmaskShot();
    const fields = secretFields();
    for (const el of fields) {
      const r = el.getBoundingClientRect();
      const box = document.createElement("div");
      box.setAttribute("data-tafs-shot-mask", "");
      box.style.cssText = `position:fixed;left:${r.left - 2}px;top:${r.top - 2}px;width:${r.width + 4}px;height:${r.height + 4}px;background:#000;z-index:2147483647;margin:0;padding:0;border:0;`;
      document.documentElement.append(box);
      shotBoxes.push(box);
    }
    let ok = true;
    for (const el of fields) {
      const r = el.getBoundingClientRect();
      const x = r.left + r.width / 2;
      const y = r.top + r.height / 2;
      // A field scrolled out of the picture isn't in it.
      if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) continue;
      const top = document.elementFromPoint(x, y);
      if (!top || !top.hasAttribute("data-tafs-shot-mask")) ok = false;
    }
    return { ok, masked: fields.length };
  }

  /** Takes the boxes away again. Safe to call twice. */
  function unmaskShot() {
    for (const box of shotBoxes) box.remove();
    shotBoxes = [];
    for (const stray of document.querySelectorAll("[data-tafs-shot-mask]")) stray.remove();
    return true;
  }

  root.TafsPage = { read, act, fillSealed, kindOf, listen, maskForShot, unmaskShot };
})(globalThis);
