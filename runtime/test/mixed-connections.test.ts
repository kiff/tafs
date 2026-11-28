import { describe, expect, it } from "vitest";
import { mixedConnections } from "../src/cloud/handler.js";

const relay = (name: string, grant: string, app = "qonto") => ({ name, url: "https://planque.example/v1/relay", app, audience: `https://planque.example/mcp/t_acme/${grant}` });
const card = (...actions: string[]) => ({ grants: actions.map((action) => ({ action })) });

// A version 2 trial read through the old Qonto connection (…_yhbp) while its own new one (…_bhtn) was never called: its
// draft named tools of both. A version that brings in a new connection to an app uses it alone; a taf that already works
// with one app through two connections (two inboxes) keeps both.
describe("a version's Card and its connections", () => {
  const conns = [relay("list_client_invoices_yhbp", "grt_old"), relay("list_client_invoices_bhtn", "grt_new"), relay("create_client_invoice_bhtn", "grt_new"),
    relay("send_email_a1", "grt_inbox_a", "gmail"), relay("send_email_b2", "grt_inbox_b", "gmail"), { name: "lookup_direct", url: "https://tools.example/mcp" }];
  const inForce = new Set(["list_client_invoices_yhbp", "send_email_a1", "send_email_b2"]);

  it("names the app whose draft brings a new connection beside the one its Cards in force use", () => {
    expect(mixedConnections([card("list_client_invoices_yhbp"), card("create_client_invoice_bhtn")], conns, inForce)).toBe("Qonto");
    expect(mixedConnections([card("List_Client_Invoices_YHBP ", "create_client_invoice_bhtn")], conns, inForce)).toBe("Qonto");
  });

  it("lets the new connection alone through, and a taf's two connections in use", () => {
    expect(mixedConnections([card("list_client_invoices_bhtn", "create_client_invoice_bhtn")], conns, inForce)).toBeUndefined();
    expect(mixedConnections([card("send_email_a1", "send_email_b2")], conns, inForce)).toBeUndefined();
  });

  it("says nothing it can't tell", () => {
    // A tool connected straight to KIFF, one the list doesn't name, no list, or no Cards in force to compare with.
    expect(mixedConnections([card("lookup_direct", "list_client_invoices_bhtn", "unknown_tool")], conns, inForce)).toBeUndefined();
    expect(mixedConnections([card("list_client_invoices_yhbp", "create_client_invoice_bhtn")], undefined, inForce)).toBeUndefined();
    expect(mixedConnections([card("list_client_invoices_yhbp", "create_client_invoice_bhtn")], conns, undefined)).toBeUndefined();
    expect(mixedConnections([{ grants: "nope" }, null, card()], conns, inForce)).toBeUndefined();
    // Two app-less grants share planque's host as their url: never "two connections" of one app.
    const appless = [relay("read_sheet_a1", "grt_sheets", ""), relay("send_mail_b2", "grt_mail", "")];
    expect(mixedConnections([card("read_sheet_a1", "send_mail_b2")], appless, new Set(["read_sheet_a1"]))).toBeUndefined();
  });
});
