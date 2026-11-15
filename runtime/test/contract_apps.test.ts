import { readFileSync, writeFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { handle, type HTTPEvent, type Taf } from "../src/cloud/handler.js";
import { AI_CONSENT_VERSION } from "../src/cloud/consent.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

// The contract between tafs cloud and the apps (contracts/README.md): what tafs cloud answers, written by its own handler,
// and read by the iOS and Android tests from the same file. A change to an answer's shape fails here until the file is
// written again (UPDATE_CONTRACTS=1), and then fails in the app that can't read it: never first on a phone.
const FILE = new URL("../../contracts/ai-consent.json", import.meta.url);
const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const NOW = new Date("2026-11-13T09:00:00Z");
const RAE = "taf-aaaa0000000000000000";

function world() {
  const agents = fakeAgents(() => NOW);
  const tafs = memoryTafs();
  const taf: Taf = { id: RAE, name: "Rae", job: "refunds", kiffAgent: "rae", nameClaim: "c", created: "2026-11-01T09:00:00Z" };
  tafs.held.set(`t_acme#${RAE}`, taf);
  void agents.api.putAgent("t_acme", RAE, {} as never);
  const coach = memoryCoach(() => NOW);
  const kiff = (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>).authorization).replace("Bearer ", "");
    if (token !== "ana") return new Response("{}", { status: 401 });
    if (String(url).endsWith("/v1/me/access")) return new Response(JSON.stringify({ agents: { rae: { use: true, oversee: false, manage: true } } }), { status: 200 });
    return new Response(JSON.stringify(ME), { status: 200 });
  }) as typeof fetch;
  const deps = {
    agents: agents.api, aiConsent: true, keys: memoryKeys(), accounts: memoryAccounts(), tafs,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), coach,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const }, forward: async () => undefined, fetch: kiff, now: () => NOW,
  };
  return async (method: string, path: string, body?: unknown, lang = "en") => {
    const event: HTTPEvent = {
      rawPath: path, requestContext: { http: { method } },
      headers: { Authorization: "Bearer ana", "Accept-Language": lang }, body: body === undefined ? undefined : JSON.stringify(body),
    };
    const r = await handle(event, deps);
    return { method, path, status: r.statusCode, body: JSON.parse(r.body) as unknown };
  };
}

/** Each answer the apps read, as tafs cloud's handler gives it, in the order a person meets them. */
async function answers() {
  const call = world();
  return [
    { name: "not given yet", ...await call("GET", "/v1/me/ai-consent") },
    { name: "a turn refused for want of it", ...await call("POST", `/v1/tafs/${RAE}/messages`, { text: "Refund order 42" }) },
    { name: "a yes from an older screen", ...await call("POST", "/v1/me/ai-consent", { version: "0" }) },
    { name: "given", ...await call("POST", "/v1/me/ai-consent", { version: AI_CONSENT_VERSION }) },
    { name: "withdrawn", ...await call("DELETE", "/v1/me/ai-consent") },
    { name: "not given yet, in French", ...await call("GET", "/v1/me/ai-consent", undefined, "fr") },
  ];
}

describe("the contract with the apps: the AI-data consent", () => {
  it("is what tafs cloud answers, as contracts/ai-consent.json has it", async () => {
    const now = { about: "What tafs cloud answers about the AI-data consent, written by runtime/test/contract_apps.test.ts from its own handler, read by the iOS and Android tests. Write it again with UPDATE_CONTRACTS=1; never by hand.", cases: await answers() };
    const text = JSON.stringify(now, null, 2) + "\n";
    if (process.env.UPDATE_CONTRACTS === "1") writeFileSync(FILE, text);
    expect(readFileSync(FILE, "utf8"), "tafs cloud's answers changed: write contracts/ai-consent.json again (UPDATE_CONTRACTS=1), and make both apps read it").toBe(text);
  });

  it("has every case the apps rely on", async () => {
    const cases = (await answers()) as Array<{ name: string; status: number; body: { code?: string; consent?: { given: boolean } } }>;
    const by = (n: string) => cases.find((c) => c.name === n)!;
    expect(by("not given yet")).toMatchObject({ status: 200, body: { consent: { given: false } } });
    expect(by("a turn refused for want of it")).toMatchObject({ status: 403, body: { code: "ai_consent_required", consent: { given: false } } });
    expect(by("a yes from an older screen")).toMatchObject({ status: 409, body: { consent: { given: false } } });
    expect(by("given")).toMatchObject({ status: 200, body: { consent: { given: true } } });
    expect(by("withdrawn")).toMatchObject({ status: 200, body: { consent: { given: false } } });
  });
});
