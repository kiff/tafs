import { describe, expect, it } from "vitest";
import { handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const NOW = new Date("2026-10-16T12:00:00Z");
const PEOPLE = {
  ana: { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] },
  bo: { tenant_id: "t_acme", subject: "u_bo", api_key: false, capabilities: ["read", "cards.manage"] },
};
const ANA = { requester: { kind: "member" as const, id: "u_ana" } };
const BO = { requester: { kind: "member" as const, id: "u_bo" } };
const RAE = "taf-aaaa0000000000000000";
const COACH = "taf-cccc0000000000000000";

function kiff() {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = PEOPLE[token as keyof typeof PEOPLE];
    if (me && String(url).endsWith("/v1/me/access")) return new Response(JSON.stringify({ agents: {} }), { status: 200 });
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

function world() {
  const agents = fakeAgents(() => NOW);
  const tafs = memoryTafs();
  for (const [id, name, job] of [[RAE, "Rae", "refunds"], [COACH, "Setup", "setup"]] as const) {
    const taf: Taf = { id, name, job, kiffAgent: name.toLowerCase(), nameClaim: "c", created: "2026-10-15T09:00:00Z" };
    tafs.held.set(`t_acme#${id}`, taf);
    void agents.api.putAgent("t_acme", id, {} as never);
  }
  void agents.api.createSchedule("t_acme", { id: RAE, agent: RAE, cron: "*/30 * * * *", input: "Work the refund queue.", enabled: true });
  return { agents, tafs };
}
type World = ReturnType<typeof world>;

async function exportAs(w: World, token: string) {
  const event: HTTPEvent = { rawPath: "/v1/me/chats/export", requestContext: { http: { method: "GET" } }, headers: { Authorization: `Bearer ${token}` } };
  const r = await handle(event, {
    agents: w.agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(),
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: kiff(),
    now: () => NOW,
  });
  return { status: r.statusCode, body: JSON.parse(r.body) };
}

describe("a person exports their own chats (kiff-cloud#1409)", () => {
  it("has their own chats, Coach's too, and their own notes, never another person's or the taf's job runs", async () => {
    const w = world();
    const mine = await w.agents.api.startRun("t_acme", RAE, "Why was order 7 held?", ANA);
    w.agents.say(mine, "It was over the Card's $50.");
    const coach = await w.agents.api.startRun("t_acme", COACH, "Set up refunds", ANA);
    w.agents.say(coach, "Your taf is set up.");
    const bos = await w.agents.api.startRun("t_acme", RAE, "Bo's private question", BO);
    w.agents.say(bos, "Bo's private answer.");
    const job = await w.agents.api.trigger("t_acme", RAE);
    w.agents.say(job, "Refunded 3 orders.");
    w.agents.notes.set(`t_acme|${RAE}`, [
      { agent: RAE, scope: "member:u_ana", name: "tone", text: "Ana likes short answers.", updated: "2026-10-16T10:00:00Z" },
      { agent: RAE, scope: "member:u_bo", name: "bo", text: "Bo's note.", updated: "2026-10-16T10:00:00Z" },
      { agent: RAE, name: "policy", text: "A shared note.", updated: "2026-10-16T10:00:00Z" },
    ]);

    const r = await exportAs(w, "ana");
    expect(r.status).toBe(200);
    const byName = Object.fromEntries(r.body.tafs.map((t: { name: string }) => [t.name, t]));
    expect(Object.keys(byName).sort()).toEqual(["Coach", "Rae"]);
    const rae = byName.Rae;
    expect(rae.chats).toHaveLength(1);
    expect(rae.chats[0].messages.map((m: { from: string; text: string }) => [m.from, m.text])).toEqual([
      ["owner", "Why was order 7 held?"], ["taf", "It was over the Card's $50."],
    ]);
    expect(rae.notes).toEqual([{ name: "tone", text: "Ana likes short answers.", updated: "2026-10-16T10:00:00.000Z" }]);
    expect(byName.Coach.chats[0].messages.at(-1).text).toBe("Your taf is set up.");
    const all = JSON.stringify(r.body);
    for (const never of ["Bo's private", "Bo's note", "A shared note", "Refunded 3 orders"]) expect(all).not.toContain(never);
    expect(r.body.readable).toContain("# Rae");
    expect(r.body.readable).toContain("You:\nWhy was order 7 held?");
    expect(r.body.readable).toContain("Ana likes short answers.");
  });

  it("says so when there's nothing to export", async () => {
    const r = await exportAs(world(), "bo");
    expect(r.status).toBe(200);
    expect(r.body.tafs).toEqual([]);
    expect(r.body.readable).toContain("You have no chats or notes in tafs.");
  });

  it("needs a signed-in person", async () => {
    expect((await exportAs(world(), "nobody")).status).toBe(401);
  });
});
