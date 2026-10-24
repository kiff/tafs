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
}

export const APPS: DoorApp[] = [
  { id: "email", label: "Email", kind: "email", proof: "proven", how: "The email must pass the sender check" },
  { id: "whatsapp", label: "WhatsApp", kind: "whatsapp", proof: "proven", how: "WhatsApp knows the phone number" },
];
