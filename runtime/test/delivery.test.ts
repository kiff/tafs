import { describe, expect, it } from "vitest";
import { DELIVERY, DELIVERY_WORDS, handoffOf } from "../src/cloud/delivery.js";
import { type Deps, handle, type HTTPEvent, sweepDeliveries, type Taf } from "../src/cloud/handler.js";
import { stepWhyKey } from "../src/cloud/steps.js";
import { fakeAgents, memoryAccounts, memoryArchived, memoryClaims, memoryCoach, memoryKeys, memoryNames, memoryRemovals, memorySlots, memoryTafs } from "./fakes.js";

const ME = { tenant_id: "t_acme", subject: "u_ana", api_key: false, capabilities: ["read", "cards.manage"] };
const NOW = new Date("2026-10-16T12:00:00Z");
const RAE = "taf-aaaa0000000000000000";
const INV = "taf-cccc0000000000000000";
const SHEET = { place: "action" as const, grant: "grt_g", action: "sheets_append_k3", args: { sheet: "Refunds" }, name: "Google Sheets · Refunds 2026" };
const TO_INV = { place: "taf" as const, taf: INV, name: "Invoices" };

function kiff() {
  return (async (url: string, init?: RequestInit) => {
    const token = String((init?.headers as Record<string, string>)?.authorization ?? "").replace("Bearer ", "");
    if (token !== "ana") return new Response("{}", { status: 401 });
    if (url.endsWith("/v1/me")) return new Response(JSON.stringify(ME), { status: 200 });
    if (url.endsWith("/v1/me/access")) return new Response(JSON.stringify({ agents: { rae: { use: true, oversee: true, manage: true }, invoices: { use: true, oversee: true, manage: true } } }), { status: 200 });
    if (url.includes("/v1/me/mandates")) return new Response(JSON.stringify({ mandates: [], exceptions: [] }), { status: 200 });
    if (url.includes("/v1/me/card-exceptions")) return new Response(JSON.stringify({ exceptions: [] }), { status: 200 });
    if (url.includes("/v1/me/did-this-happen")) return new Response(JSON.stringify({ calls: [] }), { status: 200 });
    return new Response("{}", { status: 404 });
  }) as typeof fetch;
}

/** Rae, whose results go to a sheet and to Invoices, and Invoices, whose results go back to Rae (which never happens twice). */
function world(places: Taf["actionResults"] = [SHEET]) {
  let clock = NOW;
  const agents = fakeAgents(() => clock);
  const tafs = memoryTafs();
  for (const [id, name, agent, own] of [[RAE, "Rae", "rae", places], [INV, "Invoices", "invoices", [{ place: "taf" as const, taf: RAE, name: "Rae" }]]] as const) {
    tafs.held.set(`t_acme#${id}`, { id, name, job: "refunds", kiffAgent: agent, nameClaim: "c", created: "2026-10-15T09:00:00Z", actionResults: [...own] });
    void agents.api.putAgent("t_acme", id, {} as never);
    void agents.api.createSchedule("t_acme", { id, agent: id, cron: "*/30 * * * *", input: "Work the queue.", enabled: true });
  }
  agents.calls.length = 0;
  const coach = memoryCoach(() => clock);
  const pushed: Array<{ token: string; payload: unknown }> = [];
  const deps: Deps = {
    agents: agents.api, keys: memoryKeys(), accounts: memoryAccounts(), tafs, names: memoryNames(), slots: memorySlots(), claims: memoryClaims(),
    removals: memoryRemovals(), archived: memoryArchived(), coach,
    push: { get: async () => ({ devices: [{ token: "t1", platform: "ios", subject: "u_ana", manages: true, answers: true } as never], secrets: [] }), addDevice: async () => undefined, removeDevice: async () => undefined, addSecret: async () => undefined },
    apns: { send: async (d, n) => { pushed.push({ token: d.token, payload: n.payload }); return "sent" as const; } },
    forward: async () => undefined, fetch: kiff(), now: () => clock,
  };
  const call = async (method: string, path: string, body?: unknown) => {
    const event: HTTPEvent = { rawPath: path, requestContext: { http: { method } }, headers: { authorization: "Bearer ana" }, body: body === undefined ? undefined : JSON.stringify(body) };
    const r = await handle(event, deps);
    return { status: r.statusCode, body: JSON.parse(r.body) };
  };
  /** A job run of a taf that ended with this result, and the calls its usage records show. */
  const ran = async (taf: string, output: string, waited: Array<{ tool: string; is_error?: true }>, at = clock) => {
    const run = await agents.api.trigger("t_acme", taf);
    const list = agents.events.get(run.session)!;
    list.push({ session: run.session, run: run.id, seq: list.length + 1, time: at.toISOString(), kind: "usage", body: { cost_micro_usd: 1, timing: { ms: 900, waited: waited.map((w) => ({ ms: 10, ...w })) } } });
    agents.say(run, output, at.toISOString());
    Object.assign(run, { status: "COMPLETED", output });
    return run;
  };
  const ended = (id: string, output: string, waited: Array<{ tool: string; is_error?: true }>) => {
    const run = agents.runs.find((r) => r.id === id)!;
    const list = agents.events.get(run.session)!;
    list.push({ session: run.session, run: run.id, seq: list.length + 1, time: clock.toISOString(), kind: "usage", body: { cost_micro_usd: 1, timing: { ms: 900, waited: waited.map((w) => ({ ms: 10, ...w })) } } });
    Object.assign(run, { status: "COMPLETED", output });
  };
  /** What a run was asked, as the fake keeps it: its session's first message. */
  const inputOf = (run: { session: string; id: string }) => (agents.events.get(run.session) ?? []).find((e) => e.run === run.id && e.kind === "owner_message")?.body as { text?: string } | undefined;
  return { agents, tafs, coach, pushed, deps, call, ran, ended, inputOf, at: (d: Date) => { clock = d; } };
}

describe("a taf's results reach their destination, checked by tafs cloud (results to destination, step 2)", () => {
  it("reads the hand-off the taf ends its result with, under the last Hand-off heading", () => {
    expect(handoffOf("Refunded 3 orders.\n\nHand-off\nInvoice 12 paid, €40.")).toBe("Invoice 12 paid, €40.");
    expect(handoffOf("Refunded.\n## Hand-off:\n\nInvoice 12.\nCustomer Ana.\n")).toBe("Invoice 12.\nCustomer Ana.");
    expect(handoffOf("Refunded.\n**Hand-off**\nOne.\nHand-off\nTwo.")).toBe("Two.");
    expect(handoffOf("Refunded. Hand-off: nothing")).toBeUndefined();
    expect(handoffOf("Refunded.\nHand-off\n   ")).toBeUndefined();
    expect(handoffOf(undefined)).toBeUndefined();
  });

  it("says a place was delivered when the run's record shows the call went through, on the page and the request", async () => {
    const w = world();
    const run = await w.ran(RAE, "Refunded 3 orders.", [{ tool: "sheets_append_k3" }]);
    const work = await w.call("GET", `/v1/tafs/${RAE}/work`);
    expect(work.status).toBe(200);
    expect(work.body.requests[0].deliveries).toEqual([{ place: "action", key: "grt_g:sheets_append_k3", name: SHEET.name, state: "sent", line: `Delivered to ${SHEET.name} at 12:00` }]);
    const detail = await w.call("GET", `/v1/tafs/${RAE}/requests/${run.id}`);
    expect(detail.body.steps).toContainEqual({ label: "Delivered", line: `Delivered to ${SHEET.name} at 12:00`, state: "done" });
    // Settled once: a second read keeps it, and starts nothing.
    expect(w.agents.calls.filter((c) => c.startsWith("startRun"))).toEqual([]);
  });

  it("retries once itself after a short wait, then tells the managers once, with Send again and Coach's words", async () => {
    const w = world();
    const run = await w.ran(RAE, "Refunded 3 orders, but the sheet refused the row.", [{ tool: "sheets_append_k3", is_error: true }]);
    await w.coach.put(stepWhyKey("t_acme", run.id), [{ call: "c1", tool: "sheets_append_k3", why: "The sheet was renamed." }], new Date(NOW.getTime() + 3600_000));
    let work = await w.call("GET", `/v1/tafs/${RAE}/work`);
    expect(work.body.requests[0].deliveries).toEqual([{ place: "action", key: "grt_g:sheets_append_k3", name: SHEET.name, state: "retrying", line: `Not delivered to ${SHEET.name} yet: trying again at 12:10` }]);
    expect(w.agents.calls.filter((c) => c.startsWith("startRun"))).toEqual([]);
    expect(w.pushed).toEqual([]);
    // Due: the sweep starts the taf's own retry, a task with the result, by the action's id.
    w.at(new Date(NOW.getTime() + 11 * 60_000));
    expect(await sweepDeliveries(w.deps)).toEqual([{ run: run.id, waiting: true }]);
    const retry = w.agents.runs.find((r) => r.source === `delivery:${run.id}:grt_g:sheets_append_k3`)!;
    expect(retry).toBeDefined();
    const asked = w.inputOf(retry)?.text ?? "";
    expect(asked).toContain("the place your instructions name as sheets_append_k3");
    expect(asked).toContain("Refunded 3 orders, but the sheet refused the row.");
    expect(asked).not.toContain("Google Sheets");
    work = await w.call("GET", `/v1/tafs/${RAE}/work`);
    expect(work.body.requests.find((r: { id: string }) => r.id === run.id).deliveries[0]).toMatchObject({ state: "retrying", line: `Not delivered to ${SHEET.name} yet: trying again now` });
    // The retry fails too: not delivered, one silent note to the managers, Send again and Coach on the request.
    w.at(new Date(NOW.getTime() + 15 * 60_000));
    w.ended(retry.id, "The sheet still refused it.", [{ tool: "sheets_append_k3", is_error: true }]);
    await w.coach.put(stepWhyKey("t_acme", retry.id), [{ call: "c1", tool: "sheets_append_k3", why: "The sheet was renamed." }], new Date(NOW.getTime() + 3600_000));
    work = await w.call("GET", `/v1/tafs/${RAE}/work`);
    const d = work.body.requests.find((r: { id: string }) => r.id === run.id).deliveries[0];
    expect(d).toEqual({ place: "action", key: "grt_g:sheets_append_k3", name: SHEET.name, state: "not_sent", line: `Not delivered to ${SHEET.name}: The sheet was renamed.`,
      send_again: true, open_coach: true, coach_words: `Rae didn't deliver its result to ${SHEET.name}: The sheet was renamed. Find out why and propose the fix as a version of its dial.` });
    expect(w.pushed).toHaveLength(1);
    expect(JSON.stringify(w.pushed[0]!.payload)).toContain(`Rae didn't deliver to ${SHEET.name}: The sheet was renamed. Send it again from Rae's page, or open Coach for help.`);
    // The retry's own request shows no deliveries of its own, and nothing is noted twice.
    expect(work.body.requests.find((r: { id: string }) => r.id === retry.id).deliveries).toEqual([]);
    await w.call("GET", `/v1/tafs/${RAE}/work`);
    expect(w.pushed).toHaveLength(1);
    // Send again, by hand: the same task now; delivered on this try, the note clears.
    const again = await w.call("POST", `/v1/tafs/${RAE}/requests/${run.id}/deliveries/${encodeURIComponent("grt_g:sheets_append_k3")}/send`);
    expect(again.status).toBe(200);
    expect(again.body).toEqual({ state: "retrying" });
    const second = w.agents.runs.filter((r) => r.source === `delivery:${run.id}:grt_g:sheets_append_k3`)[0]!; // newest first
    expect(second.id).not.toBe(retry.id);
    expect((await w.call("POST", `/v1/tafs/${RAE}/requests/${run.id}/deliveries/${encodeURIComponent("grt_g:sheets_append_k3")}/send`)).status).toBe(400);
    w.ended(second.id, "Added the row.", [{ tool: "sheets_append_k3" }]);
    work = await w.call("GET", `/v1/tafs/${RAE}/work`);
    expect(work.body.requests.find((r: { id: string }) => r.id === run.id).deliveries[0]).toMatchObject({ state: "sent", line: `Delivered to ${SHEET.name} at 12:15` });
    expect(await w.coach.get(`t_acme#delivery-note#${RAE}#grt_g:sheets_append_k3`)).toBeUndefined();
    expect(DELIVERY.retryMinutes).toBe(10);
  });

  it("hands the result to another taf as its work, once, and never a hand-off on", async () => {
    const w = world([TO_INV]);
    const run = await w.ran(RAE, "Refunded 3 orders.\n\nHand-off\nInvoice 12 is paid: €40, customer Ana. Mark it paid.", []);
    const work = await w.call("GET", `/v1/tafs/${RAE}/work`);
    expect(work.body.requests[0].deliveries).toEqual([{ place: "taf", key: `taf:${INV}`, name: "Invoices", state: "handed", line: "Handed to Invoices at 12:00" }]);
    const handed = w.agents.runs.find((r) => r.agent === INV && r.source === `handoff:${RAE}:${run.id}`)!;
    expect(handed).toBeDefined();
    expect(w.inputOf(handed)?.text).toBe("Rae handed you this result as your work. Take it on under your own rules and Card:\n\nInvoice 12 is paid: €40, customer Ana. Mark it paid.");
    // Read again: handed once.
    await w.call("GET", `/v1/tafs/${RAE}/work`);
    expect(w.agents.runs.filter((r) => r.agent === INV)).toHaveLength(1);
    // Invoices' results go to Rae, but a run that came as a hand-off hands nothing on.
    w.ended(handed.id, "Marked paid.\n\nHand-off\nBack to you.", []);
    const inv = await w.call("GET", `/v1/tafs/${INV}/work`);
    expect(inv.body.requests[0].deliveries).toEqual([]);
    expect(w.agents.runs.filter((r) => r.agent === RAE)).toHaveLength(1);
    // A result with no hand-off section isn't handed: said, with Coach to open and nothing to send again.
    const bare = await w.ran(RAE, "Refunded 2 orders.", []);
    const again = await w.call("GET", `/v1/tafs/${RAE}/work`);
    expect(again.body.requests.find((r: { id: string }) => r.id === bare.id).deliveries).toEqual([{ place: "taf", key: `taf:${INV}`, name: "Invoices", state: "not_sent",
      line: `Not delivered to Invoices: ${DELIVERY_WORDS.noHandoff}`, open_coach: true, coach_words: DELIVERY_WORDS.coach("Rae", "Invoices", DELIVERY_WORDS.noHandoff) }]);
    // A receiver removed meanwhile: said.
    w.tafs.held.delete(`t_acme#${INV}`);
    const gone = await w.ran(RAE, "Refunded.\n\nHand-off\nOne more.", []);
    const after = await w.call("GET", `/v1/tafs/${RAE}/work`);
    expect(after.body.requests.find((r: { id: string }) => r.id === gone.id).deliveries[0]).toMatchObject({ state: "not_sent", line: `Not delivered to Invoices: ${DELIVERY_WORDS.tafGone}` });
  });
});
