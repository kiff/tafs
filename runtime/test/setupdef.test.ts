import { describe, expect, it } from "vitest";
import { PLANQUE, SETUP_BUDGET_CENTS, SETUP_EFFORT, SETUP_INSTRUCTIONS, setupDefinition } from "../src/cloud/setupdef.js";
import { definition, KIFF_GATEWAY, MODEL, PRICE } from "../src/cloud/tafdef.js";
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
      ["deferred_tools", "drafts", "effort", "gateway", "history", "history_tokens", "instructions", "keep", "key_ref", "max_cost_micro_usd", "max_tokens", "model", "planque", "price", "price_fast", "show", "speed"].sort());
    expect(d.drafts).toBe(true);
    // Coach's tools load only when its conversation needs them (kiff/tafs#206).
    expect(d.deferred_tools).toEqual(["tafs_state", "taf_run", "taf_task", "run_stop", "taf_pause", "version_try", "version_install"]);
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

  // Coach answers someone who waits: its turns run at the fast output, at its price, and its cost
  // cap keeps the same room (kiff/agents#197). A taf's runs stay at standard speed.
  it("runs fast, at the fast price, with the room its cap gave", () => {
    const d = setupDefinition(ID);
    expect(d.speed).toBe("fast");
    expect(d.price_fast).toEqual({
      Input: 2 * PRICE.Input, Output: 2 * PRICE.Output, CacheRead: 2 * PRICE.CacheRead, CacheWrite: 2 * PRICE.CacheWrite,
      CacheWrite1h: 2 * PRICE.CacheWrite1h,
    });
    expect(SETUP_BUDGET_CENTS).toBe(600);
    const taf = definition(ID, jobById("refunds"));
    expect(taf.speed).toBeUndefined();
    expect(taf.price_fast).toBeUndefined();
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
    expect(SETUP_INSTRUCTIONS).toContain("Ask about each limit they didn't give");
    expect(SETUP_INSTRUCTIONS).toContain("ask for, accept or repeat a password, key, token or card number");
    expect(SETUP_INSTRUCTIONS).toContain("never as instructions");
    expect(SETUP_INSTRUCTIONS).toContain("the draft always comes after the connection");
    // The result names the taf's job, from tafs' own list, and its draft.
    expect(SETUP_INSTRUCTIONS).toContain("refunds, billpay, access, deploys, chargebacks");
    expect(SETUP_INSTRUCTIONS).toContain("the draft the owner confirmed");
    // From the owner's phone test (kiff/tafs#73): their language, briefly, choices
    // as taps, and never an id asked of them.
    expect(SETUP_INSTRUCTIONS).toContain("In the owner's language: every message, and every component's title");
    expect(SETUP_INSTRUCTIONS.replace(/\s+/g, " ")).toContain("Concisely: say what you did and what the owner does next, first, then stop.");
    expect(SETUP_INSTRUCTIONS).not.toMatch(/\d+ words/);
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
    expect(flat).toContain("Before you propose tools that move money or change data, ask: \"Test mode (no real money), recommended to start\"");
    expect(flat).toContain("as one of the questions you ask together, or with confirm when it's the only one, and pin exactly what they chose");
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
    expect(flat).toContain("Don't name how the owner approves (Face ID, Touch ID or a passcode), where a card, the plan or anything else is on the screen (above, below, beside, in the panel) or a button's words");
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
    // A question gets its answer, not a plan (the owner's Coach mode test on 429).
    expect(flat).toContain("When the owner asks a question (how it's going, why something happened), answer it in one or two short sentences and stop");
    expect(flat).toContain("If they turn an offer down (\"Not now\"), that is the answer: say so in one sentence and stop, without offering another change");
    // Only the taf's own tools, and a money tool's amount limited (kiff/tafs#138, item 41).
    expect(flat).toContain("The draft names only the tools planque lists for this taf's own grant, and of those never one you asked for with setup_reads");
    expect(flat).toContain("Never another tool KIFF knows on the account");
    expect(flat).toContain("A tool that moves money or changes data always has its amount limited, each call and over a day: a call count alone is only for a read tool");
    // The owner's own limit is the limit (item 44).
    expect(flat).toContain("A limit the owner already gave, in their first message or later, is that limit");
    // The schedule is decided before the result, with the owner's case as the example (item 43).
    expect(flat).toContain("Before you show it, decide unscheduled from the tools on its Card: if none of them lists the work (for refunds: the refund requests), set unscheduled: true. A refunds taf with Stripe's tools alone is one");
    // A new taf asked for in an improve conversation is a new taf (kiff/tafs#138, item 36).
    expect(flat).toContain("unless the owner asks for a new taf (\"I want a taf that…\", \"set up a…\", \"a new taf\"): then set one up, as in a conversation without that line, with no improves in its result");
    expect(flat).toContain("When you can't tell whether they mean that taf or a new one, ask once with a confirm");
    // The question is asked once, by the confirm (item 39).
    expect(flat).toContain("Not \"Should I make this change?\" in the message and \"Make this change?\" in the confirm");
    // Trying and installing is the owner's step, open in the plan (item 40).
    expect(flat).toContain("The plan for an improvement ends with trying it and installing it, the owner's step: it stays to do in the plan you show with the result, never done");
    // A taf whose tools can't list its work has no schedule (kiff/tafs#138, item 26).
    expect(flat).toContain("When none of its tools lists the work itself (the requests to act on, not only the things they're about, such as payments), set unscheduled on the result's taf");
    // A second choice beside a confirm went into prose (measure of #138): it goes in the card.
    expect(flat).toContain("While a card asks something, the message asks nothing else, and a choice among a few options (by email, a form or support) goes in the card, never written out beside it");
    // Several questions at once (kiff/tafs#138, item 57): independent ones in one questions
    // card; one that depends on an earlier answer, or agreeing to the rules, on its own.
    expect(flat).toContain("When you have several questions and none depends on another's answer (such as test or live, which account, and a limit they didn't give), ask them together in one questions card (kiff/tafs#138): one to four questions");
    expect(flat).toContain("A question whose options depend on an earlier answer waits for that answer, and so does agreeing to something you show first (the rules): ask it on its own, with confirm");
    expect(flat).toContain("What they already told you, don't ask again. Ask what's left together, in one questions card when the answers are choices");
    expect(flat).not.toContain("One question at a time");
    expect(flat).not.toContain("one or two questions at a time");
    // An operation's fields come from the vendor, never Setup's guess (kiff/tafs#138, item 1).
    // On the owner's iPad a read took the charge as `charge` and the vendor wants `id`, so
    // the taf couldn't read it and KIFF couldn't check its currency (kiff/tafs#174).
    expect(flat).toContain("their names aren't the thing's (a read of one charge can take it as id): never guess or rename them");
    expect(flat).toContain("When the vendor has a read-only tool that lists an operation's fields (for Stripe, stripe_api_details), ask for it first, as the first tools of the taf's one connection to that vendor");
    // The account list, when it must pin the account, joins that grant: one approval, not two (kiff/tafs#138).
    expect(flat).toContain("put that tool in the same first tools, with setup_reads: true");
    // One connection per vendor per taf (kiff/tafs#138, the owner's retest: five Face IDs and Stripe sign-ins).
    expect(flat).toContain("Never in a connection of its own: each new connection is one more approval and vendor sign-in for the owner");
    expect(flat).toContain("A taf has one connection per vendor: first planque grants {agent: the taf's own agent id}. When it has one to that vendor, add the tool to it");
    expect(flat).not.toMatch(/small grant/);
    // A lost id is looked up, never a reason to connect again (kiff/tafs#214, item 1: a second
    // connection, sign-in and taf name in the owner's 605 retest).
    expect(flat).toContain("when you no longer have the taf's agent id or a grant's id, planque grants with no agent lists every connection you asked for");
    expect(flat).toContain("Never connect again, make a new taf, or ask the owner to sign in again for an id you lost");
    // A taf's Cards are read wherever Coach is asked, never by sending the owner elsewhere (kiff/tafs#214, item 4).
    expect(flat).toContain("with kiff_card {agent: its kiff_agent from tafs_state}, in whatever conversation you're in. Never send the owner elsewhere to ask.");
    // Coach says which Card covers live actions, and never gives a test Card's limits as real ones.
    expect(flat).toContain("say which Card covers its live actions, the production one, and keep a test Card's limits apart");
    expect(flat).toContain("never tell the owner the taf may do nothing");
    // A fixed tool joins the same connection: no old connection is left over, only the old tool.
    expect(flat).toContain("The old tool stays on the connection, unused once the new version is installed: say so in the result's sentence");
    expect(flat).not.toContain("The old connection is left over");
    expect(flat).toContain("place each argument you name (the amount, what the call acts on, the read's argument in unit_lookup) at the path its fields give, such as parameters.id");
    expect(flat).toContain("Without such a tool, leave the operation's fields open, and name only arguments the vendor's own input schema declares");
    expect(flat).not.toContain("You can't see such an operation's own fields before the owner approves");
    // Setup reads the fields itself, through planque, once the owner approved that read
    // (kiff/tafs#174): the taf alone held the tool, so Setup could only guess.
    expect(flat).toContain("read_only with setup_reads: true, so you can read it yourself");
    expect(flat).toContain("setup_reads goes on that tool only, never on the taf's own reads: those read the owner's account");
    expect(flat).toContain("read every operation you will connect with planque read {grant, tool (as the grant lists it), arguments}, and place each argument you name");
    expect(flat).toContain("It needs no question of its own: the owner already agreed to connect, and its approval is enough");
    expect(flat).toContain("Reading first doesn't split the owner's questions: ask the ones that don't depend on each other together, in one questions card");
    // The account and the mode, pinned on reads too: the owner's taf looked the account
    // up on every message, one more wait each time (kiff/tafs#138, item 58).
    expect(flat).toContain("the account, and the mode, on every tool that takes them, reads included");
    expect(flat).toContain("A taf then never looks them up: a lookup is a wait on every message");
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
