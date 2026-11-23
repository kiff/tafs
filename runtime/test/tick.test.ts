import { describe, expect, it } from "vitest";
import type { Deps } from "../src/cloud/handler.js";
import { tick } from "../src/cloud/tick.js";
import { memoryTafs } from "./fakes.js";

function depsWith(accounts: string[]) {
  const tafs = memoryTafs();
  for (const t of accounts) tafs.held.set(`${t}#taf-1`, { id: "taf-1" } as never);
  let reads = 0;
  const counted = Object.assign(Object.create(tafs) as typeof tafs, { tenants: async () => { reads++; return tafs.tenants(); } });
  return { d: { tafs: counted } as unknown as Deps, reads: () => reads };
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
});
