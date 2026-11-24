import { describe, expect, it } from "vitest";
// The extension's own module: plain JS with no browser calls, read here only to hold the two lists equal.
import * as chrome from "../../chrome/src/sites.js";
import { MAIL, normalHost, pageSiteRefused, SENSITIVE } from "../src/cloud/page-sites.js";

describe("the sites Coach never reads a page from, at tafs cloud", () => {
  // Invariant: tafs cloud's lists are the extension's own, so the server keeps exactly the promise the panel and the store make
  it("are the extension's lists and labels, word for word", () => {
    expect(SENSITIVE.sites).toEqual(chrome.SENSITIVE.sites);
    expect(SENSITIVE.labels.source).toBe(chrome.SENSITIVE.labels.source);
    expect(SENSITIVE.labels.flags).toBe(chrome.SENSITIVE.labels.flags);
    expect(MAIL.sites).toEqual(chrome.MAIL.sites);
    expect(MAIL.labels.source).toBe(chrome.MAIL.labels.source);
    expect(MAIL.labels.flags).toBe(chrome.MAIL.labels.flags);
  });

  it("refuse what the extension's asideRefusal refuses, and only that, for every host form", () => {
    const tab = (url: string) => ({ id: 7, active: true, windowId: 2, incognito: false, url });
    for (const url of [
      "https://mail.google.com/mail/u/0/", "https://MAIL.GOOGLE.COM./mail/", "https://outlook.cloud.microsoft/mail/",
      "https://owa.example.com/owa/", "https://poczta.onet.pl/", "https://www.paypal.com./", "https://online.bank.example/",
      "https://impots.gouv.fr/", "https://www.nhs.uk/", "https://chromewebstore.google.com/detail/x",
      "https://chrome.google.com/webstore/x", "https://mail。google。com/",
      "https://shop.example/", "https://shop.example./orders", "https://mailchimp.com/", "https://www.royalmail.com/",
    ]) {
      const u = new URL(url);
      const ours = pageSiteRefused(normalHost(u.hostname), u.pathname);
      const theirs = chrome.asideRefusal(tab(url), { windowId: 2, off: [] });
      expect(ours, url).toBe(theirs);
    }
  });
});
