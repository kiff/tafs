import type { DoorKind } from "./model.js";

/**
 * The apps a door can open on, as data: email and WhatsApp. Each is offered
 * only when the deployment has set it up.
 */
export interface DoorApp {
  id: string;
  label: string;
  kind: DoorKind;
  /**
   * "proven": the app tells who wrote (an email that passed the sender
   * check, a verified number), so the door can hold keys. "none": anyone can
   * type any name; everything there knocks.
   */
  proof: "proven" | "none";
  /** One line on how it knows who's writing, for the owner. */
  how: string;
  /** How a person pairs a sender by hand, as the field's hint. */
  pairHint: string;
  /** Pairing from a sender's own message, when that's the way: said on the door's panel. */
  pairFromKnock?: string;
  /**
   * A door that listens through one of the owner's bot connections in planque, picked when the door is added:
   * planque's `provider` for it, and the step's words. The apps list the connections planque shows with that
   * provider and keep no copy of either.
   */
  bot?: { provider: string; words: { none: string; pick: string } };
}

export const APPS: DoorApp[] = [
  { id: "email", label: "Email", kind: "email", proof: "proven", how: "The email must pass the sender check", pairHint: "Email address" },
  { id: "whatsapp", label: "WhatsApp", kind: "whatsapp", proof: "proven", how: "WhatsApp knows the phone number", pairHint: "Phone number, with its country code" },
  { id: "slack", label: "Slack", kind: "slack", proof: "proven", how: "Slack knows who wrote, by their Slack user", pairHint: "Their Slack member ID, such as U012AB3CD" },
  {
    id: "telegram", label: "Telegram", kind: "telegram", proof: "proven", how: "Telegram knows who wrote, by their Telegram user",
    pairHint: "Their Telegram user id, a number",
    pairFromKnock: "Ask them to write to the bot. Their message knocks here, and Pair makes them a key.",
    bot: {
      provider: "telegram",
      words: {
        none: "Connect the taf's Telegram bot first, in Settings › Connect apps, with the token BotFather gave you. Then add its door here.",
        pick: "Which Telegram bot is it? Anyone who writes to it will knock.",
      },
    },
  },
];
