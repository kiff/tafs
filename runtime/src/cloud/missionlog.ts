import type { Run } from "./agents.js";
import type { CoachStore } from "./coach.js";
import { type Lang, wordsIn } from "./words/index.js";

/**
 * A mission's log (docs/missions.md): the shared record of what its tafs delivered, newest last. An entry keeps facts
 * (the record's counts, refs to the run and the items) and its words are made when it's read, in the reader's language;
 * only an authored note keeps words, which are data. A removed taf's entries leave what runs read (M7).
 */
export interface LogEntry {
  id: string;
  at: string;
  by: { kind: "taf" | "lead" | "person"; id?: string };
  kind: "delivered" | "handed" | "stuck" | "decided" | "note";
  /** An authored entry's words: one or two sentences, data. A delivered entry has none: its counts say it. */
  words?: string;
  /** The record's counts for a delivered run, never the model's. */
  counts?: { done: number; held: number; refused: number };
  /** Refs only, never an item's data: the run, the items it acted on, a hold. */
  evidence: Array<{ kind: "run" | "item" | "hold"; id: string }>;
}

export const LOG_LIMITS = { entries: 500, keptDays: 90, page: 20, runWords: 1200, items: 5 } as const;

const DAY_MS = 24 * 3600_000;
const CLAIM_MS = 60_000;

export const logKey = (tenant: string, mission: string) => `${tenant}#mission-log#${mission}`;
const runKey = (tenant: string, run: string) => `${tenant}#mission-run#${run}`;

export async function readLog(store: CoachStore, tenant: string, mission: string): Promise<{ entries: LogEntry[]; version: number } | undefined> {
  const kept = await store.get(logKey(tenant, mission)) as { entries?: LogEntry[]; version?: number } | undefined;
  return kept && Array.isArray(kept.entries) ? { entries: kept.entries, version: kept.version ?? 0 } : undefined;
}

/** Adds an entry, keeping the newest 500 of the last 90 days; another write's entry is kept too (tried again on it). */
export async function appendLog(store: CoachStore, tenant: string, mission: string, entry: LogEntry, now: Date): Promise<boolean> {
  for (let attempt = 0; attempt < 3; attempt++) {
    const read = await readLog(store, tenant, mission) ?? { entries: [], version: 0 };
    const since = now.getTime() - LOG_LIMITS.keptDays * DAY_MS;
    const entries = [...read.entries.filter((e) => Date.parse(e.at) >= since), entry].slice(-LOG_LIMITS.entries);
    if (!(await store.once(`${logKey(tenant, mission)}#v${read.version + 1}`, new Date(now.getTime() + CLAIM_MS)))) continue;
    await store.put(logKey(tenant, mission), { entries, version: read.version + 1 }, new Date(now.getTime() + LOG_LIMITS.keptDays * DAY_MS));
    return true;
  }
  return false;
}

/** A member's run that ended, as a delivered entry: once per run (claimed), with the record's counts and the items it acted on. */
export async function logDelivered(store: CoachStore, tenant: string, mission: string, taf: string, run: Run, now: Date): Promise<boolean> {
  if (run.status !== "COMPLETED" || run.trigger === "trial") return false;
  if (!(await store.once(runKey(tenant, run.id), new Date(now.getTime() + LOG_LIMITS.keptDays * DAY_MS)))) return false;
  const tally = Object.values(run.tally ?? {});
  const sum = (k: "sent" | "held" | "refused") => tally.reduce((n, t) => n + (t[k] ?? 0), 0);
  const items = [...new Set((run.done ?? []).flatMap((d) => (typeof d.resource === "string" && d.resource ? [d.resource] : [])))].slice(0, LOG_LIMITS.items);
  return appendLog(store, tenant, mission, {
    id: `log_${run.id}`, at: run.updated ?? now.toISOString(), by: { kind: "taf", id: taf }, kind: "delivered",
    counts: { done: sum("sent"), held: sum("held"), refused: sum("refused") },
    evidence: [{ kind: "run", id: run.id }, ...items.map((id) => ({ kind: "item" as const, id }))],
  }, now);
}

/** An entry's words in the reader's language: an authored one's as written, a delivered one's from its counts. */
export function entryWords(e: LogEntry, lang: Lang): string {
  if (e.kind === "delivered" && e.counts) return wordsIn(lang).mission_log_delivered(e.counts.done, e.counts.held, e.counts.refused);
  return e.words ?? "";
}

/**
 * The log as the person reads it, newest first, a page at a time: each entry by its taf's name, and a removed taf's
 * marked "removed" (M7: still in the person's read-only log, never in what runs read). `names` holds the tafs that exist.
 */
export function logPage(entries: LogEntry[], names: Map<string, string>, lang: Lang, before?: string) {
  const newest = [...entries].reverse();
  const from = before ? newest.findIndex((e) => e.id === before) + 1 : 0;
  if (before && from === 0) return { items: [] };
  const page = newest.slice(from, from + LOG_LIMITS.page);
  const items = page.map((e) => {
    const removed = e.by.kind === "taf" && !names.has(e.by.id ?? "");
    return { id: e.id, at: e.at, by: { kind: e.by.kind, ...(e.by.kind === "taf" && !removed ? { name: names.get(e.by.id!) } : {}) }, kind: e.kind,
      words: entryWords(e, lang), evidence: e.evidence, ...(removed ? { removed: true } : {}) };
  });
  return { items, ...(from + LOG_LIMITS.page < newest.length ? { before: page.at(-1)!.id } : {}) };
}

/**
 * What a member's run reads of the log (M7): the newest entries that fit in about 1,200 words, oldest first, as data.
 * An entry by a taf that no longer exists is left out, and so is any entry naming one of its runs.
 */
export function logForRuns(entries: LogEntry[], names: Map<string, string>, lang: Lang): string[] {
  const gone = new Set(entries.filter((e) => e.by.kind === "taf" && !names.has(e.by.id ?? "")).flatMap((e) => e.evidence.filter((v) => v.kind === "run").map((v) => v.id)));
  const lines: string[] = [];
  let words = 0;
  for (const e of [...entries].reverse()) {
    if (e.by.kind === "taf" && !names.has(e.by.id ?? "")) continue;
    if (e.evidence.some((v) => v.kind === "run" && gone.has(v.id))) continue;
    const who = e.by.kind === "taf" ? names.get(e.by.id!)! : e.by.kind;
    const line = `${e.at.slice(0, 16).replace("T", " ")} · ${who} · ${entryWords(e, lang)}`;
    words += line.split(/\s+/).length;
    if (words > LOG_LIMITS.runWords) break;
    lines.unshift(line);
  }
  return lines;
}
