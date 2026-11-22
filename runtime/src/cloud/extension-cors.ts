/**
 * The tafs Chrome extension's side panel talks to Coach (chrome/src/coach.js) from its own origin, with no host permission
 * for tafs cloud (docs/chrome.md: none is asked at install), so Chrome sends each call as a CORS request, a preflight first:
 * it carries a bearer and, on a send, a JSON body. A browser is let read the answers of those three routes alone, and only
 * from an extension's own origin: never `*` and never with credentials (the bearer rides in its header, not a cookie). The
 * origin's scheme and shape are the CORS gate only, as no Web Store id is fixed yet: the view-only KIFF session's bearer, and
 * everything tafs cloud checks behind it, stay the checks.
 */

import type { HTTPEvent, HTTPResult } from "./handler.js";

/** A Chrome extension's origin: the scheme, and an id of 32 letters a to p, as Chrome makes them, with nothing after. */
const EXTENSION_ORIGIN = /^chrome-extension:\/\/[a-p]{32}$/;

/** The routes the side panel calls: the account's Coach, its conversation, and a message to it. */
const EXTENSION_ROUTES = [/^\/v1\/setup$/, /^\/v1\/tafs\/[^/]+\/conversation$/, /^\/v1\/tafs\/[^/]+\/messages$/];

function origin(event: HTTPEvent): string {
  const h = event.headers ?? {};
  return (h.origin ?? h.Origin ?? "").trim();
}

/** An extension's origin, when the request is from one, to one of the side panel's routes; else undefined. */
export function extensionOrigin(event: HTTPEvent): string | undefined {
  const o = origin(event);
  return EXTENSION_ORIGIN.test(o) && EXTENSION_ROUTES.some((r) => r.test(event.rawPath)) ? o : undefined;
}

/**
 * Chrome's preflight for a side panel route from an extension's origin: 204, GET and POST, with its bearer and a JSON body.
 * Undefined for anything else, which goes on as any request does. It reads and writes nothing.
 */
export function extensionPreflight(event: HTTPEvent): HTTPResult | undefined {
  if (event.requestContext.http.method.toUpperCase() !== "OPTIONS") return undefined;
  const o = extensionOrigin(event);
  if (!o) return undefined;
  return {
    statusCode: 204,
    headers: {
      "access-control-allow-origin": o,
      "access-control-allow-methods": "GET, POST",
      "access-control-allow-headers": "authorization, content-type",
      "access-control-max-age": "600",
      vary: "Origin",
    },
    body: "",
  };
}

/** The answer readable by the extension that asked, on a side panel route: its origin echoed, and Vary: Origin there always. */
export function withExtensionCORS(event: HTTPEvent, r: HTTPResult): HTTPResult {
  if (!EXTENSION_ROUTES.some((x) => x.test(event.rawPath))) return r;
  const headers: Record<string, string> = { ...r.headers, vary: r.headers.vary ? `${r.headers.vary}, Origin` : "Origin" };
  const o = extensionOrigin(event);
  if (o) headers["access-control-allow-origin"] = o;
  return { ...r, headers };
}
