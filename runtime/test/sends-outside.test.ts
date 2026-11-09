import { describe, expect, it } from "vitest";
import { noteSendingOutside, notedSendingOutside, sendingOutside, sendsOutsideKey, undisclosedSending } from "../src/cloud/sends-outside.js";
import { memoryCoach } from "./fakes.js";

const NOW = new Date("2026-11-09T12:00:00Z");
const op = (tool: string, effect: string, outside?: boolean) => ({ tool, kind: effect ? "write" : "read", ...(effect ? { effect } : {}), ...(outside !== undefined ? { resource: { noun: "message", outside } } : {}) });

describe("tools that send outside the company (gap 14)", () => {
  it("are the profile's operations that send, to a recipient who may be outside", () => {
    const profile = { v: "kiff.profile.v1", operations: [op("send_email", "sends", true), op("post_internal", "sends", false), op("send_sms", "sends"), op("refund", "moves_money"), op("list", ""), op("send_email", "sends", true)] };
    expect(sendingOutside(profile)).toEqual(["send_email"]);
    for (const none of [undefined, null, "x", {}, { operations: "x" }, { operations: [null, { effect: "sends", resource: { outside: true } }] }]) expect(sendingOutside(none)).toEqual([]);
  });

  it("are noted per grant for the taf's agent, each Approve replacing its own grant's, never another's", async () => {
    const store = memoryCoach(() => NOW);
    await noteSendingOutside(store, "t1", "rae", "g_mail", { operations: [op("send_email", "sends", true)] }, NOW);
    await noteSendingOutside(store, "t1", "rae", "g_chat", { operations: [op("post", "sends", true)] }, NOW);
    expect(await notedSendingOutside(store, "t1", "rae")).toEqual(["post", "send_email"]);
    // The mail connection approved again with no sending tool: its entry goes, the chat's stays.
    await noteSendingOutside(store, "t1", "rae", "g_mail", { operations: [op("list", "")] }, NOW);
    expect(await notedSendingOutside(store, "t1", "rae")).toEqual(["post"]);
    // Another agent and another account are their own.
    expect(await notedSendingOutside(store, "t1", "max")).toEqual([]);
    expect(await notedSendingOutside(store, "t2", "rae")).toEqual([]);
    expect(await store.get(sendsOutsideKey("t1", "rae"))).toMatchObject({ grants: { g_mail: [], g_chat: ["post"] } });
  });

  it("refuse a result whose disclose leaves one out, naming each, among the tools the taf has", () => {
    expect(undisclosedSending(["send_email"], ["list", "send_email"], ["send_email"])).toBeUndefined();
    expect(undisclosedSending([], ["send_email"], [])).toBeUndefined();
    // Not among the taf's tools: not the taf's to disclose.
    expect(undisclosedSending(["send_email"], ["list"], [])).toBeUndefined();
    const one = undisclosedSending(["send_email"], ["send_email"], []);
    expect(one).toContain("disclose doesn't name send_email, which sends to people outside the company");
    expect(one).toContain("add it to disclose");
    // No tools named: every noted one counts.
    expect(undisclosedSending(["post", "send_email"], undefined, ["post"])).toContain("disclose doesn't name send_email,");
    expect(undisclosedSending(["post", "send_email"], [], [])).toContain("disclose doesn't name post, send_email, which send to people");
  });
});
