/**
 * A taf's wording rules (kiff/agents' show.Wording, with unless_done and instead): a message that says it noted,
 * saved to its notes or will remember something is held unless the same run wrote a note (memory, action write) that
 * went through. The agent is told why, writes the note or says something else; past kiff/agents' rewording limit, the
 * claim's sentence is the rule's own words, in the language it was written in, the rest of the message as written. A
 * claim is never said unbacked (a run said "I've also noted the tab name for future runs" and wrote no note; its next
 * run had nothing).
 *
 * One rule per language of Coach's 8, each with its own patterns (a verb and the paraphrases a taf uses: "c'est noté",
 * "in my notes", "for future runs") and its own words in place of the claim. kiff/agents matches without case, on
 * whole words, in RE2. A folder, sheet, tab or id the job needs is the taf's setup, never a note: a note written in a
 * person's chat is kept for that person only, so a door or a schedule never reads it. The fix sends those to Coach.
 */
export interface TafWording { patterns: string[]; unless_done: string[]; instead: string; fix: string }

const APOS = "['’]";

/** What the agent is told when its message claims a note it didn't write. */
export const NOTE_CLAIM_FIX =
  "no note was written in this run, so don't say it's kept. A lasting preference: write it with memory (action write) first, then say so. " +
  "A folder, sheet, tab, link or id the job needs is setup, not a note: say it isn't kept yet, and offer Coach's \"add it to the setup\" with an ask_coach card.";

const rule = (patterns: string[], instead: string): TafWording => ({ patterns, unless_done: ["memory:write"], instead, fix: NOTE_CLAIM_FIX });

export const TAF_WORDING: readonly TafWording[] = [
  rule([
    `i(?:${APOS}ve| have)\\s+(?:also\\s+|now\\s+|already\\s+)?(?:noted|remembered|memori[sz]ed|made a note|taken note|written (?:it |this |that )?down)`,
    `(?:i${APOS}ll|i will)\\s+(?:also\\s+)?(?:remember|keep (?:it|this|that) in mind|note (?:it|this|that))`,
    `(?:saved|stored|kept|added|recorded) (?:it |this |that )?(?:in|to) my (?:notes|memory)`,
    `(?:noted|saved|kept|remembered) for (?:future|next|later|upcoming) (?:runs?|times?|chats?)`,
  ], "I didn't save that. To keep it for every run, ask Coach to add it to my setup."),
  rule([
    `j${APOS}ai (?:bien |aussi |également |déjà )?(?:noté|pris note|retenu|mémorisé|enregistré (?:ça |cela |cette information )?dans mes notes)`,
    `c${APOS}est noté`,
    `je (?:m${APOS}en )?souviendrai`,
    `je (?:le |la |l${APOS})?retiens`,
    `pour les prochaines? (?:exécutions?|fois)`,
  ], "Je ne l'ai pas enregistré. Pour qu'il serve à chaque exécution, demandez à Coach de l'ajouter à ma configuration."),
  rule([
    `ich habe (?:[\\pL-]+ ){0,3}(?:notiert|vermerkt)`,
    `ich habe mir (?:[\\pL-]+ ){0,3}gemerkt`,
    `(?:ist )?notiert`,
    `ich (?:merke|notiere) mir`,
    `ich werde mir (?:[\\pL-]+ ){0,2}merken`,
    `für (?:künftige|zukünftige|die nächsten) (?:läufe|male)`,
  ], "Das habe ich nicht gespeichert. Damit es bei jedem Lauf gilt, bitte Coach, es zu meiner Einrichtung hinzuzufügen."),
  rule([
    `(?:lo |la )?he (?:[\\pL-]+ )?(?:anotado|apuntado|memorizado|tomado nota)`,
    `(?:queda|ya está) anotado`,
    `tomo nota`,
    `(?:lo |la )?recordaré`,
    `para (?:las )?próximas (?:ejecuciones|veces)`,
  ], "No lo he guardado. Para que sirva en cada ejecución, pide a Coach que lo añada a mi configuración."),
  rule([
    `(?:l${APOS})?ho (?:[\\pL-]+ )?(?:annotato|memorizzato|preso nota)`,
    `prendo nota`,
    `(?:me lo |lo )?ricorderò`,
    `per le prossime (?:esecuzioni|volte)`,
  ], "Non l'ho salvato. Perché valga a ogni esecuzione, chiedi a Coach di aggiungerlo alla mia configurazione."),
  rule([
    `(?:já )?(?:anotei|tomei nota|memorizei)`,
    `fica anotado`,
    `(?:vou|irei) (?:me )?lembrar`,
    `lembrarei`,
    `para as próximas (?:execuções|vezes)`,
  ], "Não guardei isso. Para valer em cada execução, peça ao Coach para o adicionar à minha configuração."),
  rule([
    `ik heb (?:[\\pL-]+ ){0,3}(?:genoteerd|onthouden)`,
    `(?:is )?genoteerd`,
    `ik (?:zal )?(?:[\\pL-]+ ){0,2}onthouden`,
    `ik onthoud`,
    `voor (?:volgende|toekomstige) (?:runs|keren)`,
  ], "Dat heb ik niet opgeslagen. Vraag Coach om het aan mijn instellingen toe te voegen, zodat het bij elke run geldt."),
  rule([
    `(?:zanotowa|zapamięta)(?:łem|łam)`,
    `zapisa(?:łem|łam)(?: to| sobie)? w (?:moich )?notatkach`,
    `zapamiętam`,
    `zanotowane`,
    `na (?:przyszłe|kolejne) (?:uruchomienia|razy)`,
  ], "Tego nie zapisano. Aby obowiązywało przy każdym uruchomieniu, poproś Coach o dodanie tego do mojej konfiguracji."),
];

/**
 * Whether tafs carry TAF_WORDING: on once kiff/agents serves unless_done and instead (TAFS_NOTE_CLAIMS=on), since
 * its registry refuses a definition with a field it doesn't know. Off, a definition is as before, so turning it on
 * changes each taf's definition, and the upgrade applies it.
 */
export function noteClaimsOn(): boolean {
  return process.env.TAFS_NOTE_CLAIMS === "on";
}
