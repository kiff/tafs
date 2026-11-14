import { describe, expect, it } from "vitest";
import type { Run } from "../src/cloud/agents.js";
import { coachWaitingOf } from "../src/cloud/coachwaiting.js";
import { endSentence, LANGUAGES } from "../src/cloud/words/index.js";

// fr and pt abbreviate a month with a period ("14 nov.", "14 de nov."), and a line that ends on the date added another:
// "Coach attend votre réponse depuis le 14 nov.." on an iPad, 14 Nov.
describe("a line that ends on a date", () => {
  it("ends its sentence with one period", () => {
    expect(endSentence("depuis le 14 nov..")).toBe("depuis le 14 nov.");
    expect(endSentence("since 14 Nov.")).toBe("since 14 Nov.");
    expect(endSentence("Version 1.2.")).toBe("Version 1.2.");
  });

  it("names a waiting Coach chat with one period at its end, in every language, for a November date", () => {
    const run = {
      id: "r1", agent: "coach", session: "s1", status: "PAUSED", created: "2026-11-14T09:00:00Z", updated: "2026-11-14T09:00:00Z",
      requester: { kind: "member", id: "u_ana" },
      requirements: [
        { call_id: "q1", tool: "show", input: { id: "q1", type: "questions" }, operation: "", expires: "2026-11-20T09:00:00Z", kind: "card" },
      ],
    } as unknown as Run;
    for (const lang of LANGUAGES) {
      for (const card of ["questions", "approval"]) {
        (run.requirements![0]!.input as { type: string }).type = card;
        const [row] = coachWaitingOf([run], "coach", "u_ana", new Date("2026-11-15T09:00:00Z"), lang, "UTC", new Map([["s1", "Rappels"]]));
        expect(row!.words.line, `${lang} ${card}`).not.toMatch(/\.\.$/);
        expect(row!.words.line, `${lang} ${card}`).toMatch(/[.。]$/);
      }
    }
  });
});
