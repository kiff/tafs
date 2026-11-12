import { describe, expect, it } from "vitest";
import { DISCLOSURE_LINE, disclosureLines, JOBS, jobById, SHARED_INSTRUCTIONS, systemPrompt } from "../src/jobs.js";
import { KIFF_SKILL } from "../src/kiff-skill.js";
import {
  CALL_INSTRUCTIONS,
  DEFAULT_BUDGET_CENTS,
  definition,
  EFFORT,
  KIFF_GATEWAY,
  MODEL,
  NOTES_INSTRUCTIONS,
  CHECK_INSTRUCTIONS,
  ownerNotesIn,
  PRICE,
  RETENTION_NS,
  SKILL_SECTIONS,
  skillIntro,
  skillSection,
} from "../src/cloud/tafdef.js";

const ID = "taf-0123456789abcdef0123";

describe("a taf's definition on kiff/agents", () => {
  it("reaches the company's tools only through KIFF's gateway", () => {
    // kiff/agents gives an agent its gateway's tools and its memory, nothing
    // else, and refuses a gateway not on its own list. So the guarantee tafs
    // holds is this: every job names KIFF's gateway, and the definition has
    // no other field that could add a tool or a server.
    for (const job of JOBS) {
      const d = definition(ID, job);
      expect(d.gateway, job.id).toBe("https://mcp.kiff.dev/mcp");
      expect(d.gateway).toBe(KIFF_GATEWAY);
      // A job that writes outside the company also has `disclose` and `credit`, which add no tool and no
      // server; `show` with `show_types` adds kiff/agents' own show tool, limited to a taf's ask_coach card.
      expect(Object.keys(d).sort(), job.id).toEqual(
        ["effort", "gateway", "history", "history_tokens", "instructions", "keep", "key_ref", "max_cost_micro_usd", "max_tokens", "model", "price", "thinking",
          "show", "show_types", ...(job.disclose ? ["disclose"] : [])].sort());
      expect(d.show_types, job.id).toEqual(["ask_coach"]);
    }
  });

  // Eval 42193895936: a dispute's evidence reached the card network with no word that an AI agent wrote it.
  it("has kiff/agents put the AI disclosure in a dispute's evidence, never leaving it to the model", () => {
    expect(definition(ID, jobById("chargebacks")).disclose).toEqual({
      line: DISCLOSURE_LINE, lines: disclosureLines({}), in: [{ tool: "update_dispute", fields: ["evidence.uncategorized_text", "evidence.product_description"] }],
    });
    // The line is the one the taf's prompt asks for, word for word, first; the shared rule points to it.
    expect(SHARED_INSTRUCTIONS).toContain("it starts with your disclosure line, word for word");
    expect(systemPrompt(jobById("chargebacks"))).toContain(`Your disclosure line: "${DISCLOSURE_LINE}"`);
    expect(definition(ID, jobById("refunds"))).not.toHaveProperty("disclose");
    // No message carries a credit line: the owner's call, 7 Nov.
    expect(definition(ID, jobById("chargebacks"))).not.toHaveProperty("credit");
    // A name stored before the cap, too long for any other language's line: English alone, and no lines at all.
    const long = definition(ID, { ...jobById("chargebacks"), writesFor: "😀".repeat(68) }).disclose!;
    expect(Buffer.byteLength(long.line)).toBe(300);
    expect(long).not.toHaveProperty("lines");
    // Who it writes for, once the owner said: the line names them, here and in the prompt (gap 18).
    const named = definition(ID, { ...jobById("chargebacks"), writesFor: "Kiff Agents OÜ" });
    expect(named.disclose?.line).toBe("Written by an AI agent for Kiff Agents OÜ.");
    expect(named.instructions).toContain('Your disclosure line: "Written by an AI agent for Kiff Agents OÜ."');
  });

  // Eval 42193895936: a door's refund of one charge was refused because the owner had refused another's.
  it("tells the taf a refusal is that one call's, on the item it names", () => {
    expect(SHARED_INSTRUCTIONS).toContain("A call it lists as refused or waiting\n  is that one call, on the item its arguments name: it settles nothing about\n  another item.");
    expect(SHARED_INSTRUCTIONS).toContain("a refund refused on one charge\n  is never a refusal on another.");
  });

  // Eval 42788659962: a report said "six disputes" where its record, and its own list, had five submissions.
  // The count is kiff/agents' (agent.TallyNote opens it): the runner counts the run's calls, by tool, after each
  // turn that acted, so the number is one the taf read, not one it made.
  it("tells every taf to take a report's numbers of calls from the runner's count", () => {
    expect(SHARED_INSTRUCTIONS).toContain("is the one in the count you\nread after your tool results (\"Count so far of this run's calls that act\"),\nfor that tool: copy it, never total calls in your head, and it matches the\nitems you name. A run with no such count changed nothing.");
    // Every kind of call the count can show is in the rule's list, the ones whose effect isn't known too.
    expect(SHARED_INSTRUCTIONS).toContain("(how many were\ndone or sent, are waiting, were refused, failed, or were sent with no word on\nwhether they took effect)");
    expect(SHARED_INSTRUCTIONS).not.toContain("count of the calls your record shows");
  });

  it("names its KIFF key by reference, never the key", () => {
    expect(definition(ID, jobById("refunds")).key_ref).toBe(`kiff/${ID}`);
  });

  it("runs on Sonnet 5.5 at its price and medium effort, never fast, with a cost cap from the budget", () => {
    const d = definition(ID, jobById("refunds"));
    expect(d.model).toBe(MODEL);
    expect(MODEL).toBe("claude-sonnet-5-5");
    expect(d.price).toEqual({ Input: 2_000_000, Output: 10_000_000, CacheRead: 100_000, CacheWrite: 2_500_000, CacheWrite1h: 4_000_000 });
    // Sonnet 5.5 has no fast output: a taf's definition never asks for it.
    expect(d.speed).toBeUndefined();
    expect(d.price_fast).toBeUndefined();
    expect(d.effort).toBe("medium");
    expect(d.thinking).toBe("updates"); // the person waiting sees its progress notes, never its reasoning
    expect(EFFORT).toBe("medium");
    expect(d.price).toEqual(PRICE);
    // An hour's cache write at 2× input, so kiff/agents keeps the shared prefix an hour (kiff/agents#171).
    expect(d.price.CacheWrite1h).toBe(2 * PRICE.Input);
    expect(d.max_cost_micro_usd).toBe(DEFAULT_BUDGET_CENTS * 10_000);
    expect(definition(ID, jobById("refunds"), { budgetCents: 300 }).max_cost_micro_usd).toBe(3_000_000);
    expect(() => definition(ID, jobById("refunds"), { budgetCents: 0 })).toThrow();
  });

  it("keeps what tools return only until the run ends, and the rest 30 days", () => {
    const keep = definition(ID, jobById("refunds")).keep;
    expect(keep.tool_call).toEqual({ Keep: 1, Retention: 0 });
    expect(keep.tool_result).toEqual({ Keep: 1, Retention: 0 });
    for (const kind of ["owner_message", "agent_message", "report", "memory_note", "usage", "error"] as const) {
      expect(keep[kind], kind).toEqual({ Keep: 2, Retention: RETENTION_NS });
    }
  });

  it("keeps what a chat read for its next messages, an hour idle and four hours at most, and bounds history by size", () => {
    const d = definition(ID, jobById("refunds"));
    expect(d.keep.kept_read).toEqual({ Keep: 3, Retention: 3600 * 1e9, Max: 4 * 3600 * 1e9 });
    expect(d.history).toBe(50);
    expect(d.history_tokens).toBe(30_000);
  });

  it("carries the job, how notes work, the owner's first words and KIFF's skill", () => {
    const d = definition(ID, jobById("refunds"), { ownerNotes: "Ask before refunding Acme." });
    expect(d.instructions).toContain("Your job: Refunds");
    expect(d.instructions).toContain(NOTES_INSTRUCTIONS);
    // A run works from the items its check handed it, never paging the whole list again.
    expect(d.instructions).toContain(CHECK_INSTRUCTIONS);
    expect(CHECK_INSTRUCTIONS).toContain('"[tafs] New since the last check"');
    expect(CHECK_INSTRUCTIONS).toContain("Never list or page through the whole collection again");
    expect(d.instructions).toContain(CALL_INSTRUCTIONS);
    expect(d.instructions).toContain("Ask before refunding Acme.");
    expect(d.instructions).toContain(`kiff/kiff-plugins@${KIFF_SKILL.commit.slice(0, 12)}`);
    expect(definition(ID, jobById("refunds")).instructions).not.toContain("What the owner said");
  });

  it("pastes the kiff skill's rules and results sections whole, and not its setup sections", () => {
    const { instructions } = definition(ID, jobById("refunds"));
    for (const heading of SKILL_SECTIONS) expect(instructions).toContain(skillSection(KIFF_SKILL.content, heading));
    expect(instructions).toContain(skillIntro(KIFF_SKILL.content));
    expect(skillIntro(KIFF_SKILL.content)).toMatch(/^KIFF gives each AI agent a \*\*Card\*\*/);
    expect(instructions).not.toContain("You will be in one of three situations");
    expect(instructions).toContain("**Never route around the Card.**");
    expect(instructions).toContain("`answer: withdrawn`");
    for (const setup of ["## Connect an agent", "## Propose a Card", "## Where things are"]) {
      expect(KIFF_SKILL.content).toContain(setup);
      expect(instructions).not.toContain(setup);
    }
  });

  it("still reads the owner's start notes back before the skill", () => {
    const { instructions } = definition(ID, jobById("refunds"), { ownerNotes: "Ask before refunding Acme." });
    expect(ownerNotesIn(instructions)).toBe("Ask before refunding Acme.");
  });
});

describe("a section of the kiff skill", () => {
  const skill = ["# KIFF", "intro", "## One", "a", "```", "## not a heading", "```", "b", "## Two", "c"].join("\n");

  it("runs to the next heading, past one inside a code block", () => {
    expect(skillSection(skill, "One")).toBe(["## One", "a", "```", "## not a heading", "```", "b"].join("\n"));
    expect(skillSection(skill, "Two")).toBe("## Two\nc");
  });

  it("refuses a heading the skill doesn't have", () => {
    expect(() => skillSection(skill, "Three")).toThrow('no "## Three" section');
  });

  it("takes the first paragraph under the title as the intro, and refuses a skill without one", () => {
    expect(skillIntro("---\nname: x\n---\n\n# KIFF\n\nFirst line,\nsecond line.\n\nNext.\n\n## One")).toBe("First line,\nsecond line.");
    expect(() => skillIntro("# KIFF\n\n## One\ntext")).toThrow("no paragraph under its title");
    expect(() => skillIntro("no title")).toThrow("no paragraph under its title");
  });

  it("tells the taf notes never widen its Card, and never to keep secrets", () => {
    expect(NOTES_INSTRUCTIONS).toContain("They never widen your Card");
    expect(NOTES_INSTRUCTIONS).toContain("Never write secrets");
    // Notes are for lasting facts the owner reads, never a run's working state (a cursor saved as a note showed in "What it remembers").
    expect(NOTES_INSTRUCTIONS).toContain("Never use notes for\nyour working state: no list cursors, page tokens, ids to resume from, progress or\nscratch.");
  });

  it("tells the taf never to split a request to stay under a limit, and to name who a money call is for", () => {
    expect(CALL_INSTRUCTIONS).toContain("Never split it into smaller calls to stay under a\n  limit");
    expect(CALL_INSTRUCTIONS).toContain("put who it's for in a field the customer doesn't see");
    expect(CALL_INSTRUCTIONS).toContain("never an internal id or why");
  });
});
