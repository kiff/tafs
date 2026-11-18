import { describe, expect, it } from "vitest";
import { connectRoute } from "../src/cloud/handler.js";
import type { ApprovalGrant } from "../src/cloud/approval.js";
import { LANGUAGES, wordsIn } from "../src/cloud/words/index.js";

const ROUTES = ["managed", "own", "key", undefined] as const;
// "public" in each language, as the old line said it: the new one never says the code is public.
const PUBLIC = /public|öffentlich|públic|pubblic|openbaar|publiczn/i;

describe("the card's broader-access line", () => {
  it("says who connects by route, what the app or key may allow, and what tafs enforces, in every language", () => {
    for (const lang of LANGUAGES) {
      const w = wordsIn(lang);
      for (const route of ROUTES) {
        for (const vendor of ["Google Sheets", undefined]) {
          const line = w.card_wider(vendor, route);
          expect(line, `${lang} ${route} ${vendor}`).not.toMatch(PUBLIC);
          expect(line, `${lang} ${route} ${vendor}`).not.toContain("{app}");
          expect(line).not.toMatch(/\s{2}|^\s|\s$/);
          // The app is named when known, and only then.
          expect(line.includes("Google Sheets"), `${lang} ${route} ${vendor}`).toBe(vendor !== undefined);
        }
      }
      // Every route says something different about who connects; an unknown one names nobody.
      const lines = ROUTES.map((r) => w.card_wider("Zendesk", r));
      expect(new Set(lines).size, lang).toBe(4);
      // A route that isn't one of the three, as data on the card's path, is no route: never a throw.
      expect(w.card_wider("Zendesk", "other" as never), lang).toBe(w.card_wider("Zendesk", undefined));
      expect(w.card_wider(undefined, "Y" as never), lang).toBe(w.card_wider(undefined, undefined));
    }
  });

  it("is the owner's words in English", () => {
    const en = wordsIn("en");
    expect(en.card_wider("Google Sheets", "managed")).toBe(
      "Composio connects your Google Sheets account. Google Sheets may ask for broader access than this job needs, but your taf can only use the tools allowed on its Card. tafs enforces those limits and blocks every other tool call.");
    expect(en.card_wider("Google Sheets", "own")).toMatch(/^tafs connects your Google Sheets account\. Google Sheets may ask/);
    expect(en.card_wider("Zendesk", "key")).toBe(
      "You connect Zendesk with a key. The key may allow more than this job needs, but your taf can only use the tools allowed on its Card. tafs enforces those limits and blocks every other tool call.");
    expect(en.card_wider("Google Sheets")).toMatch(/^Google Sheets may ask for broader access/);
    expect(en.card_wider()).toMatch(/^The app may ask for broader access/);
  });

  it("takes the route from planque's grant, and guesses none", () => {
    const g = (request: Record<string, unknown>) => ({ id: "g", status: "pending", digest: "d", request }) as ApprovalGrant;
    expect(connectRoute(g({ connector: { provider: "composio", usual_access: true } }))).toBe("managed");
    expect(connectRoute(g({ application: "googlesheets@1", connector: { usual_access: true } }))).toBe("own");
    // As planque sends it: an object.
    expect(connectRoute(g({ application: { id: "googlesheets@1", client_id: "c" }, connector: { usual_access: true } }))).toBe("own");
    expect(connectRoute(g({ kind: "api_key" }))).toBe("key");
    expect(connectRoute(g({ kind: "key" }))).toBe("key");
    expect(connectRoute(g({ connector: { provider: "another", usual_access: true } }))).toBeUndefined();
    expect(connectRoute(g({ connector: { usual_access: true } }))).toBeUndefined();
    expect(connectRoute(g({ application: "" }))).toBeUndefined();
    expect(connectRoute(g({ application: null }))).toBeUndefined();
  });
});
