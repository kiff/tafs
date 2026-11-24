import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { readForCoach } from "../src/asideread.js";
import { allowedHere, pageForSend, PAGE_CONSENT_VERSION } from "../src/pageconsent.js";
import { Coach } from "../src/coach.js";

// A Chrome whose tab in front is a shop, counting every touch of a tab: a read of its address or a script in it.
function chromeCounting() {
  const touched = [];
  return {
    touched,
    tabs: { get: async (id) => { touched.push("tab"); return { id, active: true, windowId: 2, incognito: false, url: "https://shop.example/a" }; } },
    scripting: {
      executeScript: async (o) => {
        touched.push(o.files ? "inject" : "read");
        return o.files ? [{}] : [{ result: { href: "https://shop.example/a", url: "https://shop.example/a", title: "T", text: "words", truncated: false } }];
      },
    },
  };
}

test("without the page consent, or sending alone, the tab is never touched: no script, no read, the words go alone", async () => {
  for (const [alone, given, why] of [[false, false, "consent"], [true, true, "alone"], [true, false, "alone"]]) {
    const c = chromeCounting();
    let asked = 0;
    const got = await pageForSend({ alone, consent: async () => { asked++; return given; }, read: () => readForCoach(c, 7, 2, []) });
    assert.deepEqual(got, { page: null, why }, `alone ${alone}, given ${given}`);
    assert.deepEqual(c.touched, [], `alone ${alone}, given ${given}: the tab was touched`);
    assert.equal(asked, alone ? 0 : 1, "sending alone doesn't even ask");
  }
  // With it, and not alone: read, once.
  const c = chromeCounting();
  const got = await pageForSend({ alone: false, consent: async () => true, read: () => readForCoach(c, 7, 2, []) });
  assert.equal(got.host, "shop.example");
  assert.ok(c.touched.includes("read"));
});

test("the allow kept here holds only for this pairing's person and the current words", () => {
  const session = { token: "t", tenant: "t_acme", subject: "u_ana" };
  const allow = { tenant: "t_acme", subject: "u_ana", version: PAGE_CONSENT_VERSION, at: 1 };
  assert.equal(allowedHere(session, allow), true);
  assert.equal(allowedHere(null, allow), false, "no session: signed out or unpaired");
  assert.equal(allowedHere(session, null), false, "never allowed, or withdrawn");
  assert.equal(allowedHere({ ...session, subject: "u_bo" }, allow), false, "another person's");
  assert.equal(allowedHere({ ...session, tenant: "t_other" }, allow), false, "another account's");
  assert.equal(allowedHere(session, { ...allow, version: "0" }), false, "older words");
});

test("the worker reads the tab for Coach only through the page consent, and keeps the allow with the pairing", () => {
  const sw = readFileSync(new URL("../src/sw.js", import.meta.url), "utf8");
  const send = sw.slice(sw.indexOf("coach_send:"), sw.indexOf("aside_status:"));
  // "Send without the page", or files with no words (a page goes only with words): the page is never read.
  assert.match(send, /const alone = m\.alone === true \|\| !String\(m\.words \|\| ""\)\.trim\(\);/);
  assert.match(send, /pageForSend\(\{ alone, consent: pageConsentReady, read: \(\) => asideRead\(/);
  assert.equal((sw.match(/asideRead\(/g) || []).length, 2, "asideRead is defined once and called only through pageForSend");
  // The allow is in the pairing's own store, which an unpair (also the sign-out) wipes whole; a withdrawal clears it first.
  assert.match(sw, /indexedStore\.set\(PAGE_CONSENT, \{ tenant: session\.tenant, subject: session\.subject, version: PAGE_CONSENT_VERSION/);
  assert.match(sw, /async function pageWithdraw\(\) \{\n  await indexedStore\.set\(PAGE_CONSENT, null\);/);
  assert.match(sw, /async function wipe\(\) \{[\s\S]*?await indexedStore\.wipe\(\);/);
});

test("the page consent is read, given and withdrawn at tafs cloud, as the session's own", async () => {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push([init.method, url.replace(/^https:\/\/[^/]+/, ""), init.headers.authorization, init.body && JSON.parse(init.body)]);
    const given = init.method === "POST";
    return { ok: true, status: 200, json: async () => ({ consent: { given, version: PAGE_CONSENT_VERSION } }) };
  };
  const c = new Coach({ fetch, token: "tok" });
  assert.equal((await c.pageConsent()).given, false);
  assert.equal((await c.allowPage(PAGE_CONSENT_VERSION)).given, true);
  assert.equal((await c.withdrawPage()).given, false);
  assert.deepEqual(calls, [
    ["GET", "/v1/me/page-consent", "Bearer tok", undefined],
    ["POST", "/v1/me/page-consent", "Bearer tok", { version: PAGE_CONSENT_VERSION }],
    ["DELETE", "/v1/me/page-consent", "Bearer tok", undefined],
  ]);
});

test("the extension's page consent words' version is tafs cloud's", () => {
  const consent = readFileSync(new URL("../../runtime/src/cloud/consent.ts", import.meta.url), "utf8");
  assert.match(consent, new RegExp(`PAGE_CONSENT_VERSION = "${PAGE_CONSENT_VERSION}"`));
});
