/**
 * Which of Coach's skills a conversation loads, decided by tafs cloud and never by the model (the Coach
 * skills design, 5 Nov 2026, with its v2 and v3 addenda). Coach's core instructions carry who it is, how it
 * talks, the rules that hold whatever is loaded, and the map of its skills; the skills' bodies reach the
 * model's context from kiff/agents, after the instructions, in the definition's order:
 *
 *   1. by the entry point, before the first turn (`startSkills`): how the conversation opened and the
 *      taf's state name the skills, from a table here, one test per entry point;
 *   2. by the tool, when it is used (`toolSkills`): a tool whose procedure is a skill adds it to the
 *      session in kiff/agents, read at the next turn, never in the tool's answer;
 *   3. `read_skill` stays as Coach's own fallback, by the names in the core's map.
 *
 * A free-text start loads `set-up-a-taf` alone, and the tools bring the rest (the owner's answer to Q1);
 * a classifier, if one comes, only ever adds. Nothing here reaches the apps.
 */

import { COACH_SKILLS } from "../coach-skills.js";
import { RECIPES } from "../recipes.js";

/** A skill by its stored version, as kiff/agents names it. */
export type SkillRef = `${string}@${string}`;

/** How a Coach conversation opened, as tafs cloud knows it from the first message and the account. */
export type CoachEntry = "improve" | "recipe" | "door" | "coach_lines" | "delivery" | "did_this_happen" | "free";

const SKILL_BY_NAME = new Map<string, (typeof COACH_SKILLS)[number]>(COACH_SKILLS.map((s) => [s.name, s]));

/** The stored reference of a skill by name; throws for a name no skill has, which a test catches. */
export function skillRef(name: string): SkillRef {
  const s = SKILL_BY_NAME.get(name);
  if (!s) throw new Error(`no Coach skill named ${name}`);
  return `${s.name}@${s.version}`;
}

/** Every skill's reference, in the definition's order. */
export function allSkillRefs(): SkillRef[] {
  return COACH_SKILLS.map((s) => `${s.name}@${s.version}` as SkillRef);
}

/**
 * The skills each entry point loads before the first turn. A new taf (a free start, a recipe, a door's
 * knock) sets one up; an improvement improves; a delivery note is the dial's; a "Did this happen?" tap
 * is its own flow; coach lines (a task's outcome before the owner's words) are the tafs'.
 */
const SETUP = ["set-up-a-taf", "connect-an-app", "name-the-tools", "draft-the-card", "usage-and-plans"];
const ENTRY_SKILLS: Record<CoachEntry, string[]> = {
  // Setting up a taf connects an app, names its tools and drafts its Card; those tools (planque's, KIFF's)
  // go through KIFF's gateway and never reach tafs cloud, so their skills come with the start, and so does
  // what the owner asks on the way (usage and plans).
  free: SETUP,
  recipe: SETUP,
  door: SETUP,
  // An improvement often adds a tool and drafts its Card, through the same gateway tools (agentgabo).
  improve: ["improve-a-taf", "connect-an-app", "name-the-tools", "draft-the-card"],
  coach_lines: ["the-tafs"],
  delivery: ["the-dial"],
  did_this_happen: ["did-this-happen", "cards-and-holds"],
};

/**
 * The tools whose procedure is a skill: calling one loads it for the session (way 2). Only tafs cloud's own
 * tools reach it (`POST /v1/mcp`): planque's and KIFF's go through KIFF's gateway, so their skills load at
 * the start (ENTRY_SKILLS) or by `read_skill`.
 */
const TOOL_SKILLS: Record<string, string[]> = {
  tafs_state: ["the-tafs"], taf_run: ["the-tafs"], taf_check: ["the-tafs"], taf_task: ["the-tafs"], run_stop: ["the-tafs"], taf_pause: ["halting"],
  version_try: ["the-tafs"], version_install: ["the-tafs"],
  taf_results: ["the-dial"], taf_dial: ["the-dial"],
  app_look: ["drive-the-app"], app_act: ["drive-the-app"],
};

/** The state of the account the first turn starts from, as far as the skills care. */
export interface CoachState {
  /** The account has a taf of its own (not Setup), which can be held or halted. */
  hasTaf?: boolean;
  /** The account is halted, or a taf of it is. */
  halted?: boolean;
  /** A call waits for the owner (held) on one of its tafs. */
  heldCall?: boolean;
}

/** The entry point of a conversation, from the first message as the app or tafs cloud wrote it. */
export function entryOf(text: string): CoachEntry {
  const t = text.trimStart();
  if (t.startsWith("[improve taf ")) return "improve";
  if (t.startsWith("[door knock ")) return "door";
  if (t.startsWith("[tafs coach lines]")) return "coach_lines";
  if (t.startsWith("[did this happen ")) return "did_this_happen";
  if (DELIVERY_NOTE.test(t)) return "delivery";
  const said = t.trim();
  if (RECIPES.some((r) => said === r.say.trim() || said.startsWith(r.say.trim()))) return "recipe";
  return "free";
}

/** The words a delivery note opens Coach with (delivery.ts DELIVERY_WORDS.coach). */
const DELIVERY_NOTE = /^.{1,120} didn't deliver its result to .{1,200}Find out why and propose the fix as a version of its dial\./s;

/**
 * The skills a new conversation starts with, in the definition's order: the entry point's, and the
 * state's (a held call opens the holds, a halt the halting). Each is a stored version.
 *
 * An account with a taf of its own also starts with the holds and the halting, whatever its state: an
 * owner of a running taf asks why it did nothing, or to stop everything, in words that never name a skill,
 * and the model left to read_skill didn't (kiff/agents Eval 42788659962). An account with no taf yet
 * loads neither; once Setup has made its first taf, even mid-setup, both load.
 */
export function startSkills(entry: CoachEntry, state: CoachState = {}): SkillRef[] {
  const names = new Set(ENTRY_SKILLS[entry]);
  if (state.heldCall || state.hasTaf) names.add("cards-and-holds");
  if (state.halted || state.hasTaf) names.add("halting");
  return ordered(names);
}

/** The skills a tool call loads for its session (way 2); none for a tool with no procedure of its own. */
export function toolSkills(tool: string): SkillRef[] {
  return ordered(new Set(TOOL_SKILLS[tool] ?? []));
}

/** Names as references, in the definition's order (the Coach skills design, v3 §1: a fixed order keeps the cache). */
function ordered(names: Set<string>): SkillRef[] {
  return COACH_SKILLS.filter((s) => names.has(s.name as string)).map((s) => `${s.name}@${s.version}` as SkillRef);
}

/**
 * refs at the versions the live definition names, the only ones kiff/agents loads ("name@version", both): a
 * definition changes with the Upgrade, while this module's versions change with each deploy, so a deploy
 * ahead of the Upgrade asked for versions kiff/agents refused, and every new chat was refused with them
 * (kiff/tafs#572). A skill the definition doesn't name is left out: Coach reads what it has with read_skill.
 */
export function asNamed(refs: SkillRef[], named: ReadonlyArray<{ name: string; version: string }> | undefined): SkillRef[] {
  const live = new Map((named ?? []).map((s) => [s.name, s.version]));
  return refs.flatMap((ref) => {
    const name = ref.slice(0, ref.lastIndexOf("@"));
    const version = live.get(name);
    return version ? [`${name}@${version}` as SkillRef] : [];
  });
}

/** The SKILL.md of each skill, to store in kiff/agents with Setup's definition. */
export function skillFiles(): Array<{ name: string; version: string; skillMD: string }> {
  return COACH_SKILLS.map((s) => ({ name: s.name, version: s.version, skillMD: s.skill_md }));
}

/** The key a session's loaded skill is remembered under in the coach store, so a tool loads it once. */
export function loadedKey(tenant: string, session: string, ref: SkillRef): string {
  return `${tenant}#skill-loaded#${session}#${ref}`;
}

/** How long a session's mark is kept: a conversation's life, and no longer. */
export const LOADED_KEPT_MS = 7 * 24 * 60 * 60 * 1000;

/** An approximate token count, as a budget test reads it: four characters a token, the usual for English prose. */
export function approxTokens(text: string): number {
  return Math.ceil(text.length / 4);
}
