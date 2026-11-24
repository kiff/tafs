import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// The side panel's page consent (docs/chrome.md, "Coach reads the page you're on"): giving it is a deliberate press, and
// withdrawing it is as easy as giving it.
const html = readFileSync(new URL("../sidepanel/index.html", import.meta.url), "utf8");
const js = readFileSync(new URL("../sidepanel/panel.js", import.meta.url), "utf8");

test("the consent screen opens with the focus on its heading, never on Allow, so no stray key gives consent", () => {
  const open = js.slice(js.indexOf("function openConsent("), js.indexOf("function closeConsent("));
  assert.ok(open.length > 0, "openConsent is there");
  assert.match(open, /\$\("pageConsentTitle"\)\.focus\(\)/);
  assert.doesNotMatch(open, /pageConsentAllow/, "Allow is never focused, pressed or selected when it opens");
  assert.match(html, /<h2 id="pageConsentTitle" tabindex="-1"/, "the heading can take the focus");
  // Allow does only what its press does: nothing allows on open, on a key or on a timer.
  assert.equal((js.match(/type: "page_allow"/g) || []).length, 1, "one place gives consent");
  assert.match(js, /\$\("pageConsentAllow"\)\.onclick = async \(\) => \{\n  const r = await send\(\{ type: "page_allow" \}\)/);
});

test("withdrawing is one tap from the chat, beside what a send reads, and in Settings", () => {
  const reads = /<p id="coachReads"[^\n]*<\/p>/.exec(html);
  assert.ok(reads, "the line above Coach's box");
  assert.match(reads[0], /id="coachReadsStop"[^>]*data-m="page_consent_withdraw"/);
  assert.match(html, /<section id="settings"[\s\S]*id="pageConsentStop"[^>]*data-m="page_consent_withdraw"/);
  assert.match(js, /\$\("coachReadsStop"\)\.onclick = withdrawPage;/);
  assert.match(js, /\$\("pageConsentStop"\)\.onclick = withdrawPage;/);
  assert.match(js, /async function withdrawPage\(\) \{\n  await send\(\{ type: "page_withdraw" \}\)/);
});

test("the consent is a screen over the whole panel, a modal dialog, never a line inside Coach's chat", () => {
  assert.match(html, /<section id="pageConsent" class="sheet" role="dialog" aria-modal="true" aria-labelledby="pageConsentTitle" hidden>/);
  const coach = html.slice(html.indexOf('<section id="coach"'), html.indexOf('<section id="chats"'));
  assert.doesNotMatch(coach, /id="pageConsent"/, "not inside the chat");
});

test("the composers are the app's: Send inside the box on its right, named by its words, and Enter sends", () => {
  for (const id of ["coachForm", "tafForm"]) {
    const form = new RegExp(`<form id="${id}" class="composer"[^>]*>[\\s\\S]*?</form>`).exec(html);
    assert.ok(form, id);
    assert.match(form[0], /<div class="box">\s*<textarea[^>]*>\s*<\/textarea>\s*<button type="submit" class="send" data-m-label="coach_send">/, `${id}: Send right after the words, inside the box`);
  }
  assert.match(js, /el\.setAttribute\("aria-label", t\(el\.dataset\.mLabel\)\)/, "the sign has a name");
  assert.match(js, /if \(e\.key !== "Enter" \|\| e\.shiftKey \|\| e\.isComposing \|\| e\.keyCode === 229\) return;/, "Shift+Enter and an input method's Enter don't send");
  assert.match(js, /form\.requestSubmit\(\);/, "Enter goes through the form's own submit, as the button does");
});
