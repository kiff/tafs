import { createHash, createHmac } from "node:crypto";
import { describe, expect, it } from "vitest";
import { Agents, AgentsError } from "../src/cloud/agents.js";

const URL_ = "https://abc123.lambda-url.eu-west-1.on.aws";
const NOW = new Date("2026-10-16T12:00:00Z");
const CREDS = { accessKeyId: "AKIDEXAMPLE", secretAccessKey: "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", sessionToken: "session-token" };

interface Sent {
  url: URL;
  method: string;
  headers: Record<string, string>;
  body: string | undefined;
}

/** A kiff/agents that records what it is sent and answers with `answer`. */
function server(answer: (s: Sent) => Response = () => new Response("{}", { status: 200 })) {
  const sent: Sent[] = [];
  const doFetch = (async (input: URL | string, init?: RequestInit) => {
    const s: Sent = {
      url: new URL(String(input)),
      method: init?.method ?? "GET",
      headers: Object.fromEntries(Object.entries((init?.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v])),
      body: init?.body === undefined ? undefined : String(init.body),
    };
    sent.push(s);
    return answer(s);
  }) as typeof fetch;
  const agents = new Agents({ url: URL_, region: "eu-west-1", credentials: async () => CREDS, fetch: doFetch, now: () => NOW });
  return { agents, sent };
}

const hex = (d: string) => createHash("sha256").update(d).digest("hex");
const hmac = (k: string | Buffer, d: string) => createHmac("sha256", k).update(d).digest();
/** RFC 3986 escaping, as SigV4 wants it. */
const esc = (s: string) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);

/**
 * The signature AWS computes for a request, by hand from the SigV4 spec,
 * independently of the signer: if they agree, Lambda's IAM check does too.
 */
function expectedSignature(s: Sent): string {
  const auth = s.headers.authorization ?? "";
  const signedHeaders = /SignedHeaders=([^,]+)/.exec(auth)?.[1] ?? "";
  const date = s.headers["x-amz-date"] ?? "";
  const day = date.slice(0, 8);
  const host = s.url.host;
  const headerValue = (h: string) => (h === "host" ? host : (s.headers[h] ?? "")).trim();
  // Each segment escaped once more than on the wire: twice in all.
  const path = s.url.pathname.split("/").map((seg) => esc(seg)).join("/");
  const query = [...s.url.searchParams.entries()].map(([k, v]) => `${esc(k)}=${esc(v)}`).sort().join("&");
  const canonical = [
    s.method, path, query,
    ...signedHeaders.split(";").map((h) => `${h}:${headerValue(h)}`), "",
    signedHeaders, hex(s.body ?? ""),
  ].join("\n");
  const scope = `${day}/eu-west-1/lambda/aws4_request`;
  const toSign = ["AWS4-HMAC-SHA256", date, scope, hex(canonical)].join("\n");
  let key: Buffer = hmac(`AWS4${CREDS.secretAccessKey}`, day);
  for (const part of ["eu-west-1", "lambda", "aws4_request"]) key = hmac(key, part);
  return hmac(key, toSign).toString("hex");
}

describe("kiff/agents client", () => {
  it("signs each request with tafs cloud's role, the tenant header included", async () => {
    const { agents, sent } = server();
    await agents.startRun("t_acme", "taf1", "What's waiting?", "s1");
    const s = sent[0]!;
    const auth = s.headers.authorization ?? "";
    expect(auth).toMatch(/^AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE\/20261016\/eu-west-1\/lambda\/aws4_request, /);
    const signed = (/SignedHeaders=([^,]+)/.exec(auth)?.[1] ?? "").split(";");
    // The server trusts Kiff-Tenant only because it is signed.
    expect(signed).toEqual(expect.arrayContaining(["host", "kiff-tenant", "x-amz-date", "x-amz-security-token", "content-type"]));
    expect(s.headers["kiff-tenant"]).toBe("t_acme");
    expect(s.headers["x-amz-security-token"]).toBe("session-token");
    expect(/Signature=([0-9a-f]{64})/.exec(auth)?.[1]).toBe(expectedSignature(s));
  });

  it("signs the query and an escaped path the same way AWS checks them", async () => {
    const { agents, sent } = server((s) => new Response(s.method === "GET" ? JSON.stringify({ sessions: [] }) : null, { status: s.method === "GET" ? 200 : 204 }));
    await agents.sessions("t_acme", "taf1", 5);
    await agents.forget("t_acme", "taf1", "customer prefs/acme");
    for (const s of sent) expect(/Signature=([0-9a-f]{64})/.exec(s.headers.authorization ?? "")?.[1]).toBe(expectedSignature(s));
    expect(sent[0]!.url.pathname).toBe("/sessions");
    expect(sent[0]!.url.searchParams.get("agent_id")).toBe("taf1");
    // A note's name is free text: it stays one segment, never a path.
    expect(sent[1]!.url.pathname).toBe("/agents/taf1/memories/customer%20prefs%2Facme");
  });

  it("starts runs in the background, in the session given", async () => {
    const { agents, sent } = server(() => new Response(JSON.stringify({ id: "r1", status: "PENDING" }), { status: 202 }));
    const run = await agents.startRun("t_acme", "taf1", "Hello", "s1");
    expect(run.id).toBe("r1");
    expect(sent[0]!.method).toBe("POST");
    expect(sent[0]!.url.pathname).toBe("/agents/taf1/runs");
    expect(JSON.parse(sent[0]!.body ?? "")).toEqual({ input: "Hello", session_id: "s1", background: true });
    await agents.startRun("t_acme", "taf1", "Hello");
    expect(JSON.parse(sent[1]!.body ?? "")).toEqual({ input: "Hello", background: true });
  });

  it("sends back only what a schedule can be written with, so a read schedule round-trips", async () => {
    const { agents, sent } = server((s) => new Response(s.body ?? "{}", { status: s.method === "POST" ? 201 : 200 }));
    const read = { tenant: "t_acme", id: "taf1", agent: "taf1", cron: "*/30 * * * *", time_zone: "Europe/Madrid", input: "Work the queue.", enabled: true, next: "2026-10-16T12:30:00Z" };
    await agents.updateSchedule("t_acme", { ...read, cron: "*/15 * * * *" });
    await agents.createSchedule("t_acme", read);
    for (const s of sent) {
      // kiff/agents decodes schedules with unknown fields refused: next is only ever its answer.
      expect(JSON.parse(s.body ?? "")).toEqual({ id: "taf1", agent: "taf1", cron: expect.any(String), time_zone: "Europe/Madrid", input: "Work the queue.", enabled: true });
    }
    expect(sent[0]!.url.pathname).toBe("/schedules/taf1");
  });

  it("never sends an id that isn't one", async () => {
    const { agents, sent } = server();
    for (const id of ["", ".", "..", "../agents", "a/b", "a b", "x".repeat(129)]) {
      await expect(agents.getRun("t_acme", id, "r1")).rejects.toMatchObject({ status: 404 });
    }
    await expect(agents.forget("t_acme", "taf1", "..")).rejects.toMatchObject({ status: 404 });
    await expect(agents.getRun("", "taf1", "r1")).rejects.toMatchObject({ status: 401 });
    expect(sent).toHaveLength(0);
  });

  it("says what kiff/agents said, and treats gone as gone", async () => {
    const answers: Record<string, Response> = {
      "PUT /agents/taf1": new Response(JSON.stringify({ error: "registry: a run needs a cost cap" }), { status: 400 }),
      "POST /agents/taf1/runs/r1/cancel": new Response("not json", { status: 409 }),
      "GET /agents/gone": new Response(JSON.stringify({ error: "not found" }), { status: 404 }),
      "DELETE /agents/gone": new Response(JSON.stringify({ error: "not found" }), { status: 404 }),
      "DELETE /agents/taf1": new Response(null, { status: 204 }),
    };
    const { agents } = server((s) => answers[`${s.method} ${s.url.pathname}`] ?? new Response("{}", { status: 500 }));
    const err = await agents.putAgent("t_acme", "taf1", {} as never).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(AgentsError);
    expect(err).toMatchObject({ status: 400, message: "registry: a run needs a cost cap" });
    await expect(agents.cancelRun("t_acme", "taf1", "r1")).rejects.toMatchObject({ status: 409, message: "kiff/agents answered 409" });
    expect(await agents.getAgent("t_acme", "gone")).toBeUndefined();
    await expect(agents.deleteAgent("t_acme", "gone")).resolves.toBeUndefined();
    await expect(agents.deleteAgent("t_acme", "taf1")).resolves.toBeUndefined();
  });

  it("ends a trial with its counts alone, whatever else KIFF's trial carries", async () => {
    const { agents, sent } = server(() => new Response(JSON.stringify({ ended: { why: "installed" } }), { status: 200 }));
    const fromKIFF = { status: "ended", endedBy: "install", calls: 2, held: 1, refused: 0 };
    await agents.endTrial("t1", "payouts", "installed", fromKIFF);
    expect(JSON.parse(sent[0]!.body!)).toEqual({ why: "installed", calls: 2, held: 1, refused: 0 });
  });

  it("is only ever called over https", () => {
    expect(() => new Agents({ url: "http://abc123.lambda-url.eu-west-1.on.aws", region: "eu-west-1", credentials: async () => CREDS })).toThrow(/https/);
  });
});
