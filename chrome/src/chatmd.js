// A taf's or Coach's words in the side panel, with the little Markdown they write: paragraphs, headings, bulleted and
// numbered lists, **bold**, *italic*, `code`, and [words](address). Built from elements and text nodes only, never
// innerHTML, so nothing in the words can become markup. A link is drawn as its words, then its address, and nothing opens
// on a click: a page Coach read can steer what an answer links to, and the panel opens no link (docs/chrome.md).

// A link's address, read just after its `](` (sticky: from that place, with no copy of the rest of the answer).
const ADDRESS = /([^)\s]+)\)/y;

/**
 * An address as it's shown: each format character (bidi overrides and isolates, zero-width marks) and control character
 * becomes U+FFFD, so an override inside it can't make it read as another address. Shown, not dropped, so nothing is hidden.
 */
export function shownAddress(address) {
  return String(address).replace(/[\p{Cf}\p{Cc}]/gu, "\uFFFD");
}

/** The blocks of [text]: `{ type: "p" | "h", lines: string[] }` or `{ type: "ul" | "ol", items: string[] }`. */
export function blocks(text) {
  const out = [];
  let para = null;
  let list = null;
  const end = () => {
    para = null;
    list = null;
  };
  for (const raw of String(text || "").replace(/\r\n?/g, "\n").split("\n")) {
    const line = raw.trimEnd();
    if (!line.trim()) {
      end();
      continue;
    }
    const heading = /^ {0,3}#{1,6}\s+(.*)$/.exec(line);
    const bullet = /^\s*[-*+]\s+(.*)$/.exec(line);
    const numbered = /^\s*\d{1,9}[.)]\s+(.*)$/.exec(line);
    if (heading) {
      end();
      out.push({ type: "h", lines: [heading[1].replace(/\s+#+\s*$/, "")] });
    } else if (bullet || numbered) {
      const type = bullet ? "ul" : "ol";
      if (!list || list.type !== type) {
        para = null;
        list = { type, items: [] };
        out.push(list);
      }
      list.items.push((bullet || numbered)[1]);
    } else if (list && /^\s{2,}\S/.test(raw)) {
      list.items[list.items.length - 1] += " " + line.trim(); // an item's own next line
    } else {
      list = null;
      if (!para) {
        para = { type: "p", lines: [] };
        out.push(para);
      }
      para.lines.push(line.trim());
    }
  }
  return out;
}

/**
 * The inline runs of [text]: `{ text }`, `{ text, bold }`, `{ text, italic }`, `{ text, code }` (marks may combine), and
 * `{ text, link }` for [words](address). A mark never closed stays as written; snake_case is never italic.
 */
export function inline(text, marks = {}) {
  const out = [];
  const s = String(text || "");
  let plain = "";
  const flush = () => {
    if (plain) out.push({ text: plain, ...marks });
    plain = "";
  };
  // Where the next `]` and the next line break are, kept as `i` moves on, so an answer full of `[` stays linear.
  let close = -2;
  let lineEnd = -2;
  // An address read from before here ran to here without its `)`: any start before it fails the same way (its run of
  // non-`)`, non-space characters ends at the same place), so it isn't read again. Keeps an answer full of `](` linear.
  let noAddressBefore = -1;
  let i = 0;
  while (i < s.length) {
    const c = s[i];
    if (c === "`") {
      const end = s.indexOf("`", i + 1);
      if (end > i + 1) {
        flush();
        out.push({ text: s.slice(i + 1, end), ...marks, code: true });
        i = end + 1;
        continue;
      }
    } else if (c === "*" && s[i + 1] === "*") {
      const end = s.indexOf("**", i + 2);
      if (end > i + 2) {
        flush();
        out.push(...inline(s.slice(i + 2, end), { ...marks, bold: true }));
        i = end + 2;
        continue;
      }
    } else if (c === "*" && s[i + 1] !== " " && s[i + 1] !== "*") {
      const end = s.indexOf("*", i + 1);
      if (end > i + 1 && s[end - 1] !== " ") {
        flush();
        out.push(...inline(s.slice(i + 1, end), { ...marks, italic: true }));
        i = end + 1;
        continue;
      }
    } else if (c === "[") {
      if (close !== -1 && close <= i) close = s.indexOf("]", i + 1);
      if (lineEnd !== -1 && lineEnd <= i) lineEnd = s.indexOf("\n", i + 1);
      // A link's words end at the next `]`, on the same line, and its address follows at once in `(…)`.
      if (close > i + 1 && close + 2 >= noAddressBefore && (lineEnd === -1 || close < lineEnd) && s[close + 1] === "(") {
        ADDRESS.lastIndex = close + 2;
        const m = ADDRESS.exec(s);
        if (m) {
          flush();
          out.push({ text: s.slice(i + 1, close), ...marks, link: m[1] });
          i = ADDRESS.lastIndex;
          continue;
        }
        noAddressBefore = addressRunEnd(s, close + 2);
      }
    }
    plain += c;
    i++;
  }
  flush();
  return out;
}

/** Where a run of address characters (anything but `)` and space) starting at `from` ends. */
function addressRunEnd(s, from) {
  let j = from;
  while (j < s.length && s[j] !== ")" && !/\s/.test(s[j])) j++;
  return j;
}

function runs(doc, parent, text) {
  for (const r of inline(text)) {
    let node = doc.createTextNode(r.text);
    if (r.code) {
      const el = doc.createElement("code");
      el.append(node);
      node = el;
    }
    if (r.italic) {
      const el = doc.createElement("em");
      el.append(node);
      node = el;
    }
    if (r.bold) {
      const el = doc.createElement("strong");
      el.append(node);
      node = el;
    }
    if (r.link) {
      // The words, then where they lead, as text: the panel opens nothing.
      const words = doc.createElement("span");
      words.className = "linkWords";
      words.append(node);
      parent.append(words);
      if (r.link !== r.text) {
        const addr = doc.createElement("span");
        addr.className = "linkAddress";
        addr.dir = "ltr";
        addr.textContent = " (" + shownAddress(r.link) + ")";
        parent.append(addr);
      }
      continue;
    }
    parent.append(node);
  }
}

/** [text] drawn into a fragment of `doc`: elements and text nodes only. */
export function render(doc, text) {
  const frag = doc.createDocumentFragment();
  for (const b of blocks(text)) {
    if (b.type === "ul" || b.type === "ol") {
      const list = doc.createElement(b.type);
      for (const item of b.items) {
        const li = doc.createElement("li");
        runs(doc, li, item);
        list.append(li);
      }
      frag.append(list);
      continue;
    }
    const el = doc.createElement(b.type === "h" ? "strong" : "p");
    if (b.type === "h") el.className = "heading";
    b.lines.forEach((line, n) => {
      if (n) el.append(doc.createElement("br"));
      runs(doc, el, line);
    });
    frag.append(b.type === "h" ? wrap(doc, el) : el);
  }
  return frag;
}

function wrap(doc, el) {
  const p = doc.createElement("p");
  p.append(el);
  return p;
}
