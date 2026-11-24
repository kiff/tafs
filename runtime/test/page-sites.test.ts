import { describe, expect, it } from "vitest";
// The extension's own module: plain JS with no browser calls, read here only to hold the two lists equal.
import * as chrome from "../../chrome/src/sites.js";
import { MAIL, maskedPath, normalHost, PATH_MASK, pageSiteRefused, SENSITIVE, tokenSegment } from "../src/cloud/page-sites.js";

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

  // Invariant: a path segment shaped like a key or a one-time token never reaches the AI; a name, a slug, a number or an id does
  it("mask a path segment shaped like a token, and keep names, slugs, numbers and ids", () => {
    const hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    for (const t of [hex, "Xk3pQz9LmN2rT8vW5yB7cD1e", "AbC123dEf456GhI789jKl012MnO_-x", "k3j4h5g6f7d8s9a0q1w2e3r4t5y6u7i8"]) {
      expect(tokenSegment(t), t).toBe(true);
    }
    for (const k of ["orders", "42", "late-parcels-2024", "how-to-return-an-item-bought-in-2024-online",
      "550e8400-e29b-41d4-a716-446655440000", "Refund-Policy", "abcdefghijklmnopqrstuvwxyzabcdefgh"]) {
      expect(tokenSegment(k), k).toBe(false);
    }
    expect(maskedPath(`/reset/${hex}/confirm`)).toBe(`/reset/${PATH_MASK}/confirm`);
    expect(maskedPath("/orders/42")).toBe("/orders/42");
    expect(maskedPath("/")).toBe("/");
    // Percent-encoded, and a segment that isn't valid encoding.
    expect(maskedPath("/magic/Xk3pQz9LmN2rT8vW5yB7cD1e%3D%3D")).toBe(`/magic/${PATH_MASK}`);
    expect(maskedPath("/a/%E0%A4%A/b")).toBe("/a/%E0%A4%A/b");
  });

  // Invariant: a token with dots or tildes in it (a JWT, an id and its signature) or after a prefix is masked whole
  it("mask a JWT, an id with its signature and a prefixed token; keep file names and versions", () => {
    const jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwiZXhwIjoxNzY0MDAwMDAwfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
    for (const t of [jwt, "12345.Xk3pQz9LmN2rT8vW5yB7cD1e", "tok_" + "a1b2c3d4e5".repeat(4), "x~9f86d081884c7d659a2feaa0c55ad015a3bf4f1b"]) {
      expect(tokenSegment(t), t).toBe(true);
    }
    for (const k of ["report.2024.pdf", "v1.2.3", "index.html", "late-parcels-2024.html", "my_order_history"]) {
      expect(tokenSegment(k), k).toBe(false);
    }
    expect(maskedPath(`/verify/${jwt}`)).toBe(`/verify/${PATH_MASK}`);
    expect(maskedPath("/files/report.2024.pdf")).toBe("/files/report.2024.pdf");
  });
});

