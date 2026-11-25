import { describe, expect, it } from "vitest";
import { handle, handleStream, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { AI_CONSENT_VERSION, aiConsentKey } from "../src/cloud/consent.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

// The AI-data consent (docs/accounts.md, "Your data and the AI"): nothing a person writes reaches the model before
// they allow it, each person for themselves, and they can withdraw it.
const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const BO = { ...ME, subject: "u_bo" };
const NOW = new Date("2026-11-13T09:00:00Z");
const RAE = "taf-aaaa0000000000000000";

function kiff(byToken: Record<string, object>) {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    const me = byToken[token];
    if (me && String(url).endsWith("/v1/me/access")) return new Response(JSON.stringify({ agents: { rae: { use: true, oversee: false, manage: true } } }), { status: 200 });
    return me ? new Response(JSON.stringify(me), { status: 200 }) : new Response("{}", { status: 401 });
  }) as typeof fetch;
}

function world() {
  const agents = fakeAgents(() => NOW);
  const tafs = memoryTafs();
  const taf: Taf = { id: RAE, name: "Rae", job: "refunds", kiffAgent: "rae", nameClaim: "c", created: "2026-11-01T09:00:00Z" };
  tafs.held.set(`t_acme#${RAE}`, taf);
  void agents.api.putAgent("t_acme", RAE, {} as never);
  void agents.api.createSchedule("t_acme", { id: RAE, agent: RAE, cron: "*/30 * * * *", input: "Work the refund queue.", enabled: true });
  agents.calls.length = 0;
  return { agents, tafs, coach: memoryCoach(() => NOW) };
}
type World = ReturnType<typeof world>;

function deps(w: World, aiConsent = true) {
  return {
    agents: w.agents.api, aiConsent, keys: memoryKeys(), accounts: memoryAccounts(), tafs: w.tafs,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), coach: w.coach,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const },
    forward: async () => undefined,
    fetch: kiff({ ana: ME, bo: BO }),
    now: () => NOW,
  };
}

function event(method: string, path: string, opts: { token?: string; body?: unknown; language?: string } = {}): HTTPEvent {
  return {
    rawPath: path,
    requestContext: { http: { method } },
    headers: { ...(opts.token ? { Authorization: `Bearer ${opts.token}` } : {}), ...(opts.language ? { "Accept-Language": opts.language } : {}) },
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  };
}

async function call(w: World, method: string, path: string, opts: { token?: string; body?: unknown; language?: string; aiConsent?: boolean } = {}) {
  const r = await handle(event(method, path, opts), deps(w, opts.aiConsent ?? true));
  return { status: r.statusCode, body: JSON.parse(r.body) };
}

const started = (w: World) => w.agents.calls.filter((c) => /^(startRun|streamRun|trigger|trialMessage|startTrial)/.test(c));

describe("the AI-data consent", () => {
  it("refuses a message before consent, with the screen's words, and sends nothing to the model", async () => {
    const w = world();
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Refund order 42" } });
    expect(r.status).toBe(403);
    expect(r.body.code).toBe("ai_consent_required");
    expect(r.body.error).toBe("Allow AI use first: Coach and your tafs need it to answer.");
    expect(r.body.consent).toMatchObject({ given: false, version: AI_CONSENT_VERSION });
    expect(r.body.consent.words.lines[0]).toBe("tafs uses Anthropic's Claude AI for Coach and your tafs.");
    expect(r.body.consent.words.allow).toBe("Allow");
    expect(started(w)).toEqual([]);
  });

  it("lets the message through once allowed, and refuses it again once withdrawn", async () => {
    const w = world();
    const given = await call(w, "POST", "/v1/me/ai-consent", { token: "ana", body: { version: AI_CONSENT_VERSION } });
    expect(given.status).toBe(200);
    expect(given.body.consent).toMatchObject({ given: true, at: NOW.toISOString(), version: AI_CONSENT_VERSION });
    expect((await call(w, "GET", "/v1/me/ai-consent", { token: "ana" })).body.consent.given).toBe(true);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Refund order 42" } })).status).toBe(200);
    expect(started(w)).toHaveLength(1);
    const withdrawn = await call(w, "DELETE", "/v1/me/ai-consent", { token: "ana" });
    expect(withdrawn.body.consent.given).toBe(false);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "And 43" } })).status).toBe(403);
    expect(started(w)).toHaveLength(1);
  });

  it("keeps the record when withdrawn, and each earlier window when allowed again: who allowed what, and from when to when", async () => {
    const w = world();
    let now = NOW;
    const at = (d: Date) => { now = d; };
    const calls = async (method: string) => handle(event(method, "/v1/me/ai-consent", { token: "ana", body: method === "POST" ? { version: AI_CONSENT_VERSION } : undefined }), { ...deps(w), now: () => now });
    await calls("POST");
    at(new Date("2026-11-14T09:00:00Z"));
    await calls("DELETE");
    expect(await w.coach.get(aiConsentKey("t_acme", "u_ana"))).toMatchObject({ at: NOW.toISOString(), version: AI_CONSENT_VERSION, withdrawn_at: "2026-11-14T09:00:00.000Z" });
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Refund order 42" } })).status).toBe(403);
    at(new Date("2026-11-15T09:00:00Z"));
    await calls("POST");
    expect(await w.coach.get(aiConsentKey("t_acme", "u_ana"))).toEqual({
      at: "2026-11-15T09:00:00.000Z", version: AI_CONSENT_VERSION,
      earlier: [{ at: NOW.toISOString(), version: AI_CONSENT_VERSION, withdrawn_at: "2026-11-14T09:00:00.000Z" }],
    });
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Refund order 42" } })).status).toBe(200);
  });

  it("is each person's own: Ana's yes doesn't let Bo's words through", async () => {
    const w = world();
    await call(w, "POST", "/v1/me/ai-consent", { token: "ana", body: { version: AI_CONSENT_VERSION } });
    const bo = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "bo", body: { text: "Refund order 7" } });
    expect(bo.status).toBe(403);
    expect(bo.body.code).toBe("ai_consent_required");
    expect(await w.coach.get(aiConsentKey("t_acme", "u_bo"))).toBeUndefined();
  });

  it("refuses an old screen's yes, and asks again when the version changes", async () => {
    const w = world();
    const stale = await call(w, "POST", "/v1/me/ai-consent", { token: "ana", body: { version: "0" } });
    expect(stale.status).toBe(409);
    expect(stale.body.consent.given).toBe(false);
    // A yes kept for an older version is no yes for this one.
    await w.coach.put(aiConsentKey("t_acme", "u_ana"), { at: NOW.toISOString(), version: "0" }, new Date(NOW.getTime() + 3600_000));
    expect((await call(w, "GET", "/v1/me/ai-consent", { token: "ana" })).body.consent.given).toBe(false);
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Refund order 42" } })).status).toBe(403);
  });

  it("gates Run now, a trial's message and the streamed message too", async () => {
    const w = world();
    expect((await call(w, "POST", `/v1/tafs/${RAE}/run`, { token: "ana" })).body.code).toBe("ai_consent_required");
    expect((await call(w, "POST", `/v1/tafs/${RAE}/trial/messages`, { token: "ana", body: { text: "Try it" } })).body.code).toBe("ai_consent_required");
    const r = await handleStream(event("POST", `/v1/tafs/${RAE}/messages/stream`, { token: "ana", body: { text: "Refund order 42" } }), deps(w));
    let raw = "";
    for await (const chunk of r.body) raw += chunk;
    expect(r.statusCode).toBe(403);
    expect(JSON.parse(raw).code).toBe("ai_consent_required");
    expect(started(w)).toEqual([]);
  });

  it("speaks the person's language", async () => {
    const w = world();
    const r = await call(w, "GET", "/v1/me/ai-consent", { token: "ana", language: "fr" });
    expect(r.body.consent.words.title).toBe("Vos données et l'IA");
    expect(r.body.consent.words.allow).toBe("Autoriser");
    const refused = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Rembourse la commande 42" }, language: "pl" });
    expect(refused.body.error).toBe("Najpierw zezwól na użycie AI: Coach i Twoje tafy potrzebują go, by odpowiadać.");
  });

  it("is required only where it's set: production always sets it (lambda.ts)", async () => {
    const w = world();
    expect((await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Refund order 42" }, aiConsent: false })).status).toBe(200);
    const { readFileSync } = await import("node:fs");
    expect(readFileSync(new URL("../src/cloud/lambda.ts", import.meta.url), "utf8")).toMatch(/aiConsent: true/);
  });
});

// The consent names the company that runs the model now, read from kiff/agents itself (GET /ai/source), under the
// line that says who may run it. Never a guess: unread, or with a fallback that could send a run to either, no line.
describe("the consent's \"Right now\" line", () => {
  const lines = async (w: World, language?: string) =>
    (await call(w, "GET", "/v1/me/ai-consent", { token: "ana", ...(language ? { language } : {}) })).body.consent.words.lines as string[];
  const withSource = (w: World, read: () => Promise<{ source: string; fallback?: string; geo?: string }>) => {
    let reads = 0;
    (w.agents.api as { aiSource?: unknown }).aiSource = async () => { reads++; return read(); };
    return () => reads;
  };

  it("names Amazon Web Services in the EU while kiff/agents runs on Bedrock, under the second line", async () => {
    const w = world();
    const base = await lines(w);
    withSource(w, async () => ({ source: "bedrock", geo: "eu" }));
    const got = await lines(w);
    expect(got).toHaveLength(base.length + 1);
    expect(got[1]).toBe(base[1]);
    expect(got[2]).toBe("Right now: Amazon Web Services, in the EU.");
    expect(got.slice(3)).toEqual(base.slice(2));
  });

  it("names Anthropic itself on the Claude API, in the person's language", async () => {
    const w = world();
    withSource(w, async () => ({ source: "anthropic" }));
    expect((await lines(w))[2]).toBe("Right now: Anthropic itself, in the United States and other countries.");
    const w2 = world();
    withSource(w2, async () => ({ source: "bedrock", geo: "eu" }));
    expect((await lines(w2, "de"))[2]).toBe("Derzeit: Amazon Web Services, in der EU.");
  });

  it("shows no line when it can't be read, when a run may go to either of two, or for a source it has no words for", async () => {
    for (const read of [
      async () => { throw new Error("503 this server doesn't say where its turns run"); },
      async () => ({ source: "anthropic", fallback: "bedrock" }),
      async () => ({ source: "bedrock" }),
      async () => ({ source: "vertex", geo: "eu" }),
    ]) {
      const w = world();
      const base = await lines(w);
      withSource(w, read);
      expect(await lines(w)).toEqual(base);
    }
  });

  it("is on the refusal's screen too, and read once for a while, not for each screen", async () => {
    const w = world();
    const reads = withSource(w, async () => ({ source: "bedrock", geo: "eu" }));
    const r = await call(w, "POST", `/v1/tafs/${RAE}/messages`, { token: "ana", body: { text: "Refund order 42" } });
    expect(r.status).toBe(403);
    expect(r.body.consent.words.lines[2]).toBe("Right now: Amazon Web Services, in the EU.");
    await lines(w);
    await lines(w);
    expect(reads()).toBe(1);
  });
});
