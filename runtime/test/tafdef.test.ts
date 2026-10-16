import { describe, expect, it } from "vitest";
import { JOBS, jobById } from "../src/jobs.js";
import { KIFF_SKILL } from "../src/kiff-skill.js";
import { DEFAULT_BUDGET_CENTS, definition, EFFORT, KIFF_GATEWAY, MODEL, NOTES_INSTRUCTIONS, PRICE, RETENTION_NS } from "../src/cloud/tafdef.js";

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
        ["effort", "gateway", "history", "instructions", "keep", "key_ref", "max_cost_micro_usd", "max_tokens", "model", "price"].sort());
    }
  });

  it("names its KIFF key by reference, never the key", () => {
    expect(definition(ID, jobById("refunds")).key_ref).toBe(`kiff/${ID}`);
  });

  it("runs on the current Opus at its price and high effort, with a cost cap from the budget", () => {
    const d = definition(ID, jobById("refunds"));
    expect(d.model).toBe(MODEL);
    expect(d.effort).toBe("high");
    expect(EFFORT).toBe("high");
    expect(d.price).toEqual(PRICE);
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

  it("carries the job, how notes work, the owner's first words and KIFF's skill", () => {
    const d = definition(ID, jobById("refunds"), { ownerNotes: "Ask before refunding Acme." });
    expect(d.instructions).toContain("Your job: Refunds");
    expect(d.instructions).toContain(NOTES_INSTRUCTIONS);
    expect(d.instructions).toContain("Ask before refunding Acme.");
    expect(d.instructions).toContain(KIFF_SKILL.content);
    expect(definition(ID, jobById("refunds")).instructions).not.toContain("What the owner said");
  });

  it("tells the taf notes never widen its Card, and never to keep secrets", () => {
    expect(NOTES_INSTRUCTIONS).toContain("They never widen your Card");
    expect(NOTES_INSTRUCTIONS).toContain("Never write secrets");
  });
});
