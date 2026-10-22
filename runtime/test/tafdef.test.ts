import { describe, expect, it } from "vitest";
import { JOBS, jobById } from "../src/jobs.js";
import { KIFF_SKILL } from "../src/kiff-skill.js";
import {
  DEFAULT_BUDGET_CENTS,
  definition,
  EFFORT,
  KIFF_GATEWAY,
  MODEL,
  NOTES_INSTRUCTIONS,
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
      expect(Object.keys(d).sort(), job.id).toEqual(
        ["effort", "gateway", "history", "history_tokens", "instructions", "keep", "key_ref", "max_cost_micro_usd", "max_tokens", "model", "price"].sort());
    }
  });

  it("names its KIFF key by reference, never the key", () => {
    expect(definition(ID, jobById("refunds")).key_ref).toBe(`kiff/${ID}`);
  });

  it("runs on the current Opus at its price and high effort, with a cost cap from the budget", () => {
    const d = definition(ID, jobById("refunds"));
    expect(d.model).toBe(MODEL);
    expect(d.effort).toBe("medium");
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
  });
});
