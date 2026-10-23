import { generateKeyPairSync, sign } from "node:crypto";
import { describe, expect, it } from "vitest";
import { canonical, sha256 } from "../src/cloud/jcs.js";
import { publishedKeys, verifyPermit } from "../src/cloud/permit.js";

const ISSUER = "https://kiff.test";
const AUD = "https://tafs.test/v1/mcp";
const { publicKey, privateKey } = generateKeyPairSync("ed25519");
const keys = async (kid: string) => {
  if (kid !== "k1") throw new Error("unknown key");
  return publicKey;
};
const now = new Date("2026-10-23T18:00:00Z");
const args = { taf: "taf_1", run: "run_1", tafs_op: "op_1" };

function mint(claims: Record<string, unknown>, header: Record<string, unknown> = {}): string {
  const enc = (v: unknown) => Buffer.from(JSON.stringify(v)).toString("base64url");
  const iat = Math.floor(now.getTime() / 1000);
  const h = enc({ alg: "Ed25519", typ: "kiff-permit+jwt", kid: "k1", ...header });
  const c = enc({
    iss: ISSUER, aud: AUD, sub: "agent_coach", iat, exp: iat + 60, jti: "jti_1",
    authorization_details: [{ type: "https://kiff.dev/permits/tool-call", tenant: "t1", tool: "run_stop", args_sha256: sha256(args), op: "op_1" }],
    ...claims,
  });
  return `${h}.${c}.${sign(null, Buffer.from(`${h}.${c}`), privateKey).toString("base64url")}`;
}

describe("JCS", () => {
  it("sorts keys by UTF-16 code units and writes numbers as ECMAScript does", () => {
    expect(canonical({ b: 1, a: [true, null, "é"], "é": 1e-7, A: 0.1 })).toBe('{"A":0.1,"a":[true,null,"é"],"b":1,"é":1e-7}');
  });
  it("refuses an integer past 2^53 - 1", () => {
    expect(() => canonical({ n: 2 ** 53 })).toThrow();
  });
});

describe("a KIFF permit", () => {
  it("is accepted for exactly its call, and names its tenant and subject", async () => {
    const p = await verifyPermit(mint({}), { audience: AUD, tool: "run_stop", args }, keys, ISSUER, now);
    expect(p).toMatchObject({ id: "jti_1", subject: "agent_coach", tenant: "t1", tool: "run_stop", op: "op_1" });
  });
  it.each([
    ["no permit", undefined],
    ["another server", mint({ aud: "https://elsewhere.test/mcp" })],
    ["another issuer", mint({ iss: "https://other.test" })],
    ["another key", mint({}, { kid: "k2" })],
    ["a longer lifetime", mint({ exp: Math.floor(now.getTime() / 1000) + 600 })],
    ["an expired one", mint({ iat: Math.floor(now.getTime() / 1000) - 200, exp: Math.floor(now.getTime() / 1000) - 140 })],
  ])("is refused: %s", async (_, token) => {
    await expect(verifyPermit(token, { audience: AUD, tool: "run_stop", args }, keys, ISSUER, now)).rejects.toThrow();
  });
  it("is refused for another tool or other arguments", async () => {
    await expect(verifyPermit(mint({}), { audience: AUD, tool: "taf_pause", args }, keys, ISSUER, now)).rejects.toThrow("another tool");
    await expect(verifyPermit(mint({}), { audience: AUD, tool: "run_stop", args: { ...args, run: "run_2" } }, keys, ISSUER, now)).rejects.toThrow("other arguments");
  });
  it("is refused when its signature was made with another key", async () => {
    const t = mint({});
    const forged = t.slice(0, t.lastIndexOf(".") + 1) + sign(null, Buffer.from("x"), generateKeyPairSync("ed25519").privateKey).toString("base64url");
    await expect(verifyPermit(forged, { audience: AUD, tool: "run_stop", args }, keys, ISSUER, now)).rejects.toThrow("signature");
  });
});

describe("KIFF's published keys", () => {
  it("are fetched once, and an unknown kid fetches again at most once a minute", async () => {
    const x = publicKey.export({ format: "jwk" }).x;
    let fetches = 0, t = 0;
    const doFetch = (async () => {
      fetches++;
      return new Response(JSON.stringify({ keys: [{ kty: "OKP", crv: "Ed25519", kid: "k1", alg: "Ed25519", x }] }));
    }) as unknown as typeof fetch;
    const get = publishedKeys("https://kiff.test/.well-known/kiff-permit-keys.json", doFetch, () => t);
    await get("k1");
    await get("k1");
    expect(fetches).toBe(1);
    await expect(get("k9")).rejects.toThrow();
    await expect(get("k9")).rejects.toThrow();
    expect(fetches).toBe(2);
    t = 61_000;
    await expect(get("k9")).rejects.toThrow();
    expect(fetches).toBe(3);
  });
});
