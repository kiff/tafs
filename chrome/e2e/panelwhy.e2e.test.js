// End to end: a problem is always shown to the person, never nothing (docs/chrome.md, "The taf in the side panel", 0.2.22).
// In the real side panel, with the fake tafs cloud: the page consent's banner above a taf's box before anything is sent, the
// line under a message whose page didn't go, and the chat's failed run as its last line, with its Try again pressed once. The
// failed run is tafs cloud's own contract (contracts/conversation-failed.json), as the fake serves it (planque.mjs `failNext`).

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { playwright, start, until } from "./harness.mjs";

const skip = !playwright;
const pause = (ms) => new Promise((done) => setTimeout(done, ms));

/** The text of the panel's element `selector`, or null when it isn't there or is hidden. */
function shown(h, selector) {
  return h.inPanel(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); return el && !el.closest("[hidden]") ? el.textContent : null; })()`);
}

/** The person types `words` in the taf's box and sends them, as they do. */
function sendTyped(h, words) {
  return h.inPanel(`(() => {
    document.getElementById("tafWords").value = ${JSON.stringify(words)};
    document.getElementById("tafForm").requestSubmit();
    return true;
  })()`);
}

/** The person's tab, on `url`, in front of the panel's window. */
async function inFront(h, url) {
  const owner = h.context.pages()[0];
  await owner.goto(url);
  await owner.bringToFront();
  return owner;
}

test("without the page consent, a banner above the taf's box says the page doesn't go, before any send, and opens the screen", { skip }, async () => {
  const h = await start({ cloud: true });
  try {
    await until(() => h.cloud.claimed, "the KIFF device's claim", 15_000);
    await until(async () => (await shown(h, "#tafForm")) !== null, "the taf's composer");
    const banner = "#tafConsentOff";
    // A page on the taf's own sites, in front: the banner, with its button, and no screen over the taf's chat by itself.
    await inFront(h, h.sites.url("news.test", "/hub?case=banner"));
    await until(async () => (await shown(h, banner)) !== null, "the banner on a page the taf could read");
    assert.equal(await shown(h, banner + " p"), "The page you're on doesn't go with your messages.");
    assert.equal(await shown(h, "#tafConsentOpen"), "Let Coach and your tafs read the page…");
    assert.equal(await h.inPanel(`document.getElementById("tafConsentOff").nextElementSibling.id`), "tafFiles", "right above the box");
    assert.equal(await shown(h, "#pageConsent"), null, "the screen doesn't come up over a taf's chat by itself");
    // A page off the taf's sites: nothing of it could go, so no banner.
    await inFront(h, h.sites.url("other.test", "/?case=banner"));
    await until(async () => (await shown(h, banner)) === null, "no banner off the taf's sites");
    await inFront(h, h.sites.url("news.test", "/hub?case=banner-2"));
    await until(async () => (await shown(h, banner)) !== null, "the banner again when the tab in front moves back");

    // A send without the consent: the words go alone, and the line under the message says why, with the same button.
    await sendTyped(h, "Sum up this page");
    await until(() => h.cloud.sends.length === 1, "the message");
    assert.equal(h.cloud.sends[0].page, null, "no page without the consent");
    await until(async () => (await shown(h, "#tafThread li.owner small.reading")) !== null, "the line under the message");
    assert.equal(await shown(h, "#tafThread li.owner small.reading"), "The page you're on doesn't go with your messages. Let Coach and your tafs read the page…");

    // Its button opens the consent's screen; Allow takes the banner away, and the next send carries the page.
    await h.inPanel(`(document.getElementById("tafConsentOpen").click(), true)`);
    await until(async () => (await shown(h, "#pageConsent")) !== null, "the consent's screen");
    await h.inPanel(`(document.getElementById("pageConsentAllow").click(), true)`);
    await until(async () => (await shown(h, banner)) === null, "the banner gone once consent is given");
    await sendTyped(h, "And now?");
    await until(() => h.cloud.sends.length === 2, "the second message");
    assert.equal(new URL(h.cloud.sends[1].page.url).hostname, "news.test");

    // A site the person switched off: the line says so, with the site's name.
    await h.fromPanel({ type: "aside_site", host: "news.test", off: true });
    await sendTyped(h, "Once more");
    await until(() => h.cloud.sends.length === 3, "the third message");
    assert.equal(h.cloud.sends[2].page, null);
    await until(async () => (await h.inPanel(`[...document.querySelectorAll("#tafThread li.owner small.reading")].map((x) => x.textContent).join("|")`)).includes("news.test isn't read: you switched it off"), "the switched-off line");
  } finally {
    await h.close();
  }
});

test("a failed run is the chat's last line, in tafs cloud's words, ends the working line, and its Try again sends once, as a new message", { skip }, async () => {
  const h = await start({ cloud: true });
  try {
    await until(() => h.cloud.claimed, "the KIFF device's claim", 15_000);
    await until(async () => (await shown(h, "#tafForm")) !== null, "the taf's composer");
    // tafs cloud's contract as it is, with the `retry` and `retry_label` it is to add.
    const { words } = JSON.parse(readFileSync(new URL("../../contracts/conversation-failed.json", import.meta.url), "utf8")).answer.failed;
    h.cloud.failNext = { retry: true, retry_label: "Try again" };
    await sendTyped(h, "Sum up this page");
    await until(async () => (await shown(h, "#tafThread li.failed p")) === words, "the failed turn's words, as sent");
    assert.equal(await h.inPanel(`document.querySelector("#tafThread li.failed").previousElementSibling.textContent`), "Sum up this page", "under the message whose run failed");
    assert.equal(await h.inPanel(`document.querySelector("#tafThread li.failed") === document.querySelector("#tafThread").lastElementChild`), true, "the chat's last line");
    assert.equal(await h.inPanel(`document.querySelectorAll("#tafThread li.working, #tafThread li.pending").length`), 0, "no working line once the turn failed");
    assert.equal(await shown(h, "#tafThread li.failed button.retry"), "Try again", "tafs cloud's label");

    // A double click sends once: the button goes at the first press, and the words go as a new message.
    await h.inPanel(`(() => { const b = document.querySelector("#tafThread li.failed button.retry"); b.click(); b.click(); return true; })()`);
    await until(() => h.cloud.sends.length === 2, "the Retry's message");
    await pause(1500);
    assert.equal(h.cloud.sends.length, 2, "one message for a double click");
    assert.equal(h.cloud.sends[1].text, "Sum up this page");
    assert.notEqual(h.cloud.sends[1].clientID, h.cloud.sends[0].clientID, "a new message");
    await until(async () => (await h.inPanel(`document.querySelectorAll("#tafThread li.owner").length`)) === 2, "the chat read again");
    assert.equal(await h.inPanel(`document.querySelectorAll("#tafThread button.retry").length`), 0, "Try again doesn't come back when the chat is drawn again");
    await until(async () => (await shown(h, "#tafThread li.failed")) === null, "a run that didn't fail closes the line, as tafs cloud does");
    assert.equal(await h.inPanel(`document.getElementById("tafWords").value`), "", "the box left as it was");

    // A code the panel doesn't know: its words still show, and no Try again, whatever it says. No `retry`: no button either.
    h.cloud.failNext = { code: "something_new", words: "Something new went wrong.", retry: true, retry_label: "Try again" };
    await sendTyped(h, "Another");
    await until(async () => (await h.inPanel(`[...document.querySelectorAll("#tafThread li.failed p")].map((p) => p.textContent).join("|")`)).includes("Something new went wrong."), "an unknown code's words");
    h.cloud.failNext = { code: "timeout", words: "It reached its time limit and stopped.", retry: false };
    await sendTyped(h, "A third");
    await until(async () => (await h.inPanel(`[...document.querySelectorAll("#tafThread li.failed p")].map((p) => p.textContent).join("|")`)).includes("It reached its time limit"), "a failed turn with no Retry");
    assert.equal(await h.inPanel(`document.querySelectorAll("#tafThread button.retry").length`), 0, "no Try again on either");
    assert.equal(h.cloud.sends.length, 4);
  } finally {
    await h.close();
  }
});
