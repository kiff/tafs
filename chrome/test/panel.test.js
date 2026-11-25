import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// The side panel's page consent (docs/chrome.md, "Coach reads the page you're on"): giving it is a deliberate press, and
// withdrawing it is as easy as giving it.
const html = readFileSync(new URL("../sidepanel/index.html", import.meta.url), "utf8");
const js = readFileSync(new URL("../sidepanel/panel.js", import.meta.url), "utf8");
const css = readFileSync(new URL("../sidepanel/panel.css", import.meta.url), "utf8");

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
    // The + on the left, then the words, then Send on the right, all inside the box (Chrome 0.2.10: files).
    assert.match(form[0], /<div class="box">\s*<button type="button" id="\w+Attach" class="attach" data-m-label="attach_files">\+<\/button>\s*<input type="file" id="\w+File" multiple hidden>\s*<textarea[^>]*>\s*<\/textarea>\s*<button type="submit" class="send" data-m-label="coach_send">/, `${id}: Send right after the words, inside the box`);
  }
  assert.match(js, /el\.setAttribute\("aria-label", t\(el\.dataset\.mLabel\)\)/, "the sign has a name");
  assert.match(js, /if \(e\.key !== "Enter" \|\| e\.shiftKey \|\| e\.isComposing \|\| e\.keyCode === 229\) return;/, "Shift+Enter and an input method's Enter don't send");
  assert.match(js, /form\.requestSubmit\(\);/, "Enter goes through the form's own submit, as the button does");
});

// The owner's 0.2.8 walk-through (E, F): "the chat input box should stay visible and stick at the bottom", Retour and the menu
// only after scrolling all the way up, and a second of nothing after Send.
test("the heads stay at the top, the composer at the bottom, and Coach's page line goes with the composer", () => {
  assert.match(css, /header \{\s*position: sticky;\s*top: 0;/, "the panel's head");
  assert.match(css, /\.viewhead \{\s*position: sticky;\s*top: var\(--headh\);/, "a view's own head, under it");
  assert.match(css, /\.dock,\s*#tafForm \{\s*position: sticky;\s*bottom: 0;/, "both composers");
  assert.match(html, /<div class="dock">\s*<p id="coachReads"[\s\S]*?<form id="coachForm" class="composer">[\s\S]*?<\/form>\s*<\/div>/, "what a send reads stays beside the box");
  assert.match(html, /<section id="paired" data-view="tafView" hidden>/);
  assert.match(js, /\$\("paired"\)\.dataset\.view = name;/, "the pairing's lines only on the taf's view");
});

test("a message shows at once with the working line, stays until the chat has it, and its words come back on a failure", () => {
  for (const [send, box, pending] of [["sendCoach", "coachWords", "coachPending"], ["tafForm", "tafWords", "tafPending"]]) {
    const at = js.indexOf(send === "sendCoach" ? "async function sendCoach" : '$("tafForm").onsubmit');
    const body = js.slice(at, js.indexOf("\n}", at));
    const shown = body.indexOf("showPending(");
    const sent = body.indexOf("await send(");
    assert.ok(shown > 0 && sent > shown, `${send}: shown before tafs cloud answers`);
    assert.ok(body.indexOf("workingLine(") > 0 && body.indexOf("workingLine(") < sent, `${send}: the working line too`);
    assert.match(body, new RegExp(`if \\(!\\$\\("${box}"\\)\\.value\\) \\$\\("${box}"\\)\\.value = words;`), `${send}: a failure gives the words back`);
    assert.match(js, new RegExp(`${pending} && [^\\n]*!stillPending\\(${pending}, r\\.thread\\.messages\\)`), `${pending}: kept until the chat has it`);
    assert.match(js, new RegExp(`${pending} = [^\\n]*\\{ \\.\\.\\.${pending}, id[^\\n]*\\} : null;`), `${pending}: matched by the id tafs cloud gave it`);
  }
  assert.doesNotMatch(js, /note\.textContent = t\("coach_working"\)/, "the working line is in the chat, not a note under it");
});

// Chrome 0.2.10: files on a message, as in the apps.
test("files are picked with Chrome's own file picker only, shown as chips until Send, and Send waits while one goes up", () => {
  for (const [form, tray, button, input] of [["coachForm", "coachFiles", "coachAttach", "coachFile"], ["tafForm", "tafFiles", "tafAttach", "tafFile"]]) {
    const f = new RegExp(`<form id="${form}" class="composer"[^>]*>[\\s\\S]*?</form>`).exec(html)[0];
    assert.match(f, new RegExp(`<ul id="${tray}" class="files" hidden></ul>\\s*<div class="box">`), `${form}: the chips above the box`);
    assert.match(f, new RegExp(`<input type="file" id="${input}" multiple hidden>`), `${form}: a file input, and only that`);
    assert.doesNotMatch(f, /capture=/, `${form}: no camera or page capture`);
    assert.match(js, new RegExp(`\\["${button}", "${input}",`), `${form}: the + opens it`);
  }
  // Nothing in the panel reads a page, a tab or the screen for a file, nor any microphone.
  assert.doesNotMatch(js, /getUserMedia|getDisplayMedia|captureVisibleTab|showDirectoryPicker|webkitSpeech|SpeechRecognition/);
  assert.match(js, /\$\(input\)\.accept = ACCEPT;/);
  // Send waits while a file goes up; a message may be words, files, or both; the files go by id with it.
  assert.match(js, /if \(uploading\(trayOf\(COACH_TRAY\)\)\) return;/);
  assert.match(js, /if \(!to \|\| uploading\(trayOf\(to\)\)\) return;/);
  assert.match(js, /type: "coach_send", words, alone, attachments: files,/);
  assert.match(js, /type: "taf_send", taf: to, words, attachments: files,/);
  // Each chip can be removed, by a button named for its file.
  assert.match(js, /remove\.setAttribute\("aria-label", t\("attach_remove", f\.name\)\);/);
  // A file declared on one taf never goes to another: each chat has its own tray, Coach's by itself.
  assert.match(js, /type: "attach", taf: target === COACH_TRAY \? null : target, file: f/);
});

// The owner's 0.2.10 walk-through: with a short chat the composer sat under the last message, mid-panel, not at the bottom.
test("the composer sits at the bottom even with a short chat: a full-height column, the composer pushed to its end", () => {
  assert.match(css, /body \{\s*min-height: 100vh;\s*display: flex;\s*flex-direction: column;/);
  assert.match(css, /#paired:not\(\[hidden\]\),\s*#tafView:not\(\[hidden\]\),\s*#coach:not\(\[hidden\]\) \{\s*flex: 1;/, "only the view on show, so [hidden] still hides the others");
  assert.match(css, /#tafForm,\s*#coach > \.dock \{\s*margin-top: auto;/);
  assert.match(html, /<div class="dock">/, "Coach's composer is in its dock");
});

// The owner's chrome://extensions after 0.2.12: "Unchecked runtime.lastError: Could not establish connection. Receiving end does
// not exist." from the side panel. A port whose connect found no worker ends in onDisconnect with that error set, unread.
test("the panel's port reads the connect's error and connects again; a press's message to a worker that isn't there is no unhandled error", () => {
  const open = js.slice(js.indexOf("function openHere("), js.indexOf("chrome.tabs.onActivated"));
  assert.match(open, /port\.onDisconnect\.addListener\(\(\) => \{\n\s*void chrome\.runtime\.lastError;[^\n]*\n\s*setTimeout\(openHere, 1000\);/, "the error is read where it lands, and the panel connects again");
  assert.match(open, /try \{\n\s*port\.postMessage\(\{ windowId: panelWindow \}\);\n\s*\} catch \{/, "a port already gone doesn't throw");
  // Every message whose answer nobody waits for goes through quietly(), which catches; none is a bare send().
  assert.match(js, /function quietly\(message\) \{\n\s*return send\(message\)\.catch\(\(\) => null\);/);
  assert.doesNotMatch(js, /=> send\(\{/, "no press sends without a catch");
  assert.doesNotMatch(js, /^send\(\{/m, "nor the panel's first message");
  for (const type of ["pause", "resume", "stop", "hand_answer", "go_to_run", "unpair", "state", "turn_over"]) {
    assert.ok(js.includes(`quietly({ type: "${type}"`), type);
  }
});

// Chrome 0.2.14: a taf works on the page you're on, and its chat says which page went with a message, as Coach's does.
test("a taf's chat shows the page that went with a message, and says when the page was on none of the taf's sites", () => {
  const read = js.slice(js.indexOf("async function readTaf("), js.indexOf("function scheduleTaf("));
  assert.match(read, /words\(li, m\);\n\s*reading\(li, m\);/, "the same line as Coach's");
  assert.match(read, /t\("aside_not_taf_site", notRead\.get\(m\.id\)\)/);
  assert.match(js, /if \(sent && r\.notReading === "not_taf_site" && r\.host\) notRead\.set\(sent, r\.host\);/);
  assert.match(js, /function reading\(li, m\) \{[\s\S]*?t\("aside_reading", m\.reading\)/);
  assert.equal((js.match(/\n\s*reading\(li, m\);/g) || []).length, 2, "drawn by one function, for Coach's chat and a taf's");
});
