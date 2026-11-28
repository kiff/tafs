import { describe, expect, it } from "vitest";
import { mixedConnections } from "../src/cloud/handler.js";

const relay = (name: string, grant: string, app = "qonto") => ({ name, url: "https://planque.example/v1/relay", app, audience: `https://planque.example/mcp/t_acme/${grant}` });
const card = (...actions: string[]) => ({ grants: actions.map((action) => ({ action })) });

// A version 2 trial read through the old Qonto connection (…_yhbp) while its own new one (…_bhtn) was never called: its
// draft named tools of both. A version's Card uses one connection per app.
describe("a version's Card and its connections", () => {
  const conns = [relay("list_client_invoices_yhbp", "grt_old"), relay("list_client_invoices_bhtn", "grt_new"), relay("create_client_invoice_bhtn", "grt_new"),
    relay("send_email_k3f9", "grt_mail", "gmail"), { name: "lookup_direct", url: "https://tools.example/mcp" }];

  it("names the app whose tools a draft reaches through two connections", () => {
    expect(mixedConnections([card("list_client_invoices_yhbp"), card("create_client_invoice_bhtn")], conns)).toBe("Qonto");
    expect(mixedConnections([card("List_Client_Invoices_YHBP ", "create_client_invoice_bhtn")], conns)).toBe("Qonto");
  });

  it("lets one connection per app through, across apps, and says nothing it can't tell", () => {
    expect(mixedConnections([card("list_client_invoices_bhtn"), card("create_client_invoice_bhtn"), card("send_email_k3f9")], conns)).toBeUndefined();
    // A tool connected straight to KIFF (no audience), a tool the list doesn't name, no list at all: nothing refused on a guess.
    expect(mixedConnections([card("lookup_direct", "list_client_invoices_bhtn", "unknown_tool")], conns)).toBeUndefined();
    expect(mixedConnections([card("list_client_invoices_yhbp", "create_client_invoice_bhtn")], undefined)).toBeUndefined();
    expect(mixedConnections([{ grants: "nope" }, null, card()], conns)).toBeUndefined();
  });
});
