import { describe, expect, it } from "vitest";
import { EVERY_CALL_FIGURE_FIRST, EVERY_CALL_SPACED, EVERY_CALL_UNSPACED } from "../src/cloud/every-call-words.js";
import { cardsFromRules, saysEveryCallAsks, type AgreedRule } from "../src/cloud/rules.js";
import { EVERY_CALL_SAMPLES } from "./every-call-samples.js";

// Words that say every call of a money tool asks the owner, read in all 44 app languages: the eight rules.ts reads, and
// the other 36 (35, and pt-BR's own forms) in every-call-words.ts. A figure before the noun ("every refund over €5 asks
// you") is never every call.
describe("the words that say every call asks, in every app language", () => {
  it("has samples for each language it reads, and reads no language without them", () => {
    const read = [...Object.keys(EVERY_CALL_SPACED), ...Object.keys(EVERY_CALL_UNSPACED)];
    for (const lang of read) expect(EVERY_CALL_SAMPLES[lang], lang).toBeDefined();
    for (const lang of Object.keys(EVERY_CALL_SAMPLES)) expect(read, lang).toContain(lang);
  });

  for (const [lang, { yes, no }] of Object.entries(EVERY_CALL_SAMPLES)) {
    it(`${lang}: every call asking, and not with a figure or without "every"`, () => {
      for (const text of yes) expect(saysEveryCallAsks(text), text).toBe(true);
      for (const text of no) expect(saysEveryCallAsks(text), text).toBe(false);
    });
  }

  // A figure in the script's own digits is a figure, and a clause ends at the script's own punctuation (، ؛ ؟ ۔ । ॥ ։ ។ ៕ ； ：).
  it("reads a figure in each script's own digits, and stops a clause at each script's own punctuation", () => {
    for (const text of [
      "كل استرداد فوق ٥ يورو يسألك أولاً.", "۵ یورو سے زیادہ ہر رقم کی واپسی آپ سے پوچھتی ہے۔",
      "५ € से ऊपर का हर रिफ़ंड आपसे पूछता है।", "हर ५ € से ऊपर का रिफ़ंड आपसे पूछता है।",
      "५ यूरोभन्दा माथिको हरेक फिर्ता तपाईंलाई सोध्छ।", "৫ ইউরোর বেশি প্রতিটি রিফান্ড আপনাকে জিজ্ঞাসা করে।",
      "５ユーロを超えるすべての返金はあなたに確認します。", "超過５歐元的每筆退款都需要您的核准。",
      "การคืนเงินทุกรายการที่เกิน ๕ ยูโรถามคุณก่อน", "រាល់ការសងប្រាក់លើស ៥ អឺរ៉ូសួរអ្នក", "ທຸກການຄືນເງິນເກີນ ໕ ເອີໂຣຖາມທ່ານ",
      "ការសងប្រាក់វិញលើស ៥ € សួរអ្នកជាមុន។",
    ]) expect(saysEveryCallAsks(text), text).toBe(false);
    for (const text of [
      "प्रति दिन 50 € तक। हर रिफ़ंड पहले आपसे पूछता है।", "روزانہ 50 یورو تک۔ ہر رقم کی واپسی آپ سے پوچھتی ہے۔",
      "حتى 50 يورو يوميًا، وكل استرداد يسألك أولاً.",
      "रिफ़ंड की सीमा 50 € प्रतिदिन है। हर रिफ़ंड पहले आपसे पूछता है।", "ریفنڈ کی حد 50 € روزانہ ہے۔ ہر ریفنڈ پہلے آپ سے پوچھتا ہے۔",
      "Օրական 50 € սահմանաչափ։ Յուրաքանչյուր վերադարձ նախ ձեզ հարցնում է։",
    ]) expect(saysEveryCallAsks(text), text).toBe(true);
  });

  // A daily limit, then "and every … asks you", with no comma, reads as every call where the figure comes after the noun:
  // the guard is only for the languages that put it first (EVERY_CALL_FIGURE_FIRST).
  it("reads a figure earlier in the sentence as no cap in languages that put the figure after the noun", () => {
    for (const text of [
      "Până la 50 € pe zi și fiecare rambursare te întreabă.", "Do 50 € denně a každá platba čeká na vaše schválení.",
      "Maks 50 € per hari dan setiap pembayaran menunggu persetujuan anda.", "Até 50 € por dia e cada reembolso pergunta a você.",
    ]) expect(saysEveryCallAsks(text), text).toBe(true);
    for (const [lang, { yes }] of Object.entries(EVERY_CALL_SAMPLES)) {
      if (EVERY_CALL_FIGURE_FIRST.has(lang)) continue;
      expect(saysEveryCallAsks(`50 € ${yes[0]}`), `${lang}: 50 € ${yes[0]}`).toBe(true);
    }
  });

  it("reads Turkish and Azerbaijani capitals: İ lower-cased drops its combining dot", () => {
    expect(saysEveryCallAsks("Her iade önce SİZE sorar.")).toBe(true);
    expect(saysEveryCallAsks("HER İADE ÖNCE SİZE SORAR.")).toBe(true);
  });

  it("keeps the eight languages' figure cases as they were", () => {
    for (const text of [
      "Every refund over 50 EUR asks you first.", "Each refund past €5 waits for your approval.",
      "A refund over 50 EUR is refused. Every call is pinned to test mode.", "Listing and reading payments is open.",
      "Chaque remboursement de plus de 50 EUR attend votre accord.",
    ]) expect(saysEveryCallAsks(text), text).toBe(false);
    expect(saysEveryCallAsks("Coach caps refunds at €50 a day, and every refund asks you first.")).toBe(true);
  });

  // The case an inherited ask was once refused for (an Eval, 26 Nov): "every refund asks you first" in Hungarian over a €5
  // cap that takes the rule's ask. It is read now, so the rules are shown again for the count of 0 a day that asks.
  it("reads the Hungarian notice over an inherited €5 cap as every call, and gives no Cards for it", () => {
    const hu = "Minden visszatérítés előbb téged kérdez.";
    const inherited: AgreedRule[] = [{ tool: "create_refund", over: "ask", limits: [
      { quantity: "sum(amount)", window: "call", limit: 500 }, { quantity: "sum(amount)", window: "calendar_day", limit: 5000, over: "refuse" }] }];
    const grant = { id: "grt_x", request: { tools: [{ name: "create_refund", title: "Refund a charge" }] } } as never;
    expect(saysEveryCallAsks(hu)).toBe(true);
    expect(cardsFromRules([grant], inherited, "refunds-x", hu)[0]).toMatchObject({ unbuilt: "every_call_unheld" });
    expect(saysEveryCallAsks("Minden 5 € feletti visszatérítés előbb téged kérdez.")).toBe(false);
  });
});
