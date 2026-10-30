/**
 * Who is calling, as KIFF says. tafs cloud keeps no accounts of its own: the
 * phone sends its KIFF session, and KIFF answers who that is and what they
 * may do on which account.
 */
export interface KIFFIdentity {
  tenantID: string;
  subject: string;
  /** True for an API key, false for a person's session. */
  apiKey: boolean;
  /** The agent an agent key is bound to; empty for sessions. */
  agentID: string;
  capabilities: string[];
  /** The person's sign-in email, when KIFF gives it (sessions only). */
  email?: string;
}

export class KIFFError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export type Fetch = typeof fetch;

export const KIFF_API = "https://api.kiff.dev";
/** KIFF's gateway API, where the account's tool connections are (tafdef's KIFF_GATEWAY is its MCP endpoint). */
export const KIFF_GATEWAY_API = "https://mcp.kiff.dev";

export async function whoIs(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<KIFFIdentity> {
  if (!token) throw new KIFFError(401, "sign in with KIFF");
  const res = await doFetch(`${base}/v1/me`, { headers: { authorization: `Bearer ${token}`, accept: "application/json" } });
  if (res.status === 401 || res.status === 403) throw new KIFFError(401, "KIFF did not accept this sign-in");
  if (!res.ok) throw new KIFFError(502, `KIFF answered ${res.status}`);
  const me = (await res.json()) as {
    tenant_id?: string;
    subject?: string;
    api_key?: boolean;
    agent_id?: string;
    capabilities?: string[];
    email?: string;
  };
  if (!me.tenant_id) throw new KIFFError(502, "KIFF did not say which account this is");
  return {
    tenantID: me.tenant_id,
    subject: me.subject ?? "",
    apiKey: me.api_key === true,
    agentID: me.agent_id ?? "",
    capabilities: me.capabilities ?? [],
    ...(typeof me.email === "string" && me.email ? { email: me.email } : {}),
  };
}

/**
 * A KIFF draft's status, read with the owner's own session (kiff/tafs#107);
 * undefined when KIFF has no such draft on the account. A draft still
 * unconfirmed past its expiry reads "expired": KIFF keeps it as `draft`,
 * and refuses to confirm it.
 */
export async function draftStatus(token: string, draftID: string, doFetch: Fetch = fetch, base = KIFF_API, now: Date = new Date()): Promise<string | undefined> {
  return (await draftInfo(token, draftID, doFetch, base, now))?.status;
}

/** A KIFF draft as tafs cloud checks it: its status (as draftStatus reads it) and its agent. */
export async function draftInfo(token: string, draftID: string, doFetch: Fetch = fetch, base = KIFF_API, now: Date = new Date()): Promise<{ status: string; agent: string } | undefined> {
  const res = await doFetch(`${base}/v1/me/agent-drafts/${encodeURIComponent(draftID)}`, { headers: { authorization: `Bearer ${token}`, accept: "application/json" } });
  if (res.status === 401 || res.status === 403) throw new KIFFError(401, "KIFF did not accept this sign-in");
  if (res.status === 404) return undefined;
  if (!res.ok) throw new KIFFError(502, `KIFF answered ${res.status} for the draft`);
  const d = (await res.json()) as { status?: string; expires_at?: string; agent_id?: string };
  const expired = d.status === "draft" && d.expires_at && new Date(d.expires_at).getTime() <= now.getTime();
  return { status: expired ? "expired" : d.status ?? "", agent: d.agent_id ?? "" };
}

/** Whether a draft's Cards are issued: confirmed, or claimed, which is later still with the same Cards. */
export function issued(status: string | undefined): boolean {
  return status === "confirmed" || status === "claimed";
}

/** Whether a draft will never be confirmed: discarded, failed, expired, or not there. */
export function dropped(status: string | undefined): boolean {
  return status === undefined || status === "discarded" || status === "failed" || status === "expired";
}

/** Whether a KIFF draft is confirmed (see issued), read with the owner's own session. */
export async function draftConfirmed(token: string, draftID: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<boolean> {
  return issued(await draftStatus(token, draftID, doFetch, base));
}

/**
 * Claims a confirmed draft's agent key: the draft's claim
 * secret, shown once to whoever submitted it, buys the key once. The key comes
 * straight here and goes into Secrets Manager, where kiff/agents reads it for the
 * taf's runs; the phone never holds it.
 */
export async function claimDraft(draftID: string, claimSecret: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<{ agentID: string; key: string }> {
  if (!/^drf_[A-Za-z0-9]+$/.test(draftID)) throw new KIFFError(400, "that is not a KIFF draft id");
  if (!claimSecret) throw new KIFFError(400, "the draft's claim secret is missing");
  const res = await doFetch(`${base}/v1/agent-drafts/${draftID}/claim`, {
    method: "POST",
    headers: { authorization: `Bearer ${claimSecret}`, accept: "application/json" },
  });
  const body = (await res.json().catch(() => ({}))) as { agent_id?: string; plaintext?: string; error?: string };
  if (!res.ok) throw new KIFFError(res.status, body.error ?? `KIFF answered ${res.status}`);
  if (!body.plaintext || !body.agent_id) throw new KIFFError(502, "KIFF claimed the draft but sent no key");
  return { agentID: body.agent_id, key: body.plaintext };
}

/** Asks KIFF with the owner's session; a refused or failed answer throws. */
async function ask<T>(token: string, method: string, path: string, body: unknown, doFetch: Fetch, base: string): Promise<T> {
  const res = await doFetch(`${base}${path}`, {
    method, headers: { authorization: `Bearer ${token}`, accept: "application/json", ...(body === undefined ? {} : { "content-type": "application/json" }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (res.status === 401 || res.status === 403) throw new KIFFError(401, "KIFF did not accept this sign-in");
  const out = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new KIFFError(res.status === 404 || res.status === 409 ? res.status : 502, out.error ?? `KIFF answered ${res.status}`);
  return out;
}

/**
 * The revision each of these Cards is at now, from its statement (kiff/tafs#107):
 * what a version runs under, recorded when it goes live.
 */
export async function cardRevisions(token: string, ids: string[], doFetch: Fetch = fetch, base = KIFF_API): Promise<Array<{ id: string; revision: number }>> {
  return Promise.all(ids.map(async (id) => {
    const s = await ask<{ revisions?: Array<{ revision: number }> }>(token, "GET", `/v1/me/mandates/${encodeURIComponent(id)}/statement`, undefined, doFetch, base);
    const revision = Math.max(0, ...(s.revisions ?? []).map((r) => r.revision));
    if (!revision) throw new KIFFError(502, `KIFF keeps no revision of Card ${id}`);
    return { id, revision };
  }));
}

/**
 * A draft that brings back these Cards' terms at these revisions, for going back
 * to the version that ran under them. It only waits: the owner
 * confirms it with the phone's signature.
 */
export async function restoreDraft(token: string, agent: string, revisions: Array<{ id: string; revision: number }>, label: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<string> {
  return (await ask<{ id: string }>(token, "POST", "/v1/me/agent-drafts/restore", { agent_id: agent, label, revisions }, doFetch, base)).id;
}

/** A revision draft submitted again, for one that expired or went stale. It only waits. */
export async function redraft(token: string, draftID: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<string> {
  return (await ask<{ id: string }>(token, "POST", `/v1/me/agent-drafts/${encodeURIComponent(draftID)}/redraft`, {}, doFetch, base)).id;
}

/** What KIFF counted of a trial's calls (kiff/tafs#173): kept once the trial ends. */
export interface TrialCounts {
  calls: number;
  held: number;
  refused: number;
}

/** A trial as KIFF keeps it: still trying, or ended, with its calls' counts. */
export interface KIFFTrial extends TrialCounts {
  status: "trying" | "ended";
  endedBy?: string;
}

function trialFrom(t: { status?: string; ended_by?: string; calls?: number; held?: number; refused?: number }): KIFFTrial {
  return { status: t.status === "trying" ? "trying" : "ended", ...(t.ended_by ? { endedBy: t.ended_by } : {}),
    calls: t.calls ?? 0, held: t.held ?? 0, refused: t.refused ?? 0 };
}

/** The trial of a draft (kiff/tafs#173), read with the owner's session; undefined when it was never tried. */
export async function trialOf(token: string, draftID: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<KIFFTrial | undefined> {
  try {
    return trialFrom(await ask(token, "GET", `/v1/me/agent-drafts/${encodeURIComponent(draftID)}/trial`, undefined, doFetch, base));
  } catch (err) {
    if (err instanceof KIFFError && err.status === 404) return undefined;
    throw err;
  }
}

/**
 * Go back (kiff/tafs#173): KIFF revokes the draft's trial Cards, refusing
 * its later and held calls, and answers its final counts. A trial that had
 * ended already is read instead, so asking again answers the same.
 */
export async function endTrialIn(token: string, draftID: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<TrialCounts> {
  const path = `/v1/me/agent-drafts/${encodeURIComponent(draftID)}/trial`;
  // The phone ends it first, signed, as KIFF asks of a phone's session (kiff/tafs#247):
  // then it's read here, ended, with its counts.
  const read = await trialOf(token, draftID, doFetch, base);
  if (read?.status === "ended") return read;
  try {
    return trialFrom(await ask(token, "DELETE", path, undefined, doFetch, base));
  } catch (err) {
    if (!(err instanceof KIFFError && (err.status === 404 || err.status === 409))) throw err;
  }
  const t = await trialOf(token, draftID, doFetch, base);
  if (!t || t.status !== "ended") throw new KIFFError(409, "KIFF didn't end this trial");
  return t;
}

/**
 * A halt as KIFF reports it: `halted` only once its gateway (`kiff_ack`)
 * and planque (`planque_ack`) both hold it, `halting` until then. While halted, KIFF refuses the
 * agent's calls, held ones and permits issued before the halt too.
 */
export interface KIFFHalt {
  state: "running" | "halting" | "halted";
  halted_at?: string;
  kiff_ack: boolean;
  planque_ack: boolean;
}

/** KIFF's halt route for one agent, or with none for the whole account. */
function haltPath(agent?: string): string {
  return agent ? `/v1/agents/${encodeURIComponent(agent)}/halt` : "/v1/halt";
}

/** Halts the agent in KIFF, or the whole account: the owner's session, no signature, since it only takes authority away. */
export async function haltInKIFF(token: string, agent: string | undefined, doFetch: Fetch = fetch, base = KIFF_API): Promise<KIFFHalt> {
  return halt(await ask<Partial<KIFFHalt>>(token, "POST", haltPath(agent), {}, doFetch, base));
}

/**
 * The agent's halt in KIFF, or the account's. Resuming isn't here: KIFF takes it only signed by
 * the phone, with Face ID, so the app sends it to KIFF itself.
 */
export async function haltedInKIFF(token: string, agent: string | undefined, doFetch: Fetch = fetch, base = KIFF_API): Promise<KIFFHalt> {
  return halt(await ask<Partial<KIFFHalt>>(token, "GET", haltPath(agent), undefined, doFetch, base));
}

/** A halt as read, its acknowledgements false unless KIFF said true. */
function halt(h: Partial<KIFFHalt>): KIFFHalt {
  const state = h.state === "halted" || h.state === "halting" ? h.state : "running";
  return { state, ...(h.halted_at ? { halted_at: h.halted_at } : {}), kiff_ack: h.kiff_ack === true, planque_ack: h.planque_ack === true };
}

/**
 * The person's run grant for an agent (POST /v1/me/run-grants): KIFF's signed word
 * that they may use it, which their run carries so each of its calls, and any hold it
 * opens, is theirs. `no_access` when KIFF says they may not use it; undefined when
 * KIFF gave none for another reason (not yet able to, or down), and the run goes as
 * before: kiff/agents refuses it once it requires grants.
 */
export async function runGrant(token: string, agent: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<string | "no_access" | undefined> {
  let res: Response;
  try {
    res = await doFetch(`${base}/v1/me/run-grants`, {
      method: "POST", headers: { authorization: `Bearer ${token}`, accept: "application/json", "content-type": "application/json" },
      body: JSON.stringify({ agent }),
    });
  } catch {
    return undefined;
  }
  const out = (await res.json().catch(() => ({}))) as { grant?: unknown; error?: unknown };
  if (res.status === 403 && out.error === "no_access") return "no_access";
  return res.ok && typeof out.grant === "string" && out.grant ? out.grant : undefined;
}

/** The account's tool connections in KIFF's gateway, read with the person's session. */
export async function gatewayConnections(token: string, doFetch: Fetch = fetch, base = KIFF_GATEWAY_API): Promise<Array<{ name: string; url?: string; control?: boolean }>> {
  const out = await ask<unknown>(token, "GET", "/v1/connections", undefined, doFetch, base);
  const list = Array.isArray(out) ? out : (out as { connections?: unknown }).connections;
  if (!Array.isArray(list)) throw new KIFFError(502, "KIFF's gateway sent no connections");
  return list.flatMap((c) => (c && typeof c === "object" && typeof (c as { name?: unknown }).name === "string" ? [c as { name: string; url?: string; control?: boolean }] : []));
}

/**
 * The ids of the account's Cards in force, from one read of KIFF's list. A revoked or expired Card
 * is still listed, with `active` false, so it doesn't count.
 */
export async function activeCards(token: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<Set<string>> {
  const out = await ask<{ mandates?: Array<{ id?: unknown; active?: unknown }> }>(token, "GET", "/v1/me/mandates", undefined, doFetch, base);
  return new Set((out.mandates ?? []).flatMap((m) => (m.active === true && typeof m.id === "string" ? [m.id] : [])));
}

/**
 * How KIFF would word a draft, without making it (POST /v1/me/agent-drafts/preview): the draft's
 * card_title and each Card as the submitted draft would read, with its lines. It writes nothing and
 * needs no signature. Passed on unchanged, so no copy of how a Card reads is kept here.
 */
export async function previewDraft(token: string, body: unknown, doFetch: Fetch = fetch, base = KIFF_API): Promise<{ card_title?: unknown; cards: unknown[] }> {
  const out = await ask<{ card_title?: unknown; cards?: unknown }>(token, "POST", "/v1/me/agent-drafts/preview", body, doFetch, base);
  if (!Array.isArray(out.cards)) throw new KIFFError(502, "KIFF's preview sent no Cards");
  return { ...(out.card_title === undefined ? {} : { card_title: out.card_title }), cards: out.cards };
}
