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
