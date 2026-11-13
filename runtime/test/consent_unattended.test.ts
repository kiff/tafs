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
  void agents.api.createSchedule("t_acme", { id: RAE, agent: RAE, cron: "0 9 * * 1-5", input: "Work the queue.", enabled: true });
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
/** Paused: its schedule is off, the one start tafs cloud can't gate. Nothing is halted, so a person's own chat goes on. */
const halted = async (w: World) => {
  expect((await w.agents.api.halted("t_acme", RAE)).own).toBe(false);
  return !(await w.agents.api.getSchedule("t_acme", RAE)).enabled;
};

describe("runs with no person in the moment take their taf's consent", () => {
  it("pauses a taf from before until someone who manages tafs allows AI use, then resumes it", async () => {
    const w = world();
    // No one has allowed anything: the sweep pauses it, with why, and says so on the taf.
    expect(await sweepConsent(w.deps)).toEqual([{ tenant: "t_acme", paused: [RAE], resumed: [] }]);
    expect(await halted(w)).toBe(true);
    expect((await consentPauseOf(w.coach, "t_acme", RAE))?.why).toBe("no_one");
    const shown = await call(w, "GET", "/v1/tafs", "ana");
    expect((shown.body.tafs as Array<{ id: string; consent_paused?: unknown }>).find((t) => t.id === RAE)?.consent_paused).toEqual({ why: "no_one", words: expect.stringContaining("Paused: no one has allowed AI use") });
    // Again: nothing changes, nothing is switched twice.
    w.agents.calls.length = 0;
    expect(await sweepConsent(w.deps)).toEqual([]);
    expect(w.agents.calls.filter((c) => c.startsWith("switchSchedule"))).toEqual([]);
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

  it("leaves an owner's own pause in place when a yes ends the pause for consent, and refuses their resume until then", async () => {
    const w = world();
    expect((await call(w, "POST", `/v1/tafs/${RAE}/pause`, "ana")).status).toBe(200);
    expect(await settleConsent(w.deps, "t_acme")).toEqual({ paused: [RAE], resumed: [] });
    expect((await consentPauseOf(w.coach, "t_acme", RAE))?.owner_paused).toBe(true);
    // The owner's resume would start runs with no person: refused while no consent is on record.
    const refused = await call(w, "POST", `/v1/tafs/${RAE}/resume`, "ana");
    expect(refused.status).toBe(409);
    expect(refused.body.code).toBe("ai_consent_required");
    await allow(w, "ana");
    expect(await consentPauseOf(w.coach, "t_acme", RAE)).toBeUndefined();
    expect(await halted(w)).toBe(true);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/resume`, "ana")).status).toBe(200);
    expect(await halted(w)).toBe(false);
  });

  it("keeps a person's own chat going on their consent while the taf is paused for consent, and never pauses Coach", async () => {
    const w = world();
    await sweepConsent(w.deps);
    expect(await halted(w)).toBe(true);
    // Bo allowed AI use: his message is his own words on his own consent.
    await allow(w, "bo");
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, "bo", { text: "Refund order 42" })).status).toBe(200);
    // Coach is left out of the sweep.
    w.tafs.held.set("t_acme#taf-setup0000000000000000", { id: "taf-setup0000000000000000", name: "Setup", job: "setup", kiffAgent: "setup", nameClaim: "c", created: "2026-11-01T09:00:00Z" });
    const r = await sweepConsent(w.deps);
    expect(r.flatMap((x) => x.paused)).not.toContain("taf-setup0000000000000000");
    expect(await consentPauseOf(w.coach, "t_acme", "taf-setup0000000000000000")).toBeUndefined();
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

// A person whose consent a taf takes leaves the account (the PM's call, after #855): KIFF says nothing when someone
// leaves, so a manager's read checks KIFF's people now and then; someone no longer active consents for no one.
describe("the person whose consent a taf takes leaves the account", () => {
  /** KIFF's people as given, and how many times they were read. */
  const people = (w: World, members: Array<{ subject: string; status: string }>) => {
    const inner = w.deps.fetch;
    let reads = 0;
    w.deps.fetch = (async (url: string, init?: RequestInit) => {
      if (new URL(String(url)).pathname === "/v1/tenant/members") {
        reads++;
        return new Response(JSON.stringify({ members }), { status: 200 });
      }
      return inner(url, init);
    }) as typeof fetch;
    return { reads: () => reads };
  };

  it("pauses their tafs, left, until a manager takes them over, and reads KIFF's people at most every 10 minutes", async () => {
    const w = world();
    await recordMaker(w.coach, "t_acme", RAE, "u_cy", NOW);
    await allow(w, "ana");
    await allow(w, "cy");
    expect(await halted(w)).toBe(false);
    // Cy was removed from the account: a manager opening Home finds it, and Rae waits.
    const k = people(w, [{ subject: "u_ana", status: "active" }, { subject: "u_cy", status: "removed" }]);
    await call(w, "GET", "/v1/home", "ana");
    expect(k.reads()).toBe(1);
    expect(await unattendedConsent(w.coach, "t_acme", RAE)).toEqual({ why: "left" });
    expect((await consentPauseOf(w.coach, "t_acme", RAE))?.why).toBe("left");
    expect(await halted(w)).toBe(true);
    const shown = await call(w, "GET", "/v1/tafs", "ana");
    expect((shown.body.tafs as Array<{ id: string; consent_paused?: { words: string } }>).find((t) => t.id === RAE)?.consent_paused?.words)
      .toContain("is no longer on the account");
    // Read again soon after: KIFF's people aren't asked again.
    await call(w, "GET", "/v1/home", "ana");
    expect(k.reads()).toBe(1);
    // Ana takes it over with her own consent: it goes on.
    expect((await call(w, "POST", `/v1/tafs/${RAE}/ai-consent`, "ana")).status).toBe(200);
    expect(await halted(w)).toBe(false);
  });

  it("never reads KIFF's people for someone who can't manage the tafs, and changes nothing when the read fails", async () => {
    const w = world();
    await recordMaker(w.coach, "t_acme", RAE, "u_cy", NOW);
    await allow(w, "cy");
    const k = people(w, [{ subject: "u_cy", status: "removed" }]);
    await call(w, "GET", "/v1/home", "bo");
    expect(k.reads()).toBe(0);
    // A wrong list (empty, or missing the manager reading it): nothing marked, the taf goes on.
    const wrong = people(w, []);
    await call(w, "GET", "/v1/home", "ana");
    expect(wrong.reads()).toBe(1);
    expect(await unattendedConsent(w.coach, "t_acme", RAE)).toEqual({ subject: "u_cy" });
    // Not marked as checked either: the next read tries again.
    await call(w, "GET", "/v1/home", "ana");
    expect(wrong.reads()).toBe(2);
    // A list KIFF couldn't give: nothing marked, the taf goes on.
    const inner = w.deps.fetch;
    w.deps.fetch = (async (url: string, init?: RequestInit) => new URL(String(url)).pathname === "/v1/tenant/members"
      ? new Response("{}", { status: 503 }) : inner(url, init)) as typeof fetch;
    await call(w, "GET", "/v1/home", "ana");
    expect(await unattendedConsent(w.coach, "t_acme", RAE)).toEqual({ subject: "u_cy" });
  });
});

// Each trigger goes through requireUnattended (a door's message, an app's event and a held one released via the doors'
// startRun; a hand-off and a retry via the deliveries'; continueReady; Coach's task). Their own end-to-end cases are next.
describe.todo("the trigger matrix, end to end (an app's event held: doors.test.ts): a door's message held, a hand-off and a retry waiting, a run after KIFF's alert left paused, Coach's task refused");
