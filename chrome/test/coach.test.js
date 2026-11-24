import { test } from "node:test";
import assert from "node:assert/strict";
import { Coach, MAX_TEXT, reusing } from "../src/coach.js";

test("the owner's words go alone, trimmed and cut to tafs cloud's limit: no page goes with them", async () => {
  const sent = [];
  const fetch = async (url, init) => {
    if (init.body) sent.push(JSON.parse(init.body));
    const body = url.endsWith("/v1/setup") ? { setup: { id: "taf-coach" } } : { session: "s1" };
    return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
  };
  const c = new Coach({ fetch, token: "tok" });
  await c.send("  Set up a taf here  ", { clientID: "m1", page: { url: "https://shop.example/", title: "x", text: "y" } });
  await c.send("x".repeat(MAX_TEXT + 50), { clientID: "m2" });
  const texts = sent.filter((b) => b.client_message_id).map((b) => b.text);
  assert.deepEqual(texts[0], "Set up a taf here");
  assert.equal(texts[1].length, MAX_TEXT);
});

test("Coach is read and written through tafs cloud's own routes, as the session's own", async () => {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push([init.method, url, init.headers.authorization, init.body && JSON.parse(init.body)]);
    const body = url.endsWith("/v1/setup")
      ? { setup: { id: "taf-coach" } }
      : url.endsWith("/conversation")
        ? { messages: [{ from: "owner", text: "hi", session: "s1" }, { from: "taf", text: "Hello", session: "s1" }, { from: "x" }], working: null, components: [{ type: "approval", id: "c1" }] }
        : { session: "s1", run: "r1" };
    return { ok: true, status: 200, json: async () => body };
  };
  const c = new Coach({ token: "kiff_session_view_only", fetch, base: "https://cloud.test" });
  const t = await c.thread();
  assert.deepEqual(t.messages.map((m) => m.from), ["owner", "taf"]);
  assert.equal(t.waitsOnPhone, true);
  await c.send("Set up a taf here", { clientID: "m1" });
  const sent = calls.at(-1);
  assert.equal(sent[0], "POST");
  assert.equal(sent[1], "https://cloud.test/v1/tafs/taf-coach/messages");
  assert.equal(sent[2], "Bearer kiff_session_view_only");
  assert.equal(sent[3].text, "Set up a taf here", "the owner's words alone");
  assert.equal(sent[3].client_message_id, "m1");
  assert.equal(calls.filter((x) => x[1].endsWith("/v1/setup")).length, 1, "Coach's id read once");
});

test("Coach reads a chosen chat, lists the earlier ones, and sends into a chosen chat or a new one", async () => {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push([init.method, url, init.body && JSON.parse(init.body)]);
    const body = url.endsWith("/v1/setup")
      ? { setup: { id: "taf-coach" } }
      : url.endsWith("/chats")
        ? { chats: [{ session: "s2", title: "Late parcels", updated: "2026-11-22T10:00:00Z" }, { session: "s3", title: "Old", set_aside: true }, { title: "no session" }] }
        : url.includes("/conversation")
          ? { messages: [{ from: "owner", text: "hi", session: "s2" }], working: null }
          : { session: "s9", new_session: true, run: "r1" };
    return { ok: true, status: 200, json: async () => body };
  };
  const c = new Coach({ token: "kiff_session_view_only", fetch, base: "https://cloud.test" });
  assert.deepEqual(await c.chats(), [{ session: "s2", title: "Late parcels", updated: "2026-11-22T10:00:00Z" }], "set aside and nameless-session chats aren't offered");
  await c.thread("s 2");
  assert.equal(calls.at(-1)[1], "https://cloud.test/v1/tafs/taf-coach/conversation/s%202", "a chosen chat's own route, its session escaped");
  await c.thread();
  assert.equal(calls.at(-1)[1], "https://cloud.test/v1/tafs/taf-coach/conversation", "none reads the live chat");
  await c.send("hello", { newChat: true, clientID: "m1" });
  assert.deepEqual(calls.at(-1)[2], { text: "hello", client_message_id: "m1", new_chat: true });
  await c.send("again", { session: "s9", clientID: "m2" });
  assert.deepEqual(calls.at(-1)[2], { text: "again", client_message_id: "m2", session: "s9" });
  await c.send("live", { clientID: "m3" });
  assert.deepEqual(calls.at(-1)[2], { text: "live", client_message_id: "m3" }, "no choice: the live chat");
});


test("a conversation is archived and put back on tafs cloud's own routes, unsigned, and the archived list reads its own route", async () => {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push([init.method, url, init.body, init.headers]);
    const body = url.endsWith("/v1/setup")
      ? { setup: { id: "taf-coach" } }
      : url.endsWith("/chats/archived")
        ? { chats: [{ session: "s4", title: "Old setup", updated: "2026-11-20T10:00:00Z" }, { title: "no session" }] }
        : { session: "s4", archived: !url.endsWith("/unarchive") };
    return { ok: true, status: 200, json: async () => body };
  };
  const c = new Coach({ token: "kiff_session_view_only", fetch, base: "https://cloud.test" });
  assert.deepEqual(await c.archivedChats(), [{ session: "s4", title: "Old setup", updated: "2026-11-20T10:00:00Z" }], "a chat with no session isn't offered");
  assert.equal(calls.at(-1)[1], "https://cloud.test/v1/tafs/taf-coach/chats/archived");
  await c.archive("s 4");
  assert.deepEqual(calls.at(-1).slice(0, 3), ["POST", "https://cloud.test/v1/tafs/taf-coach/chats/s%204/archive", undefined], "its session escaped, no body");
  assert.ok(!Object.keys(calls.at(-1)[3]).some((h) => /signature|passkey/i.test(h)), "nothing signed: archiving gives nothing");
  await c.archive("s4", false);
  assert.equal(calls.at(-1)[1], "https://cloud.test/v1/tafs/taf-coach/chats/s4/unarchive", "false puts it back");
  await assert.rejects(c.archive(""), (e) => e.code === "bad_chat", "no session, nothing sent");
  assert.equal(calls.filter((x) => x[0] === "POST").length, 2);
});

test("one Coach is kept for a session, so the account's Coach id is read once across every read of the chat", async () => {
  const calls = [];
  const fetch = async (url) => {
    calls.push(url);
    const body = url.endsWith("/v1/setup") ? { setup: { id: "taf-coach" } } : { messages: [], chats: [] };
    return { ok: true, status: 200, json: async () => body };
  };
  const make = reusing((token) => new Coach({ token, fetch, base: "https://cloud.test" }));
  for (let i = 0; i < 5; i++) await make("tok_a").thread();
  await make("tok_a").chats();
  assert.equal(calls.filter((u) => u.endsWith("/v1/setup")).length, 1, "setup read once for six reads");
  assert.equal(make("tok_a"), make("tok_a"), "the same Coach for the same session");
  const other = make("tok_b");
  await other.thread();
  assert.equal(calls.filter((u) => u.endsWith("/v1/setup")).length, 2, "a new session reads it again");
});

test("the panel reads the person's tafs and a taf's own chat, and writes to it, through tafs cloud's routes", async () => {
  const calls = [];
  const fetch = async (url, o) => {
    calls.push([o.method, url.replace("https://cloud.test", ""), o.body ? JSON.parse(o.body) : null]);
    const body = url.endsWith("/v1/tafs")
      ? { tafs: [{ id: "taf-a", name: "Wiki lookup", kiff_agent: "agt_wiki" }, { id: "taf-x", name: "No agent" }] }
      : url.endsWith("/conversation")
        ? { messages: [{ from: "owner", text: "Search lighthouse", at: "t1" }, { from: "taf", text: "Done.", at: "t2" }, { from: "system", text: "x" }], working: { session: "s1" }, components: [{ type: "approval" }] }
        : { ok: true };
    return { ok: true, status: 200, json: async () => body };
  };
  const c = new Coach({ token: "tok", fetch, base: "https://cloud.test" });
  assert.deepEqual(await c.tafs(), [{ id: "taf-a", name: "Wiki lookup", agent: "agt_wiki" }], "only a taf with its KIFF agent");
  const t = await c.tafThread("taf-a");
  assert.deepEqual(t.messages.map((m) => m.text), ["Search lighthouse", "Done."]);
  assert.equal(t.working, "s1");
  assert.equal(t.waitsOnPhone, true);
  await c.sendToTaf("taf-a", "  Again  ", "cm1");
  assert.deepEqual(calls.at(-1), ["POST", "/v1/tafs/taf-a/messages", { text: "Again", client_message_id: "cm1" }]);
  await assert.rejects(c.sendToTaf("taf-a", "   ", "cm2"), /nothing to send/);
});

test("a taf's chat reads the person's tafs once per change of this Chrome's connections, not on every read or send", async () => {
  let listed = 0;
  let fail = false;
  const fetch = async (url) => {
    if (url.endsWith("/v1/tafs")) {
      listed++;
      if (fail) return { ok: false, status: 503, json: async () => ({ error: "down" }) };
    }
    const body = url.endsWith("/v1/tafs") ? { tafs: [{ id: "taf-a", name: "A", kiff_agent: "agt_a" }] } : { messages: [] };
    return { ok: true, status: 200, json: async () => body };
  };
  const c = new Coach({ token: "tok", fetch, base: "https://cloud.test" });
  const key = JSON.stringify([{ grant: "g1", agent: "agt_a" }]);
  // Five reads of the chat, at once and one after another: one read of the list.
  await Promise.all([c.keptTafs(key), c.keptTafs(key)]);
  for (let i = 0; i < 3; i++) await c.keptTafs(key);
  assert.equal(listed, 1);
  // The connections changed: read again, once.
  await c.keptTafs("[]");
  await c.keptTafs("[]");
  assert.equal(listed, 2);
  // The panel shown again reads it fresh.
  await c.keptTafs("[]", true);
  assert.equal(listed, 3);
  // A read that failed isn't kept: the next one asks again.
  fail = true;
  await assert.rejects(c.keptTafs("[x]"));
  fail = false;
  assert.deepEqual((await c.keptTafs("[x]")).map((t) => t.id), ["taf-a"]);
  assert.equal(listed, 5);
});
