import { describe, expect, it } from "vitest";
import { httpPeer, PeerError } from "../src/cloud/deletion.js";

/** A peer that records what it is sent and answers with `answer`. */
function peer(answer: (url: string) => Response) {
  const sent: Array<{ url: string; auth: string | undefined; body: unknown }> = [];
  const p = httpPeer("https://kiff.test/", "/v1/account-deletions", (async (url: string, init?: RequestInit) => {
    const h = (init?.headers ?? {}) as Record<string, string>;
    sent.push({ url: String(url), auth: h.authorization, body: JSON.parse(String(init?.body)) });
    return answer(String(url));
  }) as typeof fetch);
  return { p, sent };
}

describe("a deletion's peer over HTTP", () => {
  it("carries the person's session on accept only; finish and status carry the intent alone", async () => {
    const { p, sent } = peer(() => new Response("{}", { status: 202 }));
    await p.accept("intent", "sig", "tok_ana");
    await p.finish("intent", "sig");
    await p.accepted("intent", "sig");
    expect(sent).toEqual([
      { url: "https://kiff.test/v1/account-deletions", auth: "Bearer tok_ana", body: { intent: "intent", signature: "sig" } },
      { url: "https://kiff.test/v1/account-deletions/finish", auth: undefined, body: { intent: "intent", signature: "sig" } },
      { url: "https://kiff.test/v1/account-deletions/status", auth: undefined, body: { intent: "intent", signature: "sig" } },
    ]);
  });

  it("says a refusal in the peer's words, and reads not_accepted as a deletion it never took", async () => {
    const refused = peer(() => new Response(JSON.stringify({ error: "not_accepted", detail: "KIFF finishes only a deletion it accepted" }), { status: 403 }));
    await expect(refused.p.finish("i", "s")).rejects.toMatchObject({ status: 403, code: "not_accepted", message: "KIFF finishes only a deletion it accepted" });
    expect(await refused.p.accepted("i", "s")).toBe(false);
    const down = peer(() => new Response("{}", { status: 503 }));
    await expect(down.p.accepted("i", "s")).rejects.toBeInstanceOf(PeerError);
  });
});
