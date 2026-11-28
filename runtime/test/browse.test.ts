import { describe, expect, it } from "vitest";
import { browseOf, browsingOf, reasonWords, wayFields, wayOf, waysView } from "../src/cloud/browse.js";
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

// Coach's pick is a code from a closed list, worded by tafs cloud, never Coach's own sentence on an owner's row.
describe("Coach's pick of the way", () => {
  it("takes a way with a reason that goes with it, and the time for a schedule", () => {
    expect(browseOf({ way: "cloud", reason: "schedule", time: "08:00" })).toEqual({ way: "cloud", reason: "schedule", time: "08:00" });
    expect(browseOf({ way: "cloud", reason: "door" })).toEqual({ way: "cloud", reason: "door" });
    expect(browseOf({ way: "chrome", reason: "signed_in", time: "08:00" })).toEqual({ way: "chrome", reason: "signed_in" });
    expect(browseOf(undefined)).toBeUndefined();
  });

  it("refuses a way it doesn't know, a reason of Coach's own, a reason for the other way, and a schedule with no time", () => {
    expect(() => browseOf({ way: "hosted", reason: "door" })).toThrow(/way is "cloud"/);
    expect(() => browseOf({ way: "cloud", reason: "Because it runs at night." })).toThrow(/never your own words/);
    expect(() => browseOf({ way: "chrome", reason: "schedule", time: "08:00" })).toThrow(/goes with cloud, not chrome/);
    expect(() => browseOf({ way: "cloud", reason: "schedule" })).toThrow(/HH:MM/);
    expect(() => browseOf({ way: "cloud", reason: "schedule", time: "8am" })).toThrow(/HH:MM/);
  });

  it("words each reason in tafs cloud's words", () => {
    expect(reasonWords({ way: "cloud", reason: "schedule", time: "08:00" }, "File invoices")).toBe("Coach chose the cloud browser because File invoices runs at 08:00, when you may be away.");
    expect(reasonWords({ way: "chrome", reason: "occasional" }, "Month end")).toBe("Coach chose your Chrome because Month end runs only when you ask.");
  });

  it("shows the reason on the taf's setup only while it's for the way its grant has", () => {
    const picked = browsingOf({ name: "File invoices", browse: { way: "cloud", reason: "schedule", time: "08:00" } }, [cloud]);
    expect(picked).toMatchObject({ way: "cloud", reason: "schedule", chosen_by: "coach", why: "Coach chose the cloud browser because File invoices runs at 08:00, when you may be away." });
    // The owner moved it to their Chrome since: Coach's cloud reason says nothing about it.
    const moved = browsingOf({ name: "File invoices", browse: { way: "cloud", reason: "schedule", time: "08:00" } }, [chrome]);
    expect(moved?.way).toBe("chrome");
    expect(moved).not.toHaveProperty("why");
    expect(moved).not.toHaveProperty("reason");
  });
});
