import { describe, expect, it } from "vitest";
import type { ApprovalGrant } from "../src/cloud/approval.js";
import { legalLinks, readsOnly, sheetOf, takesKey } from "../src/cloud/sheet.js";
import { LANGUAGES } from "../src/cloud/words/index.js";

const SHA = "a".repeat(64);
const grant = (tools: ApprovalGrant["request"]["tools"], extra: Record<string, unknown> = {}): ApprovalGrant => ({
  id: "g_1", status: "pending", digest: "d", vendor: "Qonto",
  request: {
    kind: "api_key", agent: "books", catalog_entry: "qonto-key@1", tools,
    api: { url: "https://github.com/qonto/api-doc/openapi.json", digest: SHA, base: "https://thirdparty.qonto.com/v2", auth: { in: "header", name: "Authorization" } },
    ...extra,
  },
});
const READS = [{ tool: "list_invoices", read_only: true }, { tool: "list_clients", read_only: true }];

describe("the secure connection sheet (docs/secure-connection.md)", () => {
  it("says read only only when the grant lists tools and every one is a read", () => {
    expect(readsOnly(grant(READS))).toBe(true);
    expect(readsOnly(grant([...READS, { tool: "create_invoice", read_only: false }]))).toBe(false);
    expect(readsOnly(grant([...READS, { tool: "create_invoice" }]))).toBe(false); // no flag is no read
    expect(readsOnly(grant([]))).toBe(false);
    expect(readsOnly(grant(undefined))).toBe(false);
  });

  it("takes a key only for an API-key grant", () => {
    expect(takesKey(grant(READS))).toBe(true);
    expect(takesKey(grant(READS, { kind: undefined }))).toBe(false);
  });

  it("words the sheet from the grant: the vendor, three lines, paste, Details, the legal line and the receipt", () => {
    const s = sheetOf(grant(READS), "en", { name: "Qonto", logo: "/v1/logos/abc" });
    expect(s.vendor).toEqual({ name: "Qonto", logo: "/v1/logos/abc", host: "thirdparty.qonto.com" });
    expect(s.title).toBe("Secure connection to Qonto");
    expect(s.lines).toEqual([
      { kind: "reads_only", text: "Read only: it can't pay, send or change anything in Qonto" },
      { kind: "sealed", text: "Your key is never shown again, and no taf sees it" },
      { kind: "disconnect", text: "You can disconnect it any time, in Connect apps" },
    ]);
    expect(s.paste).toEqual({ button: "Paste the key", what: "Copy it in Qonto, then paste it here", pasted: "Key …{last4}", cleared: "Clipboard cleared" });
    expect(s.details).toEqual({ title: "Details", lines: [
      { label: "Specification", value: "https://github.com/qonto/api-doc/openapi.json" },
      { label: "SHA-256", value: SHA },
      { label: "Sent only to", value: "thirdparty.qonto.com" },
      { label: "Header", value: "Authorization" },
    ] });
    expect(s.legal).toEqual({ text: "By connecting, you agree to the {terms} and the {privacy}.", terms: { words: "Terms", url: "https://kiff.dev/terms" }, privacy: { words: "Privacy Policy", url: "https://kiff.dev/privacy" } });
    expect(s.receipt).toEqual({ text: "Qonto connected · key …{last4}", disconnect: "Disconnect" });
    expect(s.card_title).toBe("Its Card");
    expect(s.button).toBe("Connect");
  });

  it("says it can change things for a grant with a write, and never a read-only line then", () => {
    const s = sheetOf(grant([...READS, { tool: "create_invoice", read_only: false }]), "fr");
    expect(s.lines[0]).toEqual({ kind: "may_change", text: "Il peut modifier des choses dans Qonto, comme sa Card le permet" });
    expect(s.legal.terms.url).toBe("https://kiff.dev/fr/terms");
  });

  it("leaves out a technical line it can't read, and never a non-https one", () => {
    const s = sheetOf(grant(READS, { api: { url: "http://example.com/spec", digest: "short", base: "https://api.example.com", auth: { in: "query", name: "key" } } }), "en");
    expect(s.details.lines).toEqual([{ label: "Sent only to", value: "api.example.com" }]);
    expect(s.vendor.logo).toBeUndefined();
  });

  it("has only the {last4}, {terms} and {privacy} placeholders, and every line in the 8 languages", () => {
    for (const lang of LANGUAGES) {
      const s = sheetOf(grant(READS), lang, { name: "Qonto" });
      const all = JSON.stringify(s);
      expect(all.match(/\{[a-z0-9]+\}/g)?.sort(), lang).toEqual(["{last4}", "{last4}", "{privacy}", "{terms}"]);
      expect(all, lang).not.toMatch(/undefined/);
      expect(s.title, lang).toContain("Qonto");
      expect(s.legal.privacy.url, lang).toBe(legalLinks(lang).privacy);
    }
  });
});
