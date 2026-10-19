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
      expect(prompt).toContain("Never retry a refused");
      // A hold pauses the run where it is: what the Card allows goes first, and
      // the report names each item and what became of it (kiff/agents#78).
      expect(prompt).toContain("leave any write that may need the owner's approval");
      expect(prompt).toContain("Name each item by its reference and what\nbecame of it");
      expect(prompt).toContain(j.instructions);
    }
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
      expect(flat).toContain("A request the owner makes in the chat (\"refund this payment\") is enough to act on: do it within your Card, without the checks your job runs on work you find yourself");
      // The owner's request skips the job's checks, never its "Never" rules (review on #103).
      expect(flat).toContain("since the owner has decided. Your job's \"Never\" rules still hold for the owner's requests too.");
      expect(j.instructions).toMatch(/Never /);
      expect(flat).toContain("one or two short sentences (at most about 40 words)");
      expect(flat).toContain("amounts in the owner's currency with its symbol (€150), never in cents");
      expect(flat).toContain("no ids (payments, refunds, operations, cards) unless the owner asks for one");
      expect(flat).toContain("nothing about your notes, earlier runs, or what you checked and didn't change");
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
