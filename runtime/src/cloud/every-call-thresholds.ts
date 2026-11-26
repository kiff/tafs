// A figure after the verb that asks, set by an amount word right beside it in the same clause, is a cap, not every call
// ("every refund asks you first when it's over 5 EUR"), in the 35 app languages beyond the eight and pt-BR's own forms.
// A condition word ("when", "if") sets nothing alone: "every refund asks you first over the phone, up to €50 a day" is
// every call. The look ends at a stop, at a comma unless a word that opens a condition follows it (EVERY_CALL_CONDITIONS:
// German-style grammars set a comma before "if"/"when" by rule), and at the language's "and"/"but" (EVERY_CALL_JOINS, whole
// patterns, as EVERY_CALL_CLAUSE_STARTS), so "every refund asks you first, and refunds over €200 a day are refused" stays
// every call. Words are matched whole, except in scripts written without spaces (zh-Hant, th, lo, km). ja and ko put a
// condition before the verb ("5ユーロを超える場合"), where the figure already sits between "every" and the verb, so they have
// none. A word this misses reads as every call: the rules are shown again, the safe side.
/**
 * The words that set an amount's threshold with the figure right after them ("over €5", "über 5 €", "trên 5 €"): only a
 * currency sign or code, or a word of up to three letters (an article: "τα 5 €"), may come between. Alone, or before other
 * words, they set nothing: "over the phone", "über das Handy", "preko telefona" and "přes telefon" are no threshold.
 */
export const EVERY_CALL_AMOUNTS: Record<string, string> = {
  "pt-BR": "acima de|mais de|ultrapassa|ultrapassar|excede|superior a|passa de|passar de|a partir de",
  ar: "فوق|أكثر من|اكثر من|يتجاوز|تتجاوز|تجاوز|تجاوزت|يزيد عن|تزيد عن|يزيد على|تزيد على",
  bg: "над|повече от|надвишава|надхвърля", ca: "més de|per sobre de|supera|superior a|a partir de",
  cs: "nad|přes|více než|víc než|přesáhne|přesahuje|překročí",
  el: "πάνω από|άνω των|άνω του|περισσότερο από|ξεπερνά|ξεπερνάει|υπερβαίνει", et: "üle|rohkem kui|ületab",
  he: "מעל|יותר מ\\p{L}*|עולה על|חורג מ\\p{L}*", hr: "iznad|preko|više od|premašuje|prelazi",
  hu: "több mint|meghaladja|túllépi", id: "di atas|lebih dari|melebihi", lt: "virš|daugiau nei|daugiau kaip|viršija",
  lv: "virs|vairāk nekā|pārsniedz", mk: "над|повеќе од|надминува", ro: "peste|mai mult de|mai mare de|depășește",
  sk: "nad|viac ako|viac než|presiahne|prekročí", sl: "nad|več kot|presega|preseže",
  sq: "mbi|më shumë se|më tepër se|kalon|tejkalon", "sr-Latn": "iznad|preko|više od|premašuje|prelazi",
  vi: "trên|hơn|vượt quá|vượt|lớn hơn|quá", "zh-Hant": "超過|高於|大於", th: "เกิน|มากกว่า", lo: "ເກີນ|ຫຼາຍກວ່າ",
  km: "លើស|ច្រើនជាង",
};

/**
 * The words that set it with the figure right before them, in languages that put the figure first ("5 € से ज़्यादा",
 * "5 €'yu aşarsa", "5 € felett", "5 歐元以上"): within 15 characters of it, in the same clause.
 */
export const EVERY_CALL_AMOUNTS_AFTER: Record<string, string> = {
  hu: "felett|fölött|feletti|fölötti|felettit|fölöttit",
  tr: "üzeri|üstü|üzerinde|aşarsa|aşan|aşınca|geçerse|geçen|fazla", az: "çox|artıq|yuxarı|keçərsə|keçən",
  tk: "köp|ýokary|geçse|geçýän", hy: "ավելի|գերազանցի|գերազանցում", ka: "მეტი|ზემოთ|აღემատება|გადააჭარბებს",
  mn: "дээш|илүү|хэтэрвэл|хэтэрсэн", hi: "से ज़्यादा|से ज्यादा|से अधिक|से ऊपर", ur: "سے زیادہ|سے اوپر",
  ne: "भन्दा बढी|भन्दा माथि|भन्दा बढ्ता", bn: "এর বেশি|-এর বেশি|ইউরোর বেশি|বেশি", "zh-Hant": "以上",
};
/** The words that open a condition, which a comma before them doesn't end the look at. They set no threshold alone. */
export const EVERY_CALL_CONDITIONS: Record<string, string> = {
  "pt-BR": "quando|se|caso|assim que", ar: "إذا|اذا|إن|عندما|متى", bg: "когато|ако|щом", ca: "quan|si|un cop", cs: "když|pokud|jakmile|jestliže",
  el: "όταν|αν|εάν|μόλις", et: "kui", he: "כאשר|כש\\p{L}*|אם", hr: "kada|kad|ako|čim", hu: "ha|amikor|amint|amennyiben",
  id: "jika|kalau|apabila|bila|ketika", lt: "kai|jei|jeigu", lv: "kad|ja|tiklīdz", mk: "кога|ако|штом", ro: "când|dacă|odată ce",
  sk: "keď|ak|akonáhle", sl: "ko|če|takoj ko", sq: "kur|nëse|sapo", "sr-Latn": "kada|kad|ako|čim", vi: "khi|nếu|một khi",
  tr: "eğer|şayet", az: "əgər", tk: "eger", hy: "եթե|երբ", ka: "თუ|როცა|როდესაც", mn: "хэрэв", hi: "अगर|जब|यदि", ur: "اگر|جب",
  ne: "यदि|जब", bn: "যদি|যখন", "zh-Hant": "如果|若|當", th: "ถ้า|หาก|เมื่อ", lo: "ຖ້າ|ເມື່ອ", km: "ប្រសិនបើ|បើ|ពេល",
};
/** "and"/"but" as whole patterns (Arabic and Hebrew write "and" as a prefix: a word starting with و / ו after a space). */
export const EVERY_CALL_JOINS: Record<string, string> = {
  "pt-BR": "\\s(?:e|mas)\\s", ar: "\\s(?:لكن|بينما|ثم)\\s|\\sو(?=\\p{L})", bg: "\\s(?:и|но|а)\\s", ca: "\\s(?:i|però|mentre)\\s",
  cs: "\\s(?:a|ale|zatímco)\\s", el: "\\s(?:και|αλλά|ενώ)\\s", et: "\\s(?:ja|aga|kuid)\\s", he: "\\s(?:אבל|אך|בעוד)\\s|\\sו(?=\\p{L})",
  hr: "\\s(?:i|a|ali|dok)\\s", id: "\\s(?:dan|tetapi|tapi|sedangkan)\\s", lt: "\\s(?:ir|bet|o)\\s", lv: "\\s(?:un|bet|taču)\\s",
  mk: "\\s(?:и|но|а)\\s", ro: "\\s(?:și|dar|iar)\\s", sk: "\\s(?:a|ale)\\s", sl: "\\s(?:in|a|ampak|toda)\\s", sq: "\\s(?:dhe|por|ndërsa)\\s",
  "sr-Latn": "\\s(?:i|a|ali|dok)\\s", vi: "\\s(?:và|nhưng|còn)\\s",
  tr: "\\s(?:ve|ayrıca|ancak|ama)\\s", az: "\\s(?:və|həmçinin|amma|lakin)\\s", tk: "\\s(?:we|hem-de|emma)\\s",
  hy: "\\s(?:և|եւ|ու|իսկ|բայց)\\s", ka: "\\s(?:და|ხოლო|მაგრამ)\\s", mn: "\\s(?:ба|бөгөөд|харин)\\s", hu: "\\s(?:és|valamint|de)\\s",
  hi: "\\s(?:और|तथा|एवं|लेकिन)\\s", bn: "\\s(?:এবং|আর|কিন্তু|তবে)\\s", ur: "\\s(?:اور|لیکن|مگر)\\s", ne: "\\s(?:र|तथा|अनि|तर)\\s",
  "zh-Hant": "並且|而且|另外|但是", th: "และ|แต่", lo: "ແລະ|ແຕ່", km: "និង|ហើយ|ប៉ុន្តែ",
};
