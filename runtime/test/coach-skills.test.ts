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

  // kiff/agents refuses a definition naming more skills than its cap (skill.MaxPerAgent, 16 since
  // kiff/agents#316): #525 named ten against a cap of eight, and tafs cloud's fake never applied it.
  it("are no more than kiff/agents takes on one definition", () => {
    const KIFF_AGENTS_MAX_SKILLS = 16;
    expect(COACH_SKILLS.length).toBeLessThanOrEqual(KIFF_AGENTS_MAX_SKILLS);
    expect(setupDefinition("taf-0123456789abcdef0123").skills?.length).toBe(COACH_SKILLS.length);
  });

  it("never changes a stored version: a changed body under the same version is refused, a new version is added", () => {
    const [one] = readSkills(ROOT);
    const lock = { [`${one.name}@${one.version}`]: digestOf(one.skill_md) };
    expect(() => checkLock([{ ...one, skill_md: one.skill_md + "\nOne more line.\n" }], lock)).toThrow("a stored version never changes");
    const bumped = String(Number(one.version) + 1);
    const next = checkLock([{ ...one, version: bumped, skill_md: one.skill_md + "\nOne more line.\n" }], lock);
    expect(Object.keys(next).sort()).toEqual([`${one.name}@${one.version}`, `${one.name}@${bumped}`]);
  });

  it("are twelve, each in the Agent Skills shape, within its budget, with a description that says when it applies, in the third person", () => {
    expect(COACH_SKILLS.map((s) => `${s.name}@${s.version}`)).toEqual(["cards-and-holds@3", "connect-an-app@5", "did-this-happen@1", "draft-the-card@6", "drive-the-app@2", "halting@1", "improve-a-taf@1", "name-the-tools@5", "set-up-a-taf@3", "the-dial@1", "the-tafs@2", "usage-and-plans@1"]);
    for (const s of COACH_SKILLS) {
      expect(s.skill_md.startsWith(`---\nname: ${s.name}\ndescription: `), s.name).toBe(true);
      expect(s.body).toContain("## When to use");
      expect(s.body).toContain("## Procedure");
      expect(s.body).toContain("## Verification");
      expect(s.description).toMatch(/\. Use (when|for|whenever) /);
      expect(s.description).not.toMatch(/\b(I|you can use)\b/);
      // A skill is about 1,500 tokens (the design); two cut word for word sit just over, name-the-tools@4 at 1,800.
      expect(approxTokens(s.body), s.name).toBeLessThanOrEqual(1800);
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

  // No skill tells Coach what the core forbids: a mode or "no real money" stated as fact (qa-kiff, recipe 3).
  // A setup that asked where the work comes from offered the owner's inbox to connect first, and the
  // owner took it (the Eval, 5 Nov): with no app named, the taf's own chat comes first.
  // A new Invoice reminders was connected to Stripe through a provider's live-only app while Chargebacks
  // used Stripe's own sign-in, in test (5 Nov): for a vendor the account already connects, the same kind first.
  // Gap 2 (3 of 3 recipes on 6 Nov asked for a new sign-in): the account's own sign-in is reused, so the owner
  // approves the new taf's tools alone; the same kind anew only when planque refuses the reuse.
  it("offers the kind of connection the account already uses for a vendor first, named with its taf, by reusing its sign-in", () => {
    const body = COACH_SKILLS.find((k) => k.name === "connect-an-app")!.body.replace(/\s+/g, " ");
    expect(body).toContain("Before you propose any app, look at the account's connections: the lines after [tafs connections] in the conversation's first message are tafs' data");
    expect(body).toContain("Without them, planque grants with no agent lists the same");
    expect(body).toContain("For a vendor the account already connects, the first option is reusing its sign-in, named with the taf that uses it");
    expect(body).toContain("Say a mode only as the grant gives it.");
    expect(body).toContain("Request {reuse: that grant's id, name, taf_name, tools: []}: the owner then approves the new taf's tools alone, with no new sign-in or key.");
    expect(body).toContain("Only if planque refuses the reuse, ask for the same kind: {server}, {entry} or {api}.");
    expect(body).toContain("Another kind comes only if the owner picks it");
  });

  it("offers the taf's chat first when the owner named no app for where the work comes from", () => {
    const body = COACH_SKILLS.find((k) => k.name === "set-up-a-taf")!.body.replace(/\s+/g, " ");
    expect(body).toContain("When you ask where the work comes from and the owner named no app for it, the first option is that they hand it to the taf in its chat");
    expect(body).toContain("A connection the owner didn't ask for is proposed, never assumed.");
  });

  // Customer mail goes from the company's own mailbox. The ordinary case: the account already connects one
  // person's Gmail for another taf (a calendar summary). Reusing it would send to customers from that
  // person's address, on a sign-in whose access covers their whole mailbox: the reuse above doesn't apply.
  it("sends customer mail from the company's dedicated mailbox, and never reuses a person's own inbox for it", () => {
    const body = COACH_SKILLS.find((k) => k.name === "draft-the-card")!.body.replace(/\s+/g, " ");
    expect(body).toContain("Suggest a dedicated mailbox (support@, a shared or a separate one), never a person's own inbox: the app's access covers the whole mailbox.");
    expect(body).toContain("A connection the account has comes first only when it is that dedicated mailbox (its lines don't say which mailbox: ask, with your other questions); one that is a person's own inbox is never reused for customer mail: suggest connecting the dedicated one.");
    expect(body).toContain("Ask only for what the taf does (uses: the send tool alone when it only sends).");
    expect(body).not.toContain("an app the account connects comes first");
  });

  // Eval 42781271564: Setup named a read for the currency check and left it off the Card.
  it("covers every tool the taf gets, the currency check's read included", () => {
    const body = COACH_SKILLS.find((k) => k.name === "draft-the-card")!.body.replace(/\s+/g, " ");
    expect(body).toContain("Every tool the taf gets goes on its Card, the read tools too");
    expect(body).toContain("That includes the read a money tool's unit_lookup names (the read that says the payment's currency): KIFF makes that read as the taf, so its Card covers it like any other.");
  });

  it("has no skill that states a mode or no real money as fact", () => {
    for (const s of COACH_SKILLS) {
      expect(s.body, s.name).not.toContain("no real money");
      expect(s.body, s.name).not.toContain("real money)");
    }
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
  const SETUP = ["connect-an-app", "draft-the-card", "name-the-tools", "set-up-a-taf", "usage-and-plans"];
  // name-the-tools is at its second version: the first stated the owner's mode as fact (never stored; kept in the lock).
  for (const [entry, want] of [
    ["free", SETUP], ["recipe", SETUP], ["door", SETUP],
    // An `[improve taf …]` start adds a tool and drafts its Card through the gateway's tools too (agentgabo on #525).
    ["improve", ["connect-an-app", "draft-the-card", "improve-a-taf", "name-the-tools"]],
    ["coach_lines", ["the-tafs"]], ["delivery", ["the-dial"]], ["did_this_happen", ["cards-and-holds", "did-this-happen"]],
  ] as const) {
    it(`loads ${want.join(", ")} for a conversation that opens as ${entry}`, () => {
      expect(startSkills(entry)).toEqual(want.map(skillRef));
    });
  }

  it("adds the holds to a start with a held call open, and halting to a halted account, in the definition's order", () => {
    expect(startSkills("free", { heldCall: true, halted: true })).toEqual(["cards-and-holds@3", "connect-an-app@5", "draft-the-card@6", "halting@1", "name-the-tools@5", "set-up-a-taf@3", "usage-and-plans@1"]);
    expect(startSkills("improve", { heldCall: true })).toEqual(["cards-and-holds@3", "connect-an-app@5", "draft-the-card@6", "improve-a-taf@1", "name-the-tools@5"]);
  });

  // Eval 42788659962: "Refunds hasn't done anything since this morning" and "Stop everything right now" were
  // answered without their skills, which the model was left to read. An account with a taf has them from the start.
  it("adds the holds and the halting to any start on an account with a taf of its own, and neither to a first taf", () => {
    expect(startSkills("free", { hasTaf: true })).toEqual(["cards-and-holds@3", "connect-an-app@5", "draft-the-card@6", "halting@1", "name-the-tools@5", "set-up-a-taf@3", "usage-and-plans@1"]);
    expect(startSkills("improve", { hasTaf: true })).toEqual(["cards-and-holds@3", "connect-an-app@5", "draft-the-card@6", "halting@1", "improve-a-taf@1", "name-the-tools@5"]);
    expect(startSkills("coach_lines", { hasTaf: true })).toEqual(["cards-and-holds@3", "halting@1", "the-tafs@2"]);
    expect(startSkills("free", { hasTaf: false })).toEqual(startSkills("free"));
  });

  it("loads a skill by the tool whose procedure it is, and nothing for a vendor's tool or a tool with none", () => {
    // planque's and KIFF's tools never reach tafs cloud (KIFF's gateway serves them): their skills come with the start.
    expect(toolSkills("kiff_draft")).toEqual([]);
    expect(toolSkills("catalog")).toEqual([]);
    expect(toolSkills("taf_dial")).toEqual(["the-dial@1"]);
    expect(toolSkills("taf_task")).toEqual(["the-tafs@2"]);
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
