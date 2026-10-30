import { describe, expect, it, vi } from "vitest";
import { logRequest, type HTTPEvent } from "../src/cloud/handler.js";
import { changeNotification, type Device, tellChange } from "../src/cloud/push.js";
import { fcmMessage } from "../src/cloud/fcm.js";

const phone = (token: string): Device => ({ token, environment: "production", subject: "u", answers: true, at: "" });

describe("live updates for the whole account", () => {
  it("a change is a silent push with only its kind and the taf, on both push services", () => {
    const n = changeNotification("taf", "taf-abc");
    expect(n).toEqual({ kind: "background", payload: { aps: { "content-available": 1 }, change: "taf", taf_id: "taf-abc" } });
    expect(fcmMessage(n, new Date()).data).toMatchObject({ kind: "background", change: "taf", taf_id: "taf-abc" });
  });

  it("goes to every phone of the account, once every few seconds for the same change", async () => {
    const sent: string[] = [];
    const deps = {
      push: { get: async () => ({ devices: [phone("a"), phone("b")], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
      apns: { send: async (d: Device) => { sent.push(d.token); return "sent" as const; } },
    };
    await tellChange("t_live", "halted", "taf-1", deps);
    await tellChange("t_live", "halted", "taf-1", deps);
    await tellChange("t_live", "halted", "taf-2", deps);
    expect(sent).toEqual(["a", "b", "a", "b"]);
  });

  it("logs one line per request, its ids masked, never a body or a token", () => {
    const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const event = { rawPath: "/v1/tafs/taf-aaaa0000000000000000/runs/9f2c41ab77/stream", requestContext: { http: { method: "get" } }, headers: { authorization: "Bearer secret" }, body: "{\"text\":\"hi\"}" } as HTTPEvent;
    logRequest(event, 200, 12);
    const line = log.mock.calls[0]![0] as string;
    log.mockRestore();
    expect(JSON.parse(line)).toEqual({ event: "request", method: "GET", route: "/v1/tafs/:id/runs/:id/stream", status: 200, ms: 12 });
    expect(line).not.toContain("secret");
    expect(line).not.toContain("hi");
  });
});

describe("a door's words", () => {
  it("say where it stands, what a tap does, and that its settings are below", async () => {
    const { doorWords } = await import("../src/cloud/doors/doors.js");
    const door = { id: "email", kind: "email" as const, app: "email", open: true, address: "refunds@tafs.example", route: "r", keys: [], created: "" };
    const app = { id: "email", label: "Email", kind: "email" as const, proof: "proven" as const, how: "" };
    expect(doorWords(door, app)).toEqual({
      state: "Open: messages from you and paired senders to refunds@tafs.example start work.",
      tap: "Tap to close the email door.",
      settings: "Paired senders and today's limits are below.",
    });
    expect(doorWords({ ...door, open: false }, app).tap).toBe("Tap to open the email door.");
  });
});
