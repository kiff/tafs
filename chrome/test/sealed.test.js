// Sealed sign-ins (docs/chrome.md, "Sealed values"): kept encrypted for one exact origin, never listed with a value, and
// filled only on the paired phone's own release of that handle, for that step, field and origin, once.

import { test } from "node:test";
import assert from "node:assert/strict";
import { Vault, originOf, parseHandle } from "../src/vault.js";
import { memoryStore } from "../src/store.js";
import { Runner } from "../src/runner.js";
import { LABEL, sign, newKeyPair, publicSPKI, b64u } from "../src/crypto.js";

const NOW = 1_800_000_000_000;

test("a sign-in is sealed for one exact https origin, and opens only there", async () => {
  const store = memoryStore();
  const v = new Vault(store);
  assert.equal(originOf("shop.example"), "https://shop.example");
  assert.equal(originOf("https://Shop.Example/login?x=1"), "https://shop.example");
  assert.equal(originOf("http://shop.example"), null);
  const id = await v.save("shop.example", "ana@shop.example", "hunter22");
  assert.deepEqual(await v.list(), [{ id, origin: "https://shop.example", hasUsername: true }]);
  assert.ok(!JSON.stringify(await v.list()).includes("hunter22"));
  assert.ok(!JSON.stringify([...store.map.values()].filter((x) => Array.isArray(x))).includes("hunter22"), "not kept in clear");
  assert.deepEqual(await v.handlesFor("https://shop.example"), [id + ":username", id + ":password"]);
  assert.deepEqual(await v.handlesFor("https://www.shop.example"), [], "a subdomain is another origin");
  assert.equal(await v.open(id + ":password", "https://shop.example"), "hunter22");
  assert.equal(await v.open(id + ":username", "https://shop.example"), "ana@shop.example");
  assert.equal(await v.open(id + ":password", "https://evil.example"), null);
  assert.equal(await v.open(id + ":cvc", "https://shop.example"), null);
  // An entry moved to another origin can't be opened there: the origin is bound into the encryption.
  const entries = await store.get("vault");
  await store.set("vault", entries.map((e) => ({ ...e, origin: "https://evil.example" })));
  assert.equal(await v.open(id + ":password", "https://evil.example"), null);
  // Nor can an entry's username and password be swapped in storage: each is bound to its entry and its part.
  await store.set("vault", entries.map((e) => ({ ...e, username: e.password, password: e.username })));
  assert.equal(await v.open(id + ":username", "https://shop.example"), null);
  assert.equal(await v.open(id + ":password", "https://shop.example"), null);
  await store.set("vault", entries);
  assert.equal(await v.open(id + ":password", "https://shop.example"), "hunter22");
  await v.remove(id);
  assert.deepEqual(await v.list(), []);
});

function fakeChrome(url) {
  const calls = [];
  const on = () => ({ addListener: () => {} });
  const page = {
    read_id: "rd_1",
    url,
    fields: [
      { ref: "f1", label: "Email", kind: "email" },
      { ref: "f2", label: "Password", kind: "password", masked: true },
    ],
    controls: [],
    links: [],
  };
  return {
    calls,
    webNavigation: { onBeforeNavigate: on(), onCommitted: on(), onCreatedNavigationTarget: on() },
    tabs: { onRemoved: on(), get: async (id) => ({ id, status: "complete", url }), update: async () => {}, group: async () => 7 },
    tabGroups: { update: async () => {} },
    windows: { create: async () => ({ id: 9, tabs: [{ id: 100 }] }), remove: async () => {} },
    permissions: { contains: async () => true },
    scripting: {
      executeScript: async (o) => {
        const src = String(o.func || "");
        if (src.includes("fillSealed")) calls.push(["fillSealed", ...o.args]);
        if (src.includes("TafsPage.read")) return [{ result: { ...page } }];
        if (src.includes("fillSealed")) return [{ result: { ok: true } }];
        return [{}];
      },
    },
  };
}

async function setup() {
  const phone = await newKeyPair();
  const phoneKey = b64u(await publicSPKI(phone.publicKey));
  const vault = new Vault(memoryStore());
  const id = await vault.save("shop.example", "ana@shop.example", "hunter22");
  const c = fakeChrome("https://shop.example/login");
  const r = new Runner({ chrome: c, sleep: async () => {}, vault, paired: () => ({ chromeId: "chr_1", phoneKey }), now: () => NOW });
  const sites_signed = { sites: ["shop.example"], sig: await sign(phone.privateKey, LABEL.sites, { chrome_id: "chr_1", grant: "g_1", sites: ["shop.example"] }) };
  const opened = await r.handle({ id: "op_0", run: "run_1", grant: "g_1", sites: ["shop.example"], sites_signed, op: "open", url: "https://shop.example/login" });
  return { phone, vault, id, c, r, opened };
}

function fill(id, extra = {}) {
  return { id: "op_1", run: "run_1", grant: "g_1", sites: ["shop.example"], op: "act", read_id: "rd_1", act: { kind: "fill", ref: "f2", sealed: id + ":password" }, ...extra };
}

async function release(phone, step, origin = "https://shop.example", extra = {}) {
  const body = { chrome_id: "chr_1", op: step.id, sealed: step.act.sealed, origin, field_ref: step.act.ref, read_id: step.read_id, nonce: "r1", exp: NOW + 30_000, ...extra };
  return { ...body, sig: await sign(phone.privateKey, LABEL.release, body) };
}

test("a read names the handles saved for its exact origin, never a value", async () => {
  const { opened, id } = await setup();
  assert.deepEqual(opened.page.sealed, [id + ":username", id + ":password"]);
});

test("a sealed fill runs only on the phone's own release, for that step, field and origin, once", async () => {
  const { phone, id, c, r } = await setup();
  const step = fill(id);
  assert.equal((await r.handle(step)).error.code, "release_missing", "no release");
  const planque = await newKeyPair();
  const forged = await release(planque, step);
  assert.equal((await r.handle({ ...step, release: forged })).error.code, "release_missing", "planque can't release");
  assert.equal((await r.handle({ ...step, release: await release(phone, step, "https://evil.example") })).error.code, "release_missing", "another origin");
  r.resume("run_1"); // three refused acts in a row pause the run, as any failures do
  // The same field ref on another read may be another field: the release names the read the owner saw.
  assert.equal((await r.handle({ ...step, release: await release(phone, step, undefined, { read_id: "rd_0" }) })).error.code, "release_missing", "another read");
  r.resume("run_1"); // three refused acts in a row pause the run, as any failures do
  assert.equal((await r.handle({ ...step, release: await release(phone, step, undefined, { exp: NOW - 1 }) })).error.code, "release_missing", "expired");
  r.resume("run_1");
  assert.equal(c.calls.length, 0, "nothing was typed");

  r.resume("run_1");
  const good = await release(phone, step);
  const out = await r.handle({ ...step, release: good });
  assert.ok(out.page, JSON.stringify(out.error));
  assert.deepEqual(c.calls[0], ["fillSealed", "f2", "rd_1", "https://shop.example", "hunter22", "password"]);
  assert.equal((await r.handle({ ...step, release: good })).error.code, "release_missing", "a release opens one fill");
});

test("a sealed password goes only into a password field, and a username only into a text or email field", async () => {
  const { phone, id, r } = await setup();
  const intoEmail = fill(id, { act: { kind: "fill", ref: "f1", sealed: id + ":password" } });
  assert.equal((await r.handle({ ...intoEmail, release: await release(phone, intoEmail) })).error.code, "secret_field");
  const user = fill(id, { id: "op_2", act: { kind: "fill", ref: "f1", sealed: id + ":username" } });
  const out = await r.handle({ ...user, release: await release(phone, user, undefined, { nonce: "r2" }) });
  assert.ok(out.page, JSON.stringify(out.error));
});

test("a handle is exactly sec_<id>:password or sec_<id>:username, so the part the phone's card names is the part that is filled", async () => {
  assert.deepEqual(parseHandle("sec_abc-1_Z:password"), { id: "sec_abc-1_Z", part: "password" });
  assert.deepEqual(parseHandle("sec_abc:username"), { id: "sec_abc", part: "username" });
  for (const bad of ["sec_x:password:username", "sec_x:username:password", "sec_x:password:", "sec_x:token", "sec_:password", "x:password", "sec_x", "", ":password", " sec_x:password", "sec_x:password\n", "sec_x::password", 7, null, undefined, { toString: () => "sec_x:password" }]) {
    assert.equal(parseHandle(bad), null, JSON.stringify(String(bad)));
  }
});

test("a handle with a third segment is refused whole: nothing is released, opened or filled, and the vault opens none", async () => {
  const { phone, id, r, c, vault } = await setup();
  // The phone's card would name the first or the last part; the extension must not pick the other.
  // On the field each could be filled into by reading only its first or its last part: the password field, and the email field.
  for (const [sealed, ref] of [[id + ":password:username", "f2"], [id + ":username:password", "f1"]]) {
    const step = fill(id, { id: "op_" + sealed.length, act: { kind: "fill", ref, sealed } });
    const out = await r.handle({ ...step, release: await release(phone, step, undefined, { nonce: "n" + sealed.length }) });
    assert.equal(out.error.code, "secret_field", sealed);
    assert.equal(await vault.open(sealed, "https://shop.example"), null);
  }
  assert.ok(!c.calls.some((x) => x[0] === "fillSealed"), "nothing was filled");
});
