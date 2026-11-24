import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { DISCLOSURE_LINE, DISCLOSURE_MAX_BYTES, disclosureLine, disclosureLineFor, disclosureLines, JOBS, jobById, languageOf, OLD_DISCLOSURE_LINE, ownJob, systemPrompt, WRITES_FOR_MAX_BYTES, writesForIn, writesForOf } from "../src/jobs.js";
import { KIFF_SKILL } from "../src/kiff-skill.js";

/** Every language the apps speak that a disclosure line comes in (tafs app/strings/languages.json, with Hindi, Urdu and Turkmen): the five of #363 and the 37 after them. */
const APP_LANGUAGES = ["ar", "az", "bg", "bn", "ca", "cs", "de", "el", "es", "et", "fr", "he", "hi", "hr", "hu", "hy", "id", "it", "ja", "ka", "km", "ko", "lo", "lt", "lv", "mk", "mn", "ne", "nl", "pl", "pt", "ro", "sk", "sl", "sq", "sr", "th", "tk", "tr", "ur", "vi", "zh"];

describe("the disclosure line, in the message's language (kiff/agents#363)", () => {
  it("names who the messages come from in each language, and tells a text's language as kiff/agents does", () => {
    const acme = { writesFor: "Acme" };
    const lines = disclosureLines(acme);
    expect(lines).toMatchObject({
      es: "Escrito por un agente de IA en nombre de Acme.",
      fr: "Rédigé par un agent d'IA au nom de Acme.",
      de: "Von einem KI-Agenten im Auftrag von Acme geschrieben.",
      pt: "Escrito por um agente de IA em nome de Acme.",
      it: "Scritto da un agente di IA per conto di Acme.",
      hi: "Acme की ओर से AI एजेंट द्वारा लिखा गया।",
      tr: "Acme adına bir yapay zekâ ajanı tarafından yazıldı.",
      ko: "Acme을(를) 대신해 AI 에이전트가 작성했습니다.",
      ar: "كتبه وكيل ذكاء اصطناعي نيابةً عن Acme.",
    });
    // Every language the apps speak has its line, naming who the messages come from, and none is over what kiff/agents takes.
    expect(Object.keys(lines).sort()).toEqual(APP_LANGUAGES);
    for (const [lang, line] of Object.entries(lines)) {
      expect(line, lang).toContain("Acme");
      expect(Buffer.byteLength(line), lang).toBeLessThanOrEqual(DISCLOSURE_MAX_BYTES);
    }
    // Naming no one: the same languages, none with a name in it.
    expect(Object.keys(disclosureLines({})).sort()).toEqual(APP_LANGUAGES);
    for (const [lang, line] of Object.entries(disclosureLines({}))) expect(line, lang).not.toContain("Acme");
    expect(disclosureLines({}).es).toBe("Escrito por un agente de IA.");
    for (const [lang, text] of [
      ["es", "Hecho: te hemos devuelto 42,00 EUR del pedido 1043 a tu tarjeta original. Llegará en 5–10 días."],
      ["fr", "Votre remboursement de 42,00 EUR pour la commande 1043 a été effectué. Merci !"],
      ["de", "Ihre Erstattung von 42,00 EUR für die Bestellung 1043 ist erledigt. Danke!"],
      ["pt", "Seu reembolso de 42,00 EUR do pedido 1043 foi feito. Obrigado!"],
      ["it", "Il rimborso di 42,00 EUR per l'ordine 1043 è stato fatto. Grazie!"],
      ["en", "Your refund of 42.00 EUR for order 1043 has been issued. Thanks!"],
    ] as const) {
      expect(languageOf(text), text).toBe(lang);
    }
    // Too little to tell, or as much of two languages: the default line.
    for (const text of ["OK", "Gracias.", "Hola, gracias. Olá, obrigado."]) {
      expect(disclosureLineFor(acme, text), text).toBe("Written by an AI agent on behalf of Acme.");
    }
    // Estonian has its line now: the sentence the apps give it.
    expect(disclosureLineFor(acme, "Teie tagasimakse on tehtud ja see on meie.")).toBe(lines.et);
    expect(disclosureLineFor(acme, "Hecho: te hemos devuelto el importe del pedido 1043.")).toBe("Escrito por un agente de IA en nombre de Acme.");
    // An html part's tags aren't its words, even English ones in an attribute.
    expect(languageOf('<span title="the order is for you and your thanks">Hola, gracias.</span>')).toBe("es");
  });

  // The same sentences as kiff/agents' agent/language_test.go: a door's reply and a taf's own message pick the same language.
  it("recognises every language the apps speak in a message written in it, as kiff/agents does", () => {
    for (const [lang, text] of [
    ["en", "Your refund has been issued. Thanks for your order."],
    ["es", "Hola, hemos recibido su pedido y el reembolso está en camino. Gracias por su paciencia."],
    ["pt", "Olá, recebemos o seu pedido e o reembolso está a caminho. Obrigado pela paciência."],
    ["fr", "Bonjour, nous avons bien reçu votre commande et le remboursement est en route. Merci de votre patience."],
    ["de", "Hallo, wir haben Ihre Bestellung erhalten und die Rückerstattung ist auf dem Weg. Danke für Ihre Geduld."],
    ["it", "Ciao, abbiamo ricevuto il tuo ordine e il rimborso è in arrivo. Grazie per la pazienza."],
    ["nl", "Uw bestelling is verzonden en wij hebben de factuur ook bijgevoegd. Graag gedaan."],
    ["pl", "Dziękujemy za zamówienie. Twoje zamówienie jest w drodze i nie wymaga nic więcej."],
    ["ca", "Gràcies per la seva comanda. Ens plau informar-vos que és en camí."],
    ["et", "Tere! Teie tellimus on tehtud ja see saadetakse meie ladudest, palun oodake."],
    ["lt", "Labas! Jūsų užsakymas yra išsiųstas ir buvo patvirtintas. Ačiū."],
    ["lv", "Sveiki! Jūsu pasūtījums ir nosūtīts un tas ir apstiprināts. Paldies."],
    ["cs", "Dobrý den, děkujeme za objednávku. Váš balík je na cestě a bude doručen brzy."],
    ["sk", "Dobrý deň, ďakujeme za objednávku. Váš balík je na ceste a bol odoslaný."],
    ["sl", "Zdravo, hvala za naročilo. Vaš paket je na poti in bil je poslan."],
    ["hu", "Szia! Köszönöm a rendelést. A csomag a mi raktárunkból van úton, kérem várjon."],
    ["ro", "Bună! Mulțumim pentru comanda dumneavoastră. Pachetul este în drum și vă rog să așteptați."],
    ["hr", "Hvala na narudžbi. Molim, vaša narudžba je na putu i bio sam sretan da se javim."],
    ["sr", "Hvala na porudžbini. Vaša porudžbina je na putu, šta još mogu da uradim ovde?"],
    ["tr", "Merhaba, siparişiniz için teşekkürler. Paketiniz yolda ve çok yakında teslim edilecek."],
    ["id", "Halo, terima kasih atas pesanan Anda. Paket ini sudah dikirim dan tidak ada masalah."],
    ["vi", "Chào bạn, cảm ơn bạn đã đặt hàng. Đơn hàng của bạn đã được gửi và không có vấn đề."],
    ["sq", "Përshëndetje, faleminderit për porosinë. Paketa juaj është në rrugë dhe nuk ka probleme."],
    ["az", "Salam, sifarişiniz üçün təşəkkür edirik. Bağlama göndərildi və problem yoxdur, biz buradayıq."],
    ["tk", "Salam! Sargydyňyz üçin sagbol. Ammarlama ýolda we hiç hili problema ýok, biz bu ýerde."],
    ["bg", "Здравейте, благодаря за поръчката. Пратката е на път и това е всичко, моля изчакайте."],
    ["mk", "Здраво, благодарам за нарачката. Пратката е на пат и тоа е сè, ве молам почекајте."],
    ["mn", "Сайн байна уу? Захиалга баярлалаа. Таны ачаа замдаа явж байна, энэ бол бүх зүйл."],
    ["ar", "مرحبا، شكرا لطلبك. هذا الطرد في الطريق ونحن هنا إن كان لديك ما تسأل عنه."],
    ["ur", "ہیلو، آپ کے آرڈر کا شکریہ۔ آپ کا پارسل راستے میں ہے اور ہم یہاں ہیں۔"],
    ["ne", "नमस्ते, तपाईंको अर्डरको लागि धन्यवाद। तपाईं र हामी यहाँ छौँ, यो प्याकेज बाटोमा छ।"],
    ["hi", "नमस्ते, आपके ऑर्डर के लिए धन्यवाद। आपका पार्सल रास्ते में है और हम यहाँ हैं।"],
    ["el", "Γεια σας, ευχαριστούμε για την παραγγελία σας."],
    ["he", "שלום, תודה על ההזמנה שלך."],
    ["th", "สวัสดี ขอบคุณสำหรับคำสั่งซื้อของคุณ"],
    ["lo", "ສະບາຍດີ ຂອບໃຈສໍາລັບການສັ່ງຊື້ຂອງທ່ານ"],
    ["km", "សួស្តី អរគុណសម្រាប់ការបញ្ជាទិញរបស់អ្នក"],
    ["ka", "გამარჯობა, გმადლობთ შეკვეთისთვის."],
    ["hy", "Բարեւ, շնորհակալություն պատվերի համար։"],
    ["bn", "নমস্কার, আপনার অর্ডারের জন্য ধন্যবাদ।"],
    ["ko", "안녕하세요, 주문해 주셔서 감사합니다."],
    ["ja", "こんにちは、ご注文ありがとうございます。"],
    ["zh", "您好，感谢您的订购。您的包裹已经寄出。"],
    ] as const) {
      expect(languageOf(text), `${lang}: ${text}`).toBe(lang);
    }
    // Serbian in Cyrillic is "sr" as well, and Japanese written mostly in kanji is still Japanese (it has kana).
    expect(languageOf("Здраво, хвала на поруџбини. Пошиљка је на путу и то је све, молим вас сачекајте.")).toBe("sr");
    expect(languageOf("ご注文の商品は本日発送しました。")).toBe("ja");
    // Urdu is not Arabic, though both are written in the Arabic script; a short Urdu text says no language, never "ar".
    expect(languageOf("ہیلو، آپ کے آرڈر کا شکریہ۔ آپ کا پارسل راستے میں ہے اور ہم یہاں ہیں۔")).toBe("ur");
    expect(languageOf("مرحبا، شكرا لطلبك. هذا الطرد في الطريق ونحن هنا إن كان لديك ما تسأل عنه.")).toBe("ar");
    for (const text of ["پارسل راستے میں", "ابھی", "طلب", "سلام دوست عزیز، خوش آمدید"]) expect(languageOf(text), text).not.toBe("ar");
    // A script several languages share says none by itself, and two that share their words tie: the default line.
    for (const text of ["тест", "नमस्ते", "नमस्ते धन्यवाद", "и е на", "Hvala, molim, je na putu i bio"]) expect(languageOf(text), text).toBe("");
    // The majority script reads, a name in another doesn't change it; half and half, no letters, too few words say none.
    expect(languageOf("안녕하세요, 주문해 주셔서 감사합니다. Order 1043 hello")).toBe("ko");
    expect(languageOf("Gracias, hemos enviado su pedido de 東京 hoy. Gracias por su paciencia.")).toBe("es");
    for (const text of ["", "12345 !!!", "OK", "你好你好 abcd", "안녕 hello world"]) expect(languageOf(text), text).toBe("");
    // Kana says Japanese; Han alone says Chinese; Hangul with some Han says Korean; words with combining marks stay whole.
    expect([languageOf("カタカナ"), languageOf("汉字"), languageOf("한국어 漢字 한국어"), languageOf("ऑर्डर कृपया"), languageOf("अर्डर कृपया")]).toEqual(["ja", "zh", "ko", "hi", "ne"]);
    // The line that heads a message is the one in its language, for any of them.
    const acme = { writesFor: "Acme" };
    expect(disclosureLineFor(acme, "नमस्ते, आपके ऑर्डर के लिए धन्यवाद। आपका पार्सल रास्ते में है और हम यहाँ हैं।")).toBe("Acme की ओर से AI एजेंट द्वारा लिखा गया।");
    expect(disclosureLineFor(acme, "안녕하세요, 주문해 주셔서 감사합니다.")).toBe("Acme을(를) 대신해 AI 에이전트가 작성했습니다.");
  });

  it("leaves out a language whose line wouldn't fit in what kiff/agents takes, and caps a new name so every line fits", () => {
    expect(DISCLOSURE_MAX_BYTES).toBe(300);
    // Greek's line carries the most besides the name (105 bytes: "Γράφτηκε από πράκτορα τεχνητής νοημοσύνης εκ μέρους του …", two bytes a letter), so a new name may be 195 bytes.
    expect(WRITES_FOR_MAX_BYTES).toBe(195);
    const bytes = (s: string) => Buffer.byteLength(s);
    const overhead = (lang: string) => bytes(disclosureLines({ writesFor: "x" })[lang]!) - 1;
    expect(Math.max(...APP_LANGUAGES.map(overhead))).toBe(DISCLOSURE_MAX_BYTES - WRITES_FOR_MAX_BYTES);
    expect(overhead("el")).toBe(DISCLOSURE_MAX_BYTES - WRITES_FOR_MAX_BYTES);
    // A new name at the cap (51 characters, 195 bytes): its line fits in every language, Greek's to the byte.
    const atCap = "😀".repeat(48) + "aaa";
    expect(bytes(atCap)).toBe(195);
    expect(writesForOf(atCap)).toBe(atCap);
    expect(() => writesForOf(atCap + "a")).toThrow("writes_for");
    const all = disclosureLines({ writesFor: atCap });
    expect(Object.keys(all).sort()).toEqual(APP_LANGUAGES);
    expect(bytes(all.el!)).toBe(300);
    for (const l of [disclosureLine({ writesFor: atCap }), ...Object.values(all)]) expect(bytes(l)).toBeLessThanOrEqual(300);
    // A name stored before the cap, or a company's name from KIFF, up to 272 bytes: English fits to the byte, in
    // the shorter "for" it had before "on behalf of", and heads its messages in every language, none other fitting.
    const stored = "😀".repeat(68);
    expect(bytes(disclosureLine({ writesFor: stored }))).toBe(300);
    expect(disclosureLine({ writesFor: stored })).toBe(`Written by an AI agent for ${stored}.`);
    expect(disclosureLine({ writesFor: "Acme" })).toBe("Written by an AI agent on behalf of Acme.");
    expect(writesForIn("Written by an AI agent on behalf of Acme.")).toBe("Acme");
    expect(writesForIn("Written by an AI agent for Acme.")).toBe("Acme");
    // (and lines only for the few languages whose line is shorter still, none of the five of #363)
    const fits = disclosureLines({ writesFor: stored });
    expect(Object.keys(fits).filter((l) => ["es", "fr", "de", "pt", "it"].includes(l))).toEqual([]);
    for (const l of Object.values(fits)) expect(bytes(l)).toBeLessThanOrEqual(300);
    expect(disclosureLineFor({ writesFor: stored }, "Hecho: te hemos devuelto el importe del pedido 1043.")).toBe(disclosureLine({ writesFor: stored }));
    // The prompt lists only the lines that fit, so it never offers the model one that kiff/agents would refuse.
    const stuffed = systemPrompt({ ...jobById("refunds"), writesFor: stored });
    expect(stuffed).not.toContain('es "');
    expect(stuffed).toContain(`Your disclosure line: "${disclosureLine({ writesFor: stored })}"`);
    // In between (220 bytes, a name stored before the cap): the languages whose line fits stay; Greek's (105 besides the name),
    // Hindi's (85) and Khmer's (83) don't.
    const between = "😀".repeat(55);
    expect(bytes(between)).toBe(220);
    const some = disclosureLines({ writesFor: between });
    expect(Object.keys(some)).toEqual(expect.arrayContaining(["es", "fr", "de", "pt", "it"]));
    expect([some.el, some.hi, some.km]).toEqual([undefined, undefined, undefined]);
    expect(some.ne).toBeDefined();
    expect(Object.keys(some).length).toBeLessThan(APP_LANGUAGES.length);
    for (const l of Object.values(some)) expect(bytes(l)).toBeLessThanOrEqual(300);
    expect(disclosureLineFor({ writesFor: between }, "Γεια σας, ευχαριστούμε για την παραγγελία σας.")).toBe(disclosureLine({ writesFor: between }));
  });

  it("asks the taf to start with its line, in the language it writes in, and gives it each one", () => {
    const prompt = systemPrompt({ ...jobById("chargebacks"), writesFor: "Acme" });
    expect(prompt).toContain("it starts with your disclosure line, word for word, in the language");
    expect(prompt).toContain('Your disclosure line: "Written by an AI agent on behalf of Acme."');
    expect(prompt).toContain('es "Escrito por un agente de IA en nombre de Acme."');
    expect(prompt).not.toContain("ends with your disclosure line");
  });
});

describe("jobs", () => {
  it("has the five jobs, each with a valid default schedule", () => {
    expect(JOBS.map((j) => j.id)).toEqual(["refunds", "billpay", "access", "deploys", "chargebacks"]);
    for (const j of JOBS) expect(j.cron.split(" ")).toHaveLength(5);
  });

  // Eval 44076575553's chargebacks: one run retyped a dispute id and Stripe found no such dispute; another told one
  // dispute's failed attempt as another's. The job copies ids from the record and keeps each dispute's calls to it.
  it("has the chargebacks taf copy ids from what a tool returned and tell each dispute from its own calls", () => {
    const words = jobById("chargebacks").instructions;
    expect(words).toContain("Copy each dispute's id, and any order or ticket id, from what a tool returned,\nnever typed from memory.");
    expect(words).toContain("Tell each dispute from its own calls: an attempt that\nfailed on one dispute is never part of another's story.");
    expect(systemPrompt({ ...jobById("chargebacks"), writesFor: "Acme" })).toContain("never typed from memory");
  });

  it("builds a taf's own job from what Coach drafted: any job, under the same rules as the five (kiff/tafs#476)", () => {
    const own = ownJob({ name: "Invoices", summary: "Reminds customers of overdue invoices.", kickoff: "Find every overdue invoice and remind its customer once.", instructions: "Never change an invoice." });
    expect(own).toEqual({ id: "custom", name: "Invoices", summary: "Reminds customers of overdue invoices.", kickoff: "Find every overdue invoice and remind its customer once.", cron: "",
      instructions: "What this job does: Reminds customers of overdue invoices.\n\nNever change an invoice." });
    const prompt = systemPrompt(own);
    const others = Object.entries(disclosureLines({})).map(([lang, line]) => `${lang} "${line}"`).join(", ");
    expect(others.startsWith('es "Escrito por un agente de IA.", fr "Rédigé par un agent d\'IA.", de "Von einem KI-Agenten geschrieben.", pt "Escrito por um agente de IA.", it "Scritto da un agente di IA."')).toBe(true);
    expect(prompt).toContain(`Your job: Invoices.\n\nYour disclosure line: "Written by an AI agent."\nIn other languages: ${others}\n\nWhat this job does: Reminds customers of overdue invoices.\n\nNever change an invoice.`);
    // Who it writes for, once the owner said (gaps 16 and 18): never "for the company".
    expect(systemPrompt({ ...own, writesFor: "Kiff Agents OÜ" })).toContain('Your disclosure line: "Written by an AI agent on behalf of Kiff Agents OÜ."');
    expect(prompt).toContain("kiff_pending");
    // With nothing drafted but the name, it still says what it is.
    expect(ownJob({ name: "Leads", summary: " ", kickoff: "x" }).instructions).toBe("You do this job for the company: Leads.");
    expect(ownJob({ name: "Leads", summary: "", kickoff: "x", instructions: "Reply within the hour." }).instructions).toBe("Reply within the hour.");
  });

  it("tells every taf how to work under a Card", () => {
    for (const j of JOBS) {
      const prompt = systemPrompt(j);
      expect(prompt).toContain("kiff_pending");
      expect(prompt).toContain("kiff_operation_id");
      expect(prompt).toContain("never repeat the call");
      expect(prompt).toContain("kiff skill");
      // On kiff/agents a held call pauses the run until the owner answers.
      expect(prompt).toContain("your run pauses until the owner answers");
      // A refused call follows the kiff skill (its retry, the owner's named step).
      expect(prompt).toContain("a refused call ends that\n  item as the kiff skill says");
      // The owner's refusal is the reason; the taf adds none (kiff/tafs#138, item 33).
      expect(prompt.replace(/\s+/g, " ")).toContain("When the owner refused it, say that they refused it and nothing was sent, and give no reason they didn't give.");
      // The owner reads it in tafs: no link, no "in the tafs app" (item 25).
      expect(prompt).not.toContain("tafs app");
      expect(prompt).not.toContain("Never retry a refused");
      // A hold pauses the run where it is: what the Card allows goes first, and
      // the report names each item and what became of it (kiff/agents#78).
      expect(prompt).toContain("leave any write that may need the owner's approval");
      // A click the Card allowed was narrated as "a click, which needs approval" while nothing was held (the 0.2.2 try).
      expect(prompt.replace(/\s+/g, " ")).toContain("Before KIFF answers, never say a call needs or waits for the owner's approval, in the chat or as you work: a write your Card allows simply runs. A call waits for approval only once KIFF holds it.");
      // An item left behind a hold was explained with a limit no read gave (Chargebacks, 20 Nov, 2 runs of 3).
      expect(prompt.replace(/\s+/g, " ")).toContain("An item you didn't reach because your run paused on a hold is said as just that (\"A-1048: not done, since my run paused on the hold first\"), with no limit named for it: you never made its call, so you can't know what KIFF would have done with it.");
      // Tools' descriptions carry the Card's line, so "no limit was read" was untrue (Eval 44732158037).
      expect(prompt).not.toContain("no limit was read for it");
      // A first write made with placeholder evidence to see what KIFF does paused the run on its hold (Eval 44732158037, chargebacks run 2).
      expect(prompt.replace(/\s+/g, " ")).toContain("Every write is the item's real one, with its real details: never a write to see what KIFF does with it.");
      // Each turn is a wait: the owner's Stripe taf took 5 turns in a row per message,
      // 4-5 s each (kiff/tafs#138, item 58). Independent calls go in one turn.
      expect(prompt.replace(/\s+/g, " ")).toContain("make reads that don't need each other's answers together, in one turn: kiff_pending with your first reads, and every read of one item at once.");
      // A write sent beside another on the same item outran KIFF's refusal of it
      // (Chargebacks, 21 Oct 15:52: close_dispute refused, update_dispute sent with it).
      expect(prompt.replace(/\s+/g, " ")).toContain("Writes are not batched: make one write on an item, read KIFF's answer, then decide the next");
      // A retry on "No such dispute" typed the id from memory again (Chargebacks, 20 Nov run 1).
      expect(prompt.replace(/\s+/g, " ")).toContain("When a tool answers that an item doesn't exist (\"No such …\"), find its id again in what you read, or read the item again, before you call once more: never a second try with an id typed from memory.");
      // A list says what's needed of each item: reading one again costs a turn (kiff/tafs#206).
      expect(prompt.replace(/\s+/g, " ")).toContain("Never look up a value a tool's description says KIFF always sends, or read an item again for what a list you just read already says of it");
      // KIFF decides the Card, not the taf: it decided a hold itself, so the call
      // never reached the owner (kiff/tafs#206).
      expect(prompt.replace(/\s+/g, " ")).toContain("KIFF decides what your Card allows, not you.");
      // A table when the owner asks for one: the app draws them (kiff/tafs#236, item 29).
      expect(prompt.replace(/\s+/g, " ")).toContain("a table only when the owner asks for one");
      // A deadline as time left, never in UTC (item 26).
      expect(prompt.replace(/\s+/g, " ")).toContain("no clock times or time zones, a deadline included");
      expect(prompt).toContain("Name each item by its reference and what\nbecame of it");
      // A taf pasted a calendar's raw answer, with an address and a link, as its answer to Coach's task (20 Nov 2026).
      expect(prompt.replace(/\s+/g, " ")).toContain("words, never a tool's result pasted as it came (its JSON, its list of fields): say what you read");
      // A report named a $100 limit it never read, and counted an item it never
      // acted on as a call (kiff/tafs#1512).
      expect(prompt.replace(/\s+/g, " ")).toContain("An item you made no call on is named as an item (\"A-1047: not done, because …\"), never counted among calls.");
      expect(prompt.replace(/\s+/g, " ")).toContain("Name a limit (an amount, a day's total, how many calls) only as this run read it: from kiff_card, from KIFF's answer on a call, or from the owner's note; otherwise give no number for it.");
      expect(prompt).toContain(j.instructions);
    }
  });

  it("has Refunds read the payment before asking for a refund (the 859 and 870 tests)", () => {
    // T-5005 and T-5003: the look-up and the refund went in one turn, then the taf said it
    // had checked the payment. kiff/agents refuses an act beside a read now; this line
    // keeps the taf from paying a turn for it.
    expect(jobById("refunds")!.instructions.replace(/\n/g, " ")).toContain("Look up the payment before asking for a refund: read it with your payment tool (its get or list call) and read its result. That read is the look-up.");
  });

  // On #574: a version built before gap 18 says "for the company", a placeholder, never a name.
  it("reads who a disclosure line names, and no one from the line before gap 18", () => {
    expect(writesForIn(disclosureLine({ writesFor: "Kiff Agents OÜ" }))).toBe("Kiff Agents OÜ");
    expect(writesForIn(DISCLOSURE_LINE)).toBeUndefined();
    expect(OLD_DISCLOSURE_LINE).toBe("Written by an AI agent for the company.");
    expect(writesForIn(OLD_DISCLOSURE_LINE)).toBeUndefined();
    expect(writesForIn(undefined)).toBeUndefined();
  });

  it("has every taf say an AI agent wrote what it sends outside the company", () => {
    // kiff.dev's Terms state this for tafs (AI Act Article 50); keep it true.
    for (const j of JOBS) {
      expect(systemPrompt(j)).toContain("says first, in one plain sentence, that an AI agent\nwrote it");
      expect(systemPrompt(j)).toContain("Never present yourself as a person.");
    }
  });

  it("says where the owner signs in again when a vendor ended the sign-in", () => {
    for (const j of JOBS) {
      const flat = systemPrompt(j).replace(/\s+/g, " ");
      expect(flat).toContain("say what to do: tap Connect again on this taf, which signs that vendor in again");
      // Where the button is in both apps: never the taf's Card tab, which has none.
      expect(flat).not.toContain("Card tab");
    }
  });

  it("answers the owner briefly, in their words, and acts on their own request (kiff/tafs#102)", () => {
    for (const j of JOBS) {
      const flat = systemPrompt(j).replace(/\s+/g, " ");
      expect(flat).toContain("A request the owner makes in the chat (\"refund this payment\") is enough to act on: do it within your Card, without the decisions your job makes on work you find yourself (whether it qualifies, what to offer instead)");
      // Even then it reads what it acts on first (kiff/tafs#236, item 10).
      expect(flat).toContain("You still look up what you act on before you ask for it, as your job says (that read is the look-up, made once it's in your record): that it's the one meant, and nothing already settles it (a dispute, an earlier refund, what's left on it).");
      // The owner's request skips the job's checks, never its "Never" rules (review on #103).
      expect(flat).toContain("since the owner has decided. Your job's \"Never\" rules still hold for the owner's requests too.");
      expect(j.instructions).toMatch(/Never /);
      expect(flat).toContain("concise: say what you did, or what waits on the owner, first, then stop.");
      // Text between tool calls reaches the owner too (the owner's retest, 22 Oct: "The owner asked again").
      expect(flat).toContain("the owner also sees any text you write between tool calls: write none, or speak to them as \"you\"");
      // Concise, not a word count (the owner, kiff/agents#78).
      expect(flat).not.toMatch(/\d+ words/);
      expect(flat).toContain("amounts in the currency the record or the call gives, with its symbol (€150, $12), never converted into another currency and never \"the equivalent\"");
      // An API's amount is in its smallest unit, by the currency's own decimals, and a record's major form is said as
      // given: a taf told the owner "a €700 refund" for 700 cents (Eval 44055541451, Setup run 1, answer 8).
      expect(flat).toContain("an amount the tool or record says is in its currency's smallest unit (as Stripe's are: an integer in cents) is said in the currency's own unit, by that currency's decimals: 700 in eur is €7.00, 700 in jpy is ¥700, 700 in kwd is KWD 0.700.");
      expect(flat).toContain("Where the record gives it in that unit too (a field ending _major, \"7.00 EUR\"), say that one, never your own sum.");
      // Only an amount declared in the smallest unit is: a vendor's decimal ("150.00", a Card's decimal_string) is said
      // as given, never divided, so the fix can't understate by 100 the other way.
      expect(flat).toContain("Any other amount is said as the record gives it (\"150.00\" in eur is €150.00), never divided");
      expect(flat).not.toContain("an API's amount is in its currency's smallest unit");
      // The owner's own smallest unit is said back as they wrote it, never converted, until a record carries it.
      expect(flat).toContain("an amount the owner gives in the smallest unit (\"700 cents\") goes into the call as they gave it, and you say it back in their words (\"700 cents\"), never converted, until the call's record gives it in the currency's own unit");
      expect(flat).toContain("no ids (payments, refunds, operations, cards) unless the owner asks for one");
      expect(flat).toContain("nothing about your notes, earlier runs, or what you checked and didn't change");
      // A held call in one sentence: the wait, the one limit, the deadline (the Eval's judges,
      // 28 Oct: "a redundant 'Nothing has been sent yet', and it wanders between the deadline,
      // the charge balance and the limits").
      // "Nothing is sent until you approve" is the held card's and its alert's to say, where
      // the owner answers; in the taf's sentence the Eval's judges read it as repeating the wait.
      expect(flat).toContain("Say it in one sentence: the call waits for their approval, the one limit that holds it (the most for one call, the day's total, or how many calls a day), and until when");
      expect(flat).not.toContain("waits for their approval and nothing was sent");
      expect(flat).toContain("Nothing else: not what's left on the item, not the other limits, and not that nothing was sent, since the held call's card says so.");
      // Until when, in the chat in words, as the chat's own rule says ("no clock times or time
      // zones, a deadline included"); a time only in a scheduled run's report. The two rules
      // disagreed, and a taf wrote "until 06:59 UTC today (2026-11-06)" (Eval 42176052141).
      // "Held until tomorrow, when the hold expires" for a call KIFF gave no expiry (Eval, 13 Nov): only from hold_expires_at.
      expect(flat).toContain("and until when only if KIFF's answer gives hold_expires_at, never a time you work out or a guess such as \"until tomorrow\" (kiff/tafs#206): in the chat in words (\"today\", \"in ten minutes\"), since the app shows the time in the owner's zone; in a scheduled run's report, the time in the owner's time zone.");
      expect(flat).not.toContain("the time KIFF's hold_expires_at gives, in the owner's time zone");
      // A chat answer ends with no report: the judges read a scheduled run's "nothing else was
      // done or left undone" as filler.
      expect(flat).toContain("with no closing summary of what was or wasn't done: that's a scheduled run's report");
      // What was checked comes after what waits on the owner (Eval 41026186869, rule 1), and
      // only as a clause of that sentence: a sentence of its own about checking ("I read the
      // charge first.", "Antes lo revisé: …") is process the judges fail (Eval 42176052141).
      expect(flat).toContain("What a check found goes inside the sentence that says what you did, as one short clause at most, or not at all: never in a sentence of its own about what you read or checked");
      expect(flat).toContain("but \"I refunded €3 on the €1,000 charge, which had nothing refunded before.\"");
      // A held call's sentence carries none of it: the item's balance is what the held call's own
      // rule leaves out, and this morning's "I read the charge first. It is €1,000 with nothing
      // refunded yet." was on a held call (#560).
      expect(flat).toContain("and a held call's sentence carries none of it (above)");
      // Only a check with nothing to do makes what was found the answer: "Check charge …, then
      // refund" asks for a check too, and its "Antes lo revisé: …" failed (#560).
      expect(flat).toContain("When the owner asked only for a check, with nothing to do, what you found is the answer.");
      expect(flat).not.toContain("When the owner asked you to check something");
      expect(flat).not.toContain("What you checked on the way comes after");
      // The example no longer has a held call carry the item's balance, which the held call's
      // own rule leaves out ("Nothing else: not what's left on the item").
      expect(flat).not.toContain("I checked the charge first: €1,000, nothing refunded yet.");
      // No closing offer (Eval 41043640507: "If you still want this refund, ask me again…").
      expect(flat).toContain("end when it's said: no offer of more help");
      // Each outcome said once (Eval 41015204757: "it was sent once. The refund went through.").
      // After a held call, approved or refused, the check made before asking gets no word
      // (Eval 42176052141: "Before asking, I checked the charge: …").
      expect(flat).toContain("each thing once: after a held call the owner answered, one sentence says what came of it");
      expect(flat).toContain("and no word on the check you made before asking;");
      // The scheduled run's report still names each item by its reference.
      expect(flat).toContain("End every scheduled run with a short report");
    }
  });

  it("refuses an unknown job", () => {
    expect(() => jobById("payroll")).toThrow(/no job "payroll"/);
  });
});

describe("KIFF's skill", () => {
  it("is the vendored file, pinned to one commit", () => {
    const onDisk = readFileSync(new URL("../skills/kiff/SKILL.md", import.meta.url), "utf8");
    expect(KIFF_SKILL.content).toBe(onDisk);
    expect(KIFF_SKILL.commit).toMatch(/^[0-9a-f]{40}$/);
    expect(KIFF_SKILL.content).toMatch(/^---\nname: kiff\n/);
  });
});
