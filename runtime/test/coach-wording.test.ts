import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { COACH_WORDING } from "../src/cloud/coach-wording.js";
import { setupDefinition } from "../src/cloud/setupdef.js";
import { definition } from "../src/cloud/tafdef.js";
import { jobById } from "../src/jobs.js";

/** An RE2 pattern as JavaScript reads it in u mode: RE2's one-letter classes \pL and \pN are \p{L} and \p{N}. */
const js = (p: string) => p.replace(/\\p([LN])/g, "\\p{$1}");

// Eval 43975057022: Coach wrote "read-only" of a Google Sheets connection in 1 run of 3. kiff/agents' show refuses such a
// card by these rules before anyone sees it; the Eval's plan gate reads them beside its own list.
describe("Coach's wording rules", () => {
  it("are the contract's, whole, and Setup's definition sends them", () => {
    const contract = JSON.parse(readFileSync(new URL("../../contracts/coach-wording.json", import.meta.url), "utf8"));
    expect(COACH_WORDING).toEqual(contract);
    expect(setupDefinition("coach-x").wording).toEqual(contract);
    expect(setupDefinition("coach-x").show).toBe(true);
    // A taf's own definition carries none: its cards aren't Coach's.
    expect(definition("refunds-x", jobById("refunds")).wording).toBeUndefined();
  });

  it("are within kiff/agents' limits, each pattern a whole RE2 expression with no capturing group but the next word's", () => {
    expect(COACH_WORDING.length).toBeGreaterThan(0);
    expect(COACH_WORDING.length).toBeLessThanOrEqual(32);
    for (const w of COACH_WORDING) {
      expect(w.patterns.length).toBeGreaterThan(0);
      expect(w.patterns.length).toBeLessThanOrEqual(32);
      expect(w.fix.trim().length).toBeGreaterThan(0);
      expect([...w.fix].length).toBeLessThanOrEqual(500);
      for (const p of w.patterns) {
        expect(p.length).toBeLessThanOrEqual(500);
        expect(() => new RegExp(js(p), "iu")).not.toThrow();
        // Only the "next word" group captures, and only where unless_next reads it.
        const capturing = (p.match(/\((?!\?)/g) ?? []).length;
        expect(capturing, p).toBeLessThanOrEqual(w.unless_next ? 1 : 0);
      }
    }
  });

  it("catch the Eval's words and let the job's own through", () => {
    const edge = "(?:^|[^\\p{L}\\p{N}])";
    const claims = (text: string) => COACH_WORDING.some((w) => w.patterns.some((p) => {
      for (const m of text.matchAll(new RegExp(`${edge}(?:${js(p)})(?=$|[^\\p{L}\\p{N}])`, "giu"))) {
        const next = (m[1] ?? "").trim().toLowerCase();
        if (next && (w.unless_next ?? []).includes(next)) continue;
        return true;
      }
      return false;
    }));
    for (const bad of [
      "I'm connecting Google Sheets with read-only access to cells.", "Je connecterai ensuite Google Sheets en lecture seule.", "It won't change anything.",
      // Eval 44005248914, Gmail EN: a sign-in's breadth said in Coach's own words. The card shows it, from the grant.
      "Google's sign-in asks for full mail access, but the taf will only be able to read.",
      "Google fragt nach vollem E-Mail-Zugriff.", "Google pide acceso completo a tu correo.", "Google demande un accès complet à votre messagerie.",
      "Google chiede l'accesso completo alla posta.", "Google vraagt om volledige toegang tot je mail.", "Google poprosi o pełny dostęp do poczty.",
      "O Google pede acesso total ao seu correio.",
      // A sign-in breadth claim with the app's name between "full" and "access", and the curly apostrophe Coach writes in French and Italian.
      "Google will ask you for full access to Gmail.", "The sign-in shows full Gmail access, but the taf only reads.",
      "Google chiede l’accesso completo.", "Google demandera l’accès complet à votre messagerie.",
      // Eval 44099835231: Coach's progress line said the connection only reads, with planque's sign-in wide (Gmail EN run 1,
      // Sheets FR run 2). The same order in each language.
      "Connecting your Gmail, reading only.", "Je connecte Google Sheets pour Total de la semaine, en lecture des cellules seulement.",
      "Connecting Google Sheets with read access only.", "Ich verbinde Google Sheets nur zum Lesen.",
      "Conecto Google Sheets en modo lectura de celdas solamente.", "Collego Google Sheets in lettura delle celle soltanto.",
      "Ik koppel Google Sheets alleen om te lezen.", "Ligo o Google Sheets em leitura das células apenas.",
      "Je connecte Google Sheets, lecture uniquement.",
      // Said of the connection, adverbially: es "sólo para leer", pt "apenas para leitura".
      "Conecto Gmail, sólo para leer.", "Conecto Google Sheets solo para lectura.", "A ligar o Gmail, apenas para leitura.", "Ligo o Google Sheets só para ler.",
      // Still the connection, after Eval 44128040350's unless_next words: no job object follows "read only".
      "Connecting Gmail, read only.", "It uses a read only connection.",
      // "from", "mail" and "whatever" can follow the label, so they stay caught.
      "Gmail stays read only from now on.", "It uses a read only mail connection.", "It's read only whatever you approve.",
      // "what" can follow an access claim too, so Eval 44128040350's Gmail EN run-2 line stays caught: Coach says what the taf reads instead.
      "I'll set Accountant emails to read only what accounts@example.com sends you.", "The sign-in can read only what the taf needs.",
      // The prompt audit: an app or its data said to be left unchanged, which Setup's core bans, now caught by the
      // contract rather than the model alone. The lines, one claim form each: is not changed, stays, is left.
      "Your inbox isn't changed.", "Your Gmail stays as it is.", "The sheet is left untouched.", "It leaves your invoices unchanged.",
      "Dein Postfach wird nicht geändert.", "Deine Tabelle bleibt unverändert.", "Es lässt deine Rechnungen unangetastet.",
      "Tu bandeja no se modifica.", "Tu hoja queda intacta.", "Tus facturas siguen sin cambios.",
      "Votre boîte n'est pas modifiée.", "Votre feuille reste inchangée.", "Il laisse vos factures intactes.",
      "La tua casella non viene modificata.", "Il foglio rimane invariato.", "Lascia le tue fatture intatte.",
      "Je inbox wordt niet gewijzigd.", "Je sheet blijft ongewijzigd.", "Het laat je facturen onaangeroerd.",
      "Twoja skrzynka nie zostanie zmieniona.", "Arkusz pozostaje bez zmian.", "Zostawia twoje faktury nietknięte.",
      "A sua caixa não é alterada.", "A folha fica intacta.", "Deixa as suas faturas inalteradas.",
    ]) {
      expect(claims(bad), bad).toBe(true);
    }
    for (const good of ["It will read only the rows added since its last run.", "Reading only the new rows from your sheet.",
      "Total de la semaine lit les nouvelles lignes, sans rien écrire.", "El taf solo lee los correos nuevos.", "O taf só lê as linhas novas da folha.", "Connecting your Gmail…", "If you decline, nothing changes.", "The taf only gets the tools to read.", "Full name and email are on the card.", "The taf reads your full inbox history once.",
      // "read" as the job's verb, with "unread" or "incoming" after "only".
      "It will read only the Leads sheet.", "The taf will read only incoming replies.", "It will read only unread replies.",
      // "Unchanged" as the job's own word: a taf that forwards something as it came is no claim about the app.
      "Nothing is sent until you approve.",
      // A held write said as it is: "nothing is changed until you approve" is true of a gated write, so the
      // "nothing is changed" and "without changing anything" forms aren't rules in any language.
      "Nothing is changed in Stripe until you approve each refund.", "Nothing is changed before you approve.",
      "Rien n'est modifié tant que vous n'avez pas approuvé.", "Nichts wird geändert, bevor du zustimmst.",
      "It forwards each invoice unchanged to your accountant.", "It copies each new row into the sheet.", "It keeps the subject line as you wrote it.",
      "Es leitet jede Rechnung unverändert an deine Buchhaltung weiter.",
      "Reenvía cada factura sin cambios a tu gestor.",
      "Il transfère chaque facture telle quelle à votre comptable.",
      "Inoltra ogni fattura invariata al tuo commercialista.",
      "Het stuurt elke factuur ongewijzigd door naar je boekhouder.",
      "Przesyła każdą fakturę bez zmian do księgowego.",
      "Reencaminha cada fatura inalterada para o seu contabilista.",
    ]) {
      expect(claims(good), good).toBe(false);
    }
  });
});
