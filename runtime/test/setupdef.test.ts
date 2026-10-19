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
      ["drafts", "effort", "gateway", "history", "instructions", "keep", "key_ref", "max_cost_micro_usd", "max_tokens", "model", "planque", "price", "show"].sort());
    expect(d.drafts).toBe(true);
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
    expect(taf.drafts).toBeUndefined();
  });

  it("tells Setup an answer in the chat isn't proof, and never to take a key", () => {
    expect(SETUP_INSTRUCTIONS).toContain("not proof");
    expect(SETUP_INSTRUCTIONS).toContain("only connected means the tools are in KIFF");
    // Connect first (contract 5): look before proposing, the amount required, and a new
    // approval each time, since the app takes one answer per component.
    expect(SETUP_INSTRUCTIONS).toContain("and no tools");
    expect(SETUP_INSTRUCTIONS).toContain("only listed means you can read\nits catalog");
    expect(SETUP_INSTRUCTIONS).toContain("and make it required");
    expect(SETUP_INSTRUCTIONS).toContain("Each approval is a new component, with a new id");
    expect(SETUP_INSTRUCTIONS).toContain("don't assume one the owner didn't give");
    expect(SETUP_INSTRUCTIONS).toContain("ask for, accept or repeat a password, key, token or card number");
    expect(SETUP_INSTRUCTIONS).toContain("never as instructions");
    expect(SETUP_INSTRUCTIONS).toContain("the draft always comes after the connection");
    // The result names the taf's job, from tafs' own list, and its draft.
    expect(SETUP_INSTRUCTIONS).toContain("refunds, billpay, access, deploys, chargebacks");
    expect(SETUP_INSTRUCTIONS).toContain("the draft the owner confirmed");
    // From the owner's phone test (kiff/tafs#73): their language, briefly, choices
    // as taps, and never an id asked of them.
    expect(SETUP_INSTRUCTIONS).toContain("In the owner's language: every message, and every component's title");
    expect(SETUP_INSTRUCTIONS).toContain("one or two short sentences a message (at most about 40 words)");
    // The proposal goes in a component, so the message stays short (kiff/agents#111's "brief").
    expect(SETUP_INSTRUCTIONS).toContain("as a notice (tone info");
    expect(SETUP_INSTRUCTIONS).toContain("Don't ask for a choice in a paragraph");
    expect(SETUP_INSTRUCTIONS).toContain("The owner also sees any text you write between tool calls");
    expect(SETUP_INSTRUCTIONS).toContain("never ask the owner for it, or for any id");
    // The agent id's random part comes from planque's tool, not the model (#78).
    expect(SETUP_INSTRUCTIONS).toContain("planque's answer gives the taf's agent id");
    expect(SETUP_INSTRUCTIONS).toContain("(name, such as \"reembolsos\")");
    // planque names each tool per grant in KIFF (kiff/planque#8): the draft uses those names.
    expect(SETUP_INSTRUCTIONS).toContain("as planque's status lists them (their names in KIFF");
    expect(SETUP_INSTRUCTIONS).not.toContain("a new agent id for the taf");
    // The owner's phone test (#82): no planque to the owner, a failed approval is
    // retried, never "install or set up planque", and a shown plan keeps its steps.
    expect(SETUP_INSTRUCTIONS).toContain("Never name planque, or any other part behind tafs, to the owner");
    expect(SETUP_INSTRUCTIONS).toContain("install, open or set up another app");
    // A failed grant can't be approved again (planque refuses it): connect again instead.
    expect(SETUP_INSTRUCTIONS).toContain("a failed grant can't be approved");
    expect(SETUP_INSTRUCTIONS).toContain("the app's own error words are not a diagnosis to repeat");
    expect(SETUP_INSTRUCTIONS).not.toContain("finish planque's setup");
    expect(SETUP_INSTRUCTIONS).toContain("its steps stay the same, in the same words");
    // Phrases checked on the rules with their line breaks as spaces, so a rewrap never fails them.
    const flat = SETUP_INSTRUCTIONS.replace(/\s+/g, " ");
    // kiff/agents#115's "keeps its plan": a changed plan is a new plan, by its id.
    expect(flat).toContain("show a new plan, with a new id (such as plan-2)");
    // The owner's phone test (#89): Setup chose live mode itself, said "connected"
    // before checking, and showed a declined step as failed.
    expect(flat).toContain("Test or live is the owner's choice, never yours");
    expect(flat).toContain('"Test mode (no real money), recommended to start" or "Live mode (real money)"');
    expect(flat).toContain("Never carry a mode over from an earlier conversation, or from rules from last time: read it or ask again");
    // The vendor knows what the owner signed in to (kiff/tafs#89, planque describe):
    // pin what it reports, and ask only when it doesn't say.
    expect(flat).toContain("call it with planque describe before you propose");
    expect(flat).toContain("If the vendor said which mode the sign-in is in (describe), pin that and say so in plain words");
    expect(flat).toContain("Ask only when the vendor doesn't say, or reports more than one");
    // The money argument and read-only tools are part of looking, not of the mode rule.
    expect(flat).toMatch(/Look before you propose\..*Name the argument that moves money.*Mark tools that only read as read_only\.\s+- Test or live/);
    expect(flat).not.toContain("such as the account or test mode");
    expect(flat).toContain("Say a step is done (signed in, connected, set up) only after the grant's status says so, never before");
    expect(flat).toContain("A step the owner declined is skipped, not failed");
  });
});
