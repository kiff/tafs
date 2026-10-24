import type { Fetch } from "../kiff.js";
import { at, type DoorApp } from "./apps.js";
import type { Arrival } from "./model.js";

/**
 * Connector doors: any app the deployment's connector catalog signs in to and
 * reports new messages from. The catalog keeps the app's sign-in; tafs cloud
 * keeps only which connected account is which taf's door. Its events come
 * signed (sign.ts), and the taf answers with the app's tool through KIFF's
 * gateway, under its Card, never through the door.
 */

export interface CatalogClient {
  /** A sign-in link for the app, and the connected account it will make. */
  link(authConfig: string, user: string, callback: string): Promise<{ url: string; account: string }>;
  /** The catalog reports the account's new messages; the trigger's id. */
  watch(trigger: string, account: string): Promise<string>;
  /** It stops. Already gone counts as done. */
  unwatch(triggerID: string): Promise<void>;
  /** The app's sign-in goes from the catalog. Already gone counts as done. */
  disconnect(account: string): Promise<void>;
}

export class CatalogError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export function catalogClient(apiKey: string, base: string, doFetch: Fetch = fetch): CatalogClient {
  const call = async (method: string, path: string, body?: unknown) => {
    const res = await doFetch(`${base}${path}`, {
      method,
      headers: { "x-api-key": apiKey, accept: "application/json", ...(body ? { "content-type": "application/json" } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    if (res.status === 404 && method === "DELETE") return {};
    if (!res.ok) throw new CatalogError(res.status, `the connector catalog answered ${res.status}`);
    return (await res.json().catch(() => ({}))) as Record<string, unknown>;
  };
  return {
    async link(authConfig, user, callback) {
      const out = await call("POST", "/connected_accounts/link", { auth_config_id: authConfig, user_id: user, callback_url: callback });
      if (typeof out.redirect_url !== "string" || typeof out.connected_account_id !== "string") throw new CatalogError(502, "the connector catalog gave no link");
      return { url: out.redirect_url, account: out.connected_account_id };
    },
    async watch(trigger, account) {
      const out = await call("POST", `/trigger_instances/${encodeURIComponent(trigger)}/upsert`, { connected_account_id: account, trigger_config: {} });
      if (typeof out.trigger_id !== "string") throw new CatalogError(502, "the connector catalog gave no trigger");
      return out.trigger_id;
    },
    async unwatch(triggerID) {
      await call("DELETE", `/trigger_instances/manage/${encodeURIComponent(triggerID)}`);
    },
    async disconnect(account) {
      await call("DELETE", `/connected_accounts/${encodeURIComponent(account)}`);
    },
  };
}

/**
 * A catalog event as a door's arrival, read with its app's description;
 * undefined for an event that isn't a message of a known door's trigger.
 */
export function catalogArrival(raw: unknown, deliveryID: string, byTrigger: (trigger: string) => DoorApp | undefined): Arrival | undefined {
  const e = (raw ?? {}) as { metadata?: Record<string, unknown>; data?: unknown; timestamp?: unknown };
  const account = e.metadata?.connected_account_id;
  const trigger = e.metadata?.trigger_slug;
  if (typeof account !== "string" || typeof trigger !== "string") return undefined;
  const app = byTrigger(trigger.toUpperCase());
  const ev = app?.event;
  if (!app || !ev) return undefined;
  const from = (at(e.data, ev.sender) ?? "").trim().toLowerCase();
  const text = (at(e.data, ev.text) ?? "").trim();
  if (!from || !text) return undefined;
  const thread = at(e.data, ev.thread);
  const name = at(e.data, ev.name);
  const replyTo = at(e.data, ev.reply.to);
  const replyThread = at(e.data, ev.reply.thread);
  return {
    route: `connector:${account}`,
    id: at(e.data, ev.id) ?? deliveryID,
    from,
    ...(name ? { name } : {}),
    proven: app.proof === "proven",
    text,
    attachments: 0,
    thread: { ...(replyTo ? { to: replyTo } : {}), ...(replyThread ? { thread: replyThread } : {}) },
    threadKey: thread ?? from,
    at: typeof e.timestamp === "string" ? e.timestamp : new Date().toISOString(),
  };
}
