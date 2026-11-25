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
  // A text window's budget in UTF-8 bytes, as the relay counts an answer: 20,000 characters of Japanese are 60 KB.
  const MAX_TEXT_BYTES = 20000;
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

  // One ref per element for the life of this document (gap 1): a read never renumbers what an earlier read named, so a ref
  // the taf holds names the same element until it goes or becomes something else. Counters never go back within a document;
  // a new document is a new script, which starts again from 1.
  const named = new WeakMap(); // element → {ref, print}
  let previous = new Map(); // the last read's refs, while the next read names
  let previousPrints = new Map();
  const counters = { f: 0, c: 0, l: 0 };

  /** Names `el` in this read, as `kind` ("f", "c" or "l"): its ref from an earlier read if it's still what it was, else a new one. */
  function bind(kind, el) {
    const print = printOf(el);
    const had = named.get(el);
    let ref;
    if (had && had.print === print && had.ref[0] === kind && !refs.has(had.ref)) ref = had.ref;
    else {
      // A page that draws an element again (a framework re-rendering a list) puts a new node where the old one was: the
      // ref of a node the last read named, now gone from the page, that was exactly this, is this one's.
      ref = null;
      for (const [r, old] of previous) {
        if (r[0] === kind && !refs.has(r) && !old.isConnected && previousPrints.get(r) === print) {
          ref = r;
          previous.delete(r);
          break;
        }
      }
      if (!ref) ref = kind + ++counters[kind];
      named.set(el, { ref, print });
    }
    refs.set(ref, el);
    prints.set(ref, print);
    return ref;
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

  /** A field's kind, as the extension tells fields apart: planque's set (`wireKind`), plus `username` and `radio`. */
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
      const ref = bind("f", el);
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
      if (unusable(el)) field.disabled = true;
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
      const ref = bind("c", el);
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
        const ref = bind("c", el);
        const control = { ref, label: M.maskText(clean(el.innerText || el.getAttribute("aria-label") || el.title || "")), submits: false };
        if (placesOrder(el, false)) control.places_order = true;
        controls.push(control);
        continue;
      }
      const url = M.maskURL(el.href);
      if (!url) continue;
      const ref = bind("l", el);
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
   * The page as the taf reads it, with a new read id. An element keeps the ref an earlier read gave it while it's the same
   * element and still what it was; one that's gone or changed gets none of its old ref. A page's text comes in windows of
   * at most MAX_TEXT characters and MAX_TEXT_BYTES of UTF-8 of its masked text: `offset` asks for a later one, and a page
   * that goes on says so (`more`, and the `next_offset` to ask for). Fields, controls and links are the page's whole, in
   * every window.
   */
  /**
   * The page's text. With `withoutEditable` (the read after a hand-over), what is inside an editable region (`contenteditable`, a
   * `role=textbox`) is left out: a person's typing there is part of the text and no pattern mask is enough for it. Those regions are
   * hidden for the moment the text is read, in one step with no paint between, and put back as they were.
   */
  function bodyText(withoutEditable) {
    if (!document.body) return "";
    if (!withoutEditable) return document.body.innerText;
    const hidden = [];
    for (const el of document.querySelectorAll('[contenteditable]:not([contenteditable="false"]), [role="textbox"]')) {
      hidden.push([el, el.style.getPropertyValue("display"), el.style.getPropertyPriority("display")]);
      el.style.setProperty("display", "none", "important"); // a page's own !important can't keep it showing
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

  function read(offset, opts) {
    previous = refs;
    previousPrints = prints;
    refs = new Map();
    prints = new Map();
    const text = M.maskText(bodyText(!!(opts && opts.withoutEditable)).replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n").trim());
    const start = Number.isSafeInteger(offset) && offset > 0 ? Math.min(offset, text.length) : 0;
    // A window never ends inside a surrogate pair (an emoji): it stops one short, and the next starts with the whole character.
    let end = Math.min(start + MAX_TEXT, text.length);
    // Nor goes over its bytes: 20,000 characters of Japanese or Chinese are three times that in UTF-8.
    const utf8 = new TextEncoder();
    // Each UTF-16 unit is 1 to 3 bytes: dropping a third of the excess in units each time ends just under the budget.
    for (let over = utf8.encode(text.slice(start, end)).length - MAX_TEXT_BYTES; over > 0 && end > start; over = utf8.encode(text.slice(start, end)).length - MAX_TEXT_BYTES) {
      end -= Math.max(1, Math.ceil(over / 3));
    }
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
    // The extension's own bar (bar.js) is looked through: it's ours, and the person's buttons, not the page's.
    const hit = document.elementsFromPoint(x, y).find((h) => !ourBar(h));
    return !!hit && (hit === el || el.contains(hit) || (el.labels && Array.from(el.labels).some((l) => l.contains(hit))));
  }

  function setValue(el, value) {
    const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
    setter.call(el, value);
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  /** The keys a taf may press on a ref, and nothing else: no Tab (a key event moves no focus), no shortcuts. */
  const KEYS = new Set(["Enter", "Escape", "ArrowDown", "ArrowUp"]);
  const KEY_CODES = { Enter: 13, Escape: 27, ArrowDown: 40, ArrowUp: 38 };

  /** Presses `name` on `el` as key events; whether the page let it through (none of its handlers cancelled the keydown). */
  function key(el, name) {
    const at = { key: name, code: name, keyCode: KEY_CODES[name], which: KEY_CODES[name], bubbles: true, cancelable: true, composed: true };
    const through = el.dispatchEvent(new KeyboardEvent("keydown", at));
    if (name === "Enter") el.dispatchEvent(new KeyboardEvent("keypress", at));
    el.dispatchEvent(new KeyboardEvent("keyup", at));
    return through;
  }

  /** Types `value` into `el` one character at a time, each with its key events, as a person typing it would. */
  function typeInto(el, value) {
    const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
    setter.call(el, "");
    el.dispatchEvent(new Event("input", { bubbles: true }));
    for (const ch of value) {
      const at = { key: ch, bubbles: true, cancelable: true, composed: true };
      el.dispatchEvent(new KeyboardEvent("keydown", at));
      setter.call(el, el.value + ch);
      el.dispatchEvent(new InputEvent("input", { bubbles: true, data: ch, inputType: "insertText" }));
      el.dispatchEvent(new KeyboardEvent("keyup", at));
    }
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
   * Where an act on [el] would take the page, as an address, or null when it goes nowhere the page names: a click on a link (or
   * inside one), a click on a form's submit button (its own `formaction`, else the form's `action`), and Enter in a form's field.
   * Only http and https: a `javascript:` link, say, is a control, not an address.
   */
  function headsTo(el, a) {
    let to = null;
    if (a.kind === "click") {
      const link = el.closest("a[href]");
      const form = el.form || null;
      if (link) to = link.href;
      else if (form && el.matches("button[type=submit], button:not([type]), input[type=submit], input[type=image]")) to = (el.hasAttribute("formaction") ? el.formAction : form.action) || location.href;
    } else if (a.kind === "press" && a.value === "Enter" && el.matches("input") && el.form) {
      to = el.form.action || location.href;
    }
    if (!to) return null;
    try {
      const u = new URL(to, location.href);
      return u.protocol === "https:" || u.protocol === "http:" ? u : null;
    } catch {
      return null;
    }
  }

  /** Whether [host] is one of [sites] or under it, as the run's own check reads a host (sites.js `within`). */
  function within(host, sites) {
    return (sites || []).some((s) => typeof s === "string" && (host === s || host.endsWith("." + s)));
  }

  /**
   * Why the run's fence would refuse [url], with the very code the service worker's `refuseURL` gives (sites.js), in its order,
   * or null: `fence` is {granted, allowed, sensitive, labels, never, local} (runner.js `fenceOf`). Not https, or user info: `bad_url`;
   * an address or a local name: `address_private`; a name not in ASCII: `bad_url`; Chrome's own pages: `site_blocked`; a site no
   * grant lists: `site_not_listed`; a sensitive site the phone didn't allow by name: `site_blocked`. A unit test runs this and
   * `refuseURL` over the same addresses.
   */
  function offFence(url, fence) {
    if (url.protocol !== "https:" || !url.hostname || url.username || url.password) return "bad_url";
    const host = url.hostname.toLowerCase().replace(/[。．｡]/g, ".").replace(/\.$/, "");
    const labels = host.split(".");
    const last = labels[labels.length - 1];
    const number = last !== "" && (last.startsWith("0x") ? /^[0-9a-f]*$/.test(last.slice(2)) : /^[0-9]+$/.test(last));
    if (/^\[.*\]$/.test(host) || (/^[0-9a-f:]+$/i.test(host) && host.includes(":")) || number) return "address_private";
    if ([...host].some((c) => c.charCodeAt(0) > 0x7f)) return "bad_url";
    if (host === "localhost" || !host.includes(".") || (fence.local || []).some((s) => host.endsWith(s))) return "address_private";
    if (within(host, fence.never)) return "site_blocked";
    if (!within(host, fence.granted)) return "site_not_listed";
    let labelled = false;
    try {
      labelled = new RegExp(fence.labels).test(host);
    } catch {
      labelled = true; // a pattern this page can't read: as sensitive, refused
    }
    if ((within(host, fence.sensitive) || labelled) && !within(host, fence.allowed)) return "site_blocked";
    return null;
  }

  /**
   * Whether the page made [el] unusable (0.2.20): disabled, a fieldset's disabling included (`:disabled`); inside an `inert` part
   * of the page; or `aria-disabled="true"` on it or on an element around it. An act on it is refused `disabled`.
   */
  function unusable(el) {
    return el.matches(":disabled") || !!el.closest("[inert]") || !!el.closest('[aria-disabled="true"]');
  }

  /**
   * Does one act (`{kind, ref, value?}`) planned from read `readId`, or says why not: `{ok: true}` or `{ok: false, code}`.
   * The service worker reads the page back afterwards. Nothing here ever types a saved value: the extension keeps none.
   * `opts.fence`: what the run's fence lets the tab reach (`offFence`); a link or a submit it wouldn't is refused before anything is
   * done (`site_not_listed`, or `site_blocked` for a sensitive site or Chrome's own pages), with the address it would have gone to
   * (`url`, with no query or fragment).
   */
  function act(a, readId, opts) {
    // The outline that showed where the taf would act (runner.js, before each act) goes as the act starts.
    unoutline();
    if (!current || current.read_id !== readId || current.href !== location.href) return { ok: false, code: "page_changed" };
    if (dialog) return { ok: false, code: "dialog_open" };
    // A scroll of the page itself names no ref (gap 7): by most of a screen, so what was at the bottom stays in view.
    if (a.kind === "scroll" && !a.ref) {
      window.scrollBy(0, Math.round(window.innerHeight * 0.8) * (a.value === "up" ? -1 : 1));
      current = null;
      return { ok: true };
    }
    const el = refs.get(a.ref);
    if (!el || !el.isConnected || prints.get(a.ref) !== printOf(el)) return { ok: false, code: "ref_unknown" };
    // Unusable before unseen: an inert part of the page is also never on top, and the taf is told the page's own reason.
    if (unusable(el)) return { ok: false, code: "disabled" };
    if (!visible(el) || !onTop(el)) return { ok: false, code: "not_visible" };
    const kind = el.matches("input, textarea, select") ? kindOf(el) : null;
    if (kind === "password" || kind === "card" || kind === "code") return { ok: false, code: "secret_field" };
    if (opts && opts.fence) {
      const to = headsTo(el, a);
      const why = to && offFence(to, opts.fence);
      if (why) return { ok: false, code: why, url: bare(to.href) };
    }
    switch (a.kind) {
      case "click":
        // A link or form that would open a new tab goes in this one, inside the run's fence.
        if (el.closest("a[target], form[target]")) el.closest("a[target], form[target]").target = "_self";
        if (el.form && el.form.target) el.form.target = "_self";
        press(el);
        break;
      case "fill":
        if (!el.matches("input, textarea") || kind === "checkbox" || kind === "radio") return { ok: false, code: "bad_step" };
        // A field the page made read-only takes nothing typed, as a disabled one (0.2.20).
        if (el.readOnly) return { ok: false, code: "disabled" };
        // `keys`: typed one character at a time, with key events, for a field that opens its list as you type (a city, an
        // airport). Never a submit: Enter is a `press` of its own.
        if (a.keys === true) typeInto(el, String(a.value));
        else setValue(el, String(a.value));
        break;
      case "press": {
        if (!KEYS.has(a.value)) return { ok: false, code: "bad_step" };
        // Enter on a field submits its form as the browser would (an untrusted key event submits nothing): a form whose
        // button places an order or that takes a card is refused, as a click on that button is.
        if (a.value === "Enter" && el.matches("input") && el.form) {
          const button = el.form.querySelector('button[type=submit], button:not([type]), input[type=submit]');
          if (placesOrder(button || el, true)) return { ok: false, code: "payment_step" };
        }
        const pressed = key(el, a.value);
        if (a.value === "Enter" && pressed && el.matches("input") && el.form) {
          if (el.form.target) el.form.target = "_self";
          el.form.requestSubmit();
        }
        break;
      }
      case "scroll":
        el.scrollIntoView({ block: "center" });
        break;
      case "select": {
        if (el.tagName !== "SELECT") return { ok: false, code: "bad_step" };
        const option = Array.from(el.options).find((o) => clean(o.text, 100) === a.value || o.value === a.value);
        if (!option) return { ok: false, code: "option_unknown" };
        // A choice the page disabled (or one in a disabled group) is never chosen, and no change is told (0.2.20).
        if (option.matches(":disabled")) return { ok: false, code: "disabled" };
        el.value = option.value;
        el.dispatchEvent(new Event("input", { bubbles: true }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
        break;
      }
      case "check":
        if (kind !== "checkbox" && kind !== "radio") return { ok: false, code: "bad_step" };
        // A radio is unchecked only by choosing another of its group: asking to uncheck a checked one would answer ok and change
        // nothing (0.2.20).
        if (kind === "radio" && a.value === "unchecked" && el.checked) return { ok: false, code: "bad_step" };
        if (el.checked !== (a.value !== "unchecked")) press(el);
        break;
      default:
        return { ok: false, code: "bad_step" };
    }
    current = null; // the next act needs a new read
    return { ok: true };
  }

  // The owner's own input in the run's tab pauses the run: a real keystroke, click or touch (`isTrusted`), never ours.
  let told = false;
  function ownerInput(e) {
    if (!e.isTrusted || told) return;
    // A press on the floating bar is the person answering the run, not using the page: its buttons say so themselves.
    if (e.composedPath().some(ourBar)) return;
    told = true;
    try {
      chrome.runtime.sendMessage({ type: "owner_input" });
    } catch {
      // the extension is reloading: nothing to tell
    }
    setTimeout(() => (told = false), 2000);
  }
  for (const type of ["keydown", "pointerdown", "wheel"]) addEventListener(type, ownerInput, { capture: true, passive: true });

  // The floating bar is bar.js's own element (docs/chrome.md, "While a taf works"): looked through, and a press on it isn't
  // the person using the page. Known by identity (bar.js holds it in this isolated world), never by its name: a page can make
  // an element of that name, and it's then the page's, which pauses the run and blocks an act as any element does.
  const ourBar = (node) => !!(root.TafsBar && root.TafsBar.isOurs(node));

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
  // Set when a cap stopped the search for secrets: the covers can't be said to be complete, so nothing is pictured.
  let shotIncomplete = false;

  /** The document and every open shadow root in it: a field or a text inside one is on the page, and a plain query on the
   * document does not reach it. A closed shadow root can't be seen from the page; the picture's covers can't reach it. */
  const MAX_ROOTS = 200;
  function roots() {
    const out = [document];
    for (let i = 0; i < out.length; i++) {
      for (const el of out[i].querySelectorAll("*")) {
        if (!el.shadowRoot) continue;
        // Past the cap the rest aren't looked at, and then the picture can't say every secret is covered.
        if (out.length >= MAX_ROOTS) {
          shotIncomplete = true;
          return out;
        }
        out.push(el.shadowRoot);
      }
    }
    return out;
  }

  /** Every visible frame or embedded object: a picture shows whatever is inside one, and the page's script can't read what
   * a frame from another site holds (a login widget, a bank's form), so each is covered whole. */
  function frameRects() {
    const rects = [];
    for (const root of roots()) {
      for (const el of root.querySelectorAll("iframe, frame, embed, object")) {
        if (!visible(el)) continue;
        for (const r of el.getClientRects()) if (r.width > 0 && r.height > 0) rects.push(r);
      }
    }
    return rects;
  }

  /** Every field whose value a picture must not show: a password, card or one-time-code field, and a text field whose value
   * is a secret by its shape (a key, a card number). The same rules as a read's. */
  function secretFields() {
    const out = [];
    const all = [];
    for (const root of roots()) all.push(...root.querySelectorAll("input, textarea, select"));
    for (const el of all) {
      if (el.tagName === "INPUT" && FIELD_SKIP.has((el.getAttribute("type") || "").toLowerCase())) continue;
      if (!visible(el)) continue;
      const kind = kindOf(el);
      const value = el.tagName === "SELECT" ? "" : String(el.value || "");
      if (kind === "password" || kind === "card" || kind === "code" || (value && M.maskText(value) !== value)) out.push(el);
    }
    return out;
  }

  /**
   * Puts an opaque box over each secret field, and says whether every one is covered: `{ok, masked}`. `ok` is false when a
   * field's middle shows something that isn't its box (the picture would show the field), and then nothing is captured.
   */
  const MAX_TEXT_NODES = 6000;

  /** The page rectangles of the text in visible text nodes that looks like a card number or a key (`TafsMask.secretSpans`). */
  function secretTextRects() {
    const rects = [];
    let seen = 0;
    for (const root of roots()) {
      const walker = document.createTreeWalker(root === document ? document.body || document.documentElement : root, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (seen >= MAX_TEXT_NODES) {
          shotIncomplete = true;
          return rects;
        }
        seen++;
        const text = node.nodeValue || "";
        if (text.length < 13) continue;
        const parent = node.parentElement;
        if (!parent || /^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA)$/.test(parent.tagName) || !visible(parent)) continue;
        for (const [start, end] of M.secretSpans(text)) {
          const range = document.createRange();
          range.setStart(node, start);
          range.setEnd(node, end);
          for (const r of range.getClientRects()) if (r.width > 0 && r.height > 0) rects.push(r);
        }
      }
    }
    return rects;
  }

  function addShotBox(r) {
    const box = document.createElement("div");
    box.setAttribute("data-tafs-shot-mask", "");
    box.style.cssText = `position:fixed;left:${r.left - 2}px;top:${r.top - 2}px;width:${r.width + 4}px;height:${r.height + 4}px;background:#000;z-index:2147483647;margin:0;padding:0;border:0;`;
    document.documentElement.append(box);
    shotBoxes.push(box);
  }

  /**
   * Puts an opaque box over each secret field and each run of text that looks like a card number or a key, and says whether
   * every one is covered: `{ok, masked}`. `ok` is false when the middle of a covered spot shows something that isn't its box
   * (the picture would show it), and then nothing is captured.
   */
  function maskForShot() {
    unmaskShot();
    shotIncomplete = false;
    const fields = secretFields();
    // A page with a one-time-code field to fill may be an authenticator's setup, whose QR code and seed are the second
    // factor and look like nothing the covers know: no picture of it.
    if (fields.some((el) => kindOf(el) === "code")) return { ok: false, masked: 0, why: "code" };
    const rects = [...fields.map((el) => el.getBoundingClientRect()), ...secretTextRects(), ...frameRects()];
    for (const r of rects) addShotBox(r);
    let ok = !shotIncomplete;
    for (const r of rects) {
      const x = r.left + r.width / 2;
      const y = r.top + r.height / 2;
      // A spot scrolled out of the picture isn't in it.
      if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) continue;
      const top = document.elementFromPoint(x, y);
      if (!top || !top.hasAttribute("data-tafs-shot-mask")) ok = false;
    }
    return { ok, masked: rects.length };
  }

  /** Takes the boxes away again. Safe to call twice. */
  function unmaskShot() {
    for (const box of shotBoxes) box.remove();
    shotBoxes = [];
    for (const stray of document.querySelectorAll("[data-tafs-shot-mask]")) stray.remove();
    return true;
  }

  let outlineBox = null;
  let outlineTimer = null;

  /**
   * "Show me" for a hand-over: draws an outline round the field or button `ref` names in read `readId`, and nothing else. It
   * never scrolls, focuses or clicks (the box lets every pointer through), and goes after a few seconds or when asked.
   */
  function outline(ref, readId) {
    unoutline();
    if (!current || current.read_id !== readId || current.href !== location.href) return { ok: false, code: "page_changed" };
    const el = refs.get(ref);
    if (!el || !el.isConnected || prints.get(ref) !== printOf(el) || !visible(el)) return { ok: false, code: "ref_unknown" };
    const r = el.getBoundingClientRect();
    const box = document.createElement("div");
    box.setAttribute("data-tafs-outline", "");
    box.style.cssText = `position:fixed;left:${r.left - 4}px;top:${r.top - 4}px;width:${r.width + 8}px;height:${r.height + 8}px;` +
      "outline:3px solid #F29A2E;border-radius:6px;pointer-events:none;z-index:2147483647;box-shadow:0 0 0 4px rgba(242,154,46,.25)";
    document.documentElement.appendChild(box);
    outlineBox = box;
    outlineTimer = setTimeout(unoutline, 8000);
    return { ok: true };
  }

  function unoutline() {
    clearTimeout(outlineTimer);
    if (outlineBox) outlineBox.remove();
    outlineBox = null;
    for (const stray of document.querySelectorAll("[data-tafs-outline]")) stray.remove();
    return true;
  }

  root.TafsPage = { read, act, kindOf, listen, maskForShot, unmaskShot, outline, unoutline, offFence };
})(globalThis);
