import { describe, expect, it } from "vitest";
import { replyConcerns } from "../src/cloud/doors/guard.js";

const ctx = { to: "ben@partner.example", others: ["maya@acme.example", "+34 611 111 111"] };

describe("a door reply's content check", () => {
  it("lets a reply about the job go", () => {
    expect(replyConcerns("Your order 4471 ships Monday, and the refund of $180 is on its way.", ctx)).toEqual([]);
    expect(replyConcerns("Thanks Ben, I'll reply to you here again once it's done.", ctx)).toEqual([]);
  });

  it("holds a reply that describes the owner's setup or names someone else", () => {
    expect(replyConcerns("I filed it in the P2-GD folder: https://drive.google.com/drive/folders/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms", ctx))
      .toEqual(["it links a document or a folder", "it carries what looks like a file or folder id"]);
    expect(replyConcerns("I read the Outlook inbox and the orders sheet in Google Sheets.", ctx)).toEqual(["it names google sheets, outlook"]);
    expect(replyConcerns("Maya (maya@acme.example) asked the same yesterday.", ctx)).toEqual(["it names someone else who writes to this taf"]);
    expect(replyConcerns("The id is 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms.", ctx)).toEqual(["it carries what looks like a file or folder id"]);
  });
});
