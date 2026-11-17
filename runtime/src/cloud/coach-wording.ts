/**
 * Coach's wording rules (kiff/agents' show.Wording), as contracts/coach-wording.json keeps them: a card whose own words call a
 * connection, a sign-in or a step read-only, say it changes nothing, or say how far a sign-in reaches (the card shows that, from the
 * grant; Eval 44005248914: "Google's sign-in asks for full mail access"), is refused by kiff/agents' show with the rule's fix, so
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
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
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
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
  },
  {
    "patterns": [
      "(?:changes?|modif(?:y|ies)|touch(?:es)?) nothing",
      "(?:won['’]?t|will not|doesn['’]?t|does not|can['’]?t|cannot|can not) (?:change|modify|touch) anything"
    ],
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
  },
  {
    "patterns": [
      "lecture seule",
      "ne (?:change|modifie|touche)(?:ra)? rien",
      "ne peut rien (?:changer|modifier)",
      "aucune modification"
    ],
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
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
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
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
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
  },
  {
    "patterns": [
      "(?:sólo|solo|únicamente) (?:de )?lectura",
      "no (?:cambia|cambiará|modifica|modificará|toca|tocará) nada",
      "no puede (?:cambiar|modificar) nada",
      "ningún cambio"
    ],
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
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
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
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
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
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
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
  },
  {
    "patterns": [
      "alleen[- ]lezen",
      "alleen[- ]lees(?:rechten|toegang)",
      "(?:verandert|wijzigt|raakt) niets(?: aan)?",
      "kan niets (?:veranderen|wijzigen)",
      "geen wijzigingen"
    ],
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
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
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
  },
  {
    "patterns": [
      "(?:tylko|wyłącznie) do odczytu",
      "(?:tylko|wyłącznie) odczyt",
      "ni(?:czego|c) nie (?:zmienia|zmieni|modyfikuje|zmodyfikuje|dotyka|dotknie)",
      "żadnych zmian"
    ],
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
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
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
  },
  {
    "patterns": [
      "(?:só|apenas|somente) (?:de )?leitura",
      "não (?:muda|mudará|altera|alterará|modifica|modificará|toca|tocará) nada",
      "não pode (?:mudar|alterar|modificar) nada",
      "nenhuma alteração"
    ],
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
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
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
  },
  {
    "patterns": [
      "(?:asks?|asking|will ask) for full(?: \\pL+)? access",
      "full (?:mail|mailbox|email|calendar|drive|sheets?|account) access",
      "full access to (?:your|the|all)",
      "(?:fragt|bittet|verlangt) (?:nach|um) (?:vollen|vollem|volle|vollständigen|vollständigem) (?:\\pL+-)*\\pL*zugriff",
      "vollzugriff",
      "(?:voller|vollen|vollem|vollständiger|vollständigen) (?:\\pL+-)*\\pL*zugriff auf",
      "(?:pide|pedirá|solicita|solicitará) (?:un |el )?acceso (?:completo|total)",
      "acceso (?:completo|total) (?:a|al|a la|a los|a las|a tu|a tus)",
      "(?:demande|demandera) (?:un |l')?accès (?:complet|total)",
      "accès (?:complet|total) (?:à|au|aux|à votre|à vos)",
      "(?:chiede|chiederà|richiede|richiederà) (?:l'|un )?accesso (?:completo|totale)",
      "accesso (?:completo|totale) (?:a|al|alla|ai|alle|alla tua|ai tuoi)",
      "vraagt (?:om )?volledige toegang",
      "volledige toegang tot",
      "(?:prosi|poprosi) o pełny dostęp",
      "pełny dostęp do",
      "(?:pede|pedirá|solicita|solicitará) (?:o |um )?acesso (?:completo|total)",
      "acesso (?:completo|total) (?:a|ao|à|aos|às)"
    ],
    "fix": "Describe only what the taf's job will do, in plain words. Don't say what a connection or a sign-in can or can't do, or that it changes nothing."
  }
];
