import { describe, expect, it } from "vitest";
import { appCalls, appDisplayName, runningTool, setupGrants, setupView, type GrantView } from "../src/cloud/taf-setup.js";
import { avatarRim, eachItemWords, summary } from "../src/cloud/home.js";
import { FULL_LANGUAGES } from "../src/cloud/words/index.js";
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
  it("keeps step references short and collapses repeated apps without hiding a return to one", () => {
    const other: GrantView = { ...grant, id: "grant-second", vendor: "Second App", request: { agent: "agent-example", tools: [{ tool: "send_case", title: "Send a case" }] } };
    const recipe = ["read_case", "read_case", "send_case", "send_case", "read_case"].map((tool, i) => ({ label: `Action ${i + 1}`, words: "Do the next action", tool }));
    const composed = knobOf({ name: "Example", does: { label: "Read", line: "Read cases" }, unscheduled: true, doors: [], places: ["app"],
      setting: { wakes: [], busy: "skip", results: ["app"], what: recipe }, zone: "UTC", now, today: { done: 0, working: 0, retrying: 0, needs_you: 0 } });
    const view = setupView({ agent: "agent-example", grants: [grant, other], knob: composed, rules: [], versions: { versions: [] }, notes: [], now, calls: [], statsComplete: true });
    expect(view.workflow.summary).toBe("5 steps · Example → Second App → Example");
    expect(view.connections[0]!.actions[0]!.step_words).toBe("Step 1 · Step 2 · Step 5");
    expect(view.workflow.steps.map((s) => s.label)).toEqual(recipe.map((s) => s.label));
    expect(view.versions).toMatchObject({ summary: "Runs its original definition", earlier: 0 });
    expect(view.versions.live).toBeUndefined();
  });
  it("does not claim an action is unused when the taf has no saved recipe", () => {
    const missing = knobOf({ name: "Example", does: { label: "Read", line: "Read cases" }, unscheduled: true, doors: [], places: ["app"], tools: new Map([["read_case", "Read a case"]]), zone: "UTC", now, today: { done: 0, working: 0, retrying: 0, needs_you: 0 } });
    const two: GrantView = { ...grant, request: { agent: "agent-example", tools: [...grant.request.tools!, { tool: "send_case", name: "send_case", title: "Send a case" }] } };
    const view = setupView({ agent: "agent-example", grants: [two], knob: missing, rules: [], versions: { versions: [] }, notes: [], now, calls: [], statsComplete: true });
    expect(view.workflow).toMatchObject({ steps: [], summary: "Ask Coach to set its workflow" });
    // Said once, as the app's own Workflow line, not on an action (kiff/tafs#469 item 2).
    expect(view.connections[0]!.actions.map((a) => a.step_words)).toEqual(["", ""]);
    expect(view.connections[0]!.workflow_words).toBe("Ask Coach to set its workflow");
  });
  it("distinguishes a forbidden zero-count Card from one that asks for every call", () => {
    const rules = (outside: "refuse" | "hold") => summary([{ id: "card-example", subject: "agent-example", active: true,
      grants: [{ action: "read_case" }], aggregates: [{ quantity: "count", limit: 0, window: "calendar_day" }], on_exceed: outside }]).rules;
    const args = { agent: "agent-example", grants: [grant], knob, versions: { versions: [] }, notes: [], now, calls: [], statsComplete: true };
    expect(setupView({ ...args, rules: rules("refuse") }).connections[0]!.actions[0]!.limits).toEqual(["Not allowed by this Card"]);
    expect(setupView({ ...args, rules: rules("hold") }).connections[0]!.actions[0]!.limits).toEqual(["Needs your approval for every call"]);
  });
  // A Spanish, German or Polish owner read the Setup page's limits, steps and "See its Card" in English. Every line it
  // makes is in the person's language now, and no line outside English reads as the English one.
  it("says an action's limits, its steps and its Card's each-item totals in the person's language", () => {
    // A refund with its limits, a read held each time, and an untitled action on no Card.
    const tools = [{ tool: "refund", name: "refund", title: "Refund a charge" }, { tool: "read_case", name: "read_case", title: "Read a case" }, { tool: "note", name: "note", title: "" }];
    const two: GrantView = { ...grant, request: { agent: "agent-example", tools } };
    const cards = [{ id: "card-refund", subject: "agent-example", active: true, on_exceed: "owner",
      grants: [{ action: "refund", per_action_max: [{ quantity: "sum(amount)", max: 500, unit: "EUR", scale: 2 }] }],
      aggregates: [{ quantity: "sum(amount)", limit: 5000, window: "calendar_day", unit: "EUR", scale: 2 }, { quantity: "count", limit: 1, window: "calendar_day" }] },
    { id: "card-held", subject: "agent-example", active: true, on_exceed: "owner", grants: [{ action: "read_case" }], aggregates: [{ quantity: "count", limit: 0, window: "calendar_day" }] }];
    const steps = knobOf({ name: "Example", does: { label: "Refund", line: "Refunds" }, unscheduled: true, doors: [], places: ["door_reply"],
      setting: { wakes: [], busy: "skip", results: ["door_reply"], what: [{ label: "Refund", words: "Refund a charge", tool: "refund" }] },
      zone: "UTC", now, today: { done: 0, working: 0, retrying: 0, needs_you: 0 } });
    const lines = (lang: (typeof FULL_LANGUAGES)[number]) => {
      const view = setupView({ agent: "agent-example", grants: [two], knob: steps, rules: summary(cards, lang).rules, versions: { versions: [] }, notes: [], now, calls: [], statsComplete: true, lang });
      const actions = view.connections[0]!.actions;
      return [...actions.flatMap((a) => a.limits), ...actions.map((a) => a.step_words), actions.find((a) => a.name === "note")!.title,
        eachItemWords({ quantity: "count", limit: 1, window: "rolling_7d", per: "invoice_id" }, lang),
        eachItemWords({ quantity: "sum(amount)", limit: 10000, window: "calendar_day", per: "order", unit: "EUR", scale: 2 }, lang)];
    };
    const en = lines("en");
    expect(en).toEqual(["€5.00 per action", "€50.00 a day", "1 call a day", "Needs your approval for every call", "See its Card",
      "Step 1", "Not used by the current workflow", "Not used by the current workflow", "App action",
      "1 call in any 7 days for each invoice", "€100.00 in total a day for each order"]);
    for (const lang of FULL_LANGUAGES.filter((l) => l !== "en")) {
      const said = lines(lang);
      expect(said, lang).toHaveLength(en.length);
      said.forEach((line, i) => expect(line, `${lang}: ${en[i]}`).not.toBe(en[i]));
    }
    // Polish counts in three forms.
    const pl = (n: number) => eachItemWords({ quantity: "count", limit: n, window: "calendar_day", per: "order" }, "pl");
    expect([pl(1), pl(2), pl(5)]).toEqual(["1 wywołanie dziennie na każde zamówienie", "2 wywołania dziennie na każde zamówienie", "5 wywołań dziennie na każde zamówienie"]);
    // The item in each language from tafs cloud's words, never the vendor's argument in English; a plain word for any other.
    const day = (per: string) => (lang: (typeof FULL_LANGUAGES)[number]) => eachItemWords({ quantity: "count", limit: 1, window: "calendar_day", per }, lang);
    expect(day("payment_intent")("es")).toBe("1 llamada al día por cada pago");
    expect(day("Payment_Intent_ID")("en")).toBe("1 call a day for each payment");
    expect(day("ticket_ref")("en")).toBe("1 call a day for each item");
    expect(day("paid")("en")).toBe("1 call a day for each item");
    for (const lang of FULL_LANGUAGES) expect(day("payment_intent")(lang), lang).not.toMatch(/payment.intent|_/i);
  });
  // A Spanish owner's Setup read "Workflow · Ask Coach to set its workflow" and "0 notes": the page's own lines, its
  // workflow, notes, actions, connection status, seven days and versions, are in the person's language too.
  it("says the Setup page's own lines in the person's language", () => {
    const none = knobOf({ name: "Example", does: { label: "Read", line: "Read cases" }, unscheduled: true, doors: [], places: ["app"], tools: new Map([["read_case", "Read a case"]]), zone: "UTC", now, today: { done: 0, working: 0, retrying: 0, needs_you: 0 } });
    const lines = (lang: (typeof FULL_LANGUAGES)[number]) => {
      const set = setupView({ agent: "agent-example", grants: [grant], knob, rules: [], versions: { versions: [{ n: 1 }, { n: 2, live: true }] } as VersionState,
        notes: [{ content: "A note" }, { content: "Another" }], now, calls: [], statsComplete: true, lang });
      const unset = setupView({ agent: "agent-example", grants: [grant, { ...grant, id: "held", status: "pending", signed_in: true }], knob: none, rules: [],
        versions: { versions: [] }, notes: [], now, calls: [], statsComplete: false, lang });
      return [set.workflow.title, set.workflow.summary, set.memory.title, set.connections[0]!.action_words, set.connections[0]!.status_words,
        set.connections[0]!.stats.words, set.versions.title, set.versions.summary,
        unset.workflow.summary, unset.connections[0]!.workflow_words!, unset.memory.title, unset.connections[1]!.action_words,
        unset.connections[1]!.status_words, unset.connections[0]!.stats.words, unset.versions.summary];
    };
    const en = lines("en");
    expect(en).toEqual(["Workflow", "1 step · Example", "2 notes", "1 action", "Connected", "0 calls · 0 done · 0 held in the last 7 days",
      "Changes to how it runs", "v2 live · 1 earlier", "Ask Coach to set its workflow", "Ask Coach to set its workflow", "0 notes",
      "Choose actions", "Signed in · actions waiting for approval", "The last 7 days could not be read in full; try again", "Runs its original definition"]);
    for (const lang of FULL_LANGUAGES.filter((l) => l !== "en")) {
      const said = lines(lang);
      expect(said, lang).toHaveLength(en.length);
      // "note", "notes" and "action" are French words too (as in words.test.ts).
      said.forEach((line, i) => lang === "fr" && [2, 3, 10].includes(i) ? undefined : expect(line, `${lang}: ${en[i]}`).not.toBe(en[i]));
    }
    const es = lines("es");
    expect([es[0], es[8], es[10]]).toEqual(["Flujo de trabajo", "Preguntar a Coach para que defina su flujo de trabajo", "0 notas"]);
    // Polish counts in three forms.
    const notes = (n: number) => setupView({ agent: "agent-example", grants: [], knob, rules: [], versions: { versions: [] },
      notes: Array.from({ length: n }, () => ({ content: "x" })), now, calls: [], statsComplete: true, lang: "pl" }).memory.title;
    expect([notes(1), notes(2), notes(5)]).toEqual(["1 notatka", "2 notatki", "5 notatek"]);
  });
  // The rest of the page's lines: who keeps the sign-in, a browser connection's own sign-in, and an app with no name.
  it("says who keeps a sign-in, a site's sign-in and an unnamed app in the person's language", () => {
    const lines = (lang: (typeof FULL_LANGUAGES)[number]) => {
      const view = (g: GrantView) => setupView({ agent: "agent-example", grants: [g], knob, rules: [], versions: { versions: [] }, notes: [], now, calls: [], statsComplete: true, lang }).connections[0]!;
      const provider = view({ ...grant, custody: "provider" }), planque = view({ ...grant, custody: "planque" }), browser = view({ ...grant, provider: "browser" });
      return [provider.custody_words!, planque.custody_words!, browser.site_sign_in!.title, browser.site_sign_in!.words, appDisplayName({ ...grant, vendor: undefined }, lang)];
    };
    const en = lines("en");
    expect(en).toEqual(["Your sign-in is kept by our sign-in partner; the taf never sees it.", "Your sign-in is kept sealed by tafs; the taf never sees it.",
      "Sign in to a site", "You sign in yourself, in a live view of the taf's browser. What you type goes to the site, never to the taf. Tap Done when you're in.", "Connected app"]);
    for (const lang of FULL_LANGUAGES.filter((l) => l !== "en")) {
      const said = lines(lang);
      said.forEach((line, i) => expect(line, `${lang}: ${en[i]}`).not.toBe(en[i]));
    }
    expect(lines("es")[3]).toContain("Toca Hecho");
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
    // Who holds the sign-in, in one line as planque says it, and whether the app was set up on the owner's pick (kiff/planque#87).
    const managed = setupView({ ...args, grants: [{ ...grant, custody: "provider", on_demand: true }] });
    expect(managed.connections[0]).toMatchObject({ custody_words: "Your sign-in is kept by our sign-in partner; the taf never sees it.", on_demand: true });
    const own = setupView({ ...args, grants: [{ ...grant, custody: "planque" }] });
    expect(own.connections[0]).toMatchObject({ custody_words: "Your sign-in is kept sealed by tafs; the taf never sees it." });
    expect(own.connections[0]).not.toHaveProperty("on_demand");
    expect(view.connections[0]).not.toHaveProperty("custody_words");
    // Which account and mode, one line as planque words them; a part it doesn't send is left out, and with neither, no line.
    const account = setupView({ ...args, grants: [{ ...grant, account_name: "Demo  Shop\n", mode_words: "Mode not known yet: treated as live" }] });
    expect(account.connections[0]).toMatchObject({ account_words: "Demo Shop · Mode not known yet: treated as live" });
    const nameOnly = setupView({ ...args, grants: [{ ...grant, account_name: "Demo Shop", mode_words: " " }] });
    expect(nameOnly.connections[0]).toMatchObject({ account_words: "Demo Shop" });
    expect(view.connections[0]).not.toHaveProperty("account_words");
    // A vendor's long account name is cut to one short line.
    const long = setupView({ ...args, grants: [{ ...grant, account_name: "x".repeat(200), mode_words: "Live" }] });
    expect(long.connections[0]?.account_words).toBe("x".repeat(59) + "… · Live");
    // A connected browser connection offers its own sign-in to a site, in tafs cloud's words; no other connection does.
    const browser = setupView({ ...args, grants: [{ ...grant, provider: "browser" }] });
    expect(browser.connections[0]).toMatchObject({ site_sign_in: { title: "Sign in to a site", words: expect.stringContaining("never to the taf") } });
    expect(own.connections[0]).not.toHaveProperty("site_sign_in");
    const signedOut = setupView({ ...args, grants: [{ ...grant, provider: "browser", status: "needs_reconnect" }] });
    expect(signedOut.connections[0]).not.toHaveProperty("site_sign_in");
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
