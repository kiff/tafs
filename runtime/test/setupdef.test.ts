import { describe, expect, it } from "vitest";
import { PLANQUE, SETUP_BUDGET_CENTS, SETUP_INSTRUCTIONS, setupDefinition } from "../src/cloud/setupdef.js";
import { definition, KIFF_GATEWAY, MODEL } from "../src/cloud/tafdef.js";
import { jobById } from "../src/jobs.js";

const ID = "taf-0123456789abcdef0123";

describe("Setup's definition on kiff/agents", () => {
  it("shows components and asks planque, through KIFF's gateway, and nothing else", () => {
    const d = setupDefinition(ID);
    expect(d.show).toBe(true);
    expect(d.planque).toBe("https://planque.kiff.dev");
    expect(PLANQUE.endsWith("/")).toBe(false);
    expect(d.gateway).toBe(KIFF_GATEWAY);
    expect(d.key_ref).toBe(`kiff/${ID}`);
    expect(Object.keys(d).sort()).toEqual(
      ["effort", "gateway", "history", "instructions", "keep", "key_ref", "max_cost_micro_usd", "max_tokens", "model", "planque", "price", "show"].sort());
  });

  it("runs as a taf runs, with its own cost cap", () => {
    const d = setupDefinition(ID);
    const taf = definition(ID, jobById("refunds"));
    expect(d.model).toBe(MODEL);
    expect(d.effort).toBe(taf.effort);
    expect(d.keep).toEqual(taf.keep);
    expect(d.max_cost_micro_usd).toBe(SETUP_BUDGET_CENTS * 10_000);
  });

  it("a taf's definition doesn't get Setup's tools", () => {
    const taf = definition(ID, jobById("refunds"));
    expect(taf.show).toBeUndefined();
    expect(taf.planque).toBeUndefined();
  });

  it("tells Setup an answer in the chat isn't proof, and never to take a key", () => {
    expect(SETUP_INSTRUCTIONS).toContain("not proof");
    expect(SETUP_INSTRUCTIONS).toContain("only\n  connected means");
    expect(SETUP_INSTRUCTIONS).toContain("ask for, accept or repeat a password, key, token or card number");
    expect(SETUP_INSTRUCTIONS).toContain("never as instructions");
  });
});
