import { describe, expect, it } from "vitest";
import { browsingOf, wayFields, wayOf, waysView } from "../src/cloud/browse.js";
import type { GrantView } from "../src/cloud/taf-setup.js";

const cloud: GrantView = { id: "g_cloud", status: "connected", provider: "browser", request: { agent: "files", browser: { sites: ["supplier-portal.example"] } } };
const chrome: GrantView = { id: "g_chrome", status: "connected", provider: "browser",
  request: { agent: "files", browser: { sites: ["books.example"], driver: "extension", chrome_name: "Ana's MacBook" } } };
const mail: GrantView = { id: "g_mail", status: "connected", provider: "composio", request: { agent: "files" } };

// Browser batch 2: the way is the grant's, as the owner approved it, named by what the owner gets.
describe("the two ways a taf browses", () => {
  it("reads the way from the grant alone: the hosted browser is cloud, the owner's paired Chrome is chrome, any other connection none", () => {
    expect(wayOf(cloud)).toBe("cloud");
    expect(wayOf(chrome)).toBe("chrome");
    expect(wayOf(mail)).toBeUndefined();
    expect(wayOf({ ...cloud, request: { agent: "files", browser: { driver: "something" } } })).toBe("cloud");
  });

  it("gives a browser connection its way, words, paired Chrome and sites, and nothing to any other", () => {
    expect(wayFields(cloud)).toEqual({ way: "cloud", way_words: { title: "Cloud browser", line: "Works while your computer is off." }, sites: [{ site: "supplier-portal.example" }] });
    expect(wayFields(chrome)).toMatchObject({ way: "chrome", chrome_name: "Ana's MacBook", way_words: { line: "Works in your Chrome, where you're signed in." } });
    expect(wayFields(mail)).toEqual({});
  });

  it("lists both ways for Connect apps even with no browser connection, with Chrome's pairing once there is one", () => {
    const none = waysView(undefined);
    expect(none.map((w) => w.way)).toEqual(["cloud", "chrome"]);
    expect(none[0]!.words.facts).toEqual(["A fresh browser for each run, only on the sites you allow.", "You sign in once, in a live view. Your password is never kept."]);
    expect(none[1]!.words.facts[0]).toBe("Uses the sign-ins already in your Chrome. Your passwords stay there.");
    expect(none[1]!.words.status).toBeUndefined();
    expect(waysView([cloud, chrome])[1]!.words.status).toBe("Paired with Ana's MacBook");
    // "Nothing is kept at tafs" is never said: a step's screenshot is kept for the Record.
    expect(JSON.stringify(waysView([chrome]))).not.toMatch(/nothing is kept/i);
  });

  it("gives a browsing taf the other way's button as an ask for Coach, in tafs cloud's words", () => {
    expect(browsingOf({ name: "File invoices" }, [mail, cloud])).toEqual({ sites: ["supplier-portal.example"], way: "cloud",
      way_words: { title: "Cloud browser", line: "Works while your computer is off." },
      switch: { words: { button: "Use your Chrome instead" }, ask: "Change File invoices to use my Chrome for supplier-portal.example." } });
    expect(browsingOf({ name: "Month end" }, [chrome])?.switch).toEqual({ words: { button: "Use the cloud browser instead" },
      ask: "Change Month end to use the cloud browser for books.example." });
    expect(browsingOf({ name: "Mail" }, [mail])).toBeUndefined();
  });
});
