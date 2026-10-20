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
}

export class KIFFError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export type Fetch = typeof fetch;

export const KIFF_API = "https://api.kiff.dev";

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
  };
  if (!me.tenant_id) throw new KIFFError(502, "KIFF did not say which account this is");
  return {
    tenantID: me.tenant_id,
    subject: me.subject ?? "",
    apiKey: me.api_key === true,
    agentID: me.agent_id ?? "",
    capabilities: me.capabilities ?? [],
  };
}

/**
 * A KIFF draft's status, read with the owner's own session (kiff/tafs#107);
 * undefined when KIFF has no such draft on the account.
 */
export async function draftStatus(token: string, draftID: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<string | undefined> {
  const res = await doFetch(`${base}/v1/me/agent-drafts/${encodeURIComponent(draftID)}`, { headers: { authorization: `Bearer ${token}`, accept: "application/json" } });
  if (res.status === 401 || res.status === 403) throw new KIFFError(401, "KIFF did not accept this sign-in");
  if (res.status === 404) return undefined;
  if (!res.ok) throw new KIFFError(502, `KIFF answered ${res.status} for the draft`);
  return ((await res.json()) as { status?: string }).status ?? "";
}

/** Whether a draft's Cards are issued: confirmed, or claimed, which is later still with the same Cards. */
export function issued(status: string | undefined): boolean {
  return status === "confirmed" || status === "claimed";
}

/** Whether a draft will never be confirmed: discarded, failed, or not there. */
export function dropped(status: string | undefined): boolean {
  return status === undefined || status === "discarded" || status === "failed";
}

/** Whether a KIFF draft is confirmed (see issued), read with the owner's own session. */
export async function draftConfirmed(token: string, draftID: string, doFetch: Fetch = fetch, base = KIFF_API): Promise<boolean> {
  return issued(await draftStatus(token, draftID, doFetch, base));
}

/**
 * Claims a confirmed draft's agent key (kiff-cloud #1217): the draft's claim
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
