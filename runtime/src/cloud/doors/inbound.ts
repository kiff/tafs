import { arrive, type DoorsContext, type Outcome } from "./doors.js";
import { type Arrival, doorKeys } from "./model.js";
import { emailArrival } from "./email.js";
import { signedWebhook, whatsappSigned } from "./sign.js";
import { signupPage, whatsappArrivals } from "./whatsapp.js";

/**
 * The doors' webhooks, which need no sign-in: each provider signs what it
 * sends, and nothing is read before the signature holds. A delivery that
 * fails on tafs cloud's side answers 5xx so the provider sends it again; a
 * message already taken starts nothing twice (doors.ts).
 */

export interface Inbound {
  method: string;
  path: string;
  query: URLSearchParams;
  header: (name: string) => string;
  body: string;
}

export interface InboundResult {
  status: number;
  type: "json" | "text" | "html";
  body: string;
}

const ok = (body: unknown = { ok: true }): InboundResult => ({ status: 200, type: "json", body: JSON.stringify(body) });
const fail = (status: number, error: string): InboundResult => ({ status, type: "json", body: JSON.stringify({ error }) });

/** Whether tafs cloud serves this path as a door's webhook or page. */
export function isDoorPath(path: string): boolean {
  return path === "/v1/doors/email" || path === "/v1/doors/whatsapp" || path === "/doors/whatsapp/connect" || path === "/v1/doors/planque";
}

export async function inbound(req: Inbound, ctx: DoorsContext): Promise<InboundResult> {
  const cfg = await ctx.doors.config();
  if (req.path === "/v1/doors/email" && req.method === "POST") {
    if (!cfg.email || !cfg.emailDomain || !cfg.emailWebhookSecret) return fail(404, "not found");
    const signed = signedWebhook({
      secret: cfg.emailWebhookSecret, id: req.header("svix-id"), timestamp: req.header("svix-timestamp"),
      signature: req.header("svix-signature"), body: req.body, now: ctx.now,
    });
    if (!signed) return fail(401, "the signature doesn't hold");
    const event = parse(req.body) as { type?: unknown; data?: { email_id?: unknown } } | undefined;
    if (event?.type !== "email.received" || typeof event.data?.email_id !== "string") return ok({ ignored: true });
    let outcome: Outcome | "not_ours";
    try {
      const email = await cfg.email.received(event.data.email_id);
      const a = emailArrival(email, cfg.emailDomain);
      outcome = a ? await arrive(a, ctx) : "not_ours";
    } catch (err) {
      console.error("email door:", err instanceof Error ? err.message : "error");
      return fail(502, "try again");
    }
    return ok({ outcome });
  }

  if (req.path === "/v1/doors/whatsapp") {
    const wa = cfg.whatsapp;
    if (!wa) return fail(404, "not found");
    if (req.method === "GET") {
      // WhatsApp checks the webhook is tafs': it sends back what tafs was given.
      if (req.query.get("hub.mode") === "subscribe" && wa.verifyToken && req.query.get("hub.verify_token") === wa.verifyToken) {
        return { status: 200, type: "text", body: req.query.get("hub.challenge") ?? "" };
      }
      return fail(403, "not verified");
    }
    if (req.method !== "POST") return fail(405, "method not allowed");
    if (!whatsappSigned(wa.appSecret, req.header("x-hub-signature-256"), req.body)) return fail(401, "the signature doesn't hold");
    const outcomes: Outcome[] = [];
    try {
      for (const a of whatsappArrivals(parse(req.body))) outcomes.push(await arrive(a, ctx));
    } catch (err) {
      console.error("whatsapp door:", err instanceof Error ? err.message : "error");
      return fail(502, "try again");
    }
    return ok({ outcomes });
  }

  if (req.path === "/v1/doors/planque" && req.method === "POST") {
    // A Slack message planque took from its Slack bot connection and hands over, signed (planque.door.v1):
    // the route is the grant and channel planque's own records name, never anything the message says.
    if (!cfg.planqueHandoffSecret) return fail(404, "not found");
    const signed = signedWebhook({
      secret: cfg.planqueHandoffSecret, id: req.header("planque-event-id"), timestamp: req.header("planque-event-timestamp"),
      signature: req.header("planque-event-signature"), body: req.body, now: ctx.now,
    });
    if (!signed) return fail(401, "the signature doesn't hold");
    const a = planqueArrival(parse(req.body), req.header("planque-event-id"));
    if (!a) return fail(400, "not a planque.door.v1 message");
    let outcome: Outcome;
    try {
      const at = (await ctx.doors.store.get(doorKeys.route(a.route))) as { tenant?: string; taf?: string } | undefined;
      const taf = at?.tenant === a.tenant && at.taf ? await ctx.tafs.get(at.tenant, at.taf) : undefined;
      // The grant was asked for this taf's KIFF agent, in this account: any other is no door of ours.
      if (!taf || taf.kiffAgent !== a.agent) return fail(404, "no such door");
      outcome = await arrive(a.arrival, ctx);
    } catch (err) {
      console.error("slack door:", err instanceof Error ? err.message : "error");
      return fail(502, "try again");
    }
    if (outcome === "unknown") return fail(404, "no such door");
    if (outcome === "closed") return fail(409, "the door is closed");
    return { status: 202, type: "json", body: JSON.stringify({ outcome }) };
  }

  if (req.path === "/doors/whatsapp/connect" && req.method === "GET") {
    const wa = cfg.whatsapp;
    const taf = req.query.get("taf") ?? "";
    if (!wa || !/^[A-Za-z0-9-]{1,64}$/.test(taf)) return fail(404, "not found");
    return { status: 200, type: "html", body: signupPage({ appID: wa.appID, configID: wa.configID, taf, back: "tafs://doors/whatsapp" }) };
  }

  return fail(404, "not found");
}

/** planque's handover, as an arrival on the Slack door its grant and channel name; undefined when it isn't one. */
export function planqueArrival(v: unknown, eventID: string): { tenant: string; agent: string; route: string; arrival: Arrival } | undefined {
  const b = (v && typeof v === "object" ? v : {}) as Record<string, unknown>;
  const s = (k: string) => (typeof b[k] === "string" ? (b[k] as string) : "");
  if (b.v !== "planque.door.v1" || !s("id") || s("id") !== eventID || !s("tenant") || !s("agent") || !s("grant") || !s("channel") || !s("sender")) return undefined;
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(s("grant")) || !/^[CGD][A-Z0-9]{2,30}$/.test(s("channel").toUpperCase())) return undefined;
  const channel = s("channel").toUpperCase();
  const route = `slack:${s("grant")}:${channel}`;
  const thread = s("thread");
  const at = typeof b.timestamp === "number" ? new Date(b.timestamp * 1000).toISOString() : s("timestamp") || new Date().toISOString();
  return {
    tenant: s("tenant"), agent: s("agent"), route,
    arrival: {
      route, id: s("id"), from: s("sender").toLowerCase(), proven: true, text: s("text").slice(0, 40_000), attachments: 0,
      thread: { slack_channel: channel, ...(thread ? { slack_thread: thread } : {}) }, threadKey: thread || s("id"), at,
    },
  };
}

function parse(body: string): unknown {
  try { return JSON.parse(body); } catch { return undefined; }
}
