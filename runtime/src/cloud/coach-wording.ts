/**
 * Coach's wording rules (kiff/agents' show.Wording), as contracts/coach-wording.json keeps them: a card whose own words call a
 * connection, a sign-in or a step read-only, or say it changes nothing, is refused by kiff/agents' show with the rule's fix, so
 * Coach redraws it before anyone sees it (Eval 43975057022: "I'm connecting Google Sheets with read-only access to cells").
 * The Eval's plan gate reads the same rules beside its own list. All 8 languages, by legal-i18n; a pattern's first group is
 * the word after the phrase, and one of unless_next lets it through ("it will read only the rows…" names the job).
 * Kept equal to the contract by test/coach-wording.test.ts: change the contract, then this.
 */
export interface Wording { patterns: string[]; unless_next?: string[]; fix: string }

export const COACH_WORDING: readonly Wording[] = [
  {
    "patterns": [
      "read-only"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "read only(\\s+\\pL+)?"
    ],
    "unless_next": [
      "the",
      "a",
      "an",
      "its",
      "your",
      "my",
      "their",
      "this",
      "that",
      "these",
      "those",
      "new",
      "each",
      "every",
      "rows",
      "lines",
      "emails",
      "messages",
      "pages"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "(?:changes?|modif(?:y|ies)|touch(?:es)?) nothing",
      "(?:won['’]?t|will not|doesn['’]?t|does not|can['’]?t|cannot|can not) (?:change|modify|touch) anything"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "lecture seule",
      "ne (?:change|modifie|touche)(?:ra)? rien",
      "ne peut rien (?:changer|modifier)",
      "aucune modification"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "schreibgeschützt",
      "nur[ -]lesen",
      "nur lesend",
      "nur[ -]lese(?:zugriff|rechte?)",
      "(?:ändert|verändert|rührt) nichts(?: an)?",
      "nichts (?:ändern|verändern)",
      "keine änderungen"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "liest nur(\\s+\\pL+)?"
    ],
    "unless_next": [
      "die",
      "der",
      "den",
      "dem",
      "deine",
      "ihre",
      "seine",
      "neue",
      "neuen",
      "jede",
      "jeden",
      "zeilen",
      "e-mails",
      "nachrichten",
      "seiten"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "(?:sólo|solo|únicamente) (?:de )?lectura",
      "no (?:cambia|cambiará|modifica|modificará|toca|tocará) nada",
      "no puede (?:cambiar|modificar) nada",
      "ningún cambio"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "(?:sólo|solo|únicamente) lee(\\s+\\pL+)?",
      "lee (?:sólo|solo|únicamente)(\\s+\\pL+)?"
    ],
    "unless_next": [
      "las",
      "los",
      "la",
      "el",
      "sus",
      "tus",
      "su",
      "tu",
      "cada",
      "nuevas",
      "nuevos",
      "filas",
      "correos",
      "mensajes",
      "páginas"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "sola lettura",
      "solo (?:in )?lettura",
      "si limita a leggere",
      "non (?:cambia|cambierà|modifica|modificherà|tocca|toccherà) (?:nulla|niente)",
      "non può (?:cambiare|modificare) (?:nulla|niente)",
      "nessuna modifica"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "legge (?:solo|soltanto)(\\s+\\pL+)?"
    ],
    "unless_next": [
      "le",
      "i",
      "gli",
      "il",
      "la",
      "lo",
      "tue",
      "tuoi",
      "sue",
      "suoi",
      "ogni",
      "nuove",
      "nuovi",
      "righe",
      "email",
      "messaggi",
      "pagine"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "alleen[- ]lezen",
      "alleen[- ]lees(?:rechten|toegang)",
      "(?:verandert|wijzigt|raakt) niets(?: aan)?",
      "kan niets (?:veranderen|wijzigen)",
      "geen wijzigingen"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "leest alleen(\\s+\\pL+)?"
    ],
    "unless_next": [
      "de",
      "het",
      "een",
      "je",
      "jouw",
      "zijn",
      "haar",
      "hun",
      "elke",
      "nieuwe",
      "rijen",
      "e-mails",
      "berichten",
      "pagina's"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "(?:tylko|wyłącznie) do odczytu",
      "(?:tylko|wyłącznie) odczyt",
      "ni(?:czego|c) nie (?:zmienia|zmieni|modyfikuje|zmodyfikuje|dotyka|dotknie)",
      "żadnych zmian"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "(?:tylko|wyłącznie) (?:czyta|odczytuje)(\\s+\\pL+)?",
      "(?:czyta|odczytuje) (?:tylko|wyłącznie)(\\s+\\pL+)?"
    ],
    "unless_next": [
      "nowe",
      "wiersze",
      "e-maile",
      "wiadomości",
      "strony",
      "twoje",
      "jego",
      "jej",
      "ich",
      "każdy",
      "każdą",
      "każde"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "(?:só|apenas|somente) (?:de )?leitura",
      "não (?:muda|mudará|altera|alterará|modifica|modificará|toca|tocará) nada",
      "não pode (?:mudar|alterar|modificar) nada",
      "nenhuma alteração"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  },
  {
    "patterns": [
      "(?:só|apenas|somente) lê(\\s+\\pL+)?",
      "lê (?:só|apenas|somente)(\\s+\\pL+)?"
    ],
    "unless_next": [
      "as",
      "os",
      "suas",
      "seus",
      "sua",
      "seu",
      "cada",
      "novas",
      "novos",
      "linhas",
      "emails",
      "e-mails",
      "mensagens",
      "páginas"
    ],
    "fix": "Don't call a connection, a sign-in or a step read-only, or say it changes nothing: how far a sign-in reaches is for the card to show, from the grant, and the taf's Card is what limits what it does. Say what the taf's job does instead."
  }
];
