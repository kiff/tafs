import { describe, expect, it } from "vitest";
import { consentPauseOf } from "../src/cloud/consent.js";
import type { Deps, Taf } from "../src/cloud/handler.js";
import { SWEEPS, sweepsFor, tick } from "../src/cloud/tick.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

// The tick's groups run side by side (tick.ts), so a group may get an account the deletions sweep is erasing, or erased a
// moment ago in another group. Neither may leave anything behind: nothing written, started or switched for it.
const NOW = new Date("2026-11-13T09:00:00Z");
const RAE = "taf-aaaa0000000000000000";

function world(deleting: Array<{ tenant: string; accepting?: boolean }> = []) {
  const agents = fakeAgents(() => NOW);
  const tafs = memoryTafs();
  const taf: Taf = { id: RAE, name: "Rae", job: "refunds", kiffAgent: "rae", nameClaim: "c", created: "2026-11-01T09:00:00Z" };
  tafs.held.set(`t_acme#${RAE}`, taf);
  void agents.api.putAgent("t_acme", RAE, {} as never);
  void agents.api.createSchedule("t_acme", { id: RAE, agent: RAE, cron: "0 9 * * 1-5", input: "Work the queue.", enabled: true });
  agents.calls.length = 0;
  const coach = memoryCoach(() => NOW);
  // The index still names the account, whatever its tafs item says (it drops the id at the erasure, or at the hour's check).
  const listed = Object.assign(Object.create(tafs) as typeof tafs, { tenants: async () => ["t_acme"] });
  const deps = {
    agents: agents.api, aiConsent: true, keys: memoryKeys(), accounts: memoryAccounts(), tafs: listed,
    names: memoryNames(), slots: memorySlots(), claims: memoryClaims(), removals: memoryRemovals(), archived: memoryArchived(), coach,
    push: { get: async () => ({ devices: [], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async () => "sent" as const }, forward: async () => undefined, now: () => NOW,
    deletions: { pending: async () => deleting },
  } as unknown as Deps;
  return { agents, tafs, coach, deps };
}

const group4 = sweepsFor("tick-4", SWEEPS);

describe("a tick group and an account being erased", () => {
  it("the sweeps would pause this taf for want of consent, so the cases below are tested against something", async () => {
    const w = world();
    await tick(w.deps, group4, () => {});
    expect((await consentPauseOf(w.coach, "t_acme", RAE))?.why).toBe("no_one");
  });

  it("writes, starts and switches nothing for an account whose tafs are erased, though the index still names it", async () => {
    const w = world();
    w.coach.held.set("t_acme#first-runs-owed", { value: [RAE], until: NOW.getTime() + 3600_000 });
    w.tafs.held.clear(); // erased: its tafs item is gone
    const before = new Set(w.coach.held.keys());
    await tick(w.deps, group4, () => {});
    // Nothing new kept; the owed first run, whose taf is gone, is let go rather than kept.
    expect([...w.coach.held.keys()].filter((k) => !before.has(k))).toEqual([]);
    expect(w.coach.held.has("t_acme#first-runs-owed")).toBe(false);
    expect(w.agents.calls).toEqual([]);
  });

  it("leaves alone an account whose deletion is accepted, even while its tafs are still there", async () => {
    const w = world([{ tenant: "t_acme" }]);
    const before = new Set(w.coach.held.keys());
    await tick(w.deps, group4, () => {});
    expect(await consentPauseOf(w.coach, "t_acme", RAE)).toBeUndefined();
    expect([...w.coach.held.keys()].filter((k) => !before.has(k))).toEqual([]);
    expect(w.agents.calls).toEqual([]);
  });
});
