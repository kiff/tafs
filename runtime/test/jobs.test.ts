import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { JOBS, jobById, systemPrompt } from "../src/jobs.js";
import { KIFF_SKILL } from "../src/kiff-skill.js";

describe("jobs", () => {
  it("has the five jobs, each with a valid default schedule", () => {
    expect(JOBS.map((j) => j.id)).toEqual(["refunds", "billpay", "access", "deploys", "chargebacks"]);
    for (const j of JOBS) expect(j.cron.split(" ")).toHaveLength(5);
  });

  it("tells every taf how to work under a Card", () => {
    for (const j of JOBS) {
      const prompt = systemPrompt(j);
      expect(prompt).toContain("kiff_pending");
      expect(prompt).toContain("kiff_operation_id");
      expect(prompt).toContain("never repeat the call");
      expect(prompt).toContain("kiff skill");
      // On kiff/agents a held call pauses the run until the owner answers.
      expect(prompt).toContain("your run pauses until the owner answers");
      // A refused call follows the kiff skill (its retry, the owner's named step).
      expect(prompt).toContain("a refused call ends that\n  item as the kiff skill says");
      // The owner's refusal is the reason; the taf adds none (kiff/tafs#138, item 33).
      expect(prompt.replace(/\s+/g, " ")).toContain("When the owner refused it, say that they refused it and nothing was sent, and give no reason they didn't give.");
      // The owner reads it in tafs: no link, no "in the tafs app" (item 25).
      expect(prompt).not.toContain("tafs app");
      expect(prompt).not.toContain("Never retry a refused");
      // A hold pauses the run where it is: what the Card allows goes first, and
      // the report names each item and what became of it (kiff/agents#78).
      expect(prompt).toContain("leave any write that may need the owner's approval");
      // Each turn is a wait: the owner's Stripe taf took 5 turns in a row per message,
      // 4-5 s each (kiff/tafs#138, item 58). Independent calls go in one turn.
      expect(prompt.replace(/\s+/g, " ")).toContain("make reads that don't need each other's answers together, in one turn: kiff_pending with your first reads, and every read of one item at once.");
      // A write sent beside another on the same item outran KIFF's refusal of it
      // (Chargebacks, 21 Oct 15:52: close_dispute refused, update_dispute sent with it).
      expect(prompt.replace(/\s+/g, " ")).toContain("Writes are not batched: make one write on an item, read KIFF's answer, then decide the next");
      // A list says what's needed of each item: reading one again costs a turn (kiff/tafs#206).
      expect(prompt.replace(/\s+/g, " ")).toContain("Never look up a value a tool's description says KIFF always sends, or read an item again for what a list you just read already says of it");
      // KIFF decides the Card, not the taf: it decided a hold itself, so the call
      // never reached the owner (kiff/tafs#206).
      expect(prompt.replace(/\s+/g, " ")).toContain("KIFF decides what your Card allows, not you.");
      // A table when the owner asks for one: the app draws them (kiff/tafs#236, item 29).
      expect(prompt.replace(/\s+/g, " ")).toContain("a table only when the owner asks for one");
      // A deadline as time left, never in UTC (item 26).
      expect(prompt.replace(/\s+/g, " ")).toContain("no clock times or time zones, a deadline included");
      expect(prompt).toContain("Name each item by its reference and what\nbecame of it");
      expect(prompt).toContain(j.instructions);
    }
  });

  it("has Refunds read the payment before asking for a refund (the 859 and 870 tests)", () => {
    // T-5005 and T-5003: the look-up and the refund went in one turn, then the taf said it
    // had checked the payment. kiff/agents refuses an act beside a read now; this line
    // keeps the taf from paying a turn for it.
    expect(jobById("refunds")!.instructions.replace(/\n/g, " ")).toContain("Look up the payment before asking for a refund: read it with your payment tool (its get or list call) and read its result. That read is the look-up.");
  });

  it("has every taf say an AI agent wrote what it sends outside the company", () => {
    // kiff.dev's Terms state this for tafs (AI Act Article 50); keep it true.
    for (const j of JOBS) {
      expect(systemPrompt(j)).toContain("says, in one plain sentence, that an AI agent wrote it");
      expect(systemPrompt(j)).toContain("Never present yourself as a person.");
    }
  });

  it("answers the owner briefly, in their words, and acts on their own request (kiff/tafs#102)", () => {
    for (const j of JOBS) {
      const flat = systemPrompt(j).replace(/\s+/g, " ");
      expect(flat).toContain("A request the owner makes in the chat (\"refund this payment\") is enough to act on: do it within your Card, without the decisions your job makes on work you find yourself (whether it qualifies, what to offer instead)");
      // Even then it reads what it acts on first (kiff/tafs#236, item 10).
      expect(flat).toContain("You still look up what you act on before you ask for it, as your job says (that read is the look-up, made once it's in your record): that it's the one meant, and nothing already settles it (a dispute, an earlier refund, what's left on it).");
      // The owner's request skips the job's checks, never its "Never" rules (review on #103).
      expect(flat).toContain("since the owner has decided. Your job's \"Never\" rules still hold for the owner's requests too.");
      expect(j.instructions).toMatch(/Never /);
      expect(flat).toContain("concise: say what you did, or what waits on the owner, first, then stop.");
      // Text between tool calls reaches the owner too (the owner's retest, 22 Oct: "The owner asked again").
      expect(flat).toContain("the owner also sees any text you write between tool calls: write none, or speak to them as \"you\"");
      // Concise, not a word count (the owner, kiff/agents#78).
      expect(flat).not.toMatch(/\d+ words/);
      expect(flat).toContain("amounts in the owner's currency with its symbol (€150), never in cents");
      expect(flat).toContain("no ids (payments, refunds, operations, cards) unless the owner asks for one");
      expect(flat).toContain("nothing about your notes, earlier runs, or what you checked and didn't change");
      // A held call in one sentence: the wait, the one limit, the deadline (the Eval's judges,
      // 28 Oct: "a redundant 'Nothing has been sent yet', and it wanders between the deadline,
      // the charge balance and the limits").
      // "Nothing is sent until you approve" is the held card's and its alert's to say, where
      // the owner answers; in the taf's sentence the Eval's judges read it as repeating the wait.
      expect(flat).toContain("Say it in one sentence: the call waits for their approval, the one limit that holds it (the most for one call, the day's total, or how many calls a day), and until when");
      expect(flat).not.toContain("waits for their approval and nothing was sent");
      expect(flat).toContain("Nothing else: not what's left on the item, not the other limits, and not that nothing was sent, since the held call's card says so.");
      // A chat answer ends with no report: the judges read a scheduled run's "nothing else was
      // done or left undone" as filler.
      expect(flat).toContain("with no closing summary of what was or wasn't done: that's a scheduled run's report");
      // What was checked comes after what waits on the owner (Eval 41026186869, rule 1).
      expect(flat).toContain("What you checked on the way comes after, in a few words.");
      // No closing offer (Eval 41043640507: "If you still want this refund, ask me again…").
      expect(flat).toContain("end when it's said: no offer of more help");
      // Each outcome said once (Eval 41015204757: "it was sent once. The refund went through.").
      expect(flat).toContain("each thing once: after a held call the owner approved, one sentence says what went through");
      // The scheduled run's report still names each item by its reference.
      expect(flat).toContain("End every scheduled run with a short report");
    }
  });

  it("refuses an unknown job", () => {
    expect(() => jobById("payroll")).toThrow(/no job "payroll"/);
  });
});

describe("KIFF's skill", () => {
  it("is the vendored file, pinned to one commit", () => {
    const onDisk = readFileSync(new URL("../skills/kiff/SKILL.md", import.meta.url), "utf8");
    expect(KIFF_SKILL.content).toBe(onDisk);
    expect(KIFF_SKILL.commit).toMatch(/^[0-9a-f]{40}$/);
    expect(KIFF_SKILL.content).toMatch(/^---\nname: kiff\n/);
  });
});
