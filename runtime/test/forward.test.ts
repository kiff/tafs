import { describe, expect, it } from "vitest";
import { forwardAlert } from "../src/cloud/forward.js";

const answering = (status: number, sent: Array<{ url: string; init?: RequestInit }> = []) =>
  (async (url: string, init?: RequestInit) => { sent.push({ url, init }); return new Response(status === 204 ? null : "{}", { status }); }) as unknown as typeof fetch;

describe("handing KIFF's alert to kiff/agents", () => {
  it("sends it exactly as KIFF signed it", async () => {
    const sent: Array<{ url: string; init?: RequestInit }> = [];
    await forwardAlert(answering(200, sent), "https://alerts.example/kiff/alerts", "t=1,v1=ab", '{"tenant_id":"t_acme"}');
    expect(sent[0]!.url).toBe("https://alerts.example/kiff/alerts");
    expect(sent[0]!.init?.body).toBe('{"tenant_id":"t_acme"}');
    expect((sent[0]!.init?.headers as Record<string, string>)["kiff-signature"]).toBe("t=1,v1=ab");
  });

  it("fails on a 401, so KIFF sends it again: tafs cloud checked it, so kiff/agents had another target's secret", async () => {
    await expect(forwardAlert(answering(401), "https://a.example/kiff/alerts", "s", "{}")).rejects.toThrow(/401/);
  });

  it("fails on a 5xx, and takes any other 4xx as final", async () => {
    await expect(forwardAlert(answering(503), "https://a.example/kiff/alerts", "s", "{}")).rejects.toThrow(/503/);
    for (const status of [400, 404, 413]) {
      await expect(forwardAlert(answering(status), "https://a.example/kiff/alerts", "s", "{}")).resolves.toBeUndefined();
    }
  });
});
