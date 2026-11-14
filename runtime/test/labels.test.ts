import { describe, expect, it } from "vitest";
import { businessText } from "../src/cloud/business.js";
import { keyOf, type LabelSet, labelsView, withLabelChanged, withTafLabels } from "../src/cloud/labels.js";

// Labels (docs/labels.md): chips on one flat list, with the filter row's counts and headline from tafs cloud.
describe("labels", () => {
  const ids = (() => { let n = 0; return () => `lbl_${++n}`; })();

  it("know a category's name in each of the 8 languages, and a name nobody else has, whatever its case", () => {
    expect(["Money", "Geld", "Argent", "Dinero", "Denaro", "Dinheiro", "Pieniądze", "money"].map(keyOf)).toEqual(Array(8).fill("money"));
    expect(keyOf("VIP")).toBeUndefined();
    const set: LabelSet = { labels: [{ id: "lbl_a", name: "VIP", color: "errands" }], version: 3 };
    const next = withTafLabels(set, [{ id: "t1", labels: ["lbl_a"] }], "t2", ["vip", "  Key accounts  "], ids);
    expect(next.labels.map((l) => l.name)).toEqual(["VIP", "Key accounts"]);
    expect(next.ids[0]).toBe("lbl_a");
    // A new label takes the colour fewest have.
    expect(next.labels[1]!.color).toBe("money");
    expect(() => withTafLabels(set, [], "t1", ["x".repeat(25)], ids)).toThrow("1 to 24 characters");
    expect(() => withTafLabels(set, [], "t1", ["a\u0007b"], ids)).toThrow("1 to 24 characters");
    expect(() => withTafLabels(set, [], "t1", "Money", ids)).toThrow("whole list of labels");
    expect(() => withLabelChanged(next as unknown as LabelSet, next.labels[1]!, { name: "vip" })).toThrow("another label is named vip");
  });

  it("show on Home only where a listed taf carries them, with how many tafs, how many wait, and the filtered headline", () => {
    const set: LabelSet = { labels: [{ id: "m", name: "Money", color: "money", key: "money" }, { id: "x", name: "Unused", color: "errands" }], version: 1 };
    const listed = [
      { id: "t1", state: "working", waiting: 0 },
      { id: "t2", state: "waiting", waiting: 2 },
      { id: "t3", state: "working", waiting: 0 },
    ];
    const labelsOf = new Map([["t1", ["m"]], ["t2", ["m"]], ["t3", []]]);
    expect(labelsView(set, listed, labelsOf, "en")).toEqual([{ id: "m", name: "Money", color: "money", count: 2, waiting: 1, headline: { words: "1 taf working · Money" } }]);
    // A suggested label reads in the person's language; a named one as named.
    expect(labelsView(set, listed, labelsOf, "fr")[0]).toMatchObject({ name: "Argent", headline: { words: "1 taf en cours · Argent" } });
  });
});

describe("the business description's limits", () => {
  it("keeps its lines, trims it, and refuses one past 300 words or 2400 characters", () => {
    expect(businessText("  We sell lamps.  \r\n\r\n\r\n\r\nTo shops.  ")).toBe("We sell lamps.\n\nTo shops.");
    expect(businessText("")).toBe("");
    expect(() => businessText("w ".repeat(301))).toThrow("this one has 301 words");
    expect(() => businessText("x".repeat(2401))).toThrow("2401 characters");
    expect(() => businessText(undefined)).toThrow("the whole description");
  });
});
