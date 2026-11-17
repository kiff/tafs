import { describe, expect, it } from "vitest";
import { NOTE_CLAIM_MS, NOTE_RELEASE_MARGIN_MS, noteSendingOutside, notedSendingOutside, sendingOutside, sendingProfileOf, sendsOutsideKey, undisclosedSending } from "../src/cloud/sends-outside.js";
import { memoryCoach } from "./fakes.js";

const NOW = new Date("2026-11-09T12:00:00Z");
const op = (tool: string, effect: string, outside?: boolean) => ({ tool, kind: effect ? "write" : "read", ...(effect ? { effect } : {}), ...(outside !== undefined ? { resource: { noun: "message", outside } } : {}) });

describe("tools that send outside the company (gap 14)", () => {
  it("are the profile's operations that send, to a recipient who may be outside", () => {
    const profile = { v: "kiff.profile.v1", operations: [op("send_email", "sends", true), op("post_internal", "sends", false), op("send_sms", "sends"), op("refund", "moves_money"), op("list", ""), op("send_email", "sends", true)] };
    expect(sendingOutside(profile)).toEqual(["send_email"]);
    for (const none of [undefined, null, "x", {}, { operations: "x" }, { operations: [null, { effect: "sends", resource: { outside: true } }] }]) expect(sendingOutside(none)).toEqual([]);
  });

  // A draft reaches the people it's for once the owner sends it, as written: its tool carries the line by default, so a
  // forgotten mark still discloses, and a draft that stays out of it opts out explicitly with outside: false, as a draft
  // invoice does (the line is about who wrote a message; an invoice has its own legal rules). Fail closed.
  it("include every tool that drafts (none_until_approved) unless it says outside: false, as a draft invoice does", () => {
    const profile = { v: "kiff.profile.v1", operations: [op("drafts_create", "none_until_approved", true), op("draft_reply", "none_until_approved"), op("draft_invoice", "none_until_approved", false), op("update_record", "writes", true), op("send_email", "sends", true), op("post_internal", "sends")] };
    expect(sendingOutside(profile)).toEqual(["draft_reply", "drafts_create", "send_email"]);
    expect(undisclosedSending(sendingOutside(profile), ["drafts_create", "send_email"], ["send_email"])).toContain("disclose doesn't name drafts_create, which reaches people outside the company, a draft once it's sent included");
    expect(undisclosedSending(sendingOutside(profile), ["drafts_create", "send_email"], ["send_email", "drafts_create"])).toBeUndefined();
  });

  // Eval 43975057022, Replay run 2: a draft email on an API-key connection, which carries no profile, so nothing was
  // noted and the taf was made without the line. planque marks a key write's text fields from its schema: such a write
  // sends; one with none (a status) or a read doesn't, whatever the vendor's words or the asking agent say.
  it("on an API-key connection, are its writes with text fields, as planque read the schema", () => {
    const key = { api: { url: "https://desk.example/openapi.json" }, tools: [
      { tool: "createDraft", name: "create_draft_k3", text_fields: ["body", "subject"] },
      { tool: "setStatus", name: "set_status_k3" },
      { tool: "markRow", name: "mark_row_k3", text_fields: [] },
      { tool: "listTickets", name: "list_tickets_k3", read_only: true, text_fields: ["q"] },
      { tool: "readNotes", name: "read_notes_k3", setup_reads: true, text_fields: ["text"] },
      { tool: "addNote", text_fields: ["text"] },
    ] };
    expect(sendingOutside(sendingProfileOf(key))).toEqual(["addNote", "create_draft_k3"]);
    expect(undisclosedSending(sendingOutside(sendingProfileOf(key)), ["create_draft_k3", "set_status_k3"], [])).toContain("disclose doesn't name create_draft_k3");
    expect(undisclosedSending(sendingOutside(sendingProfileOf(key)), ["create_draft_k3", "set_status_k3"], ["create_draft_k3"])).toBeUndefined();
    // Not a key connection: text_fields count for nothing, the profile alone does.
    const managed = { profile: { operations: [op("send_email", "sends", true)] }, tools: [{ tool: "x", name: "x_k3", text_fields: ["body"] }] };
    expect(sendingOutside(sendingProfileOf(managed))).toEqual(["send_email"]);
    for (const none of [undefined, null, "x", {}, { api: {} }, { api: {}, tools: "x" }]) expect(sendingOutside(sendingProfileOf(none))).toEqual([]);
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

  // agentgabo on #606: two Approves of the same taf at once (two phones, two connections) never lose an entry.
  it("keep both entries when two Approves of the same taf note at once", async () => {
    const store = memoryCoach(() => NOW);
    const noWait = async () => undefined;
    await Promise.all([
      noteSendingOutside(store, "t1", "rae", "g_mail", { operations: [op("send_email", "sends", true)] }, NOW, noWait),
      noteSendingOutside(store, "t1", "rae", "g_chat", { operations: [op("post", "sends", true)] }, NOW, noWait),
      noteSendingOutside(store, "t1", "rae", "g_sms", { operations: [op("send_sms", "sends", true)] }, NOW, noWait),
    ]);
    expect(await notedSendingOutside(store, "t1", "rae")).toEqual(["post", "send_email", "send_sms"]);
    // Every claim is given back.
    expect([...store.held.keys()].filter((k) => k.endsWith("#claim"))).toEqual([]);
  });

  it("wait for a claim another write holds, give up past their tries, and pass a claim a dead write left once it expires", async () => {
    let clock = NOW;
    const store = memoryCoach(() => clock);
    const key = `${sendsOutsideKey("t1", "rae")}#claim`;
    await store.once(key, new Date(NOW.getTime() + NOTE_CLAIM_MS));
    const waits: number[] = [];
    await expect(noteSendingOutside(store, "t1", "rae", "g_mail", { operations: [op("send_email", "sends", true)] }, NOW, async (ms) => { waits.push(ms); }))
      .rejects.toThrow("approve again");
    expect(waits).toEqual([50, 100, 150, 200, 250, 300]);
    expect(await notedSendingOutside(store, "t1", "rae")).toEqual([]);
    // The write that held it died: past its expiry, the next one goes in.
    clock = new Date(NOW.getTime() + NOTE_CLAIM_MS + 1);
    await noteSendingOutside(store, "t1", "rae", "g_mail", { operations: [op("send_email", "sends", true)] }, clock, async () => undefined);
    expect(await notedSendingOutside(store, "t1", "rae")).toEqual(["send_email"]);
  });

  it("leave a claim alone once the write took longer than its claim, since another write may hold it", async () => {
    const store = memoryCoach(() => NOW);
    const key = `${sendsOutsideKey("t1", "rae")}#claim`;
    let t = 0;
    // The write ran past its claim: the claim isn't given back, it expires on its own.
    const slow = () => { const at = t; t += NOTE_CLAIM_MS - NOTE_RELEASE_MARGIN_MS; return at; };
    await noteSendingOutside(store, "t1", "rae", "g_mail", { operations: [op("send_email", "sends", true)] }, NOW, async () => undefined, slow);
    expect(store.held.has(key)).toBe(true);
    expect(await notedSendingOutside(store, "t1", "rae")).toEqual(["send_email"]);
  });

  it("refuse a result whose disclose leaves one out, naming each, among the tools the taf has", () => {
    expect(undisclosedSending(["send_email"], ["list", "send_email"], ["send_email"])).toBeUndefined();
    expect(undisclosedSending([], ["send_email"], [])).toBeUndefined();
    // Not among the taf's tools: not the taf's to disclose.
    expect(undisclosedSending(["send_email"], ["list"], [])).toBeUndefined();
    const one = undisclosedSending(["send_email"], ["send_email"], []);
    expect(one).toContain("disclose doesn't name send_email, which reaches people outside the company");
    expect(one).toContain("add it to disclose");
    // No tools named: every noted one counts.
    expect(undisclosedSending(["post", "send_email"], undefined, ["post"])).toContain("disclose doesn't name send_email,");
    expect(undisclosedSending(["post", "send_email"], [], [])).toContain("disclose doesn't name post, send_email, which reach people");
  });
});
