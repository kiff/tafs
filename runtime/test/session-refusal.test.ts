import { describe, expect, it } from "vitest";
import { type Deps, handle } from "../src/cloud/handler.js";
import { KIFFError, sessionRefusal, whoIs } from "../src/cloud/kiff.js";

// Every app signs out on tafs cloud's 401. So only KIFF's 401 (the session isn't valid any more) may become one: a 403 (KIFF
// knows the session and forbids this) stays a 403, with KIFF's code, and the person stays signed in.
describe("a session KIFF refuses", () => {
  const kiff = (status: number, body?: object) => new Response(body ? JSON.stringify(body) : "", { status });

  it("is tafs cloud's 401 only when KIFF says 401", async () => {
    const e = await sessionRefusal(kiff(401, { error: "unauthorized" }));
    expect(e).toBeInstanceOf(KIFFError);
    expect(e).toMatchObject({ status: 401, message: "KIFF did not accept this sign-in" });
  });

  it("stays a 403, with KIFF's code and message, when KIFF forbids it", async () => {
    expect(await sessionRefusal(kiff(403, { error: "tenant_suspended", message: "This account is suspended" })))
      .toMatchObject({ status: 403, code: "tenant_suspended", message: "This account is suspended" });
    // KIFF sent no body: still 403, never 401.
    expect(await sessionRefusal(kiff(403))).toMatchObject({ status: 403, code: "forbidden" });
  });

  it("leaves every other answer to its caller, and the body readable", async () => {
    for (const status of [200, 400, 404, 409, 429, 500, 502]) expect(await sessionRefusal(kiff(status, { ok: true }))).toBeUndefined();
    const res = kiff(403, { error: "no_access" });
    await sessionRefusal(res);
    expect(await res.json()).toEqual({ error: "no_access" });
  });

  it("answers /v1/me's 403 as 403 at sign-in, and its 401 as 401", async () => {
    const at = (status: number, body?: object) => (async () => kiff(status, body)) as unknown as typeof fetch;
    await expect(whoIs("t", at(403, { error: "tenant_suspended" }))).rejects.toMatchObject({ status: 403, code: "tenant_suspended" });
    await expect(whoIs("t", at(401))).rejects.toMatchObject({ status: 401 });
    // Other answers are as before: KIFF failing is a 502.
    await expect(whoIs("t", at(500))).rejects.toMatchObject({ status: 502 });
  });

  it("reaches the app as 403 with KIFF's code on a route, and 401 only for KIFF's 401", async () => {
    const deps = (status: number, body?: object) => ({ fetch: (async () => kiff(status, body)) as unknown as Deps["fetch"] }) as unknown as Deps;
    const get = (d: Deps) => handle({ rawPath: "/v1/home", requestContext: { http: { method: "GET" } }, headers: { Authorization: "Bearer ana" } }, d);
    const forbidden = await get(deps(403, { error: "tenant_suspended", message: "This account is suspended" }));
    expect(forbidden.statusCode).toBe(403);
    expect(JSON.parse(forbidden.body)).toEqual({ error: "This account is suspended", code: "tenant_suspended" });
    const signedOut = await get(deps(401));
    expect(signedOut.statusCode).toBe(401);
    expect(JSON.parse(signedOut.body)).toEqual({ error: "KIFF did not accept this sign-in" });
  });
});
