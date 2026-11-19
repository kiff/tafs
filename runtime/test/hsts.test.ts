import { describe, expect, it } from "vitest";
import { handle, handleStream, HSTS, type HTTPEvent } from "../src/cloud/handler.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const event = (rawPath: string, method = "GET"): HTTPEvent => ({ rawPath, requestContext: { http: { method } }, headers: {} });

function deps() {
  const now = () => new Date("2026-11-19T14:00:00Z");
  return {
    agents: fakeAgents(now).api, keys: memoryKeys(), accounts: memoryAccounts(), tafs: memoryTafs(), names: memoryNames(), slots: memorySlots(),
    claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), coach: memoryCoach(now),
    fetch: (async () => new Response("{}", { status: 401 })) as typeof fetch, now,
  } as never;
}

// HSTS on every answer tafs cloud gives, as kiff.dev's: a year, no subdomains, no preload. A refusal and a stream too, since a
// client's first answer can be either.
describe("tafs cloud's answers", () => {
  it("carry HSTS, a refusal's and a stream's too", async () => {
    expect(HSTS).toBe("max-age=31536000");
    const r = await handle(event("/v1/nothing-here"), deps());
    expect(r.statusCode).toBeGreaterThanOrEqual(400);
    expect(r.headers["strict-transport-security"]).toBe(HSTS);
    const s = await handleStream(event("/v1/nothing-here", "POST"), deps());
    expect(s.statusCode).toBe(404);
    expect(s.headers["strict-transport-security"]).toBe(HSTS);
  });
});
