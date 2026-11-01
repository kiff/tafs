import { describe, expect, it } from "vitest";
import type { OutgoingEmail } from "../src/cloud/doors/email.js";
import type { DoorStore } from "../src/cloud/doors/model.js";
import { handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { isAccountItem } from "../src/cloud/account.js";
import { REPORTS, reportKey } from "../src/cloud/reports.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ANA_ID = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const BO_ID = { ...ANA_ID, subject: "u_bo" };
const NOW = new Date("2026-10-16T12:00:00Z");
const ANA = { requester: { kind: "member" as const, id: "u_ana" } };
const BO = { requester: { kind: "member" as const, id: "u_bo" } };
const RAE = "taf-aaaa0000000000000000";
const COACH = "taf-cccc0000000000000000";

function kiff(byToken: Record<string, object>) {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = byToken[token];
    if (me && String(url).endsWith("/v1/me/access")) {
      return new Response(JSON.stringify({ agents: { rae: { use: true, oversee: false, manage: true }, coach: { use: true, oversee: false, manage: true } } }), { status: 200 });
    }
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

function memoryStore(): DoorStore & { held: Map<string, unknown> } {
  const held = new Map<string, unknown>();
  return {
    held,
    async get(k) { return structuredClone(held.get(k)); },
    async put(k, v) { held.set(k, structuredClone(v)); },
    async create(k, v) { if (held.has(k)) return false; held.set(k, structuredClone(v)); return true; },
    async remove(k) { held.delete(k); },
    async swap() { return false; },
    async bump() { return false; },
  };
}

function world(opts: { emailFails?: boolean; noEmail?: boolean } = {}) {
  const agents = fakeAgents(() => NOW);
  const tafs = memoryTafs();
  for (const [id, name, job] of [[RAE, "Rae", "refunds"], [COACH, "Coach", "setup"]] as const) {
    const taf: Taf = { id, name, job, kiffAgent: name.toLowerCase(), nameClaim: "c", created: "2026-10-15T09:00:00Z" };
    tafs.held.set(`t_acme#${id}`, taf);
    void agents.api.putAgent("t_acme", id, {} as never);
  }
  const store = memoryStore();
  const sent: OutgoingEmail[] = [];
  const email = opts.noEmail ? undefined : async () => ({
    domain: "tafs.example",
    send: async (e: OutgoingEmail) => {
      if (opts.emailFails) throw new Error("provider down");
      sent.push(e);
    },
  });
  return { agents, tafs, store, sent, email };
}
type World = ReturnType<typeof world>;

async function call(w: World, method: string, path: string, opts: { token?: string; body?: unknown; noReports?: boolean } = {}) {
  const event: HTTPEvent = {
    rawPath: path,
    requestContext: { http: { method } },
    headers: opts.token ? { Authorization: `Bearer ${opts.token}` } : {},
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  };
  const r = await handle(event, {
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(),
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: kiff({ ana: ANA_ID, bo: BO_ID }),
    ...(opts.noReports ? {} : { reports: { store: w.store, ...(w.email ? { email: w.email } : {}) } }),
    now: () => NOW,
  });
  return { status: r.statusCode, body: JSON.parse(r.body) };
}

/** A chat with `taf`: Ana's question and its answer. */
async function chat(w: World, taf: string, answer: string, who = ANA) {
  const run = await w.agents.api.startRun("t_acme", taf, "Why was order 7 held?", who);
  w.agents.say(run, answer);
  return { session: run.session };
}

async function answerID(w: World, taf: string, token = "ana") {
  const r = await call(w, "GET", `/v1/tafs/${taf}/conversation`, { token });
  return (r.body.messages as { id: string; from: string; session: string }[]).find((m) => m.from === "taf")!;
}

describe("reporting an answer", () => {
  it("keeps the answer as kiff/agents holds it and emails support, never the app's words for it", async () => {
    const w = world();
    await chat(w, RAE, "Order 7 is over the Card's $50.");
    const m = await answerID(w, RAE);
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages/${m.id}/report`, { token: "ana", body: { reason: "offensive", note: " Rude. ", text: "made up" } });
    expect(r.status).toBe(200);
    expect(r.body.report).toMatchObject({ at: NOW.toISOString(), new: true });
    const kept = w.store.held.get(reportKey("t_acme", "u_ana", m.id)) as Record<string, unknown>;
    expect(kept).toMatchObject({ by: "u_ana", taf: RAE, coach: false, message: m.id, session: m.session, reason: "offensive", note: "Rude.", text: "Order 7 is over the Card's $50." });
    expect(w.sent).toHaveLength(1);
    expect(w.sent[0]).toMatchObject({ to: REPORTS.to, from: "tafs reports <reports@tafs.example>", subject: "Report: offensive answer from the taf Rae" });
    expect(w.sent[0]!.text).toContain("Order 7 is over the Card's $50.");
    expect(w.sent[0]!.text).not.toContain("made up");
  });

  it("names Coach when the answer is Coach's", async () => {
    const w = world();
    await chat(w, COACH, "Your taf is set up.");
    const m = await answerID(w, COACH);
    const r = await call(w, "POST", `/v1/tafs/${COACH}/messages/${m.id}/report`, { token: "ana", body: { reason: "harmful" } });
    expect(r.status).toBe(200);
    expect(w.sent[0]!.subject).toBe("Report: harmful answer from Coach");
  });

  it("answers a second report of the same answer with the first, and sends nothing again", async () => {
    const w = world();
    await chat(w, RAE, "Refunded.");
    const m = await answerID(w, RAE);
    const first = await call(w, "POST", `/v1/tafs/${RAE}/messages/${m.id}/report`, { token: "ana", body: { reason: "wrong" } });
    const again = await call(w, "POST", `/v1/tafs/${RAE}/messages/${m.id}/report`, { token: "ana", body: { reason: "other" } });
    expect(again.status).toBe(200);
    expect(again.body.report).toEqual({ ...first.body.report, new: false });
    expect(w.sent).toHaveLength(1);
  });

  it("keeps the report when the email can't go, or isn't set up", async () => {
    for (const opts of [{ emailFails: true }, { noEmail: true }]) {
      const w = world(opts);
      await chat(w, RAE, "Refunded.");
      const m = await answerID(w, RAE);
      const r = await call(w, "POST", `/v1/tafs/${RAE}/messages/${m.id}/report`, { token: "ana", body: { reason: "wrong" } });
      expect(r.status).toBe(200);
      expect(w.store.held.size).toBe(1);
    }
  });

  it("refuses the person's own message, someone else's chat, an unknown id and a bad reason", async () => {
    const w = world();
    await chat(w, RAE, "Refunded.");
    const own = (await call(w, "GET", `/v1/tafs/${RAE}/conversation`, { token: "ana" })).body.messages.find((m: { from: string }) => m.from === "owner");
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages/${own.id}/report`, { token: "ana", body: { reason: "wrong" } })).status).toBe(404);
    const m = await answerID(w, RAE);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages/${m.id}/report`, { token: "bo", body: { reason: "wrong", session: m.session } })).status).toBe(404);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages/nope.1/report`, { token: "ana", body: { reason: "wrong" } })).status).toBe(404);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages/${m.id}/report`, { token: "ana", body: { reason: "spam" } })).status).toBe(400);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages/${m.id}/report`, { token: "ana", body: { reason: "other", note: "x".repeat(REPORTS.noteChars + 1) } })).status).toBe(400);
    expect(w.sent).toHaveLength(0);
  });

  it("finds an answer in a chat the person names, beyond the thread", async () => {
    const w = world();
    const { session } = await chat(w, RAE, "Refunded.");
    const m = await answerID(w, RAE);
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages/${m.id}/report`, { token: "ana", body: { reason: "wrong", session } });
    expect(r.status).toBe(200);
  });

  it("says so when reports aren't taken", async () => {
    const w = world();
    await chat(w, RAE, "Refunded.");
    const m = await answerID(w, RAE);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages/${m.id}/report`, { token: "ana", body: { reason: "wrong" }, noReports: true })).status).toBe(503);
  });

  it("keeps the report under the account, so deleting the account erases it", () => {
    expect(isAccountItem(reportKey("t_acme", "u_ana", "run.3"), "t_acme")).toBe(true);
  });
});
