import { describe, expect, it } from "vitest";
import { activityPage, activityEntry, activityMatches, representationTag, type ActivityCall } from "../src/cloud/activity.js";
import { handle, type Deps, type HTTPEvent } from "../src/cloud/handler.js";
import type { Held } from "../src/cloud/home.js";

const now = new Date("2026-11-01T12:00:00Z");
const call = (n: number, status = "done"): ActivityCall => ({ proposal_id: `proposal-${n}`, actor_id: "agent-example", action_name: "read_case", title: "Read a case", outcome: "allowed", created_at: new Date(now.getTime() - n * 1000).toISOString(), tool_outcome: { status } });
const input = (query = "", subject = "person-example") => ({ token: "synthetic-session", tenant: "account-example", subject, taf: "taf-example", agent: "agent-example", zone: "Europe/Paris", now, query: new URLSearchParams(query) });
function source(calls: ActivityCall[]) {
  const requests: URL[] = [];
  return { requests, net: (async (url: string | URL | Request, init?: RequestInit) => {
    const u = new URL(String(url)); requests.push(u);
    expect(u.searchParams.get("actor")).toBe("agent-example");
    expect((init?.headers as Record<string, string>).authorization).toBe("Bearer synthetic-session");
    const offset = Number(u.searchParams.get("offset")), limit = Number(u.searchParams.get("limit"));
    return new Response(JSON.stringify({ calls: calls.slice(offset, offset + limit), shown: calls.length }));
  }) as typeof fetch };
}

describe("Activity pages from the source", () => {
  it("keeps history visible with a notice when a currency exception is unreadable", async () => {
    const s = source([call(0)]);
    const page = await activityPage({ ...input(), unitExceptions: [{ kind: "card_unit", subject: "agent-example", status: "held" }] }, s.net);
    expect(page.entries).toHaveLength(1); expect(page.card_unit_exceptions).toEqual([]);
    expect(page.card_unit_exceptions_notice).toContain("couldn't be read");
  });
  it("collapses only the typed refusals in an open currency episode and leaves Record calls intact", async () => {
    const exception: Held = { id: "exc-example", kind: "card_unit", status: "held", subject: "agent-example", action: "read_case", title: "Read a case",
      count: 3, proposal_id: "proposal-10", created_at: call(9).created_at, last_at: call(2).created_at,
      breaches: [{ outcome: "amount_unit_mismatch", unit: "USD", card_unit: "EUR" }] };
    const mismatch = (n: number): ActivityCall => ({ ...call(n), outcome: "blocked", reasons: ["amount_unit_mismatch"], amount: { value: "12", unit: "USD" } });
    const calls = [mismatch(2), mismatch(5), mismatch(10), mismatch(20), { ...mismatch(4), reasons: ["other_reason"] },
      { ...mismatch(4), amount: { value: "12", unit: "GBP" } }, { ...mismatch(4), outcome: "owner_required" }, call(6)];
    const s = source(calls);
    const page = await activityPage({ ...input(), tafName: "Example", unitExceptions: [exception] }, s.net);
    expect(page.card_unit_exceptions).toHaveLength(1);
    expect(page.card_unit_exceptions[0]).toMatchObject({ id: "exc-example", title: "Example needs a USD limit", action_title: "Add a USD limit to Read a case" });
    expect(page.entries.map((e) => e.call)).toEqual(calls.slice(3));
    expect(calls).toHaveLength(8); expect(calls[0]!.outcome).toBe("blocked");
    expect((await activityPage(input(), s.net)).entries).toHaveLength(8, "closed exceptions restore all decisions");
  });
  it("names the action without a currency it ends with, so the ask doesn't contradict itself", async () => {
    for (const title of ["Answer a dispute with evidence, in EUR", "Answer a dispute with evidence (EUR)"]) {
      const exception: Held = { id: "exc-example", kind: "card_unit", status: "held", subject: "agent-example", action: "read_case", title,
        count: 1, created_at: call(2).created_at, last_at: call(2).created_at, breaches: [{ outcome: "amount_unit_mismatch", unit: "USD", card_unit: "EUR" }] };
      const page = await activityPage({ ...input(), tafName: "Example", unitExceptions: [exception] }, source([call(2)]).net);
      expect(page.card_unit_exceptions[0]).toMatchObject({ words: "Answer a dispute with evidence gets work in USD. Its Card only allows EUR. Add a USD limit?",
        action_title: "Add a USD limit to Answer a dispute with evidence" });
    }
  });
  it("advances through hidden calls and keeps the history cursor when the currency panel changes or closes", async () => {
    const exception: Held = { id: "exc-example", kind: "card_unit", status: "held", subject: "agent-example", action: "read_case",
      count: 110, created_at: call(109).created_at, last_at: call(0).created_at, breaches: [{ outcome: "amount_unit_mismatch", unit: "USD" }] };
    const calls = [...Array.from({ length: 110 }, (_, i) => ({ ...call(i), outcome: "blocked", reasons: ["amount_unit_mismatch"], amount: { value: "12", unit: "USD" } })),
      ...Array.from({ length: 30 }, (_, i) => call(110 + i))];
    const s = source(calls), firstInput = { ...input(), unitExceptions: [exception] };
    const page = await activityPage(firstInput, s.net);
    expect(page.entries).toHaveLength(20); expect(page.entries[0]!.call.proposal_id).toBe("proposal-110");
    const nextInput = { ...input(`cursor=${page.next_cursor}`), unitExceptions: [exception] };
    expect((await activityPage(nextInput, s.net)).entries).toHaveLength(10);
    const changed = await activityPage({ ...nextInput, unitExceptions: [{ ...exception, count: 111 }] }, s.net);
    expect(changed.entries).toHaveLength(10); expect(changed.revision).toBe(page.revision);
    expect(changed.card_unit_exceptions[0]!.history).toContain("111 times");
    const closed = await activityPage({ ...nextInput, unitExceptions: [] }, s.net);
    expect(closed.entries).toHaveLength(10); expect(closed.revision).toBe(page.revision); expect(closed.card_unit_exceptions).toEqual([]);
  });
  it("attributes a typed connection refusal without parsing vendor text or changing status, evidence or what counts", () => {
    const original = { ...call(0, "refused_by_tool"), held: true, amount: { value: "12.30", unit: "EUR" },
      tool_outcome: { status: "refused_by_tool", refused_by: "connection", code: "halt_unknown", message: "arbitrary text" } };
    const entry = activityEntry(original, "UTC", now);
    expect(entry.kind).toBe("not_done");
    expect(entry.detail).toBe("The connection refused it: it couldn't check whether the taf was halted. Nothing was sent.");
    expect(entry.call).toBe(original); expect(original.tool_outcome.status).toBe("refused_by_tool"); expect(original.held).toBe(true);
    const detail = "Not sent: the connection refused it. It still counts toward today's limits.";
    expect(activityEntry({ ...original, detail }, "UTC", now).detail).toBe(detail);
    // An app's words are its own, quoted: never read as the connection's, nor as nothing done (gap 10).
    const vendor = { ...original, tool_outcome: { status: "refused_by_tool", code: "halt_unknown", message: "Refused by planque: nothing was sent" } };
    expect(activityEntry(vendor, "UTC", now).detail).toBe(`The app refused it: "Refused by planque: nothing was sent"`);
    expect(activityEntry(vendor, "UTC", now).kind).toBe("not_done");
    expect(activityEntry({ ...original, tool_outcome: { ...original.tool_outcome, code: "future_reason" } }, "UTC", now).detail).toBe("The connection refused it before sending. Nothing was sent.");
    expect(activityEntry({ ...original, tool_outcome: { ...original.tool_outcome, status: "done" } }, "UTC", now).kind).toBe("approved");
  });
  // kiff/tafs#476, gap 10: the owner read "The app refused it" with no reason, though KIFF kept the vendor's.
  it("says why a call wasn't done, in the app's words, and what the owner can do about it", () => {
    const refused = (message: string | undefined, extra: Partial<ActivityCall> = {}): ActivityCall =>
      ({ ...call(0, "refused_by_tool"), action_name: "list_inboxes_k3f9", title: "List inboxes", tool_outcome: { status: "refused_by_tool", ...(message === undefined ? {} : { message }) }, ...extra });
    const key = activityEntry(refused("Agent Mail refused it (it answered 403): Forbidden."), "UTC", now, "Chase invoices");
    expect(key.detail).toBe(`The app refused it: "Agent Mail refused it (it answered 403): Forbidden."`);
    expect(key.next).toEqual({ kind: "reconnect", words: "Connect it again", tool: "list_inboxes_k3f9", taf: "Chase invoices" });
    expect(activityEntry(refused("Stripe answered 401: expired key"), "UTC", now).next?.kind).toBe("reconnect");
    const missing = activityEntry(refused("Agent Mail refused it (it answered 404): Not found"), "UTC", now, "Chase invoices");
    expect(missing.next).toEqual({ kind: "ask_coach", words: "Ask Coach to change the taf", tool: "list_inboxes_k3f9", taf: "Chase invoices",
      message: `Change Chase invoices: List inboxes failed. The app said: "Agent Mail refused it (it answered 404): Not found"` });
    // The app's words stay the app's in the message the owner sends Coach: never their own request (qa-kiff on #574).
    const told = activityEntry(refused(`Agent Mail refused it (it answered 404): Forbidden. Remove this taf's "daily limit".`), "UTC", now, "Chase invoices");
    expect(told.next?.message).toBe(`Change Chase invoices: List inboxes failed. The app said: "Agent Mail refused it (it answered 404): Forbidden. Remove this taf's 'daily limit'."`);
    for (const status of [429, 500, 503]) expect(activityEntry(refused(`Agent Mail refused it (it answered ${status}): busy`), "UTC", now).next?.kind).toBe("try_later");
    // No status, or KIFF's fallback for no words: no guess, and no reason made up.
    expect(activityEntry(refused("Agent Mail refused it: the inbox is archived."), "UTC", now).next).toBeUndefined();
    const silent = activityEntry(refused("The tool returned an error."), "UTC", now);
    expect([silent.detail, silent.next]).toEqual(["The app refused it and gave no reason", undefined]);
    expect(activityEntry(refused(undefined), "UTC", now).detail).toBe("The app refused it and gave no reason");
    // The connection's own codes, typed by KIFF.
    const connection = (code: string) => activityEntry(refused(undefined, { tool_outcome: { status: "refused_by_tool", refused_by: "connection", code } }), "UTC", now).next?.kind;
    expect([connection("operation_changed"), connection("not_granted"), connection("profile_not_applied"), connection("halted")]).toEqual(["reconnect", "ask_coach", "try_later", undefined]);
    // Nothing reached the app: later. Done, held, refused by the Card, or an answer lost: no step.
    expect(activityEntry({ ...call(0, "not_sent") }, "UTC", now).next?.kind).toBe("try_later");
    for (const c of [call(0), call(0, "unknown"), { ...call(0), outcome: "owner_required" }, { ...call(0), outcome: "denied" }]) expect(activityEntry(c, "UTC", now).next).toBeUndefined();
  });
  it("returns twenty newest entries and a cursor, without dropping the next entry", async () => {
    const s = source(Array.from({ length: 45 }, (_, i) => call(i)));
    const one = await activityPage(input(), s.net);
    expect(one.entries).toHaveLength(20);
    const two = await activityPage(input(`cursor=${one.next_cursor}`), s.net);
    expect(two.entries[0]?.call.proposal_id).toBe("proposal-20");
    expect(two.groups[0]?.title).toBe("", "the next page continues the day heading");
    const three = await activityPage(input(`cursor=${two.next_cursor}`), s.net);
    expect(three.entries).toHaveLength(5); expect(three.next_cursor).toBeUndefined();
    expect(one.end_words).toBeUndefined(); expect(two.end_words).toBeUndefined();
    expect(three.end_words).toBe("Nothing older in this view");
    expect(new Set([...one.entries, ...two.entries, ...three.entries].map((x) => x.id)).size).toBe(45);
  });
  it("filters before paging, even when a hundred nonmatching outcomes come first", async () => {
    const s = source([...Array.from({ length: 110 }, (_, i) => call(i, "sending")), ...Array.from({ length: 30 }, (_, i) => call(110 + i))]);
    const one = await activityPage(input("filter=done"), s.net);
    expect(one.entries).toHaveLength(20); expect(one.entries[0]?.call.proposal_id).toBe("proposal-110");
    const two = await activityPage(input(`filter=done&cursor=${one.next_cursor}`), s.net);
    expect(two.entries).toHaveLength(10);
  });
  it("keeps a cursor through a full scan with no matching rows and ends only at the source's last row", async () => {
    const s = source([...Array.from({ length: 1000 }, (_, i) => call(i, "sending")), call(1000)]);
    const first = await activityPage(input("filter=done"), s.net);
    expect(first.entries).toEqual([]); expect(first.next_cursor).toBeTruthy(); expect(first.end_words).toBeUndefined();
    const last = await activityPage(input(`filter=done&cursor=${first.next_cursor}`), s.net);
    expect(last.entries.map((e) => e.call.proposal_id)).toEqual(["proposal-1000"]);
    expect(last.next_cursor).toBeUndefined(); expect(last.end_words).toBe("Nothing older in this view");
  });
  it("invalidates a changed snapshot instead of repeating or skipping entries", async () => {
    const calls = Array.from({ length: 30 }, (_, i) => call(i)); const s = source(calls);
    const first = await activityPage(input(), s.net); calls.unshift(call(-1));
    await expect(activityPage(input(`cursor=${first.next_cursor}`), s.net)).rejects.toMatchObject({ status: 409 });
  });
  it("binds a cursor to the account, person, taf and filter", async () => {
    const s = source(Array.from({ length: 30 }, (_, i) => call(i))); const first = await activityPage(input(), s.net);
    await expect(activityPage(input(`cursor=${first.next_cursor}`, "another-person"), s.net)).rejects.toMatchObject({ status: 400 });
    await expect(activityPage(input(`filter=done&cursor=${first.next_cursor}`), s.net)).rejects.toMatchObject({ status: 400 });
    await expect(activityPage(input("limit=1000000"), s.net)).rejects.toMatchObject({ status: 400 });
  });
  it("does not call an allowed-but-unknown action done, and never shows another agent's row", async () => {
    expect(activityMatches(call(0, "unknown"), "done")).toBe(false);
    expect(activityMatches(call(0, "unknown"), "held")).toBe(false);
    expect(activityMatches(call(0, "sending"), "held")).toBe(false);
    expect(activityMatches(call(0, "refused_by_tool"), "refused")).toBe(true);
    const s = source([{ ...call(0), actor_id: "other-agent" }, call(1)]);
    expect((await activityPage(input(), s.net)).entries).toHaveLength(1);
  });
  it("writes dates, titles, amounts and stable ids for both screens", () => {
    const c = { ...call(0), amount: { value: "12.30", unit: "EUR" } };
    expect(activityEntry(c, "Europe/Paris", now)).toMatchObject({ time: "13:00", day_title: "Today", amount_text: "€12.30", title: "Read a case" });
    expect(representationTag(activityEntry(c, "Europe/Paris", now))).toBe(representationTag(activityEntry(c, "Europe/Paris", now)));
  });
});

describe("Activity's HTTP contract", () => {
  it("returns 304 without a body and checks account and agent access before reading calls", async () => {
    const id = "taf-01234567890123456789";
    let tenant = "account-example", allowed = true, reads = 0, exceptionReads = 0, exceptionUnavailable = false;
    const deps = {
      now: () => now,
      tafs: { get: async (account: string) => account === "account-example" ? { id, name: "Example", job: "refunds", kiffAgent: "agent-example" } : undefined },
      fetch: (async (url: string | URL | Request) => {
        const u = new URL(String(url));
        if (u.pathname === "/v1/me") return new Response(JSON.stringify({ tenant_id: tenant, subject: "person-example", api_key: false, capabilities: ["read"], time_zone: "UTC" }));
        if (u.pathname === "/v1/me/access") return new Response(JSON.stringify({ agents: allowed ? { "agent-example": { use: true } } : {} }));
        if (u.pathname === "/v1/me/card-exceptions") { exceptionReads++; return new Response(JSON.stringify({ exceptions: [] }), { status: exceptionUnavailable ? 503 : 200 }); }
        if (u.pathname === "/v1/me/activity") { reads++; return new Response(JSON.stringify({ calls: [call(0)], shown: 1 })); }
        throw new Error("unexpected request");
      }) as typeof fetch,
    } as unknown as Deps;
    const event: HTTPEvent = { rawPath: `/v1/tafs/${id}/activity`, headers: { authorization: "Bearer synthetic-session" }, requestContext: { http: { method: "GET" } } };
    const first = await handle(event, deps); expect(first.statusCode).toBe(200); expect(first.headers.etag).toBeTruthy();
    const unchanged = await handle({ ...event, headers: { ...event.headers, "if-none-match": first.headers.etag } }, deps);
    expect(unchanged.statusCode).toBe(304); expect(unchanged.body).toBe("");
    exceptionUnavailable = true;
    const available = await handle(event, deps); expect(available.statusCode).toBe(200);
    const page = JSON.parse(available.body);
    expect(page.entries).toHaveLength(1); expect(page.card_unit_exceptions).toEqual([]);
    expect(page.card_unit_exceptions_notice).toContain("couldn't be read");
    expect(page.revision).toBe(JSON.parse(first.body).revision);
    const before = reads, beforeExceptions = exceptionReads; allowed = false;
    expect((await handle(event, deps)).statusCode).toBe(404); expect(reads).toBe(before);
    expect(exceptionReads).toBe(beforeExceptions);
    tenant = "other-account";
    expect((await handle(event, deps)).statusCode).toBe(404); expect(reads).toBe(before);
    expect(exceptionReads).toBe(beforeExceptions);
  });
});

describe("Activity in the person's language", () => {
  it("writes its words, days and amounts in it, and asks KIFF in it too, only when the app sent a language", async () => {
    const id = "taf-01234567890123456789";
    const sent: Array<string | undefined> = [];
    const refused: ActivityCall = { ...call(1, "refused_by_tool"), tool_outcome: { status: "refused_by_tool", refused_by: "connection", code: "not_granted" } };
    const older: ActivityCall = { ...call(0), created_at: "2026-10-30T09:00:00Z", amount: { value: "1250.5", unit: "EUR" } };
    const deps = {
      now: () => now,
      tafs: { get: async () => ({ id, name: "Example", job: "refunds", kiffAgent: "agent-example" }) },
      fetch: (async (url: string | URL | Request, init?: RequestInit) => {
        const u = new URL(String(url));
        sent.push((init?.headers as Record<string, string> | undefined)?.["accept-language"]);
        if (u.pathname === "/v1/me") return new Response(JSON.stringify({ tenant_id: "account-example", subject: "person-example", api_key: false, capabilities: ["read"], time_zone: "UTC" }));
        if (u.pathname === "/v1/me/access") return new Response(JSON.stringify({ agents: { "agent-example": { use: true } } }));
        if (u.pathname === "/v1/me/card-exceptions") return new Response("{}", { status: 503 });
        if (u.pathname === "/v1/me/activity") return new Response(JSON.stringify({ calls: [refused, older], shown: 2 }));
        throw new Error("unexpected request");
      }) as typeof fetch,
    } as unknown as Deps;
    const event = (headers: Record<string, string>): HTTPEvent => ({ rawPath: `/v1/tafs/${id}/activity`, headers: { authorization: "Bearer synthetic-session", ...headers }, requestContext: { http: { method: "GET" } } });
    const r = await handle(event({ "accept-language": "de-DE,de;q=0.9" }), deps);
    expect(r.statusCode).toBe(200);
    const page = JSON.parse(r.body);
    expect(page.filters.map((f: { title: string }) => f.title)).toEqual(["Alle", "Erledigt", "Zurückgehalten", "Abgelehnt"]);
    expect(page.end_words).toBe("Nichts Älteres in dieser Ansicht");
    expect(page.card_unit_exceptions_notice).toBe("Währungsanfragen konnten nicht gelesen werden. Deine Aktivität wird trotzdem angezeigt; versuche es mit Aktualisieren.");
    expect(page.entries[0]).toMatchObject({ day_title: "Heute", detail: "Die Verbindung hat es abgelehnt: die Verbindung enthält diese Aktion nicht. Es wurde nichts gesendet.",
      next: { kind: "ask_coach", words: "Coach bitten, den taf zu ändern", message: "Ändere Example: Read a case ist fehlgeschlagen." } });
    expect(page.entries[1]).toMatchObject({ detail: "Erledigt, innerhalb seiner Card", amount_text: new Intl.NumberFormat("de", { style: "currency", currency: "EUR" }).format(1250.5) });
    expect(page.entries[1].day_title).toBe(new Intl.DateTimeFormat("de", { timeZone: "UTC", weekday: "long", day: "numeric", month: "short" }).format(new Date("2026-10-30T09:00:00Z")));
    // Every call to KIFF for this request carried the person's language, resolved to one of the eight.
    expect(sent.length).toBeGreaterThan(2);
    expect(new Set(sent)).toEqual(new Set(["de"]));
    // An app that sends none leaves KIFF's own choice: no header is added, and the words are English.
    sent.length = 0;
    const plain = JSON.parse((await handle(event({}), deps)).body);
    expect(new Set(sent)).toEqual(new Set([undefined]));
    expect(plain.filters[2].title).toBe("Held");
    expect(plain.entries[0].detail).toBe("The connection refused it: the connection doesn't include this action. Nothing was sent.");
  });

  it("keeps a Request's own headers when it adds the language to a call to KIFF", async () => {
    const seen: Headers[] = [];
    const deps = {
      kiffBase: "https://kiff.test",
      fetch: (async (_input: string | URL | Request, init?: RequestInit) => {
        seen.push(new Headers(init?.headers));
        return new Response(JSON.stringify({ tenant_id: "account-example", subject: "person-example", api_key: false, capabilities: ["read"] }));
      }) as typeof fetch,
    } as unknown as Deps;
    const { withKIFFLanguage } = await import("../src/cloud/handler.js");
    const wrapped = withKIFFLanguage({ rawPath: "/", headers: { "accept-language": "pl" }, requestContext: { http: { method: "GET" } } }, deps).fetch!;
    await wrapped(new Request("https://kiff.test/v1/me", { headers: { authorization: "Bearer synthetic-session" } }));
    expect(seen[0]!.get("authorization")).toBe("Bearer synthetic-session");
    expect(seen[0]!.get("accept-language")).toBe("pl");
  });

  it("adds the language only to KIFF's own host, and never over a language the caller set", async () => {
    const seen: Array<{ url: string; lang: string | null }> = [];
    const deps = {
      kiffBase: "https://kiff.test",
      fetch: (async (input: string | URL | Request, init?: RequestInit) => {
        seen.push({ url: String(input instanceof Request ? input.url : input), lang: new Headers(init?.headers).get("accept-language") });
        return new Response("{}");
      }) as typeof fetch,
    } as unknown as Deps;
    const { withKIFFLanguage } = await import("../src/cloud/handler.js");
    const wrapped = withKIFFLanguage({ rawPath: "/", headers: { "accept-language": "de-DE" }, requestContext: { http: { method: "GET" } } }, deps).fetch!;
    await wrapped("https://kiff.test/v1/me");
    // planque, or any host that isn't KIFF's, gets none; nor a host that only starts with KIFF's base.
    await wrapped("https://planque.test/v1/grants", { headers: { authorization: "Bearer x" } });
    await wrapped("https://kiff.test.evil/v1/me", { headers: { authorization: "Bearer x" } });
    // A caller's own language stays, in either form of headers.
    await wrapped("https://kiff.test/v1/me", { headers: new Headers({ "Accept-Language": "fr" }) });
    await wrapped("https://kiff.test/v1/me", { headers: { "Accept-Language": "it" } });
    expect(seen).toEqual([
      { url: "https://kiff.test/v1/me", lang: "de" },
      { url: "https://planque.test/v1/grants", lang: null },
      { url: "https://kiff.test.evil/v1/me", lang: null },
      { url: "https://kiff.test/v1/me", lang: "fr" },
      { url: "https://kiff.test/v1/me", lang: "it" },
    ]);
  });
});

describe("recent work pages", () => {
  it("pages the retained runs without repeats when a newer run arrives and excludes another member's work", async () => {
    const id = "taf-01234567890123456789";
    const runs = Array.from({ length: 60 }, (_, i) => ({ id: `run-${i}`, session: `session-${i}`, status: "COMPLETED", created: new Date(now.getTime() - i * 1000).toISOString(), updated: now.toISOString(), requester: { kind: "member", id: "person-example" } }));
    runs.unshift({ ...runs[0]!, id: "another-members-run", requester: { kind: "member", id: "other-person" } });
    const deps = { now: () => now,
      tafs: { get: async () => ({ id, name: "Example", job: "refunds", kiffAgent: "agent-example" }) },
      agents: { runs: async (_account: string, _taf: string, _active: boolean, limit: number) => { expect(limit).toBe(100); return runs; } },
      fetch: (async (url: string | URL | Request) => {
        const path = new URL(String(url)).pathname;
        if (path === "/v1/me") return new Response(JSON.stringify({ tenant_id: "account-example", subject: "person-example", api_key: false, capabilities: ["read"] }));
        if (path === "/v1/me/access") return new Response(JSON.stringify({ agents: { "agent-example": { use: true } } }));
        throw new Error("unexpected request");
      }) as typeof fetch,
    } as unknown as Deps;
    const event: HTTPEvent = { rawPath: `/v1/tafs/${id}/work/runs`, headers: { authorization: "Bearer synthetic-session" }, requestContext: { http: { method: "GET" } } };
    const first = await handle(event, deps); expect(first.statusCode).toBe(200);
    // Titled as its runs, never as the work panel above it ("Its work"): the two read apart on both apps.
    expect(JSON.parse(first.body)).toMatchObject({ title: "Its runs", more_title: "Show all runs" });
    const fr = await handle({ ...event, headers: { ...event.headers, "accept-language": "fr-FR" } }, deps);
    expect(JSON.parse(fr.body)).toMatchObject({ title: "Ses exécutions", more_title: "Voir toutes les exécutions" });
    const one = JSON.parse(first.body); expect(one.requests).toHaveLength(20); expect(one.requests[0].id).toBe("run-0");
    runs.unshift({ ...runs[0]!, id: "new-run", created: new Date(now.getTime() + 1000).toISOString(), requester: { kind: "member", id: "person-example" } });
    const two = JSON.parse((await handle({ ...event, rawQueryString: `cursor=${encodeURIComponent(one.next_cursor)}` }, deps)).body);
    expect(two.requests).toHaveLength(20); expect(two.requests[0].id).toBe("run-20");
    const all = [...one.requests, ...two.requests]; expect(new Set(all.map((r) => r.id)).size).toBe(40);
    expect(all.some((r) => r.id === "another-members-run")).toBe(false);
    const invalid = await handle({ ...event, rawQueryString: "cursor=invalid" }, deps); expect(invalid.statusCode).toBe(400);
  });
});
