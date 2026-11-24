import { test } from "node:test";
import assert from "node:assert/strict";
import { blocks, inline, render, shownAddress } from "../src/chatmd.js";

// A document with elements and text nodes only: no innerHTML, no parser. render must build everything from these.
function fakeDoc() {
  const node = (tag) => ({
    tag,
    children: [],
    className: "",
    dir: "",
    set textContent(v) {
      this.children = [{ text: String(v) }];
    },
    append(...kids) {
      for (const k of kids) {
        if (k.tag === "#fragment") this.children.push(...k.children);
        else this.children.push(k);
      }
    },
  });
  return { createElement: (t) => node(t), createTextNode: (text) => ({ text: String(text) }), createDocumentFragment: () => node("#fragment") };
}

// The tree as a short HTML-like string, for reading the tests.
function show(n) {
  if ("text" in n && !n.tag) return n.text;
  const inner = n.children.map(show).join("");
  if (n.tag === "#fragment") return inner;
  const cls = n.className ? `.${n.className}` : "";
  return `<${n.tag}${cls}>${inner}</${n.tag}>`;
}

test("the taf's list of page titles reads as a list, its bold as bold, and no asterisk shows", () => {
  const text = "The page titles are:\n\n1. **Great white shark**\n2. **Ice Warrior**\n3. **Mairzy Doates**\n\nThe browser only opens pages that the previous read gave as links.";
  assert.equal(
    show(render(fakeDoc(), text)),
    "<p>The page titles are:</p><ol><li><strong>Great white shark</strong></li><li><strong>Ice Warrior</strong></li><li><strong>Mairzy Doates</strong></li></ol><p>The browser only opens pages that the previous read gave as links.</p>",
  );
});

test("a link is its words, then its address as text: nothing in the panel can open it", () => {
  const out = show(render(fakeDoc(), "See [the receipt](https://shop.example/r/1) now"));
  assert.equal(out, "<p>See <span.linkWords>the receipt</span><span.linkAddress> (https://shop.example/r/1)</span> now</p>");
  assert.ok(!/<a[ >.]/.test(out), "no anchor is ever made");
  // A link whose words are its address shows it once.
  assert.equal(show(render(fakeDoc(), "[https://a.example/](https://a.example/)")), "<p><span.linkWords>https://a.example/</span></p>");
  // Another app's link is text too.
  assert.match(show(render(fakeDoc(), "[Call](tel:+15550100)")), /<span\.linkAddress> \(tel:\+15550100\)<\/span>/);
});

test("markup in a taf's words stays words: nothing becomes an element but what the Markdown names", () => {
  const out = show(render(fakeDoc(), '<img src=x onerror=alert(1)> and <a href="https://evil.example">click</a>'));
  assert.equal(out, '<p><img src=x onerror=alert(1)> and <a href="https://evil.example">click</a></p>', "the whole line is one text node");
  const p = render(fakeDoc(), "<b>x</b>").children[0];
  assert.equal(p.children.length, 1);
  assert.equal(p.children[0].text, "<b>x</b>");
});

test("italic, code, headings, bullets and line breaks; a mark never closed and snake_case stay as written", () => {
  assert.equal(show(render(fakeDoc(), "## Plan\n- *soon*\n- `pay_invoice`")), "<p><strong.heading>Plan</strong></p><ul><li><em>soon</em></li><li><code>pay_invoice</code></li></ul>");
  assert.equal(show(render(fakeDoc(), "one\ntwo")), "<p>one<br></br>two</p>");
  assert.deepEqual(inline("a **b"), [{ text: "a **b" }]);
  assert.deepEqual(inline("pay_invoice and grant_access"), [{ text: "pay_invoice and grant_access" }]);
  assert.deepEqual(inline("2 * 3 * 4"), [{ text: "2 * 3 * 4" }], "a spaced star is arithmetic, not italic");
  assert.deepEqual(inline("**bold** and *italic*"), [{ text: "bold", bold: true }, { text: " and " }, { text: "italic", italic: true }]);
  assert.deepEqual(inline("**a *b* c**"), [{ text: "a ", bold: true }, { text: "b", bold: true, italic: true }, { text: " c", bold: true }], "italic inside bold");
});

test("blocks: a blank line ends a paragraph or a list, and an item's indented next line stays in it", () => {
  assert.deepEqual(blocks("a\nb\n\nc"), [{ type: "p", lines: ["a", "b"] }, { type: "p", lines: ["c"] }]);
  assert.deepEqual(blocks("- one\n  more\n- two\nafter"), [{ type: "ul", items: ["one more", "two"] }, { type: "p", lines: ["after"] }]);
  assert.deepEqual(blocks("1) a\n2) b"), [{ type: "ol", items: ["a", "b"] }]);
  assert.deepEqual(blocks(""), []);
});

test("a format or control character in a link's address shows as U+FFFD, so the address can't read as another", () => {
  const rlo = "\u202E";
  const out = show(render(fakeDoc(), `[the docs](https://shop.example/${rlo}gnp.exe)`));
  assert.ok(!out.includes(rlo), "no override reaches the panel");
  assert.ok(out.includes("<span.linkAddress> (https://shop.example/\uFFFDgnp.exe)</span>"), out);
  for (const c of ["\u202A", "\u2066", "\u2069", "\u200B", "\u200F", "\uFEFF", "\u0007", "\u0085"]) {
    assert.equal(shownAddress("a" + c + "b"), "a\uFFFDb", "U+" + c.codePointAt(0).toString(16));
  }
  assert.equal(shownAddress("https://a.example/ok"), "https://a.example/ok", "an ordinary address is as written");
});

test("an answer full of [ stays quick: no copy of the rest of the text at each one", () => {
  const text = "[".repeat(200_000) + "x";
  const start = Date.now();
  assert.deepEqual(inline(text), [{ text }]);
  assert.ok(Date.now() - start < 2_000, "linear, not quadratic");
  const unclosed = "[".repeat(100_000) + "](" + "a".repeat(100_000);
  const t2 = Date.now();
  assert.deepEqual(inline(unclosed), [{ text: unclosed }], "an address that never closes is no link");
  assert.ok(Date.now() - t2 < 2_000, "and is read once");
  // Many `[a](`, each with its own `]`, and no `)` anywhere: every address runs to the end, so it's read once in all.
  const many = "[a](".repeat(50_000);
  const t3 = Date.now();
  assert.deepEqual(inline(many), [{ text: many }]);
  assert.ok(Date.now() - t3 < 2_000, "an address run that never closes is read once, from any start");
  // A failed address doesn't stop a later, real link.
  assert.deepEqual(inline("[a](b c [d](https://e.example/)").at(-1), { text: "d", link: "https://e.example/" });
  // Links are still found anywhere in a long answer.
  assert.deepEqual(inline("a ".repeat(5_000) + "[w](https://a.example/)").at(-1), { text: "w", link: "https://a.example/" });
});
