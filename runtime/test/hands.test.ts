import { describe, expect, it } from "vitest";
import { handsOf, parseHands } from "../src/cloud/hands.js";

const AT = new Date("2026-11-28T05:30:00Z");
const hand = (over: Record<string, unknown> = {}) => ({
  grant: "g_web", agent: "agent_rae", hand: "hd_1", site: "Shop.Example.com", words: "Accept the cookie wall, then tap Done",
  since: "2026-11-28T05:28:00Z", expires: "2026-11-28T05:38:00Z", ...over,
});

describe("hosted hand-overs (hands.ts)", () => {
  it("reads planque's waiting hand-overs, leaving out anything malformed", () => {
    const got = parseHands({ hands: [
      hand(),
      hand({ hand: "../x" }), hand({ site: "https://shop.example.com/" }), hand({ site: "localhost" }), hand({ words: " " }),
      hand({ expires: "soon" }), hand({ grant: 7 }), null, "x",
    ] });
    expect(got).toEqual([{ ...hand(), site: "shop.example.com", since: "2026-11-28T05:28:00.000Z", expires: "2026-11-28T05:38:00.000Z" }]);
    expect(parseHands({})).toEqual([]);
    expect(parseHands({ hands: "x" })).toEqual([]);
    // planque's words are one line, bounded, so a hand-over can't carry a message.
    const long = parseHands({ hands: [hand({ words: "a\nb" + "c".repeat(400) })] })[0]!;
    expect(long.words.startsWith("a b")).toBe(true);
    expect([...long.words].length).toBe(200);
  });

  it("draws only the person's own tafs' hand-overs not yet lapsed, oldest first, in their words and zone", () => {
    const tafs = [{ id: "rae", name: "Rae", kiffAgent: "agent_rae" }];
    const hands = parseHands({ hands: [
      hand({ hand: "hd_2", since: "2026-11-28T05:29:00Z" }), hand(),
      hand({ hand: "hd_other", agent: "agent_someone_else" }), hand({ hand: "hd_lapsed", expires: "2026-11-28T05:29:59Z" }),
    ] });
    const views = handsOf(hands, tafs, "fr", "Europe/Paris", AT);
    expect(views.map((v) => v.hand)).toEqual(["hd_1", "hd_2"]);
    expect(views[0]).toEqual({
      grant: "g_web", hand: "hd_1", site: "shop.example.com", ask: "Accept the cookie wall, then tap Done",
      since: "2026-11-28T05:28:00.000Z", expires: "2026-11-28T05:38:00.000Z", taf: { id: "rae", name: "Rae" },
      words: { title: "À vous sur shop.example.com", ask: "« Accept the cookie wall, then tap Done »", until: "Ouvert jusqu’à 06:38", open: "Ouvrir", not_now: "Pas maintenant" },
    });
  });
});
