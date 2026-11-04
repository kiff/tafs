import { describe, expect, it } from "vitest";
import { appCalls, appDisplayName, runningTool, setupGrants, setupView, type GrantView } from "../src/cloud/taf-setup.js";
import { avatarRim } from "../src/cloud/home.js";
import { knobOf } from "../src/cloud/knob.js";
import type { Run, SessionEvent, VersionState } from "../src/cloud/agents.js";

const now = new Date("2026-11-01T12:00:00Z");
const grant: GrantView = { id: "grant-example", vendor: "example-mcp", status: "connected", request: { agent: "agent-example", tools: [{ tool: "read_case", name: "read_case", title: "Read a case" }] } };
const knob = knobOf({ name: "Example", does: { label: "Read", line: "Read cases" }, unscheduled: true, doors: [], places: ["door_reply"],
  setting: { wakes: [], busy: "skip", results: ["door_reply"], what: [{ label: "Read", words: "Read a case", tool: "read_case" }] },
  zone: "UTC", now, today: { done: 0, working: 0, retrying: 0, needs_you: 0 } });

describe("a taf's server-composed setup", () => {
  it("supplies the same door rim state to both apps", () => {
    expect(avatarRim([]).visible).toBe(false);
    expect(avatarRim([{ open: true, resting: true }])).toMatchObject({ visible: true, tint: "resting", length: 0.22 });
    expect(avatarRim([{ open: true, knocks: 1 }]).tint).toBe("knock");
    expect(avatarRim(Array.from({ length: 10 }, () => ({ open: true }))).length).toBe(0.6);
  });
  it("resolves app names and groups actions, workflow, seven-day counts and summaries once", () => {
    const view = setupView({ agent: "agent-example", grants: [grant, { ...grant, id: "other", request: { agent: "another-agent" } }], knob, rules: [],
      versions: { versions: [{ n: 1 }, { n: 2, live: true }] } as VersionState, notes: [{ content: "Newest note" }], now, running: "read_case", statsComplete: true,
      calls: [{ proposal_id: "p-example", actor_id: "agent-example", action_name: "read_case", outcome: "allowed", tool_outcome: { status: "done" }, created_at: now.toISOString() },
        { proposal_id: "old", actor_id: "agent-example", action_name: "read_case", outcome: "owner_required", created_at: "2026-10-01T12:00:00Z" }] });
    expect(view.connections).toHaveLength(1);
    expect(view.connections[0]).toMatchObject({ name: "Example", action_count: 1, steps: [1], running: true, stats: { calls: 1, done: 1, held: 0, complete: true } });
    expect(view.workflow).toMatchObject({ summary: "1 step · Example", running_step: 1 });
    expect(view.memory).toMatchObject({ count: 1, latest: "Newest note" });
    expect(view.versions.summary).toBe("v2 live · 1 earlier");
  });
  it("sends a step's words empty when they only repeat its label", () => {
    const same = knobOf({ name: "Example", does: { label: "Read", line: "Read cases" }, unscheduled: true, doors: [], places: ["door_reply"],
      setting: { wakes: [], busy: "skip", results: ["door_reply"], what: [{ label: "Read a case", words: "Read a case.", tool: "read_case" }, { label: "Read", words: "Read a case", tool: "read_case" }] },
      zone: "UTC", now, today: { done: 0, working: 0, retrying: 0, needs_you: 0 } });
    const view = setupView({ agent: "agent-example", grants: [grant], knob: same, rules: [], versions: { versions: [] } as VersionState, notes: [], now, calls: [], statsComplete: true });
    expect(view.workflow.steps.map((s) => s.words)).toEqual(["", "Read a case"]);
    expect(view.connections[0]!.workflow_steps[0]!.words).toBe("");
  });
  it("keeps vendor-supplied display names and removes a server-id suffix only at the source", () => {
    expect(appDisplayName({ ...grant, vendor: "Example Suite" })).toBe("Example Suite");
    expect(appDisplayName(grant)).toBe("Example");
  });
  it("keeps held sign-ins visible without claiming their unapproved actions are usable", () => {
    const args = { agent: "agent-example", knob, rules: [], versions: { versions: [] } as VersionState, notes: [], now, calls: [], statsComplete: true };
    const view = setupView({ ...args, grants: [{ ...grant, status: "pending", signed_in: true }, { ...grant, id: "failed", status: "failed" }] });
    expect(view.connections).toHaveLength(1);
    expect(view.connections[0]).toMatchObject({ action_count: 0, action_words: "Choose actions", actions: [] });
    expect(view.workflow.steps[0]?.grant).toBeUndefined();
    const reconnect = setupView({ ...args, grants: [{ ...grant, status: "needs_reconnect", status_words: "Signed out by the app" }] });
    expect(reconnect.connections[0]).toMatchObject({ reconnect: true, status_words: "Signed out by the app" });
    expect(appDisplayName({ ...grant, vendor: undefined })).toBe("Connected app");
  });
  it("marks a step running only while its tool call has no result", () => {
    const run = { id: "run-example", status: "RUNNING" } as Run;
    const events = [{ run: run.id, kind: "tool_call", body: { call_id: "call-example", tool: "read_case" } }] as SessionEvent[];
    expect(runningTool(events, run)).toBe("read_case");
    expect(runningTool([...events, { run: run.id, kind: "tool_result", body: { call_id: "call-example" } } as SessionEvent], run)).toBeUndefined();
    expect(runningTool(events, { ...run, status: "COMPLETED" })).toBeUndefined();
  });
  it("says statistics are incomplete when their source fails", async () => {
    const net = (async () => { throw new Error("source unavailable"); }) as typeof fetch;
    expect(await appCalls("synthetic-session", "agent-example", now, net, "https://api.example")).toEqual({ calls: [], complete: false });
  });
  it("forwards the phone's read proof without signing or granting anything", async () => {
    const proof = "t=synthetic,v1=synthetic,d=device-example";
    const net = (async (url: string | URL | Request, init?: RequestInit) => {
      expect(String(url)).toBe("https://planque.example/v1/accounts/account-example/grants");
      expect(init?.headers).toMatchObject({ "Planque-Device-Signature": proof });
      expect(init?.body).toBeUndefined();
      return new Response(JSON.stringify({ grants: [grant] }));
    }) as typeof fetch;
    expect(await setupGrants("https://planque.example", "account-example", proof, net)).toEqual([grant]);
    await expect(setupGrants("https://planque.example", "account-example", "", net)).rejects.toThrow();
  });
});
