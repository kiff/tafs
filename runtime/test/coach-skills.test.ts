import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { COACH_SKILLS } from "../src/coach-skills.js";
import { allSkillRefs, approxTokens, entryOf, loadedKey, skillFiles, skillRef, startSkills, toolSkills } from "../src/cloud/coach-skills.js";
import { DELIVERY_WORDS } from "../src/cloud/delivery.js";
import { disclosureLine } from "../src/jobs.js";
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
    expect(COACH_SKILLS.map((s) => `${s.name}@${s.version}`)).toEqual(["cards-and-holds@3", "connect-an-app@16", "did-this-happen@1", "draft-the-card@11", "drive-the-app@3", "halting@1", "improve-a-taf@1", "name-the-tools@7", "set-up-a-taf@8", "the-dial@4", "the-tafs@2", "usage-and-plans@3"]);
    for (const s of COACH_SKILLS) {
      expect(s.skill_md.startsWith(`---\nname: ${s.name}\ndescription: `), s.name).toBe(true);
      expect(s.body).toContain("## When to use");
      expect(s.body).toContain("## Procedure");
      expect(s.body).toContain("## Verification");
      expect(s.description).toMatch(/\. Use (when|for|whenever) /);
      expect(s.description).not.toMatch(/\b(I|you can use)\b/);
      // A skill is about 1,500 tokens (the design); two cut word for word sit just over, name-the-tools@4 at 1,800.
      // set-up-a-taf is the one exception, at 1,900: sizing the job first (A4) on top of rules from earlier Evals.
      expect(approxTokens(s.body), s.name).toBeLessThanOrEqual(s.name === "set-up-a-taf" ? 1900 : 1800);
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
  // Owner's phone, 13 Nov (Shippo): Coach asked the owner's choices, then found the app couldn't be connected; it
  // offered no other way, and only said to write again later. Checked first now, with a way forward, for any app.
  // KIFF holds a call past a total but can't approve it once (CanApproveOnce): the owner raises the limit or turns
  // it down. Coach wrote "waits but can't be approved", which a reader took as refused (Eval, 13 Nov).
  it("says a call past a day's total waits until the limit is raised, or is refused, never that it can't be approved", () => {
    const body = COACH_SKILLS.find((k) => k.name === "draft-the-card")!.body.replace(/\s+/g, " ");
    expect(body).toContain("one action over its own limit can be approved once; past a day's total, it waits until they raise the limit, or is refused: never \"can't be approved\".");
    expect(body).not.toContain("but one that would go past a day's total can't");
  });

  // Coach finds a vendor's published API description itself, with web_read, never one it makes up.
  it("finds a vendor's published API description with web_read, and loads connect-an-app when it reads a page", () => {
    const body = COACH_SKILLS.find((k) => k.name === "connect-an-app")!.body.replace(/\s+/g, " ");
    expect(body).toContain("Find the description with web_read on the vendor's docs, or use the owner's; never make one up.");
    expect(toolSkills("web_read")).toEqual(["connect-an-app@16"]);
  });

  // planque declares a key request's tools to KIFF before the key only when the request names them, and only
  // then can one approval cover the key and the Card. Coach can't know planque's names for a description's
  // operations before a grant, so it asks planque's describe first (the Eval's desk and billing sent none).
  it("names the job's tools on every key request, so planque declares them and the key and the Card are one approval", () => {
    const body = COACH_SKILLS.find((k) => k.name === "connect-an-app")!.body.replace(/\s+/g, " ");
    expect(body).toContain("Describe {api} (a count: again with q, the job's words), then request the job's operations as tools, by describe's names, with full specs (name-the-tools), never none, so it's declared.");
    expect(body).toContain("Else, key then Card.");
    expect(body).not.toContain("The request carries its tools (an entry's come with it).");
    // A big API (Stripe's 608 operations, GitHub's 1,277) answers its count: Coach describes it again with the job's words.
    expect(body).toContain("(a count: again with q, the job's words)");
    // The red Eval of 13 Nov: a key request named its tools with no pins or resource_argument, since Coach had no
    // fields before a grant. Each tool now carries its full spec, from describe's arguments.
    expect(body).toContain("with full specs (name-the-tools)");
    const tools = COACH_SKILLS.find((k) => k.name === "name-the-tools")!.body.replace(/\s+/g, " ");
    expect(tools).toContain("(before a key: describe's arguments, by in and type)");
  });

  it("checks an app can be connected before asking anything else, and offers a way forward when it can't", () => {
    const setup = COACH_SKILLS.find((k) => k.name === "set-up-a-taf")!.body.replace(/\s+/g, " ");
    expect(setup).toContain("If the job needs an app, search {q} first: say if it can't be connected, with a way forward, before other questions.");
    const connect = COACH_SKILLS.find((k) => k.name === "connect-an-app")!.body.replace(/\s+/g, " ");
    expect(connect).toContain("When an app can't be connected (needs_setup, or its sign-in, request or describe failed), say why in one sentence from planque's answer and offer a way forward then: the same app's key entry, its key by its published API description, or another app with the operation; never only \"try later\".");
    // A key some vendors want after a word ("ShippoToken <key>"): the request names it.
    expect(connect).toContain("(in: header or query, name, prefix such as \"Token \")");
  });

  it("offers the kind of connection the account already uses for a vendor first, named with its taf, by reusing its sign-in", () => {
    const body = COACH_SKILLS.find((k) => k.name === "connect-an-app")!.body.replace(/\s+/g, " ");
    expect(body).toContain("Before you propose any app, look at the account's connections: the lines after [tafs connections] in the conversation's first message are tafs' data");
    expect(body).toContain("Without them, planque grants with no agent lists the same");
    expect(body).toContain("For a vendor a taf already connects, the first option is reusing its sign-in, named with the taf that uses it");
    // A test agent's connected key was offered as "your existing Qonto connection" (owner's phone, 12 Nov):
    // only the account's tafs' connections, or one made in this conversation, are offered.
    expect(body).toContain("Offer only one a taf uses, or one made in this conversation; another agent's grant (a test's) is never offered or reused.");
    // A reviewed key entry refused uses (owner's phone, 12 Nov) and dropping it widened managed apps' sign-ins:
    // every entry now takes the taf's own tools (kiff/planque#156), so uses is sent for each.
    expect(body).toContain("Connect the chosen app with request {entry, name, taf_name, uses}: uses are the slugs of the tools search found that the taf will use, so only those are asked.");
    expect(body).not.toContain("and no other field");
    // Four asks for one read-only report, two of them approvals, the key before the Card (owner's phone, 12 Nov):
    // a key grant's tools are declared at the request, so the Card is drafted first and the key sheet signs both.
    expect(body).toContain("For a key, the sheet is the ask, with no card before it.");
    // planque takes tools only on a signed-in grant, and declares a key grant's from the request (agentgabo on #830).
    expect(body).not.toContain("(planque tools) while it waits");
    expect(body).toContain("Describe {api} (a count: again with q, the job's words), then request the job's operations as tools, by describe's names, with full specs (name-the-tools), never none, so it's declared. If declared, agree the rules, draft the Card, show one approval with the grant and the draft: the sheet signs both. Else, key then Card.");
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
    // The rules agreed "1,000 reads a day each" and the Card signed "4,000 a day" for all (owner's phone, 12 Nov):
    // the draft is the agreed rules line for line, and a limit it can't hold is agreed again, never changed silently.
    expect(body).toContain("The draft is the agreed rules, line for line, never merged (\"1,000 reads a day each\" stays a line per tool, not 4,000 for all).");
    expect(body).toContain("A limit it can't hold as agreed is a changed rule: show it in a new rules notice and get it agreed first. Never change a number silently.");
    expect(body).toContain("Suggest a dedicated mailbox (support@, a shared or a separate one), never a person's own inbox: the app's access covers the whole mailbox.");
    expect(body).toContain("A connection the account has comes first only when it is that dedicated mailbox (its lines don't say which mailbox: ask, with your other questions); one that is a person's own inbox is never reused for customer mail: suggest connecting the dedicated one.");
    expect(body).toContain("Ask only for what the taf does (uses: the send tool alone when it only sends).");
    expect(body).not.toContain("an app the account connects comes first");
  });

  // Go-to-market job 1 (owner's phone, 13 Nov): Coach asked how to tell who was already written to and where
  // do-not-contact is noted, and offered only the owner's or the company's name. The taf keeps its own records,
  // and speaks as itself first, which the disclosure line then names.
  it("has the taf keep its own columns in the owner's sheet, and offers speaking as tafs first", () => {
    const setup = COACH_SKILLS.find((k) => k.name === "set-up-a-taf")!.body.replace(/\s+/g, " ");
    expect(setup).toContain("A taf keeps its own records: when it works from the owner's sheet or list, propose it adds and updates its own columns there (written to, replied, do not contact), with a tool that writes to it. Ask only what only the owner knows, never how to track it.");
    const card = COACH_SKILLS.find((k) => k.name === "draft-the-card")!.body.replace(/\s+/g, " ");
    // The owner reads "tafs, your AI assistant"; the recipient reads "on behalf of tafs", never "your" assistant (qa-kiff on #889).
    expect(card).toContain("who its messages come from: first \"tafs, your AI assistant\" (it speaks as itself: writes_for \"tafs\"), then the owner's name, then the company's (\"Kiff Agents OÜ\"); never the app's default. Put the answer in the result's taf as writes_for.");
    expect(card).not.toContain("or another name?");
    expect(card).toContain("Never promise an approval the Card can't give.");
    expect(disclosureLine({ writesFor: "tafs" })).toBe("Written by an AI agent on behalf of tafs.");
  });

  // Eval 43395198798: "one invoice per order, ever" and "up to 20 a day" were drafted as 1 call in the Card's whole life,
  // so the taf could invoice one order and never another. KIFF counts a total for each item (per).
  it("drafts a limit for each item as a total per its resource argument, never a lifetime cap on the tool", () => {
    const body = COACH_SKILLS.find((k) => k.name === "draft-the-card")!.body.replace(/\s+/g, " ");
    expect(body).toContain('A limit for each item ("one invoice per order, ever") is a total with per: the tool\'s resource_argument (lifetime, or rolling_7d), beside the day\'s total.');
    // A tool with no resource argument: KIFF would refuse a per naming an argument it doesn't declare (qa-kiff on #851).
    expect(body).toContain("With none, it goes in the taf's instructions, and the rules say KIFF can't count it.");
    expect(body).toContain("Never a lifetime total on the tool: it stops after one item.");
    expect(body).not.toContain("KIFF can't enforce it yet");
  });

  // Eval 42781271564: Setup named a read for the currency check and left it off the Card.
  it("covers every tool the taf gets, the currency check's read included", () => {
    const body = COACH_SKILLS.find((k) => k.name === "draft-the-card")!.body.replace(/\s+/g, " ");
    expect(body).toContain("Every tool the taf gets goes on its Card, the read tools too");
    expect(body).toContain("That includes the read a money tool's unit_lookup names (the read that says the payment's currency): KIFF makes that read as the taf, so its Card covers it like any other.");
  });

  // A quiet schedule used credits all day for nothing: every schedule checks first, so the AI wakes only for work.
  it("gives a taf on a schedule the read that lists its open work, and never a check on a read that doesn't list it", () => {
    const setUp = COACH_SKILLS.find((k) => k.name === "set-up-a-taf")!.body.replace(/\s+/g, " ");
    expect(setUp).toContain('"0 9 * * 1-5") and check, the read tool that lists its open work: arguments (open ones only, where the tool allows), items (where the list is in its result), cursor (a field that only grows: created time, an increasing id) and first: "all" when its arguments limit it to open work, "new" when they can\'t.');
    expect(setUp).toContain("It reads through its Card at every firing (96 a day at every 15 minutes): the read's calls a day must allow that.");
    expect(setUp).toContain("Each firing reads it without the AI: a quiet one costs nothing, and the AI wakes only for work.");
    expect(setUp).toContain("Never a check on a read that doesn't list the work: with no such read, unscheduled.");
    const dial = COACH_SKILLS.find((k) => k.name === "the-dial")!.body.replace(/\s+/g, " ");
    expect(dial).toContain("A taf on a schedule names the read that lists its work: taf_dial's check.");
    expect(dial).toContain("Never a check on a read that doesn't list the work.");
    expect(dial).toContain("A taf with a check keeps one timetable: when taf_dial refuses a second one, offer the nearest it names.");
  });

  // A taf on a large list paged through it until its cost cap, its first check failed, and Coach called it set up:
  // size the job first, and never call a taf working after its check failed.
  it("sizes the job before drafting it, and never calls a taf working after its first check failed", () => {
    const body = COACH_SKILLS.find((k) => k.name === "set-up-a-taf")!.body.replace(/\s+/g, " ");
    expect(body).toContain("Size the job first: make the check's read once (its arguments, limit 1, the total if given); never hand over a check you haven't seen read.");
    expect(body).toContain("Over 50 waiting, or no total: bound the kickoff and instructions (the 20 most recent, and the total) and say how many wait.");
    expect(body).toContain("An answer with check: its first check couldn't read. Never call the taf ready or working: say what failed and fix it with taf_dial.");
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
    // In every language the apps speak, since the recipe's words come in the person's.
    for (const r of RECIPES) for (const [lang, w] of Object.entries(r.languages)) expect(entryOf(w.say), `${lang}/${r.id}`).toBe("recipe");
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
    expect(startSkills("free", { heldCall: true, halted: true })).toEqual(["cards-and-holds@3", "connect-an-app@16", "draft-the-card@11", "halting@1", "name-the-tools@7", "set-up-a-taf@8", "usage-and-plans@3"]);
    expect(startSkills("improve", { heldCall: true })).toEqual(["cards-and-holds@3", "connect-an-app@16", "draft-the-card@11", "improve-a-taf@1", "name-the-tools@7"]);
  });

  // Eval 42788659962: "Refunds hasn't done anything since this morning" and "Stop everything right now" were
  // answered without their skills, which the model was left to read. An account with a taf has them from the start.
  it("adds the holds and the halting to any start on an account with a taf of its own, and neither to a first taf", () => {
    expect(startSkills("free", { hasTaf: true })).toEqual(["cards-and-holds@3", "connect-an-app@16", "draft-the-card@11", "halting@1", "name-the-tools@7", "set-up-a-taf@8", "usage-and-plans@3"]);
    expect(startSkills("improve", { hasTaf: true })).toEqual(["cards-and-holds@3", "connect-an-app@16", "draft-the-card@11", "halting@1", "improve-a-taf@1", "name-the-tools@7"]);
    expect(startSkills("coach_lines", { hasTaf: true })).toEqual(["cards-and-holds@3", "halting@1", "the-tafs@2"]);
    expect(startSkills("free", { hasTaf: false })).toEqual(startSkills("free"));
  });

  it("loads a skill by the tool whose procedure it is, and nothing for a vendor's tool or a tool with none", () => {
    // planque's and KIFF's tools never reach tafs cloud (KIFF's gateway serves them): their skills come with the start.
    expect(toolSkills("kiff_draft")).toEqual([]);
    expect(toolSkills("catalog")).toEqual([]);
    expect(toolSkills("taf_dial")).toEqual(["the-dial@4"]);
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
