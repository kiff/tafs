/**
 * The endings kiff/agents' show writes after each line of Coach's rules notice, by where the line says it's kept (show's
 * KeptWords): the connection holds it, KIFF adds it, or it's in the taf's instructions. A Card's own line has none. Coach sends
 * each line with its `kept` and never types an ending, so the owner always reads these words, in their language, and the checks
 * after it read the claim as data. The words in Coach's 8 languages: "keeps it" means the connection makes sure of it,
 * not that it stores it; "its" is the taf's, agreeing with each language's gender for taf; no capital, no full stop, since an
 * ending follows a line that may end with one. Every language has all three (test/coach-kept-words.test.ts), and show refuses a
 * notice in a language without them rather than mixing in another's.
 */
export type Kept = "connection" | "kiff" | "instructions";

export const COACH_KEPT_WORDS: Readonly<Record<string, Readonly<Record<Kept, string>>>> = {
  en: { connection: "(the connection keeps it)", kiff: "(KIFF adds it)", instructions: "(in its instructions)" },
  de: { connection: "(die Verbindung sorgt dafür)", kiff: "(KIFF fügt das hinzu)", instructions: "(in seinen Anweisungen)" },
  es: { connection: "(lo mantiene la conexión)", kiff: "(lo añade KIFF)", instructions: "(en sus instrucciones)" },
  fr: { connection: "(la connexion s'en charge)", kiff: "(KIFF l'ajoute)", instructions: "(dans ses instructions)" },
  it: { connection: "(lo garantisce il collegamento)", kiff: "(lo aggiunge KIFF)", instructions: "(nelle sue istruzioni)" },
  nl: { connection: "(de koppeling zorgt ervoor)", kiff: "(KIFF voegt dit toe)", instructions: "(in zijn instructies)" },
  pl: { connection: "(pilnuje tego połączenie)", kiff: "(dodaje to KIFF)", instructions: "(w jego instrukcjach)" },
  pt: { connection: "(a ligação garante-o)", kiff: "(o KIFF acrescenta-o)", instructions: "(nas suas instruções)" },
};
