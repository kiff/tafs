import { describe, expect, it } from "vitest";
import { handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

// The AI-data consent's trigger matrix (docs/accounts.md, "Your data and the AI"): whatever a person does that
// makes a model turn start or go on, with no consent of theirs it reaches no turn at kiff/agents. Each case first
// shows, with the consent not required, that the route does reach a turn, so a case can't pass by never getting there.
const ANA = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const BO = { ...ANA, subject: "u_bo", capabilities: ["read", "holds.answer"] };
const ANA_RUN = { requester: { kind: "member" as const, id: "u_ana" } };
const NOW = new Date("2026-11-13T09:00:00Z");
const RAE = "taf-aaaa0000000000000000";

/** Every kiff/agents call that starts a model turn or lets a paused one go on. */
const TURN = /^(startRun|streamRun|resumeRun|trigger|trialMessage|startTrial|steerRun|answerCard|answerResult|answerWait|continueRun|continueHeld) /;

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
  return { agents, tafs, coach: memoryCoach(() => NOW) };
}
type World = ReturnType<typeof world>;

async function call(w: World, method: string, path: string, token: string, body: unknown, aiConsent: boolean) {
  const event: HTTPEvent = {
    rawPath: path, requestContext: { http: { method } },
    headers: { Authorization: `Bearer ${token}` }, body: body === undefined ? undefined : JSON.stringify(body),
  };
  const r = await handle(event, {
    agents: w.agents.api, aiConsent, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), coach: w.coach,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const }, forward: async () => undefined,
    fetch: kiff({ ana: ANA, bo: BO }), now: () => NOW,
  });
  return { status: r.statusCode, body: JSON.parse(r.body) as Record<string, unknown> };
}

interface Case { name: string; token: string; method: string; path: string; body?: unknown; given?: (w: World) => Promise<void> }

const CASES: Case[] = [
  { name: "a chat message", token: "ana", method: "POST", path: `/v1/tafs/${RAE}/messages`, body: { text: "Refund order 42" } },
  { name: "Run now", token: "ana", method: "POST", path: `/v1/tafs/${RAE}/run`,
    given: async (w) => { await w.agents.api.createSchedule("t_acme", { id: RAE, agent: RAE, cron: "*/30 * * * *", input: "Work the queue.", enabled: true }); } },
  { name: "a steer to the run at work", token: "ana", method: "POST", path: `/v1/tafs/${RAE}/steer`, body: { run: "", text: "Skip order 41" },
    given: async (w) => {
      const run = await w.agents.api.startRun("t_acme", RAE, "Work the queue", ANA_RUN);
      (CASES.find((c) => c.name === "a steer to the run at work")!.body as { run: string }).run = run.id;
    } },
  { name: "an answer to a card that waits for it", token: "ana", method: "POST", path: `/v1/tafs/${RAE}/components/which/answer`, body: { answer: "test" },
    given: async (w) => {
      const run = await w.agents.api.startRun("t_acme", RAE, "Connect my Stripe", ANA_RUN);
      w.agents.ask(run, { type: "confirm", id: "which", title: "Which mode?", options: [{ value: "test", label: "Test mode" }, { value: "live", label: "Live" }] });
    } },
  { name: "a held call answered: its run goes on", token: "bo", method: "POST", path: `/v1/tafs/${RAE}/holds/exc_1/continue`,
    given: async (w) => {
      const run = await w.agents.api.startRun("t_acme", RAE, "Refund pi_1", ANA_RUN);
      Object.assign(w.agents.runs.find((x) => x.id === run.id)!, {
        status: "PAUSED", requirements: [{ call_id: "c1", tool: "refund", input: {}, operation: "op1", exception: "exc_1" }],
      });
    } },
];

describe("the AI-data consent's trigger matrix: no consent, no model turn", () => {
  for (const c of CASES) {
    it(`${c.name}: reaches a turn without the requirement, none with it and no consent`, async () => {
      const open = world();
      await c.given?.(open);
      open.agents.calls.length = 0;
      const reached = await call(open, c.method, c.path, c.token, c.body, false);
      expect(open.agents.calls.filter((x) => TURN.test(x)), `the case doesn't reach a turn: ${reached.status} ${JSON.stringify(reached.body)}`).not.toEqual([]);

      const gated = world();
      await c.given?.(gated);
      gated.agents.calls.length = 0;
      const r = await call(gated, c.method, c.path, c.token, c.body, true);
      expect(gated.agents.calls.filter((x) => TURN.test(x))).toEqual([]);
      expect(r.status).toBe(403);
      expect(r.body.code).toBe("ai_consent_required");
    });
  }
});

// Runs no person starts in the moment take the taf owner's consent (the PM's 09:46 decision on (1a) and (2a)).
// Each trigger goes through tafs cloud before kiff/agents, so each gets the same matrix once the owner's consent
// is read there.
describe.todo("the trigger matrix for runs with no person in the moment: a door's message (doors.ts), an app's event and a held one released (inbound.ts, hold.ts), a handoff and a retry (delivery.ts), a task Coach hands a taf (handler.ts startRun for an MCP call), a paused run continued after KIFF's alert (continueReady)");
