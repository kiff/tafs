import { describe, expect, it } from "vitest";
import { withLine } from "../src/cloud/handler.js";

describe("a sign-in card's buttons", () => {
  const card = { type: "sign_in", id: "si", grant: "grt_abcdef", service: "Stripe", title: "Połącz Stripe", text: "Zaloguj się w Stripe, aby taf mógł zwracać płatności.", yes: "Zaloguj się", no: "Nie teraz", waits: "owner" };

  it("are the app's chrome, in the app's language, whatever language Coach wrote the card in", () => {
    expect(withLine(card, "fr")).toMatchObject({ yes: "Se connecter", no: "Pas maintenant" });
    expect(withLine(card, "en")).toMatchObject({ yes: "Sign in", no: "Not now" });
  });

  it("leave Coach's own words on the card as written", () => {
    expect(withLine(card, "fr")).toMatchObject({ title: "Połącz Stripe", text: card.text, service: "Stripe", grant: "grt_abcdef" });
  });

  it("change no other card", () => {
    const confirm = { type: "confirm", id: "c", text: "Ok?", options: [{ value: "y", label: "Tak" }], waits: "owner" };
    expect(withLine(confirm, "fr")).toEqual(confirm);
  });
});
