import { arrive, type DoorsContext, type Outcome } from "./doors.js";
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
  return path === "/v1/doors/email" || path === "/v1/doors/whatsapp" || path === "/doors/whatsapp/connect";
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

  if (req.path === "/doors/whatsapp/connect" && req.method === "GET") {
    const wa = cfg.whatsapp;
    const taf = req.query.get("taf") ?? "";
    if (!wa || !/^[A-Za-z0-9-]{1,64}$/.test(taf)) return fail(404, "not found");
    return { status: 200, type: "html", body: signupPage({ appID: wa.appID, configID: wa.configID, taf, back: "tafs://doors/whatsapp" }) };
  }

  return fail(404, "not found");
}

function parse(body: string): unknown {
  try { return JSON.parse(body); } catch { return undefined; }
}
