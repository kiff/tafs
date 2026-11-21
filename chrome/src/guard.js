// The extension's own check of an act on the page it was planned from (docs/chrome.md, "The guard"). planque refuses the
// same steps first (internal/browser Refuse); this holds even if planque didn't. A fill of a secret field, a click that pays
// or places an order, any click on a checkout or payment page, and a ref the page lacks are refused, and nothing runs.

export const CODE = Object.freeze({
  paymentStep: "payment_step",
  secretField: "secret_field",
  refUnknown: "ref_unknown",
  badStep: "bad_step",
});

/** The words of a control that pays or places an order (en, fr, de, es, it, nl, pt), as planque's `payWords`. */
export const PAY_WORDS = new RegExp(
  "\\b(" +
    // en
    "pay|pay now|place (your |the |my )?order|buy( it)?( now)?|complete (the |your |my )?(purchase|order|payment)|confirm (and pay|purchase|order|payment)|purchase|subscribe|checkout now|order now|" +
    // fr
    "commander|acheter|payer|payer maintenant|(confirmer|valider|passer|finaliser) (la |ma )?commande|r[ée]gler|" +
    // de
    "kaufen|jetzt kaufen|bestellen|bezahlen|jetzt bezahlen|(zahlungspflichtig|kostenpflichtig) bestellen|bestellung (abschicken|absenden|aufgeben)|kauf abschlie[sß]+en|" +
    // es
    "comprar|comprar ahora|pagar|pagar ahora|realizar (el )?pedido|confirmar (el |la )?(pedido|compra|pago)|finalizar (la )?compra|tramitar pedido|" +
    // it
    "acquista( ora)?|compra( ora)?|paga( ora)?|ordina( ora)?|(conferma|completa) (l'?)?(ordine|acquisto|pagamento)|procedi al pagamento|" +
    // nl
    "kopen|nu kopen|koop nu|betalen|nu betalen|afrekenen|bestelling (plaatsen|bevestigen)|" +
    // pt
    "comprar agora|pagar agora|(finalizar|concluir) (a )?compra|confirmar (o |a )?(pedido|compra|pagamento)|efetuar pagamento|fazer pedido" +
    ")\\b",
  "i",
);

export function secret(field) {
  return field.kind === "password" || field.kind === "card" || field.kind === "code";
}

export function pays(control) {
  return control.places_order === true || PAY_WORDS.test(control.label || "");
}

export function payment(page) {
  if (page.payment_frame) return true;
  if ((page.fields || []).some((f) => f.kind === "card")) return true;
  return (page.controls || []).some(pays);
}

const KINDS = new Set(["click", "fill", "select", "check"]);

/** Why `act` may not run on `page`: `{code, why}`, or null when it may. */
export function refuse(page, act) {
  if (!act || !KINDS.has(act.kind) || typeof act.ref !== "string" || !act.ref) {
    return { code: CODE.badStep, why: "an act is a click, fill, select or check of one ref" };
  }
  if (act.kind === "click") {
    const control = (page.controls || []).find((c) => c.ref === act.ref);
    if (control) {
      if (pays(control)) return { code: CODE.paymentStep, why: "that click would pay or place an order; only a pay held for the owner may" };
      if (page.checkout || payment(page)) return { code: CODE.paymentStep, why: "on a checkout or payment page a taf clicks no control" };
      return null;
    }
    const link = (page.links || []).find((l) => l.ref === act.ref);
    if (link) {
      // A link whose words pay is refused as a control would be. On a checkout or payment page no link is followed at
      // all: neither its URL nor its words can prove that following it places no order (a GET to /place-order).
      if (PAY_WORDS.test(link.label || "")) return { code: CODE.paymentStep, why: "that link's words pay or place an order" };
      if (page.checkout || payment(page)) return { code: CODE.paymentStep, why: "on a checkout or payment page a taf follows no link" };
      return null;
    }
    return { code: CODE.refUnknown, why: "the page has no control or link " + act.ref };
  }
  const field = (page.fields || []).find((f) => f.ref === act.ref);
  if (!field) return { code: CODE.refUnknown, why: "the page has no field " + act.ref };
  if (field.kind === "card") return { code: CODE.paymentStep, why: "a taf never types card details" };
  if (secret(field) && !act.sealed) return { code: CODE.secretField, why: "a taf never types a password or a code" };
  if (act.sealed && !["password", "code", "text", "email"].includes(field.kind)) {
    return { code: CODE.secretField, why: "a sealed value goes only into a password, username or one-time-code field" };
  }
  if (act.kind === "fill" && !act.sealed && typeof act.value !== "string") return { code: CODE.badStep, why: "a fill has a value" };
  if (act.kind === "select" && typeof act.value !== "string") return { code: CODE.badStep, why: "a select names its option" };
  return null;
}
