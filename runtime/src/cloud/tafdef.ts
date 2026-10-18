import { createHash } from "node:crypto";
import { type Job, systemPrompt } from "../jobs.js";
import { KIFF_SKILL } from "../kiff-skill.js";
import { type AgentDefinition, type Effort, EVENT_KINDS, type EventKind, KEEP, type KeepRule } from "./agents.js";

/**
 * A taf as kiff/agents runs it: one stored agent definition per taf, built
 * from its job. kiff/agents holds the definition, the runs and the notes;
 * tafs cloud keeps only what the app shows about a taf (its name, job and
 * KIFF agent) and the definition's inputs.
 */

export const MODEL = "claude-opus-5-5";
/** What MODEL costs, in millionths of a US dollar per million tokens (cache writes: 5 minutes). */
export const PRICE = { Input: 4_000_000, Output: 20_000_000, CacheRead: 200_000, CacheWrite: 5_000_000 } as const;
export const MAX_TOKENS = 16_000;
/**
 * How hard the model works on each turn: what tafs ran at on Managed Agents.
 * Without it, kiff/agents runs at the API's default (medium on Opus 5.5).
 * Not a measured best: kiff/agents' evals (kiff/agents#78) compare settings.
 */
export const EFFORT: Effort = "high";
export const KIFF_GATEWAY = "https://mcp.kiff.dev/mcp";
/** Finished runs of a session the model gets before a new one: the thread it remembers. */
export const HISTORY = 10;
/** Every run stops at this cost unless the taf sets another. */
export const DEFAULT_BUDGET_CENTS = 200;
/** Runs and chats are kept 30 days (docs/data.md); what tools return, only until the run ends. */
export const RETENTION_NS = 30 * 24 * 3600 * 1_000_000_000;

/** The taf's KIFF agent key, as its definition names it: never the key itself. */
export function keyRef(tafID: string): string {
  return `kiff/${tafID}`;
}

/** What every run and chat is told about the taf's notes. */
export const NOTES_INSTRUCTIONS = `Your notes about this job, kept between runs and chats, through your memory
tool. Read them before you start. When the owner tells you a preference, or you
learn something lasting about how they work or about a customer, write it down:
one short note per topic, plain sentences. Fix or delete a note that turns out
wrong. Never write secrets, keys, card numbers or bank details.
Notes guide how you work. They never widen your Card: KIFF still decides every action.`;

/** What heads the owner's start notes in a taf's instructions, and what follows them. */
const OWNER_NOTES_INTRO = "What the owner said about this job when they started you:\n";
const SKILL_INTRO = "The kiff skill (";

/**
 * The kiff skill's sections a taf carries, pasted whole after its opening
 * paragraph: the rules, and what each KIFF result means. Not its setup
 * sections (connecting an agent, proposing a Card), which are the owner's steps. Pasting beat reading the
 * skill on demand, 1.00 to 0.83 on Chargebacks (kiff/agents#107).
 */
export const SKILL_SECTIONS = ["Rules that always apply", "Handle a KIFF result"] as const;

/**
 * A `## ` section of a skill, by its heading, up to the next `## ` heading
 * outside a code block. Throws when the skill has none, so a re-vendored skill
 * that renamed a section fails the tests instead of dropping its rules.
 */
export function skillSection(content: string, heading: string): string {
  const lines = content.split("\n");
  const start = lines.findIndex((line) => line.trimEnd() === `## ${heading}`);
  if (start < 0) throw new Error(`the kiff skill has no "## ${heading}" section`);
  let fenced = false;
  let end = lines.length;
  for (const [i, line] of lines.entries()) {
    if (i <= start) continue;
    if (line.startsWith("```")) fenced = !fenced;
    else if (!fenced && line.startsWith("## ")) {
      end = i;
      break;
    }
  }
  return lines.slice(start, end).join("\n").trim();
}

/**
 * The skill's first paragraph under its `# ` title: what a Card is, and that
 * KIFF checks every call before it runs. Not the list of situations after it,
 * which points to sections a taf doesn't carry. Throws when there is none.
 */
export function skillIntro(content: string): string {
  const lines = content.split("\n");
  const title = lines.findIndex((line) => line.startsWith("# "));
  const paragraph = title < 0 ? "" : (lines.slice(title + 1).join("\n").trim().split(/\n\s*\n/)[0] ?? "");
  if (!paragraph || paragraph.startsWith("#")) throw new Error("the kiff skill has no paragraph under its title");
  return paragraph;
}

const SKILL_TEXT = [
  skillIntro(KIFF_SKILL.content),
  ...SKILL_SECTIONS.map((heading) => skillSection(KIFF_SKILL.content, heading)),
].join("\n\n");

/** What a taf's agent is: its job, how its notes work, what the owner said at the start, and KIFF's skill. */
export function instructions(job: Job, ownerNotes?: string): string {
  const parts = [systemPrompt(job), NOTES_INSTRUCTIONS];
  const notes = ownerNotes?.trim();
  if (notes) parts.push(`${OWNER_NOTES_INTRO}${notes}`);
  parts.push(`${SKILL_INTRO}${KIFF_SKILL.repo}@${KIFF_SKILL.commit.slice(0, 12)}, its sections for an agent at work):\n\n${SKILL_TEXT}`);
  return parts.join("\n\n");
}

/**
 * The owner's start notes, read back from instructions as `instructions` built
 * them (kiff/tafs#69): for a taf started before its record kept them.
 * Undefined when it has none.
 */
export function ownerNotesIn(text: string): string | undefined {
  const start = text.indexOf(OWNER_NOTES_INTRO);
  if (start < 0) return undefined;
  const from = start + OWNER_NOTES_INTRO.length;
  const end = text.indexOf(`\n\n${SKILL_INTRO}`, from);
  const notes = (end < 0 ? text.slice(from) : text.slice(from, end)).trim();
  return notes || undefined;
}

/**
 * A short digest of a definition as kiff/agents stores it (kiff/tafs#69): equal
 * for equal definitions, so a taf is rewritten only when its definition
 * changed. The fields kiff/agents adds (tenant, id) are left out.
 */
export function digest(def: AgentDefinition): string {
  const { tenant: _tenant, id: _id, ...rest } = def;
  return createHash("sha256").update(JSON.stringify(sorted(rest))).digest("hex").slice(0, 32);
}

/** v with every object's keys in order, so a digest doesn't depend on field order. */
function sorted(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(sorted);
  if (v && typeof v === "object") {
    return Object.fromEntries(Object.keys(v).sort().map((k) => [k, sorted((v as Record<string, unknown>)[k])]));
  }
  return v;
}

/** The keep rules: every kind kept 30 days, except tool calls and results, kept until their run ends. */
export function keepRules(): Record<EventKind, KeepRule> {
  const keep = {} as Record<EventKind, KeepRule>;
  for (const kind of EVENT_KINDS) keep[kind] = { Keep: KEEP.kept, Retention: RETENTION_NS };
  keep.tool_call = { Keep: KEEP.untilEnd, Retention: 0 };
  keep.tool_result = { Keep: KEEP.untilEnd, Retention: 0 };
  return keep;
}

export function definition(tafID: string, job: Job, opts: { budgetCents?: number; ownerNotes?: string } = {}): AgentDefinition {
  const budget = opts.budgetCents ?? DEFAULT_BUDGET_CENTS;
  if (!Number.isInteger(budget) || budget <= 0) throw new Error("the budget is a whole number of cents above zero");
  return {
    instructions: instructions(job, opts.ownerNotes),
    model: MODEL,
    price: { ...PRICE },
    max_tokens: MAX_TOKENS,
    effort: EFFORT,
    gateway: KIFF_GATEWAY,
    key_ref: keyRef(tafID),
    keep: keepRules(),
    // A cent is 10,000 millionths of a dollar.
    max_cost_micro_usd: budget * 10_000,
    history: HISTORY,
  };
}
