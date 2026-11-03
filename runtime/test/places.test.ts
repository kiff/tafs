import { describe, expect, it } from "vitest";
import { JOBS } from "../src/jobs.js";
import { vendorInstructions, vendorPlace, vendorWords } from "../src/cloud/places.js";
import { instructions, ownerNotesIn } from "../src/cloud/tafdef.js";

describe("vendor result places", () => {
  it("are checked, and Slack waits for its door", () => {
    expect(vendorPlace({ place: "notion", page: " 1a2b-3c " })).toEqual({ place: "notion", page: "1a2b-3c" });
    expect(vendorPlace({ place: "gmail", to: "team@shop.com" })).toEqual({ place: "gmail", to: "team@shop.com" });
    expect(vendorPlace({ place: "teams", team: "t-1", channel: "19:abc@thread.tacv2" })).toEqual({ place: "teams", team: "t-1", channel: "19:abc@thread.tacv2" });
    expect(() => vendorPlace({ place: "teams", channel: "19:abc" })).toThrow("team's id");
    expect(() => vendorPlace({ place: "gmail", to: "not an address" })).toThrow("names the address");
    expect(() => vendorPlace({ place: "notion", page: "../x/y" })).toThrow("page's id");
    expect(() => vendorPlace({ place: "slack", channel: "C1" })).toThrow("Slack door");
    expect(() => vendorPlace({ place: "fax" })).toThrow("a place is");
    expect(vendorWords({ place: "gmail", to: "team@shop.com" })).toEqual({ label: "Gmail", words: "Emails team@shop.com each result, from your Gmail" });
  });

  it("go into a version's instructions after the owner's notes, which still read back alone", () => {
    const job = JOBS[0]!;
    const text = instructions(job, "Ask before every refund.", [{ place: "notion", page: "p1" }, { place: "gmail", to: "a@b.co" }]);
    expect(text).toContain("Where your results also go:\n- Add a page with the result under the Notion page p1, with your Notion tool.\n- Email the result to a@b.co, with your Gmail tool.");
    expect(text).toContain("never skip one silently");
    expect(ownerNotesIn(text)).toBe("Ask before every refund.");
    expect(instructions(job)).not.toContain("Where your results also go");
    expect(vendorInstructions([])).toBeUndefined();
  });
});
