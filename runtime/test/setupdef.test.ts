import { describe, expect, it } from "vitest";
import { PLANQUE, SETUP_BUDGET_CENTS, SETUP_EFFORT, SETUP_INSTRUCTIONS, setupDefinition } from "../src/cloud/setupdef.js";
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

  it("runs as a taf runs, with its own cost cap and effort", () => {
    const d = setupDefinition(ID);
    const taf = definition(ID, jobById("refunds"));
    expect(d.model).toBe(MODEL);
    // Setup plans and judges more than a taf's steps (kiff/tafs#102): it keeps high.
    expect(d.effort).toBe(SETUP_EFFORT);
    expect(SETUP_EFFORT).toBe("high");
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
    // A generic tool's operation is pinned, one connected tool per operation (kiff/tafs#115).
    expect(SETUP_INSTRUCTIONS).toContain("and the operation that makes a tool the job's");
    expect(SETUP_INSTRUCTIONS).toContain("Never leave the operation to the taf");
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
    expect(flat).toContain("Never carry a mode over from an earlier conversation, or from rules from last time: ask again");
    // Nothing is read from the owner's account before they approve its tools
    // (kiff/planque#23, describe removed): Setup asks the mode, always.
    expect(flat).not.toContain("describe");
    expect(flat).toContain("so don't try to read what the sign-in covers first: ask");
    expect(flat).toContain("Before you propose tools that move money or change data, ask with confirm");
    // The money argument and read-only tools are part of looking, not of the mode rule.
    expect(flat).toMatch(/Look before you propose\..*Name the argument that moves money.*Mark tools that only read as read_only: reading in the owner's account is open, with no limits\.\s+- Test or live/);
    expect(flat).not.toContain("such as the account or test mode");
    expect(flat).toContain("Say a step is done (signed in, connected, set up) only after the grant's status says so, never before");
    expect(flat).toContain("A step the owner declined is skipped, not failed");
    // The owner's iPad test (#95): no device's biometry or screen position, a taf that
    // can find its work, the plan finished, and a day that is the account's day.
    // Face ID is named only to say not to name it.
    expect(SETUP_INSTRUCTIONS.match(/Face ID/g)?.length).toBe(1);
    expect(flat).not.toContain("approves with Face ID");
    expect(flat).toContain("Don't name how the owner approves (Face ID, Touch ID or a passcode), where a card is on the screen (above, below) or a button's words");
    expect(flat).toContain("where the work comes from (for refunds: where customers ask for them)");
    expect(flat).toContain("A taf needs a way to find its work, not only a way to act");
    // The batch measurement: asked for Gmail's MCP server address and looped.
    expect(flat).toContain("Never ask the owner for a server address, a URL or anything technical to connect");
    expect(flat).toContain("or from the owner, who can tell it in its chat");
    expect(flat).toContain("Never call a taf ready when it can only act");
    // The whole job, from the vendor's catalog, with room to act (kiff/tafs#107).
    expect(flat).toContain("the tools the whole job needs: to find the work (list or search), to check one item (read it), and to act (the write)");
    expect(flat).toContain("don't narrow a tool's arguments to stand in for a limit");
    expect(flat).toContain("reading in the owner's account is open, with no limits");
    expect(flat).toContain("check that its tools cover the whole job: find the work, check an item, act on it, and say what it did");
    expect(flat).not.toContain("the fewest tools");
    // Improving a taf: from its context, the change made as its next version (kiff/tafs#107).
    expect(flat).toContain('A conversation that starts with "[improve taf <id>]" improves that taf; it doesn\'t set up a new one');
    expect(flat).toContain("Then make it as the taf's next version, never as a new taf");
    expect(flat).toContain("with each Card it changes written again under the same id, as KIFF holds it, with only the agreed change");
    // The revision is confirmed at the switch, never in the chat (finding 1 on #107).
    expect(flat).toContain("Don't show an approval for this draft: the owner confirms it when they switch to the new version");
    expect(flat).toContain("planque request with agent set to the taf's own agent id (never name, which makes a new agent)");
    expect(flat).toContain("improves: the taf's id from the first line");
    expect(flat).toContain("Never say it's live: the owner decides");
    expect(flat).not.toContain("A taf can't move to a new version yet");
    // A read tool left off the Card is refused by KIFF: every tool goes on it (review on #108).
    expect(flat).toContain("Every tool the taf gets goes on its Card, the read tools too: KIFF refuses any call no Card covers");
    // An operation's fields come from the vendor, never Setup's guess (kiff/tafs#138, item 1).
    expect(flat).toContain("You can't see such an operation's own fields before the owner approves, so never guess or rename them");
    expect(flat).toContain("connect the vendor's read-only tool that lists an operation's fields (for Stripe, stripe_api_details)");
    expect(flat).toContain("A read tool's line has no amount limit, only a call count generous enough never to get in the way, and you don't ask the owner to set it");
    expect(flat).toContain("Show plan again with every step done or skipped");
    // The nightly of 2026-10-19 (62 words in one message): the day and the mode are lines
    // of the rules notice, not repeated in every message.
    expect(flat).toContain("say so as a line of the rules notice (\"a day ends at midnight in your account's time zone\"), not in a message: messages stay short");
    expect(flat).toContain("as a line of the rules notice (\"test mode, no real money\" or \"live mode, real money\"), not again in every message");
    expect(flat).not.toContain("in every message about those tools");
    expect(flat).not.toContain("KIFF's settings");
  });
});
