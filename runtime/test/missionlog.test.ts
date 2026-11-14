import { describe, expect, it } from "vitest";
import type { Run } from "../src/cloud/agents.js";
import { type Deps, sweepMissionLog, type Taf } from "../src/cloud/handler.js";
import { appendLog, type LogEntry, logForRuns, logPage, readLog } from "../src/cloud/missionlog.js";
import { saveMissions } from "../src/cloud/missions.js";
import { memoryCoach, memoryTafs } from "./fakes.js";

const T = "t_acme";
const NOW = new Date("2026-10-16T12:00:00Z");
const run = (id: string, over: Partial<Run> = {}): Run => ({ id, agent: "taf-bills", session: "s", trigger: "schedule", status: "COMPLETED", cost_micro_usd: 0, turns: 3,
  created: "2026-10-16T09:00:00Z", updated: "2026-10-16T09:05:00Z", tally: { pay: { sent: 2, held: 1 }, read: { sent: 4 } },
  done: [{ resource: "invoice 0142" }, { resource: "invoice 0142" }, { resource: "invoice 0143" }], ...over } as Run);

// A mission's log (docs/missions.md): what its tafs delivered, from the record, and what runs may read of it (M7).
describe("a mission's log", () => {
  it("logs a member's completed run once, with the record's counts and the items as refs, never a trial's or a failed one", async () => {
    const coach = memoryCoach(() => NOW);
    const tafs = memoryTafs();
    tafs.held.set(`${T}#taf-bills`, { id: "taf-bills", name: "Bills", job: "custom", kiffAgent: "agent_bills", nameClaim: "c", created: "" } as Taf);
    await saveMissions(coach, T, { missions: [], version: 0 }, [{ id: "msn_1", name: "Money", goal: "Run the money side.", label: "lbl_1", tafs: ["taf-bills"], created: "2026-10-16T08:00:00Z", by: "u_ana" }], NOW);
    const runs = [run("r1"), run("r2", { trigger: "trial" }), run("r3", { status: "ERROR" }), run("r0", { created: "2026-10-15T09:00:00Z" })];
    const deps = { coach, tafs, agents: { runs: async () => runs }, now: () => NOW } as unknown as Deps;
    expect(await sweepMissionLog(deps)).toBe(1);
    expect(await sweepMissionLog(deps)).toBe(0);
    const log = (await readLog(coach, T, "msn_1"))!;
    expect(log.entries).toEqual([{ id: "log_r1", at: "2026-10-16T09:05:00Z", by: { kind: "taf", id: "taf-bills" }, kind: "delivered",
      counts: { done: 6, held: 1, refused: 0 }, evidence: [{ kind: "run", id: "r1" }, { kind: "item", id: "invoice 0142" }, { kind: "item", id: "invoice 0143" }] }]);
    const names = new Map([["taf-bills", "Bills"]]);
    expect(logPage(log.entries, names, "en").items[0]).toMatchObject({ by: { kind: "taf", name: "Bills" }, words: "6 done · 1 held for you" });
    expect(logPage(log.entries, names, "fr").items[0]!.words).toBe("6 faits · 1 retenu");
  });

  it("keeps a removed taf's entries in the person's log, marked removed, and out of what runs read (M7)", async () => {
    const coach = memoryCoach(() => NOW);
    const entry = (id: string, taf: string, words: string): LogEntry => ({ id, at: "2026-10-16T10:00:00Z", by: { kind: "taf", id: taf }, kind: "note", words, evidence: [{ kind: "run", id: `run_${id}` }] });
    await appendLog(coach, T, "msn_1", entry("a", "taf-gone", "Client X says they'll pay Friday."), NOW);
    await appendLog(coach, T, "msn_1", { ...entry("b", "taf-bills", "Matched bill 0412."), evidence: [{ kind: "run", id: "run_a" }] }, NOW);
    await appendLog(coach, T, "msn_1", entry("c", "taf-bills", "Matched bill 0413."), NOW);
    const { entries } = (await readLog(coach, T, "msn_1"))!;
    const names = new Map([["taf-bills", "Bills"]]);
    const page = logPage(entries, names, "en");
    expect(page.items.map((i) => [i.id, i.removed === true])).toEqual([["c", false], ["b", false], ["a", true]]);
    expect(page.items[2]!.by).toEqual({ kind: "taf" });
    // What runs read: nothing of the removed taf, nor an entry naming one of its runs.
    expect(logForRuns(entries, names, "en")).toEqual(["2026-10-16 10:00 · Bills · Matched bill 0413."]);
  });

  it("pages the person's log newest first, and fits what runs read in about 1,200 words", async () => {
    const entries: LogEntry[] = Array.from({ length: 45 }, (_, i) => ({ id: `e${i}`, at: "2026-10-16T10:00:00Z", by: { kind: "lead" }, kind: "note", words: "word ".repeat(40).trim(), evidence: [] }));
    const first = logPage(entries, new Map(), "en");
    expect(first.items.map((i) => i.id).slice(0, 2)).toEqual(["e44", "e43"]);
    expect(first.before).toBe("e25");
    const second = logPage(entries, new Map(), "en", first.before);
    expect(second.items[0]!.id).toBe("e24");
    expect(logPage(entries, new Map(), "en", "nope")).toEqual({ items: [] });
    const lines = logForRuns(entries, new Map(), "en");
    expect(lines.length).toBe(26);
    expect(lines.at(-1)).toContain("lead");
  });
});
