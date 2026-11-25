// A taf's or Coach's words in the side panel, drawn by the phones' Markdown rules (docs/chat.md; `MarkdownText` on Android and
// iOS): paragraphs with their line breaks, headings, bulleted and numbered lists (each item with its own number), code blocks,
// tables (a column of numbers to the right), a rule left out, and **bold**, *italic* or _italic_, `code`, a \-escaped mark and
// [words](address) inline. Built from elements and text nodes only, never innerHTML, so nothing in the words can become
// markup. A link is drawn as its words, then its address, and nothing opens on a click: a page Coach read can steer what an
// answer links to, and the panel opens no link (docs/chrome.md).

// A link's address, read just after its `](` (sticky: from that place, with no copy of the rest of the answer).
const ADDRESS = /([^)\s]+)\)/y;

/**
 * An address as it's shown: each format character (bidi overrides and isolates, zero-width marks) and control character
 * becomes U+FFFD, so an override inside it can't make it read as another address. Shown, not dropped, so nothing is hidden.
 */
export function shownAddress(address) {
  return String(address).replace(/[\p{Cf}\p{Cc}]/gu, "\uFFFD");
}

/**
 * The blocks of [text]: `{ type: "p" | "h", lines: string[] }`, `{ type: "ul", items: string[] }`, `{ type: "ol", items:
 * string[], numbers: number[] }`, `{ type: "code", text }` or `{ type: "table", rows: string[][], numeric: boolean[] }`. As the
 * phones read it: a fence (```) opens and closes a code block, kept as written; a table is a header, its |---| line and its
 * rows, anything less is text; a line of three or more of - * _ is a rule, and nothing is drawn for it.
 */
export function blocks(text) {
  const out = [];
  let para = null;
  let list = null;
  let code = null;
  let table = [];
  const endTable = () => {
    if (!table.length) return;
    if (table.length >= 2 && isTableRule(table[1])) {
      const rows = [table[0], ...table.slice(2)].map(tableCells);
      out.push({ type: "table", rows, numeric: rows[0].map((_, c) => numericColumn(c, rows)) });
    } else {
      // Not a table after all: its lines are text, as written.
      if (!para) {
        para = { type: "p", lines: [] };
        out.push(para);
      }
      para.lines.push(...table);
    }
    table = [];
  };
  const end = () => {
    endTable();
    para = null;
    list = null;
  };
  for (const raw of String(text || "").replace(/\r\n?/g, "\n").split("\n")) {
    const line = raw.trim();
    if (line.startsWith("```")) {
      if (code) {
        out.push({ type: "code", text: code.join("\n") });
        code = null;
      } else {
        end();
        code = [];
      }
      continue;
    }
    if (code) {
      code.push(raw);
      continue;
    }
    if (!line) {
      end();
      continue;
    }
    // A table's lines go together: a line that starts one (a pipe first, two cells or more) ends the paragraph before it.
    if (line.includes("|") && (table.length || (line.startsWith("|") && tableCells(line).length >= 2))) {
      if (!table.length) {
        para = null;
        list = null;
      }
      table.push(line);
      continue;
    }
    endTable();
    const heading = /^#{1,6}\s+(.*)$/.exec(line);
    const bullet = /^[-*•+]\s+(.*)$/.exec(line);
    const numbered = /^(\d{1,3})[.)]\s+(.*)$/.exec(line);
    if (heading) {
      end();
      out.push({ type: "h", lines: [heading[1].replace(/\s+#+\s*$/, "")] });
    } else if (bullet || numbered) {
      const type = bullet ? "ul" : "ol";
      if (!list || list.type !== type) {
        para = null;
        list = type === "ol" ? { type, items: [], numbers: [] } : { type, items: [] };
        out.push(list);
      }
      if (bullet) list.items.push(bullet[1]);
      else {
        list.items.push(numbered[2]);
        list.numbers.push(Number(numbered[1]));
      }
    } else if (line.length >= 3 && /^[-*_]+$/.test(line)) {
      end(); // a rule: nothing to draw
    } else if (list && /^\s{2,}\S/.test(raw)) {
      list.items[list.items.length - 1] += " " + line; // an item's own next line
    } else {
      list = null;
      if (!para) {
        para = { type: "p", lines: [] };
        out.push(para);
      }
      para.lines.push(line);
    }
  }
  // A fence never closed holds what came after it, as the phones keep it.
  if (code) out.push({ type: "code", text: code.join("\n") });
  endTable();
  return out;
}

/** A table row's cells: the text between its pipes, without the outer ones, trimmed. A pipe written as \| stays in its cell. */
export function tableCells(line) {
  let row = String(line).trim();
  if (row.startsWith("|")) row = row.slice(1);
  if (row.endsWith("|") && !row.endsWith("\\|")) row = row.slice(0, -1);
  return row.split(/(?<!\\)\|/).map((c) => c.replace(/\\\|/g, "|").trim());
}

/** The line under a table's header: a run of dashes per column, colons allowed. */
function isTableRule(line) {
  const cells = tableCells(line);
  return cells.length >= 2 && cells.every((c) => /^[-:]+$/.test(c) && c.includes("-"));
}

/** A column whose cells under the header are all amounts or numbers ("20.00 USD" too): drawn to the right. */
function numericColumn(column, rows) {
  const body = rows.slice(1).map((r) => r[column]).filter((c) => c);
  if (!body.length) return false;
  return body.every((cell) => {
    const bare = cell.replace(/\b[A-Z]{3}\b/g, "");
    return /\d/.test(bare) && /^[0-9.,\-+%$€£¥ ]*$/.test(bare);
  });
}

/**
 * The inline runs of [text]: `{ text }`, `{ text, bold }`, `{ text, italic }`, `{ text, code }` (marks may combine), and
 * `{ text, link }` for [words](address). As the phones read them: a mark never closed stays as written; an italic mark (* or
 * _) opens before a word and never before a space, and _ never inside a word, so snake_case is never italic; a backslash
 * before a Markdown mark writes the mark itself.
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
  // An italic mark with no closing one after it: no later mark of that kind closes either (whether a mark closes doesn't
  // depend on where it opened), so it isn't looked for again. Keeps an answer full of `*a *b` linear.
  const noCloser = { "*": Infinity, _: Infinity };
  let i = 0;
  while (i < s.length) {
    const c = s[i];
    if (c === "\\" && i + 1 < s.length && ESCAPED.includes(s[i + 1])) {
      plain += s[i + 1];
      i += 2;
      continue;
    }
    if (c === "*" && s[i + 1] === "*") {
      const end = s.indexOf("**", i + 2);
      if (end > i + 2) {
        flush();
        out.push(...inline(s.slice(i + 2, end), { ...marks, bold: true }));
        i = end + 2;
        continue;
      }
      plain += "**";
      i += 2;
      continue;
    }
    if (c === "`") {
      const end = s.indexOf("`", i + 1);
      if (end > i + 1) {
        flush();
        out.push({ text: s.slice(i + 1, end), ...marks, code: true });
        i = end + 1;
        continue;
      }
    } else if ((c === "*" || c === "_") && opensItalic(s, i) && i < noCloser[c]) {
      const end = closesItalic(s, i);
      if (end > i + 1) {
        flush();
        out.push(...inline(s.slice(i + 1, end), { ...marks, italic: true }));
        i = end + 1;
        continue;
      }
      noCloser[c] = i;
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

// The marks a backslash writes as themselves, as the phones read them.
const ESCAPED = "\\`*_[]()#+-.!|";

/** An italic mark opens before a word: never before a space or the same mark, and _ never inside a word (snake_case). */
function opensItalic(s, i) {
  const next = s[i + 1];
  if (next === undefined || /\s/.test(next) || next === s[i]) return false;
  return !(s[i] === "_" && i > 0 && /[\p{L}\p{N}]/u.test(s[i - 1]));
}

/** Where the italic mark at [from] closes: the same mark after a non-space, and for _ not inside a word; -1 for nowhere. */
function closesItalic(s, from) {
  const mark = s[from];
  for (let j = from + 1; j < s.length; j++) {
    if (s[j] === mark && !/\s/.test(s[j - 1]) && (mark === "*" || !/[\p{L}\p{N}]/u.test(s[j + 1] || ""))) return j;
  }
  return -1;
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
      b.items.forEach((item, n) => {
        const li = doc.createElement("li");
        // Each item keeps the number it was written with, as on the phones.
        if (b.type === "ol") li.value = b.numbers[n];
        runs(doc, li, item);
        list.append(li);
      });
      frag.append(list);
      continue;
    }
    if (b.type === "code") {
      const pre = doc.createElement("pre");
      const code = doc.createElement("code");
      code.textContent = b.text;
      pre.append(code);
      frag.append(pre);
      continue;
    }
    if (b.type === "table") {
      frag.append(table(doc, b));
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

/** A table, as the phones draw it: the header in bold, a column of numbers to the right; a wide one scrolls sideways. */
function table(doc, b) {
  const box = doc.createElement("div");
  box.className = "tableWrap";
  const t = doc.createElement("table");
  b.rows.forEach((row, r) => {
    const tr = doc.createElement("tr");
    row.forEach((cell, c) => {
      const td = doc.createElement(r === 0 ? "th" : "td");
      if (b.numeric[c]) td.className = "num";
      runs(doc, td, cell);
      tr.append(td);
    });
    t.append(tr);
  });
  box.append(t);
  return box;
}

function wrap(doc, el) {
  const p = doc.createElement("p");
  p.append(el);
  return p;
}
