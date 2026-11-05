import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { COACH_SKILLS } from "../src/coach-skills.js";
import { allSkillRefs, approxTokens, entryOf, loadedKey, skillFiles, skillRef, startSkills, toolSkills } from "../src/cloud/coach-skills.js";
import { DELIVERY_WORDS } from "../src/cloud/delivery.js";
import { improveContext } from "../src/cloud/improve.js";
import { CORE_RULES, SETUP_CORE, SETUP_INSTRUCTIONS, setupDefinition, SKILLS_MAP } from "../src/cloud/setupdef.js";
import { RECIPES } from "../src/recipes.js";
// The generator is plain JavaScript, so the script and the test parse the files the same way.
import { checkLock, digestOf, LOCK, readSkills, render } from "../scripts/coach-skills.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, "..", "skills", "coach");
const MODULE = join(here, "..", "src", "coach-skills.ts");

describe("Coach's skills, as files", () => {
  it("serves exactly what skills/coach/*/*/SKILL.md says: the generated module matches the files, and the lock their versions", () => {
    const fromFiles = readSkills(ROOT);
    expect(readFileSync(MODULE, "utf8")).toBe(render(fromFiles));
    const lock = JSON.parse(readFileSync(join(ROOT, LOCK), "utf8")) as Record<string, string>;
    for (const s of fromFiles) expect(lock[`${s.name}@${s.version}`], `${s.name}@${s.version} in the lock`).toBe(digestOf(s.skill_md));
  });

  it("never changes a stored version: a changed body under the same version is refused, a new version is added", () => {
    const [one] = readSkills(ROOT);
    const lock = { [`${one.name}@${one.version}`]: digestOf(one.skill_md) };
    expect(() => checkLock([{ ...one, skill_md: one.skill_md + "\nOne more line.\n" }], lock)).toThrow("a stored version never changes");
    const next = checkLock([{ ...one, version: "2", skill_md: one.skill_md + "\nOne more line.\n" }], lock);
    expect(Object.keys(next).sort()).toEqual([`${one.name}@1`, `${one.name}@2`]);
  });

  it("are ten, each in the Agent Skills shape, within its budget, with a description that says when it applies, in the third person", () => {
    expect(COACH_SKILLS.map((s) => s.name)).toEqual(["cards-and-holds", "connect-an-app", "draft-the-card", "halting", "improve-a-taf", "name-the-tools", "set-up-a-taf", "the-dial", "the-tafs", "usage-and-plans"]);
    for (const s of COACH_SKILLS) {
      expect(s.skill_md.startsWith(`---\nname: ${s.name}\ndescription: `), s.name).toBe(true);
      expect(s.body).toContain("## When to use");
      expect(s.body).toContain("## Procedure");
      expect(s.body).toContain("## Verification");
      expect(s.description).toMatch(/\. Use (when|for|whenever) /);
      expect(s.description).not.toMatch(/\b(I|you can use)\b/);
      // A skill is at most 1,600 tokens (the design says 1,500; two cut word for word sit just over).
      expect(approxTokens(s.body), s.name).toBeLessThanOrEqual(1700);
    }
    expect(skillFiles().map((f) => `${f.name}@${f.version}`)).toEqual(allSkillRefs());
  });
});

describe("Coach's core", () => {
  it("is about 2,000 tokens, with the map of its skills and the rules that hold whatever is loaded", () => {
    expect(SETUP_INSTRUCTIONS).toBe(SETUP_CORE);
    expect(approxTokens(SETUP_CORE)).toBeLessThanOrEqual(2500);
    expect(SETUP_CORE).toContain(SKILLS_MAP);
    for (const s of COACH_SKILLS) expect(SETUP_CORE).toContain(`  - ${s.name}: ${s.description}`);
    // Two or three short examples of how Coach talks stay in the core (the owner's answer to Q2).
    expect(SETUP_CORE).toContain("Falta que apruebes");
    expect(SETUP_CORE).toContain("…al día.");
  });

  // One test per rule, against the core alone with no skill loaded: cutting the base can't drop one (v2 §1).
  for (const rule of CORE_RULES) {
    it(`keeps the rule "${rule.slice(0, 50)}…" in the core with no skill loaded`, () => {
      expect(SETUP_CORE).toContain(rule);
      // The rule lives in the core, not only in a skill: the skills' bodies alone don't carry it.
      expect(COACH_SKILLS.map((s) => s.body).join("\n")).not.toContain(rule);
    });
  }

  // Coach asked "past 20 a day" twice on the owner's phone (recipe 3, 5 Nov): the core says once, whatever skill is loaded.
  it("asks each thing once, in the core", () => {
    expect(SETUP_CORE).toContain("Ask each thing once. What the owner already told you, a limit included");
  });

  it("names its skills in the definition, each at its stored version", () => {
    const d = setupDefinition("taf-0123456789abcdef0123");
    expect(d.skills).toEqual(COACH_SKILLS.map((s) => ({ name: s.name, version: s.version })));
    expect(d.instructions).toBe(SETUP_CORE);
  });
});

describe("which skills a conversation loads: tafs cloud's decision, never the model's", () => {
  it("reads the entry point from the first message", () => {
    expect(entryOf(improveContext({ id: "taf-1", name: "Rae", job: "refunds", kiffAgent: "rae" }, { tools: [], rules: [] }, [], "add disputes"))).toBe("improve");
    expect(entryOf("[door knock dk_1] The owner says: yes\n\nhello")).toBe("door");
    expect(entryOf("[tafs coach lines]\n[taf Rae task t1 ended] answer: done\nThe owner says: thanks")).toBe("coach_lines");
    expect(entryOf(DELIVERY_WORDS.coach("Rae", "Google Sheets · Refunds 2026", "the app didn't take it"))).toBe("delivery");
    expect(entryOf("[did this happen p_1] Refund a payment at Stripe, 400.00 EUR")).toBe("did_this_happen");
    expect(entryOf(RECIPES[0]!.say)).toBe("recipe");
    expect(entryOf("I want a taf that chases overdue invoices")).toBe("free");
  });

  // One test per entry point: the skill it needs is named, in the definition's order, as a stored version.
  for (const [entry, want] of [
    ["free", ["set-up-a-taf"]], ["recipe", ["set-up-a-taf"]], ["door", ["set-up-a-taf"]], ["improve", ["improve-a-taf"]],
    ["coach_lines", ["the-tafs"]], ["delivery", ["the-dial"]], ["did_this_happen", ["cards-and-holds"]],
  ] as const) {
    it(`loads ${want.join(", ")} for a conversation that opens as ${entry}`, () => {
      expect(startSkills(entry)).toEqual(want.map(skillRef));
    });
  }

  it("adds the holds to a start with a held call open, and halting to a halted account, in the definition's order", () => {
    expect(startSkills("free", { heldCall: true, halted: true })).toEqual(["cards-and-holds@1", "halting@1", "set-up-a-taf@1"]);
    expect(startSkills("improve", { heldCall: true })).toEqual(["cards-and-holds@1", "improve-a-taf@1"]);
  });

  it("loads a skill by the tool whose procedure it is, and nothing for a vendor's tool or a tool with none", () => {
    expect(toolSkills("kiff_draft")).toEqual(["draft-the-card@1"]);
    expect(toolSkills("catalog")).toEqual(["name-the-tools@1"]);
    expect(toolSkills("taf_dial")).toEqual(["the-dial@1"]);
    expect(toolSkills("taf_task")).toEqual(["the-tafs@1"]);
    expect(toolSkills("taf_pause")).toEqual(["halting@1"]);
    expect(toolSkills("stripe_refund_k3f9")).toEqual([]);
    expect(toolSkills("show")).toEqual([]);
    // A tool's answer is never where a skill comes from: the name decides, whatever the answer says.
    expect(toolSkills("# Skill: halting")).toEqual([]);
  });

  it("marks a loaded skill per session, so a tool loads it once", () => {
    expect(loadedKey("t_acme", "s1", "the-dial@1")).toBe("t_acme#skill-loaded#s1#the-dial@1");
    expect(() => skillRef("no-such-skill")).toThrow("no Coach skill named no-such-skill");
  });
});
