import { describe, expect, it } from "vitest";
import { extensionOrigin, extensionPreflight, withExtensionCORS } from "../src/cloud/extension-cors.js";
import { handle, type Deps, type HTTPEvent, type HTTPResult } from "../src/cloud/handler.js";

// The Chrome extension's side panel talks to Coach from its own origin with no host permission for tafs cloud, so Chrome
// asks first (22 Nov, the owner's Chrome: the preflight got 401). Only an extension's origin, on the panel's three routes,
// is let read the answers: never `*`, never with credentials.
const EXT = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";
const ev = (method: string, path: string, origin?: string): HTTPEvent => ({
  rawPath: path,
  requestContext: { http: { method } },
  headers: origin === undefined ? {} : { origin, "access-control-request-method": "POST", "access-control-request-headers": "authorization,content-type" },
});
const answer = (): HTTPResult => ({ statusCode: 401, headers: { "content-type": "application/json" }, body: `{"error":"sign in"}` });
const ROUTES = ["/v1/setup", "/v1/tafs/taf-1/conversation", "/v1/tafs/taf-1/messages"];

describe("the Chrome extension's side panel routes (extension-cors.ts)", () => {
  it("answer Chrome's preflight from an extension's origin: 204, GET and POST, its bearer and a JSON body, no credentials", () => {
    for (const path of ROUTES) {
      const r = extensionPreflight(ev("OPTIONS", path, EXT));
      expect(r, path).toEqual({
        statusCode: 204,
        headers: {
          "access-control-allow-origin": EXT, "access-control-allow-methods": "GET, POST",
          "access-control-allow-headers": "authorization, content-type", "access-control-max-age": "600", vary: "Origin",
        },
        body: "",
      });
      // The call itself is no preflight: it goes on to the route.
      for (const method of ["GET", "POST"]) expect(extensionPreflight(ev(method, path, EXT)), `${method} ${path}`).toBeUndefined();
    }
  });

  it("echo that origin on the answers themselves, refusals included, with Vary: Origin, and never Allow-Credentials", () => {
    for (const path of ROUTES) {
      const r = withExtensionCORS(ev("GET", path, EXT), answer());
      expect(r.headers["access-control-allow-origin"]).toBe(EXT);
      expect(r.headers.vary).toBe("Origin");
      expect(r.headers["access-control-allow-credentials"]).toBeUndefined();
      expect(r.statusCode).toBe(401);
      expect(r.body).toBe(answer().body);
    }
  });

  it("say nothing to any other origin: a web page, another scheme, an id not Chrome's shape, a look-alike, null, none", () => {
    for (const origin of ["https://evil.example", "moz-extension://abcdefghijklmnopabcdefghijklmnop", "chrome-extension://abcdefghijklmnopabcdefghijklmnoz",
      "chrome-extension://abcdefghijklmnopabcdefghijklmnop.evil.example", "chrome-extension://abcdefghijklmnopabcdefghijklmnop/x", "chrome-extension://short", "null", ""]) {
      expect(extensionPreflight(ev("OPTIONS", "/v1/setup", origin)), origin).toBeUndefined();
      expect(withExtensionCORS(ev("GET", "/v1/setup", origin), answer()).headers["access-control-allow-origin"], origin).toBeUndefined();
      expect(extensionOrigin(ev("GET", "/v1/setup", origin)), origin).toBeUndefined();
    }
  });

  it("only on those three routes: any other route gets no preflight answer and no CORS header", () => {
    for (const path of ["/v1/tafs", "/v1/tafs/taf-1", "/v1/tafs/taf-1/messages/stream", "/v1/connect/grants/g/check", "/v1/setup/x", "/v1/home"]) {
      expect(extensionPreflight(ev("OPTIONS", path, EXT)), path).toBeUndefined();
      expect(withExtensionCORS(ev("GET", path, EXT), answer()).headers, path).toEqual(answer().headers);
    }
  });

  it("answer the preflight in handle() before anything is read, and the rest as before, with the origin", async () => {
    // Any read of the services fails the test: the preflight touches none.
    const untouched = new Proxy({}, { get: (_t, k) => { throw new Error(`the preflight read deps.${String(k)}`); } }) as Deps;
    const pre = await handle(ev("OPTIONS", "/v1/tafs/taf-1/messages", EXT), untouched);
    expect(pre.statusCode).toBe(204);
    expect(pre.headers["access-control-allow-origin"]).toBe(EXT);
    expect(pre.headers["strict-transport-security"]).toBeDefined();
    // An OPTIONS from a web page goes on as any request does: no CORS answer.
    const web = await handle(ev("OPTIONS", "/v1/setup", "https://evil.example"), {} as Deps).catch((e) => e);
    expect(web.headers?.["access-control-allow-origin"]).toBeUndefined();
  });
});
