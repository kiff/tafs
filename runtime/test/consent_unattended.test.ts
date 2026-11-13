import { describe, expect, it } from "vitest";
import { AgentsError } from "../src/cloud/agents.js";
import { consentPauseOf, recordMaker, tafConsentOf, unattendedConsent, waitsForConsent } from "../src/cloud/consent.js";
import { waitsToStart } from "../src/cloud/doors/hold.js";
import { handle, type HTTPEvent, settleConsent, sweepConsent, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

// Runs with no person in the moment (docs/accounts.md): each taf's runs take one person's consent, its maker's, else the
// account's first yes from someone who manages its tafs, else that of a manager who took it over. Without it, the taf is
// paused with why, so a schedule firing in kiff/agents starts nothing either, and a yes resumes it.
const ANA = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const CY = { ...ANA, subject: "u_cy" };
const BO = { ...ANA, subject: "u_bo", capabilities: ["read"] };
const NOW = new Date("2026-11-13T09:00:00Z");
const RAE = "taf-aaaa0000000000000000";

function kiff(byToken: Record<string, object>) {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = byToken[token];
    if (me && String(url).endsWith("/v1/me/access")) return new Response(JSON.stringify({ agents: { rae: { use: true, oversee: true, manage: true } } }), { status: 200 });
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

function world() {
  const agents = fakeAgents(() => NOW);
  const tafs = memoryTafs();
  const taf: Taf = { id: RAE, name: "Rae", job: "refunds", kiffAgent: "rae", nameClaim: "c", created: "2026-11-01T09:00:00Z" };
  tafs.held.set(`t_acme#${RAE}`, taf);
  void agents.api.putAgent("t_acme", RAE, {} as never);
  agents.calls.length = 0;
  const coach = memoryCoach(() => NOW);
  const deps = {
    agents: agents.api, aiConsent: true, keys: memoryKeys(), accounts: memoryAccounts(), tafs,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), coach,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const }, forward: async () => undefined,
    fetch: kiff({ ana: ANA, bo: BO, cy: CY }), now: () => NOW,
  };
  return { agents, tafs, coach, deps };
}
type World = ReturnType<typeof world>;

async function call(w: World, method: string, path: string, token: string, body?: unknown) {
  const event: HTTPEvent = {
    rawPath: path, requestContext: { http: { method } },
    headers: { Authorization: `Bearer ${token}` }, body: body === undefined ? undefined : JSON.stringify(body),
  };
  const r = await handle(event, w.deps);
  return { status: r.statusCode, body: JSON.parse(r.body) as Record<string, unknown> };
}

const allow = (w: World, token: string) => call(w, "POST", "/v1/me/ai-consent", token, { version: "1" });
const halted = async (w: World) => (await w.agents.api.halted("t_acme", RAE)).own;

describe("runs with no person in the moment take their taf's consent", () => {
  it("pauses a taf from before until someone who manages tafs allows AI use, then resumes it", async () => {
    const w = world();
    // No one has allowed anything: the sweep pauses it, with why, and says so on the taf.
    expect(await sweepConsent(w.deps)).toEqual([{ tenant: "t_acme", paused: [RAE], resumed: [] }]);
    expect(await halted(w)).toBe(true);
    expect((await consentPauseOf(w.coach, "t_acme", RAE))?.why).toBe("no_one");
    const shown = await call(w, "GET", "/v1/tafs", "ana");
    expect((shown.body.tafs as Array<{ id: string; consent_paused?: unknown }>).find((t) => t.id === RAE)?.consent_paused).toEqual({ why: "no_one", words: expect.stringContaining("Paused: no one has allowed AI use") });
    // Again: nothing changes, nothing is halted twice.
    w.agents.calls.length = 0;
    expect(await sweepConsent(w.deps)).toEqual([]);
    expect(w.agents.calls.filter((c) => c.startsWith("halt"))).toEqual([]);
    // Someone who can't manage tafs allowing it covers their own words, not the account's tafs.
    await allow(w, "bo");
    expect(await halted(w)).toBe(true);
    // A manager's yes is the account's first: the taf from before takes it, and goes on at once.
    expect((await allow(w, "ana")).status).toBe(200);
    expect(await halted(w)).toBe(false);
    expect(await consentPauseOf(w.coach, "t_acme", RAE)).toBeUndefined();
    expect((await tafConsentOf(w.coach, "t_acme", RAE))?.subject).toBe("u_ana");
  });

  it("takes its maker's consent; their withdrawal pauses it until another manager takes it over", async () => {
    const w = world();
    await recordMaker(w.coach, "t_acme", RAE, "u_cy", NOW);
    await allow(w, "ana");
    // Ana's yes is the account's first, but Rae's maker is Cy, who hasn't allowed it.
    expect(await unattendedConsent(w.coach, "t_acme", RAE)).toEqual({ why: "not_given" });
    expect(await halted(w)).toBe(true);
    await allow(w, "cy");
    expect(await halted(w)).toBe(false);
    // Cy withdraws: paused again, and an owner's resume can't step past it.
    await call(w, "DELETE", "/v1/me/ai-consent", "cy");
    expect((await consentPauseOf(w.coach, "t_acme", RAE))?.why).toBe("not_given");
    expect(await halted(w)).toBe(true);
    // Taking it over needs the manager's own consent, and resumes it.
    expect((await call(w, "POST", `/v1/tafs/${RAE}/ai-consent`, "bo")).status).toBe(403);
    const taken = await call(w, "POST", `/v1/tafs/${RAE}/ai-consent`, "ana");
    expect(taken.status).toBe(200);
    expect((taken.body.taf as { consent_paused?: unknown }).consent_paused).toBeUndefined();
    expect(await halted(w)).toBe(false);
    expect(await tafConsentOf(w.coach, "t_acme", RAE)).toMatchObject({ subject: "u_ana", by: "took_over" });
  });

  it("leaves an owner's own halt in place when a yes ends the pause", async () => {
    const w = world();
    await w.agents.api.halt("t_acme", RAE);
    expect(await settleConsent(w.deps, "t_acme")).toEqual({ paused: [RAE], resumed: [] });
    expect((await consentPauseOf(w.coach, "t_acme", RAE))?.owner_halted).toBe(true);
    await allow(w, "ana");
    expect(await consentPauseOf(w.coach, "t_acme", RAE)).toBeUndefined();
    expect(await halted(w)).toBe(true);
  });

  it("is off without the requirement, as for the person routes", async () => {
    const w = world();
    expect(await sweepConsent({ ...w.deps, aiConsent: false })).toEqual([]);
    expect(await halted(w)).toBe(false);
  });

  it("holds what came in for a taf paused for consent, as for credits, never counting it as a try", () => {
    const refused = new AgentsError(403, "paused", undefined, "ai_consent_required");
    expect(waitsForConsent(refused)).toBe(true);
    expect(waitsToStart(refused)).toBe(true);
    expect(waitsToStart(new AgentsError(403, "no"))).toBe(false);
  });
});

// Each trigger goes through requireUnattended (a door's message, an app's event and a held one released via the doors'
// startRun; a hand-off and a retry via the deliveries'; continueReady; Coach's task). Their own end-to-end cases are next.
describe.todo("the trigger matrix, end to end: a door's message held, an app's event held, a hand-off and a retry waiting, a run after KIFF's alert left paused, Coach's task refused");
