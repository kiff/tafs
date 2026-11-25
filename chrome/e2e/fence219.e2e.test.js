// End to end: Chrome's own fence says what refuseURL says (0.2.19, docs/chrome.md, "The fence"). A host inside a granted site that
// refuseURL refuses is blocked by Chrome before its request is sent, never left to the extension's backstop: a sensitive label under
// a granted site (`bank.shop.test`), a host Chrome never opens under a granted one (`accounts.google.com` under `google.com`), a
// site a grant may not list (`github.io`, a public suffix) and an address. Each is reached by the taf's click on a link and by a 302
// from a granted page, in a tab of the run's own and in the person's tab the run joined, with Chrome already holding a connection
// to it (the person uses it), and the server logs no request for it from the run.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { playwright, start, newRun, ref, until } from "./harness.mjs";
import { joining } from "./joining.mjs";
import { TARGET_WORDS } from "./sites.mjs";

const skip = !playwright;
let h;
let j;

before(async () => {
  if (skip) return;
  h = await start({ cloud: true });
  j = await joining(h);
});

after(async () => {
  if (h) await h.close();
});

/** The person's own visit to `host`, so Chrome holds a connection to it (the request then goes at once if nothing blocks it). */
async function warm(host) {
  const page = await h.context.newPage();
  const before = asked(host, "case=warm").length;
  await page.goto(h.sites.url(host, "/here?case=warm"));
  await page.close();
  // The host answers the person: a "nothing asked" below is the fence's doing, not a host the test can't reach.
  assert.ok(asked(host, "case=warm").length > before, host + " never answered the person's own visit");
}

/** What `host` was asked for whose query carries `tag`. */
const asked = (host, tag) => h.sites.hits.filter((x) => x.startsWith(host + " ") && x.includes(tag));

/** A step under the grant of `sites`, the phone's signature over them with it. */
async function granted(sites, fields) {
  return h.planque.sendStep({ sites, sites_signed: await h.planque.sitesSigned(sites), ...fields });
}

const SHOP = ["shop.test", "news.test"];
const CASES = [
  // A label that names a bank, under a granted site: refuseURL's sensitive labels.
  { name: "a bank label under a granted site", sites: SHOP, from: "shop.test", target: "bank.shop.test", code: "site_blocked", joined: true },
  // A host Chrome never opens (ALWAYS_BLOCKED) under a granted site.
  { name: "accounts.google.com under a google.com grant", sites: ["google.com"], from: "www.google.com", target: "accounts.google.com", code: "site_blocked", joined: false },
  // A public suffix a grant lists: siteOK drops it, so nothing under it is the run's.
  { name: "a site under a listed github.io", sites: ["github.io", "shop.test"], from: "shop.test", target: "me.github.io", code: "site_not_listed", joined: true },
  // An address: never a site.
  { name: "an address", sites: SHOP, from: "shop.test", target: "127.0.0.1", code: "address_private", joined: true },
];

for (const c of CASES) {
  const target = (tag) => h.sites.url(c.target, "/here?case=" + tag);

  test(`${c.name}: an open of it is ${c.code}, and nothing is asked of it`, { skip }, async () => {
    await warm(c.target);
    const tag = "open-" + c.target;
    const r = await granted(c.sites, { op: "open", run: newRun("f-open"), url: target(tag) });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, c.code);
    assert.deepEqual(asked(c.target, tag), []);
  });

  for (const how of ["link", "302"]) {
    for (const kind of c.joined ? ["own", "joined"] : ["own"]) {
      // In the person's tab the act is refused in the page before it fires (page.js offFence), with refuseURL's very code (0.2.19).
      test(`${c.name}, by ${how === "link" ? "the taf's click on a link" : "a 302 from a granted page"}, in ${kind === "own" ? "a tab of the run's own" : "the person's joined tab"}: Chrome blocks it before it's asked`, { skip }, async () => {
        await warm(c.target);
        const tag = `${how}-${kind}-${c.target}`;
        const goal = target(tag);
        // The page the run is on: a granted page with a link, to the target or to a granted 302 to the target.
        const via = how === "link" ? goal : h.sites.url(c.from, "/redirect-to?to=" + encodeURIComponent(goal));
        const start = h.sites.url(c.from, "/link-to?to=" + encodeURIComponent(via));
        const run = newRun("f-" + how + "-" + kind);
        let agentRun;
        if (kind === "joined") ({ agentRun } = await j.sendFromPanel(start));
        const as = kind === "joined" ? { agent_run: agentRun } : {};
        const opened = await granted(c.sites, { op: "open", run, url: start, ...as });
        assert.equal(opened.ok, true, JSON.stringify(opened.error));
        const r = await granted(c.sites, { op: "act", run, read_id: opened.page.read_id, act: { kind: "click", ref: ref(opened.page, "Go there", "links") }, ...as });
        await new Promise((resolve) => setTimeout(resolve, 500));
        await h.fromPanel({ type: "stop", run });
        assert.deepEqual(asked(c.target, tag), [], `${c.target} was asked for its page`);
        assert.ok(!JSON.stringify(r).includes(TARGET_WORDS), "its page was read");
        assert.equal(r.ok, false, "the act went through");
        assert.equal(r.error.code, c.code, "the act's answer says why: " + JSON.stringify(r.error));
      });
    }
  }
}
