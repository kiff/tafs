import { describe, expect, it } from "vitest";
import { EVERY_CALL_CONDITIONS, EVERY_CALL_JOINS, EVERY_CALL_THRESHOLDS } from "../src/cloud/every-call-thresholds.js";
import { EVERY_CALL_SPACED, EVERY_CALL_UNSPACED } from "../src/cloud/every-call-words.js";
import { saysEveryCallAsks } from "../src/cloud/rules.js";

// The look after the verb that asks, in the 36 languages beyond the eight (every-call-thresholds.ts): a figure behind a word
// that sets a threshold, in the same clause, is a cap that asks past it, not every call. Another rule's figure, after the
// language's "and" or "but", or a comma not followed by a condition, leaves every call read.
// Figure after the verb, set off by a threshold word: a cap, never every call.
const AFTER_NO = {
  en: ["Every refund asks you first when it's over 5 EUR.", "Each refund waits for your approval when it's above 5 euros.", "Each refund needs your approval on your phone once it's over 5 EUR; 50 EUR a day at most.", "Every refund asks you first if it exceeds €5."],
  fr: ["Chaque remboursement vous demande d'abord votre accord s'il dépasse 5 €.", "Chaque remboursement attend votre accord au-delà de 5 €."],
  de: ["Jede Erstattung fragt dich zuerst, wenn sie über 5 € liegt.", "Jede Erstattung wartet auf deine Zustimmung ab 5 €."],
  es: ["Cada reembolso te pregunta primero cuando supera 5 €.", "Cada reembolso espera tu aprobación si es de más de 5 €."],
  it: ["Ogni rimborso ti chiede prima quando supera 5 €.", "Ogni rimborso aspetta la tua approvazione oltre 5 €."],
  nl: ["Elke terugbetaling vraagt je eerst als die boven € 5 komt.", "Elke terugbetaling wacht op je goedkeuring vanaf € 5."],
  pl: ["Każdy zwrot pyta cię najpierw, gdy przekracza 5 €.", "Każdy zwrot czeka na twoją zgodę powyżej 5 €."],
  pt: ["Cada reembolso espera a sua aprovação quando passa de 5 €.", "Cada reembolso passa primeiro por você se for acima de 5 €."],
  "pt-BR": ["Cada reembolso pergunta a você quando passar de 5 €.", "Cada pagamento pede sua aprovação acima de 5 €."],
  ar: ["كل استرداد يسألك أولاً إذا تجاوز 5 يورو.", "كل عملية دفع تنتظر موافقتك عندما تزيد عن 5 يورو."],
  bg: ["Всяко възстановяване първо ви пита, когато е над 5 €."],
  ca: ["Cada reemborsament et pregunta primer quan supera 5 €."],
  cs: ["Každá refundace se vás nejdřív zeptá, když přesáhne 5 €."],
  el: ["Κάθε επιστροφή χρημάτων σε ρωτά πρώτα όταν ξεπερνά τα 5 €."],
  et: ["Iga tagasimakse küsib sinult kõigepealt, kui see on üle 5 €."],
  he: ["כל החזר שואל אותך קודם כשהוא מעל 5 €."],
  hr: ["Svaki povrat vas prvo pita kada je iznad 5 €."],
  hu: ["Minden visszatérítésnél először megkérdez, ha 5 € felett van."],
  id: ["Setiap pengembalian dana bertanya kepada Anda dulu jika lebih dari 5 €."],
  lt: ["Kiekvienas grąžinimas pirmiausia klausia jūsų, kai viršija 5 €."],
  lv: ["Katra atmaksa vispirms jautā jums, ja tā pārsniedz 5 €."],
  mk: ["Секое враќање прво ве прашува кога е над 5 €."],
  ro: ["Fiecare rambursare te întreabă mai întâi când depășește 5 €."],
  sk: ["Každá refundácia sa vás najprv spýta, keď presiahne 5 €."],
  sl: ["Vsako vračilo vas najprej vpraša, ko presega 5 €."],
  sq: ["Çdo rimbursim ju pyet fillimisht kur kalon 5 €."],
  "sr-Latn": ["Svaki povraćaj vas prvo pita kada je iznad 5 €."],
  vi: ["Mỗi khoản hoàn tiền đều hỏi bạn trước khi vượt quá 5 €."],
  tr: ["Her iade önce size sorar, eğer 5 €'yu aşarsa."],
  hy: ["Յուրաքանչյուր վերադարձ նախ ձեզ հարցնում է, եթե 5 €-ից ավելի է։"],
  hi: ["हर रिफ़ंड पहले आपसे पूछता है, अगर वह 5 € से ज़्यादा हो।"],
  ur: ["ہر ریفنڈ پہلے آپ سے پوچھتا ہے، اگر وہ 5 € سے زیادہ ہو۔"],
  bn: ["প্রতিটি রিফান্ড আগে আপনাকে জিজ্ঞাসা করে, যদি তা 5 ইউরোর বেশি হয়।"],
  "zh-Hant": ["每一筆退款都會先詢問您，如果超過 5 歐元。"],
  th: ["ทุกการคืนเงินจะถามคุณก่อน ถ้าเกิน 5 €"],
};
// Still every call: a ceiling after the verb, or a figure that isn't set off by a threshold word.
const AFTER_YES = [
  "Every refund asks you first, up to €50 a day.", "Each refund waits for your approval; 50 EUR a day at most.",
  "Every refund asks you first. A refund over 50 EUR is refused.",
  "Chaque remboursement attend votre accord, jusqu'à 50 € par jour.", "Jede Erstattung fragt dich zuerst, bis zu 50 € am Tag.",
  "Cada reembolso te pregunta primero, hasta 50 € al día.", "Każdy zwrot czeka na twoją zgodę, do 50 € dziennie.",
  "Fiecare rambursare te întreabă mai întâi, până la 50 € pe zi.", "Mỗi khoản hoàn tiền đều hỏi bạn trước, tối đa 50 € mỗi ngày.",
  "Every refund asks you first, and refunds stop after 3 a day.",
];
// Another rule's figure after "and"/"but" (or a comma not followed by a condition): every call still read.
const JOIN_YES = [
  "Každá refundace se vás nejdřív zeptá, a refundace nad 200 € denně se odmítají.", "Fiecare rambursare te întreabă mai întâi, iar peste 200 € pe zi se refuză.",
  "كل استرداد يسألك أولاً، ويُرفض ما يتجاوز 200 يورو في اليوم.", "כל החזר שואל אותך קודם, ומעל 200 € ביום נדחה.",
  "Her iade önce size sorar ve günde 200 € üzeri reddedilir.", "हर रिफ़ंड पहले आपसे पूछता है और प्रतिदिन 200 € से ऊपर अस्वीकार होता है।",
  "Mỗi khoản hoàn tiền đều hỏi bạn trước, và trên 200 € mỗi ngày sẽ bị từ chối.", "每一筆退款都會先詢問您，而且每天超過 200 歐元會被拒絕。",
  "Setiap pengembalian dana bertanya kepada Anda dulu, tetapi lebih dari 200 € per hari ditolak.", "Svaki povrat vas prvo pita, a iznad 200 € dnevno se odbija.",
  "Každá refundácia sa vás najprv spýta, nad 200 € denne sa odmieta.",
];

describe("a threshold after the verb that asks, in the 36 other languages", () => {
  it("has the three maps for every language it reads, but ja and ko", () => {
    for (const lang of [...Object.keys(EVERY_CALL_SPACED), ...Object.keys(EVERY_CALL_UNSPACED)]) {
      if (lang === "ja" || lang === "ko") continue;
      expect(EVERY_CALL_THRESHOLDS[lang], lang).toBeTruthy();
      expect(EVERY_CALL_CONDITIONS[lang], lang).toBeTruthy();
      expect(EVERY_CALL_JOINS[lang], lang).toBeTruthy();
    }
    expect(EVERY_CALL_THRESHOLDS.ja).toBeUndefined();
    expect(EVERY_CALL_THRESHOLDS.ko).toBeUndefined();
  });

  for (const [lang, texts] of Object.entries(AFTER_NO)) {
    it(`${lang}: a figure after the verb, behind a threshold word, is a cap`, () => {
      for (const text of texts) expect(saysEveryCallAsks(text), text).toBe(false);
    });
  }

  it("still reads every call with a ceiling after the verb, or another rule's figure after a join", () => {
    for (const text of [...AFTER_YES, ...JOIN_YES]) expect(saysEveryCallAsks(text), text).toBe(true);
  });

  // NFC writes the Greek semicolon (U+0387) as U+00B7: it ends a clause in that form, or the look ran into the next rule.
  it("ends a Greek clause at its semicolon, as NFC writes it", () => {
    expect(saysEveryCallAsks("Κάθε επιστροφή σε ρωτά πρώτα· πάνω από 200 € την ημέρα απορρίπτεται.")).toBe(true);
    expect(saysEveryCallAsks("Κάθε επιστροφή σε ρωτά πρώτα όταν ξεπερνά τα 5 €.")).toBe(false);
  });

  // Catalan's l·l uses the same middle dot inside a word: it's read as ll, never as a clause's end.
  it("reads Catalan's l·l as one word", () => {
    expect(saysEveryCallAsks("Cada reemborsament et pregunta primer quan la col·lecció supera 5 €.")).toBe(false);
    expect(saysEveryCallAsks("Cada reemborsament de la col·lecció et pregunta primer.")).toBe(true);
  });
});
