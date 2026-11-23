import { describe, expect, it } from "vitest";
import type { Deps } from "../src/cloud/handler.js";
import { erasedBy, GROUPS, SWEEPS, sweepsFor, tick } from "../src/cloud/tick.js";
import { memoryTafs } from "./fakes.js";

function depsWith(accounts: string[], deleting: Array<{ tenant: string; accepting?: boolean }> = []) {
  const tafs = memoryTafs();
  for (const t of accounts) tafs.held.set(`${t}#taf-1`, { id: "taf-1" } as never);
  let reads = 0;
  const counted = Object.assign(Object.create(tafs) as typeof tafs, { tenants: async () => { reads++; return tafs.tenants(); } });
  return { d: { tafs: counted, deletions: { pending: async () => deleting } } as unknown as Deps, reads: () => reads };
}

describe("the scheduled tick", () => {
  it("logs one line per sweep, by name, with the accounts and how long it took", async () => {
    const { d } = depsWith(["t_a", "t_b"]);
    const lines: string[] = [];
    const out = await tick(d, [["late", async () => ["x"]], ["briefs", async () => []]], (l) => lines.push(l));
    expect(out).toEqual({ late: ["x"], briefs: [] });
    const logged = lines.map((l) => JSON.parse(l) as Record<string, unknown>);
    expect(logged.map((l) => [l.event, l.name, l.accounts])).toEqual([["sweep", "late", 2], ["sweep", "briefs", 2]]);
    expect(logged.every((l) => typeof l.ms === "number" && !("failed" in l))).toBe(true);
  });

  it("reads the accounts once, and every sweep gets that list with the rest of the store", async () => {
    const { d, reads } = depsWith(["t_a", "t_b"]);
    const seen: string[][] = [];
    await tick(d, [
      ["one", async (x) => seen.push(await x.tafs.tenants())],
      ["two", async (x) => seen.push((await x.tafs.list("t_a")).map((t) => t.id))],
      ["three", async (x) => seen.push(await x.tafs.tenants())],
    ], () => {});
    expect(reads()).toBe(1);
    expect(seen).toEqual([["t_a", "t_b"], ["taf-1"], ["t_a", "t_b"]]);
  });

  it("logs a sweep that throws as failed, without its error, and stops the tick there", async () => {
    const { d } = depsWith(["t_a"]);
    const lines: string[] = [];
    let later = false;
    await expect(tick(d, [
      ["late", async () => { throw new Error("t_a's taf refused"); }],
      ["briefs", async () => { later = true; }],
    ], (l) => lines.push(l))).rejects.toThrow("refused");
    expect(later).toBe(false);
    expect(lines).toHaveLength(1);
    expect(JSON.parse(lines[0])).toMatchObject({ event: "sweep", name: "late", accounts: 1, failed: true });
    expect(lines[0]).not.toContain("t_a");
  });

  it("takes an account the deletions sweep erased out of the list every later sweep gets, and counts it before", async () => {
    const { d, reads } = depsWith(["t_a", "t_gone", "t_half"]);
    const seen: string[][] = [];
    const lines: string[] = [];
    await tick(d, [
      ["deletions", async () => [
        { tenant: "t_gone", done: ["tafs", "planque", "kiff"], pending: false },
        // Its own data not erased yet (the tafs step failed): still swept, as before.
        { tenant: "t_half", done: [], pending: true },
      ]],
      ["late", async (x) => seen.push(await x.tafs.tenants())],
      ["briefs", async (x) => seen.push(await x.tafs.tenants())],
    ], (l) => lines.push(l));
    expect(seen).toEqual([["t_a", "t_half"], ["t_a", "t_half"]]);
    expect(lines.map((l) => (JSON.parse(l) as { accounts: number }).accounts)).toEqual([3, 2, 2]);
    expect(reads()).toBe(1);
  });

  it("erasedBy reads only the deletions sweep's own erasures", () => {
    expect(erasedBy("deletions", [{ tenant: "t_a", done: ["tafs"], pending: true }, { tenant: "t_b", done: ["planque"], pending: true }])).toEqual(["t_a"]);
    expect(erasedBy("late", [{ tenant: "t_a", done: ["tafs"] }])).toEqual([]);
    expect(erasedBy("deletions", undefined)).toEqual([]);
    expect(erasedBy("deletions", [null, { tenant: 7, done: ["tafs"] }])).toEqual([]);
  });

  it("gives no sweep an account whose deletion is accepted, but still one whose accept KIFF hasn't confirmed", async () => {
    const { d } = depsWith(["t_a", "t_erasing", "t_asking"], [{ tenant: "t_erasing" }, { tenant: "t_asking", accepting: true }]);
    const seen: string[][] = [];
    await tick(d, [["late", async (x) => seen.push(await x.tafs.tenants())], ["briefs", async (x) => seen.push(await x.tafs.tenants())]], () => {});
    expect(seen).toEqual([["t_a", "t_asking"], ["t_a", "t_asking"]]);
  });

  it("splits the tick into groups that run every sweep once, deletions first; \"deletions\" still runs them all", () => {
    const names = SWEEPS.map(([n]) => n);
    expect(names).toHaveLength(14);
    const grouped = Object.keys(GROUPS).flatMap((g) => sweepsFor(g, SWEEPS).map(([n]) => n));
    expect([...grouped].sort()).toEqual([...names].sort());
    expect(new Set(grouped).size).toBe(names.length);
    expect(Object.keys(GROUPS)).toHaveLength(5); // EventBridge's most targets on one rule
    expect(sweepsFor("tick-1", SWEEPS)[0][0]).toBe("deletions");
    // A group keeps the sweeps' own order.
    for (const g of Object.keys(GROUPS)) {
      const own = sweepsFor(g, SWEEPS).map(([n]) => names.indexOf(n));
      expect(own).toEqual([...own].sort((a, b) => a - b));
    }
    expect(sweepsFor("deletions", SWEEPS)).toBe(SWEEPS);
    expect(sweepsFor("consent", SWEEPS)).toEqual([]);
    expect(sweepsFor("tick-9", SWEEPS)).toEqual([]);
  });
});
