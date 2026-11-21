# Store listing

The App Store and Play Store listings, in English (the source) and the seven other languages tafs
speaks ([languages.md](languages.md)). Wave 1's two App Store localizations for Latin America and Brazil, es-MX
and pt-BR, are below Portuguese: new in App Store Connect, those storefronts read the primary English listing until they're added. Australia and
Canada read the primary English (US) listing as it is. Each language has its App Store fields and its Play fields,
ready to paste: the store's default listing comes from English, and each language's listing from its
section. The number beside each field is its length and the store's limit.

**The listing leads with what a taf takes off the person's hands,** then how they stay in control:
the jobs, the Card, approvals, Did this happen? and the Record.

**Each language is reviewed against the glossary before it's added in a store,** as
[languages.md](languages.md) says. No language waits for a native speaker: fixes go forward after
release.

## Rules for every language

- **The same claims and limits as English.** Nothing added, nothing stronger, nothing left out.
  Claims about what can happen stay soft: a refund left overnight *can turn into* a chargeback, with a
  fee you *may* pay even if you win.
- **The glossary's words.** taf, Coach and Card stay as names. Needs you, Record, door, credits,
  credit pack, Approve once, Refuse and held use the glossary's term in each language. Where a sentence sends someone to a screen, it names the screen as the app titles it in that language (deleting an account is under Account: `settings.title`).
- **Revoke is not Halt.** "Revoke the Card" ends the Card itself, so no new action goes through
  under it. It isn't the glossary's Halt, which blocks a taf in every system it works through, so
  each language uses its own word for revoke (widerrufen, révoquer, revocar, revocare, revogar,
  intrekken, odwołać) and never Halt's word.
- **Amounts in euros,** written the language's way.
- **Approving on the device, per store.** The App Store listing says Face ID or Touch ID; the Play
  listing says fingerprint or face.
- **The store's name, per store.** Where a sentence names the store (the Plans paragraph), the App
  Store listing says App Store and the Play listing says Google Play.
- **No price page on the App Store.** The Play description ends its Plans paragraph with the link to
  kiff.dev's plans and prices; the App Store description doesn't, since App Review flags a link to
  web prices from an app that sells in the app.
- **Each field is exactly what's in the store,** figures included, so it pastes as it is.
- **The legal lines close each description.** The App Store description ends with three lines:
  Apple's standard EULA (its URL exactly, `apple.com/legal/internet-services/itunes/dev/stdeula/`),
  the terms (`kiff.dev/terms`) and the privacy policy (`kiff.dev/privacy`), as the owner approved the
  App Store English on 18 Nov. The Play description ends with the two tafs lines, without Apple's. The labels are the app's own words for
  terms of use and privacy. Each language links its own translated pages, which kiff.dev serves in German, French,
  Spanish, Italian, Portuguese, Dutch and Polish (`kiff.dev/es/terms`, `kiff.dev/es/tafs/privacy`; es-MX and es-419 read
  the Spanish pages, pt-BR the Portuguese ones), with no "in English" mark. Korean, which has no translated pages yet,
  keeps the English pages marked as in English. Deleting an account points to the language's
  `kiff.dev/xx/privacy#delete-account` on the App Store and `kiff.dev/xx/tafs/privacy#rights` on Play (English:
  `kiff.dev/privacy#delete-account` and `kiff.dev/tafs/privacy#rights`). Every one of these pages and anchors answered on
  21 Nov.
- **App Store lengths:** name up to 30 characters, subtitle up to 30, promotional text up to 170,
  keywords up to 100, description up to 4,000.
- **Play lengths:** title up to 30 characters, short description up to 80, full description up to
  4,000.
- **App Store keywords** are separated by commas with no spaces, and fit 100 characters. Each
  language picks the terms people search for in that language, not a word-for-word translation of the
  English list, and doesn't repeat words already in the name or subtitle.
- **Search.** Play ranks on the title, the short description and the full description. The short
  description names AI agents in the language's usual form (AI agents, KI-Agenten, agents IA, agentes
  de IA, agenti IA, AI-agents, agenci AI). The descriptions name them, automation and the jobs where
  the sentence needs them, never as a repeated keyword. "AI teammate" and "AI assistant" appear once
  each in each description, beside AI agents, never in the name, the subtitle, the title, the short
  description or a heading: the Card leads. "Autonomous" only with its limit (a taf never acts past
  its Card).

## English

### App Store

**Name** (28/30): tafs: AI Agents for Business

**Subtitle** (22/30): Get your mornings back

**Promotional text** (166/170):

```
Support tickets, new leads, late parcels: hand them to a taf and get your mornings back. It works within limits you set, and anything that matters waits for your yes.
```

**Keywords** (96/100):

```
automation,assistant,support,tickets,leads,parcels,customers,approvals,workflow,routine,teammate
```

**Description** (2742/4000):

```
They taf. You kiff.
Automate your business without giving up control.

Every day the same jobs pile up. A ticket left overnight becomes an angry customer. A lead that waits a day buys elsewhere. A late parcel nobody mentions turns into a refund request.

With tafs, you hand those jobs off. Tell Coach the job in your own words, or pick a ready-made one. Coach, the AI assistant in tafs, connects your apps and builds a taf: an AI agent, or AI teammate, that does that job every day, so you spend your time on the work you actually enjoy.

WHY YOU CAN LET IT
Every taf works within a Card you sign: what it does on its own, what it asks you first, and what it never does. Every action is checked against its Card by KIFF, outside the AI, before it reaches your apps. No email trick or clever prompt can push it past your limits. Anything big waits for your yes on your phone, with Face ID or Touch ID. No answer means no.

JOBS YOU CAN HAND OFF TODAY
• No ticket waits: acknowledges every support ticket the same day, and passes anything that promises money to you.
• Never lose a lead to a slow reply: turns each new form entry into a deal and sends a first reply within the hour.
• Tell customers before they ask: warns them when a parcel runs late. Reships wait for your yes.
• Keep customers who cancel: offers what your Card allows, and anything more waits for you.
More are coming: answering order questions from your shop, giving new joiners their apps and removing leavers, bookings and receipts. Every limit is yours to change.

WHAT YOU SEE
• Needs you: what's waiting for your yes.
• Record: everything your tafs did, newest first, with a receipt for each action that went through.
• Chat: ask a taf what it did, or tell it what to do differently, and watch its work as it goes.
• You decide what really happened: when an app can't confirm an action went through, tafs asks you "Did this happen?" Nothing is counted as done on a guess.

YOUR DATA
The taf never sees your passwords or tokens. Approvals are signed with a key kept in your phone's secure hardware. Halt stops one taf, or all of them, with one tap. No ads, no trackers. Delete your account in the app, under Account, or see kiff.dev/privacy#delete-account.

PLANS
Solo, with 500 credits a month, and packs of credits that never expire, both bought in the app through the App Store. Each run of a taf uses credits for the AI it used. A quiet check, when nothing is new, costs nothing. Coach is included: enough to set up about 4 new jobs a month. Past that, Coach's help uses your credits. There's no free trial.

Terms of Use (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
tafs terms: https://kiff.dev/terms
Privacy: https://kiff.dev/privacy
```

### Google Play

**Title** (28/30): tafs: AI Agents for Business

**Short description** (71/80): Hand off refunds, disputes and invoices to AI agents. You keep control.

**Full description** (2884/4000):

```
They taf. You kiff.
Automate your business without giving up control.

Every day the same jobs pile up. A refund left overnight can turn into a chargeback, with a fee you may pay even if you win. A dispute nobody answers in time is lost. Late payers stay late. A lead that waits a day buys elsewhere.

With tafs, you hand those jobs off. Tell Coach the job in your own words, or pick a ready-made one. Coach, the AI assistant in tafs, connects your apps and builds a taf: an AI agent, or AI teammate, that does that job every day, so you spend your time on the work you actually enjoy.

WHY YOU CAN LET IT
Every taf works within a Card you sign: what it does on its own, what it asks you first, and what it never does. Every action is checked against its Card by KIFF, outside the AI, before it reaches your apps. No email trick or clever prompt can push it past your limits. Anything big waits for your yes on your phone, with your fingerprint or face. No answer means no.

JOBS YOU CAN HAND OFF TODAY
• No refund waits overnight: answers refund requests from the support queue and refunds within the Card, for example up to €150 each and €2,000 a day.
• Stop losing disputes you could win: answers card disputes with the order, delivery proof and the customer's messages before the deadline.
• Get paid without chasing: reminds customers of overdue invoices on a schedule and moves no money.
• Never lose a lead to a slow reply: turns each new form entry into a deal and sends a first reply within the hour.
• Stop answering "where is my order?": answers order questions from the shop and passes anything about money to you.
More come ready: bookings, receipts and keeping customers who cancel. Every limit is yours to change.

WHAT YOU SEE
• Needs you: what's waiting for your yes.
• Record: everything your tafs did, newest first, with a receipt for each action that went through.
• Chat: ask a taf what it did, or tell it what to do differently, and watch its work as it goes.
• You decide what really happened: when an app can't confirm a payment went through, tafs asks you "Did this happen?" Nothing is counted as done on a guess.

YOUR DATA
The taf never sees your passwords or tokens. Approvals are signed with a key kept in your phone's secure hardware. Halt stops one taf, or all of them, with one tap. No ads, no trackers. Delete your account in the app, under Account, or see kiff.dev/tafs/privacy#rights.

PLANS
Solo, with 500 credits a month, and packs of credits that never expire, both bought in the app through Google Play. Each run of a taf uses credits for the AI it used. A quiet check, when nothing is new, costs nothing. Coach is included: enough to set up about 4 new jobs a month. Past that, Coach's help uses your credits. There's no free trial. Plans and prices: kiff.dev/tafs

tafs terms: https://kiff.dev/tafs/terms
Privacy: https://kiff.dev/tafs/privacy
```

## German (de)

### App Store

**Name** (27/30): tafs: KI-Agenten für Firmen

**Subtitle** (23/30): Deine Vormittage zurück

**Promotional text** (164/170):

```
Support-Tickets, neue Leads, verspätete Pakete: Gib sie an einen taf ab und hol dir deine Vormittage zurück. Er hält deine Limits ein, Wichtiges wartet auf dein Ja.
```

**Keywords** (87/100):

```
Automatisierung,Assistent,Support,Tickets,Leads,Pakete,Kunden,Freigabe,Workflow,Routine
```

**Description** (3200/4000):

```
They taf. You kiff.
Automatisiere dein Unternehmen, ohne die Kontrolle abzugeben.

Jeden Tag stapeln sich dieselben Aufgaben. Ein Ticket, das über Nacht liegen bleibt, wird zum verärgerten Kunden. Ein Lead, der einen Tag wartet, kauft woanders. Ein verspätetes Paket, das niemand erwähnt, wird zur Erstattungsanfrage.

Mit tafs gibst du diese Aufgaben ab. Sag Coach die Aufgabe in deinen eigenen Worten oder wähle eine fertige. Coach, der KI-Assistent in tafs, verbindet deine Apps und baut einen taf: einen KI-Agenten oder KI-Kollegen, der diese Aufgabe jeden Tag erledigt. So bleibt dir Zeit für die Arbeit, die dir wirklich Freude macht.

WARUM DU IHN MACHEN LASSEN KANNST
Jeder taf arbeitet mit einer Card, die du unterschreibst: was er selbst macht, was er dich zuerst fragt und was er nie macht. KIFF prüft jede Aktion gegen seine Card, außerhalb der KI, bevor sie deine Apps erreicht. Kein Prompt ändert ein Limit, auch kein Trick in einer E-Mail. Alles Große wartet auf deinem Handy auf dein Ja, mit Face ID oder Touch ID. Keine Antwort heißt Nein.

AUFGABEN, DIE DU HEUTE ABGEBEN KANNST
• Kein Ticket wartet: bestätigt jedes Support-Ticket noch am selben Tag und gibt alles, was Geld verspricht, an dich weiter.
• Verlier keinen Lead mehr an eine langsame Antwort: macht aus jedem neuen Formulareintrag einen Deal und schickt innerhalb einer Stunde eine erste Antwort.
• Sag es Kunden, bevor sie fragen: warnt sie, wenn sich ein Paket verspätet. Ein Neuversand wartet auf dein Ja.
• Halte Kunden, die kündigen: bietet an, was deine Card erlaubt, und alles Weitere wartet auf dich.
Weitere kommen: Fragen zu Bestellungen aus deinem Shop beantworten, neuen Leuten ihre Apps einrichten und sie Ausscheidenden wieder entziehen, Termine und Belege. Jedes Limit kannst du ändern.

WAS DU SIEHST
• Braucht dich: was auf dein Ja wartet.
• Protokoll: alles, was deine tafs getan haben, das Neueste zuerst, mit einem Beleg für jede Aktion, die durchging.
• Chat: Frag einen taf, was er getan hat, oder sag ihm, was er anders machen soll, und verfolge seine Arbeit live.
• Du entscheidest, was wirklich passiert ist: Wenn eine App nicht bestätigen kann, dass eine Aktion durchging, fragt dich tafs „Ist das passiert?“ Nichts zählt auf Verdacht als erledigt.

DEINE DATEN
Der taf sieht nie deine Passwörter oder Tokens. Genehmigungen werden mit einem Schlüssel signiert, der in der Sicherheitshardware deines Handys liegt. Mit Anhalten stoppst du einen taf oder alle mit einem Tippen. Keine Werbung, keine Tracker. Lösch dein Konto in der App unter „Konto“, oder siehe kiff.dev/de/privacy#delete-account.

TARIFE
Solo mit 500 Credits im Monat und Credit-Pakete, die nie verfallen, beides in der App über den App Store gekauft. Jeder Lauf eines tafs verbraucht Credits für die KI, die er genutzt hat. Eine ruhige Prüfung, wenn nichts neu ist, kostet nichts. Coach ist inklusive: genug, um etwa 4 neue Aufgaben im Monat einzurichten. Darüber hinaus nutzt die Hilfe von Coach deine Credits. Es gibt keine kostenlose Testphase.

Nutzungsbedingungen (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
tafs-Bedingungen: https://kiff.dev/de/terms
Datenschutz: https://kiff.dev/de/privacy
```

### Google Play

**Title** (27/30): tafs: KI-Agenten für Firmen

**Short description** (79/80): KI-Agenten für Erstattungen, Streitfälle und Rechnungen. Du hast die Kontrolle.

**Full description** (3444/4000):

```
They taf. You kiff.
Automatisiere dein Unternehmen, ohne die Kontrolle abzugeben.

Jeden Tag stapeln sich dieselben Aufgaben. Eine Erstattung, die über Nacht liegen bleibt, kann zur Rückbuchung werden, mit einer Gebühr, die du womöglich sogar zahlst, wenn du gewinnst. Ein Streitfall, den niemand rechtzeitig beantwortet, ist verloren. Wer spät zahlt, zahlt weiter spät. Ein Lead, der einen Tag wartet, kauft woanders.

Mit tafs gibst du diese Aufgaben ab. Sag Coach die Aufgabe in deinen eigenen Worten oder wähle eine fertige. Coach, der KI-Assistent in tafs, verbindet deine Apps und baut einen taf: einen KI-Agenten oder KI-Kollegen, der diese Aufgabe jeden Tag erledigt. So bleibt dir Zeit für die Arbeit, die dir wirklich Freude macht.

WARUM DU IHN MACHEN LASSEN KANNST
Jeder taf arbeitet mit einer Card, die du unterschreibst: was er selbst macht, was er dich zuerst fragt und was er nie macht. KIFF prüft jede Aktion gegen seine Card, außerhalb der KI, bevor sie deine Apps erreicht. Kein Prompt ändert ein Limit, auch kein Trick in einer E-Mail. Alles Große wartet auf deinem Handy auf dein Ja, mit Fingerabdruck oder Gesicht. Keine Antwort heißt Nein.

AUFGABEN, DIE DU HEUTE ABGEBEN KANNST
• Keine Erstattung wartet über Nacht: beantwortet Erstattungsanfragen aus dem Support und erstattet innerhalb der Card, zum Beispiel bis 150 € pro Fall und 2.000 € am Tag.
• Verlier keine Streitfälle mehr, die du gewinnen könntest: beantwortet Kartenstreitfälle vor der Frist mit der Bestellung, dem Liefernachweis und den Nachrichten des Kunden.
• Werde bezahlt, ohne hinterherzulaufen: erinnert Kunden nach Plan an überfällige Rechnungen und bewegt kein Geld.
• Verlier keinen Lead mehr an eine langsame Antwort: macht aus jedem neuen Formulareintrag einen Deal und schickt innerhalb einer Stunde eine erste Antwort.
• Schluss mit „Wo ist meine Bestellung?“: beantwortet Fragen zu Bestellungen mit Blick in den Shop und gibt alles, was Geld betrifft, an dich weiter.
Weitere gibt es auch fertig: Termine, Belege und Kunden halten, die kündigen. Jedes Limit kannst du ändern.

WAS DU SIEHST
• Braucht dich: was auf dein Ja wartet.
• Protokoll: alles, was deine tafs getan haben, das Neueste zuerst, mit einem Beleg für jede Aktion, die durchging.
• Chat: Frag einen taf, was er getan hat, oder sag ihm, was er anders machen soll, und verfolge seine Arbeit live.
• Du entscheidest, was wirklich passiert ist: Wenn eine App nicht bestätigen kann, dass eine Zahlung durchging, fragt dich tafs „Ist das passiert?“ Nichts zählt auf Verdacht als erledigt.

DEINE DATEN
Der taf sieht nie deine Passwörter oder Tokens. Genehmigungen werden mit einem Schlüssel signiert, der in der Sicherheitshardware deines Handys liegt. Mit Anhalten stoppst du einen taf oder alle mit einem Tippen. Keine Werbung, keine Tracker. Lösch dein Konto in der App unter „Konto“, oder siehe kiff.dev/de/tafs/privacy#rights.

TARIFE
Solo mit 500 Credits im Monat und Credit-Pakete, die nie verfallen, beides in der App über Google Play gekauft. Jeder Lauf eines tafs verbraucht Credits für die KI, die er genutzt hat. Eine ruhige Prüfung, wenn nichts neu ist, kostet nichts. Coach ist inklusive: genug, um etwa 4 neue Aufgaben im Monat einzurichten. Darüber hinaus nutzt die Hilfe von Coach deine Credits. Es gibt keine kostenlose Testphase. Tarife und Preise: kiff.dev/de/tafs

tafs-Bedingungen: https://kiff.dev/de/tafs/terms
Datenschutz: https://kiff.dev/de/tafs/privacy
```

## French (fr)

### App Store

**Name** (29/30): tafs : agents IA d'entreprise

**Subtitle** (22/30): Retrouvez vos matinées

**Promotional text** (164/170):

```
Tickets de support, nouveaux prospects, colis en retard : confiez-les à un taf et retrouvez vos matinées. Il agit dans vos limites, et l'important attend votre oui.
```

**Keywords** (92/100):

```
automatisation,assistant,support,tickets,prospects,colis,clients,validation,workflow,routine
```

**Description** (3301/4000):

```
They taf. You kiff.
Automatisez votre entreprise sans perdre le contrôle.

Chaque jour, les mêmes tâches s'accumulent. Un ticket laissé pour la nuit devient un client mécontent. Un prospect qui attend un jour achète ailleurs. Un colis en retard dont personne ne parle devient une demande de remboursement.

Avec tafs, vous confiez ces tâches. Décrivez la tâche à Coach avec vos mots, ou choisissez-en une toute prête. Coach, l'assistant IA de tafs, connecte vos apps et construit un taf : un agent IA, ou coéquipier IA, qui fait cette tâche chaque jour. Vous gardez votre temps pour le travail qui vous plaît vraiment.

POURQUOI VOUS POUVEZ LUI FAIRE CONFIANCE
Chaque taf travaille avec une Card que vous signez : ce qu'il fait seul, ce qu'il vous demande d'abord, et ce qu'il ne fait jamais. Chaque action est vérifiée par KIFF au regard de sa Card, en dehors de l'IA, avant d'atteindre vos apps. Aucun prompt ne change une limite, pas même une ruse dans un e-mail. Tout ce qui est important attend votre oui sur votre téléphone, avec Face ID ou Touch ID. Pas de réponse veut dire non.

DES TÂCHES À CONFIER DÈS AUJOURD'HUI
• Aucun ticket n'attend : accuse réception de chaque ticket de support le jour même, et vous transmet tout ce qui promet de l'argent.
• Ne perdez plus un prospect à cause d'une réponse lente : transforme chaque nouvelle entrée de formulaire en affaire et envoie une première réponse dans l'heure.
• Prévenez les clients avant qu'ils ne demandent : les avertit quand un colis est en retard. Un renvoi attend votre oui.
• Gardez les clients qui résilient : propose ce que votre Card permet, et tout le reste vous attend.
D'autres arrivent : répondre aux questions sur les commandes de votre boutique, donner leurs apps aux nouveaux arrivants et les retirer à ceux qui partent, rendez-vous et reçus. Chaque limite se change à votre guise.

CE QUE VOUS VOYEZ
• Vous attend : ce qui attend votre oui.
• Registre : tout ce que vos tafs ont fait, du plus récent au plus ancien, avec un reçu pour chaque action passée.
• Chat : demandez à un taf ce qu'il a fait, ou dites-lui quoi faire autrement, et suivez son travail en direct.
• Vous décidez de ce qui s'est vraiment passé : quand une app ne peut pas confirmer qu'une action est passée, tafs vous demande « Est-ce arrivé ? » Rien n'est compté comme fait sur une supposition.

VOS DONNÉES
Le taf ne voit jamais vos mots de passe ni vos jetons. Les approbations sont signées avec une clé conservée dans le matériel sécurisé de votre téléphone. Bloquer arrête un taf, ou tous, d'un geste. Pas de publicité, pas de traceurs. Supprimez votre compte dans l'app, depuis Compte, ou voir kiff.dev/fr/privacy#delete-account.

FORFAITS
Solo, avec 500 crédits par mois, et des packs de crédits qui n'expirent jamais, tous deux achetés dans l'app via l'App Store. Chaque exécution d'un taf utilise des crédits pour l'IA qu'elle a utilisée. Une vérification quand rien n'est nouveau ne coûte rien. Coach est inclus : de quoi mettre en place environ 4 nouvelles tâches par mois. Au-delà, l'aide de Coach utilise vos crédits. Il n'y a pas d'essai gratuit.

Conditions d'utilisation (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
Conditions de tafs: https://kiff.dev/fr/terms
Confidentialité: https://kiff.dev/fr/privacy
```

### Google Play

**Title** (29/30): tafs : agents IA d'entreprise

**Short description** (76/80): Confiez remboursements, litiges et factures à des agents IA. Gardez la main.

**Full description** (3554/4000):

```
They taf. You kiff.
Automatisez votre entreprise sans perdre le contrôle.

Chaque jour, les mêmes tâches s'accumulent. Un remboursement laissé jusqu'au lendemain peut devenir une rétrofacturation, avec des frais que vous payez parfois même si vous gagnez. Un litige auquel personne ne répond à temps est perdu. Les mauvais payeurs restent en retard. Un prospect qui attend un jour achète ailleurs.

Avec tafs, vous confiez ces tâches. Décrivez la tâche à Coach avec vos mots, ou choisissez-en une toute prête. Coach, l'assistant IA de tafs, connecte vos apps et construit un taf : un agent IA, ou coéquipier IA, qui fait cette tâche chaque jour. Vous gardez votre temps pour le travail qui vous plaît vraiment.

POURQUOI VOUS POUVEZ LUI FAIRE CONFIANCE
Chaque taf travaille avec une Card que vous signez : ce qu'il fait seul, ce qu'il vous demande d'abord, et ce qu'il ne fait jamais. Chaque action est vérifiée par KIFF au regard de sa Card, en dehors de l'IA, avant d'atteindre vos apps. Aucun prompt ne change une limite, pas même une ruse dans un e-mail. Tout ce qui est important attend votre oui sur votre téléphone, avec votre empreinte ou votre visage. Pas de réponse veut dire non.

DES TÂCHES À CONFIER DÈS AUJOURD'HUI
• Aucun remboursement n'attend jusqu'au lendemain : répond aux demandes de remboursement du support et rembourse dans les limites de la Card, par exemple jusqu'à 150 € chacun et 2 000 € par jour.
• Arrêtez de perdre des litiges que vous pourriez gagner : répond aux litiges sur carte avant l'échéance avec la commande, la preuve de livraison et les messages du client.
• Soyez payé sans relancer : relance les clients pour les factures en retard selon un calendrier et ne déplace aucun argent.
• Ne perdez plus un prospect à cause d'une réponse lente : transforme chaque nouvelle entrée de formulaire en affaire et envoie une première réponse dans l'heure.
• Fini le « où est ma commande ? » : répond aux questions sur les commandes en consultant la boutique, et vous transmet tout ce qui touche à l'argent.
D'autres sont prêtes aussi : rendez-vous, justificatifs et clients à retenir quand ils résilient. Vous pouvez changer chaque limite.

CE QUE VOUS VOYEZ
• Vous attend : ce qui attend votre oui.
• Registre : tout ce que vos tafs ont fait, du plus récent au plus ancien, avec un reçu pour chaque action passée.
• Chat : demandez à un taf ce qu'il a fait, ou dites-lui quoi faire autrement, et suivez son travail en direct.
• Vous décidez de ce qui s'est vraiment passé : quand une app ne peut pas confirmer qu'un paiement est passé, tafs vous demande « Est-ce arrivé ? » Rien n'est compté comme fait sur une supposition.

VOS DONNÉES
Le taf ne voit jamais vos mots de passe ni vos jetons. Les approbations sont signées avec une clé conservée dans le matériel sécurisé de votre téléphone. Bloquer arrête un taf, ou tous, d'un geste. Pas de publicité, pas de traceurs. Supprimez votre compte dans l'app, depuis Compte, ou voir kiff.dev/fr/tafs/privacy#rights.

FORFAITS
Solo, avec 500 crédits par mois, et des packs de crédits qui n'expirent jamais, tous deux achetés dans l'app via Google Play. Chaque exécution d'un taf utilise des crédits pour l'IA qu'elle a utilisée. Une vérification quand rien n'est nouveau ne coûte rien. Coach est inclus : de quoi mettre en place environ 4 nouvelles tâches par mois. Au-delà, l'aide de Coach utilise vos crédits. Il n'y a pas d'essai gratuit. Forfaits et prix : kiff.dev/fr/tafs

Conditions de tafs: https://kiff.dev/fr/tafs/terms
Confidentialité: https://kiff.dev/fr/tafs/privacy
```

## Spanish (es)

### App Store

**Name** (30/30): tafs: agentes IA para empresas

**Subtitle** (20/30): Recupera tus mañanas

**Promotional text** (163/170):

```
Tickets de soporte, contactos nuevos, paquetes con retraso: pásaselos a un taf y recupera tus mañanas. Trabaja dentro de tus límites, y lo importante espera tu sí.
```

**Keywords** (92/100):

```
automatización,asistente,soporte,tickets,contactos,paquetes,clientes,aprobación,flujo,rutina
```

**Description** (3039/4000):

```
They taf. You kiff.
Automatiza tu negocio sin perder el control.

Cada día se acumulan las mismas tareas. Un ticket que se queda sin respuesta de un día para otro acaba en un cliente enfadado. Un contacto que espera un día compra en otro sitio. Un paquete con retraso del que nadie avisa acaba en una solicitud de reembolso.

Con tafs, delegas esas tareas. Cuéntale a Coach la tarea con tus palabras o elige una ya hecha. Coach, el asistente de IA de tafs, conecta tus apps y crea un taf: un agente de IA, o compañero de IA, que hace esa tarea cada día. Así dedicas tu tiempo al trabajo que de verdad te gusta.

POR QUÉ PUEDES DEJARLO HACER
Cada taf trabaja con una Card que tú firmas: lo que hace por su cuenta, lo que te pregunta primero y lo que nunca hace. KIFF comprueba cada acción contra su Card, fuera de la IA, antes de que llegue a tus apps. Ningún prompt cambia un límite, ni siquiera un truco en un correo. Todo lo importante espera tu sí en tu teléfono, con Face ID o Touch ID. Sin respuesta, es un no.

TAREAS QUE PUEDES DELEGAR HOY
• Ningún ticket espera: acusa recibo de cada ticket de soporte el mismo día y te pasa todo lo que prometa dinero.
• No pierdas ni un contacto por responder tarde: convierte cada nueva entrada del formulario en una oportunidad y envía una primera respuesta en menos de una hora.
• Avisa a los clientes antes de que pregunten: les avisa cuando un paquete va con retraso. Un reenvío espera tu sí.
• Conserva a los clientes que cancelan: ofrece lo que tu Card permite, y todo lo demás te espera.
Vienen más: responder preguntas sobre pedidos de tu tienda, dar sus apps a quien entra y quitárselas a quien se va, reservas y recibos. Cada límite lo cambias tú.

LO QUE VES
• Te necesita: lo que espera tu sí.
• Registro: todo lo que hicieron tus tafs, lo más reciente primero, con un recibo por cada acción que salió adelante.
• Chat: pregunta a un taf qué hizo, o dile qué hacer de otra forma, y sigue su trabajo mientras avanza.
• Tú decides qué pasó de verdad: cuando una app no puede confirmar que una acción se hizo, tafs te pregunta «¿Ocurrió esto?». Nada se da por hecho a ciegas.

TUS DATOS
El taf nunca ve tus contraseñas ni tus tokens. Las aprobaciones se firman con una clave guardada en el hardware seguro de tu teléfono. Bloquear detiene un taf, o todos, con un toque. Sin anuncios, sin rastreadores. Borra tu cuenta en la app, en Cuenta, o consulta kiff.dev/es/privacy#delete-account.

PLANES
Solo, con 500 créditos al mes, y paquetes de créditos que no caducan nunca, ambos comprados en la app a través del App Store. Cada ejecución de un taf usa créditos por la IA que utilizó. Una comprobación tranquila, cuando no hay nada nuevo, no cuesta nada. Coach está incluido: lo suficiente para configurar unos 4 trabajos nuevos al mes. Pasado eso, la ayuda de Coach usa tus créditos. No hay prueba gratuita.

Condiciones de uso (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
Condiciones de tafs: https://kiff.dev/es/terms
Privacidad: https://kiff.dev/es/privacy
```

### Google Play

**Title** (30/30): tafs: agentes IA para empresas

**Short description** (78/80): Delega reembolsos, disputas y facturas en agentes de IA. Tú tienes el control.

**Full description** (3252/4000):

```
They taf. You kiff.
Automatiza tu negocio sin perder el control.

Cada día se acumulan las mismas tareas. Un reembolso que espera hasta mañana puede acabar en un contracargo, con una comisión que quizá pagues aunque ganes. Una disputa que nadie responde a tiempo se pierde. Quien paga tarde sigue pagando tarde. Un lead que espera un día compra en otra parte.

Con tafs, delegas esas tareas. Cuéntale a Coach la tarea con tus palabras o elige una ya hecha. Coach, el asistente de IA de tafs, conecta tus apps y crea un taf: un agente de IA, o compañero de IA, que hace esa tarea cada día. Así dedicas tu tiempo al trabajo que de verdad te gusta.

POR QUÉ PUEDES DEJARLO HACER
Cada taf trabaja con una Card que tú firmas: lo que hace por su cuenta, lo que te pregunta primero y lo que nunca hace. KIFF comprueba cada acción contra su Card, fuera de la IA, antes de que llegue a tus apps. Ningún prompt cambia un límite, ni siquiera un truco en un correo. Todo lo importante espera tu sí en tu teléfono, con tu huella o tu cara. Sin respuesta, es un no.

TAREAS QUE PUEDES DELEGAR HOY
• Ningún reembolso espera hasta mañana: responde las solicitudes de reembolso del servicio de atención y reembolsa dentro de la Card, por ejemplo hasta 150 € cada uno y 2.000 € al día.
• Deja de perder disputas que podrías ganar: responde las disputas de tarjeta antes del plazo con el pedido, la prueba de entrega y los mensajes del cliente.
• Cobra sin perseguir a nadie: recuerda a los clientes sus facturas vencidas según un calendario y no mueve dinero.
• No pierdas ni un contacto por responder tarde: convierte cada nueva entrada del formulario en una oportunidad y envía una primera respuesta en menos de una hora.
• Deja de responder «¿dónde está mi pedido?»: responde las preguntas sobre pedidos consultando la tienda y te pasa todo lo que tenga que ver con dinero.
Vienen más ya listas: reservas, recibos y retener a clientes que cancelan. Cada límite lo puedes cambiar tú.

LO QUE VES
• Te necesita: lo que espera tu sí.
• Registro: todo lo que hicieron tus tafs, lo más reciente primero, con un recibo por cada acción que salió adelante.
• Chat: pregunta a un taf qué hizo, o dile qué hacer de otra forma, y sigue su trabajo mientras avanza.
• Tú decides qué pasó de verdad: cuando una app no puede confirmar que un pago se hizo, tafs te pregunta «¿Ocurrió esto?». Nada se da por hecho a ciegas.

TUS DATOS
El taf nunca ve tus contraseñas ni tus tokens. Las aprobaciones se firman con una clave guardada en el hardware seguro de tu teléfono. Bloquear detiene un taf, o todos, con un toque. Sin anuncios, sin rastreadores. Borra tu cuenta en la app, en Cuenta, o consulta kiff.dev/es/tafs/privacy#rights.

PLANES
Solo, con 500 créditos al mes, y paquetes de créditos que no caducan nunca, ambos comprados en la app a través de Google Play. Cada ejecución de un taf usa créditos por la IA que utilizó. Una comprobación tranquila, cuando no hay nada nuevo, no cuesta nada. Coach está incluido: lo suficiente para configurar unos 4 trabajos nuevos al mes. Pasado eso, la ayuda de Coach usa tus créditos. No hay prueba gratuita. Planes y precios: kiff.dev/es/tafs

Condiciones de tafs: https://kiff.dev/es/tafs/terms
Privacidad: https://kiff.dev/es/tafs/privacy
```

## Italian (it)

### App Store

**Name** (27/30): tafs: agenti IA per aziende

**Subtitle** (25/30): Riprenditi le tue mattine

**Promotional text** (162/170):

```
Ticket di assistenza, nuovi contatti, pacchi in ritardo: affidali a un taf e riprenditi le tue mattine. Lavora nei tuoi limiti, e ciò che conta aspetta il tuo sì.
```

**Keywords** (94/100):

```
automazione,assistente,assistenza,ticket,contatti,pacchi,clienti,approvazione,workflow,routine
```

**Description** (3115/4000):

```
They taf. You kiff.
Automatizza la tua attività senza rinunciare al controllo.

Ogni giorno si accumulano le stesse attività. Un ticket lasciato per la notte diventa un cliente arrabbiato. Un contatto che aspetta un giorno compra altrove. Un pacco in ritardo di cui nessuno parla diventa una richiesta di rimborso.

Con tafs, affidi queste attività. Descrivi l'attività a Coach con parole tue, o scegline una pronta. Coach, l'assistente IA di tafs, collega le tue app e crea un taf: un agente IA, o collega IA, che fa quell'attività ogni giorno. Così dedichi il tuo tempo al lavoro che ti piace davvero.

PERCHÉ PUOI LASCIARLO FARE
Ogni taf lavora con una Card che firmi tu: cosa fa da solo, cosa ti chiede prima e cosa non fa mai. Ogni azione viene verificata da KIFF rispetto alla sua Card, fuori dall'IA, prima che raggiunga le tue app. Nessun prompt cambia un limite, nemmeno un trucco in un'email. Tutto ciò che conta aspetta il tuo sì sul telefono, con Face ID o Touch ID. Nessuna risposta vuol dire no.

ATTIVITÀ DA AFFIDARE GIÀ OGGI
• Nessun ticket aspetta: conferma ogni ticket di assistenza in giornata e ti passa tutto ciò che promette soldi.
• Non perdere più un contatto per una risposta lenta: trasforma ogni nuova compilazione del modulo in un'opportunità e invia una prima risposta entro un'ora.
• Avvisa i clienti prima che chiedano: li avverte quando un pacco è in ritardo. Una nuova spedizione aspetta il tuo sì.
• Trattieni i clienti che disdicono: offre ciò che la tua Card consente, e tutto il resto aspetta te.
Altre in arrivo: rispondere alle domande sugli ordini del tuo negozio, dare le app a chi arriva e toglierle a chi se ne va, prenotazioni e ricevute. Ogni limite lo cambi tu.

COSA VEDI
• Ti aspetta: ciò che aspetta il tuo sì.
• Registro: tutto ciò che hanno fatto i tuoi tafs, dal più recente, con una ricevuta per ogni azione andata a buon fine.
• Chat: chiedi a un taf cosa ha fatto, o digli cosa fare in modo diverso, e segui il suo lavoro mentre procede.
• Decidi tu cosa è successo davvero: quando un'app non riesce a confermare che un'azione è andata a buon fine, tafs ti chiede «È successo?». Niente viene considerato fatto per supposizione.

I TUOI DATI
Il taf non vede mai le tue password né i tuoi token. Le approvazioni sono firmate con una chiave custodita nell'hardware sicuro del tuo telefono. Blocca ferma un taf, o tutti, con un tocco. Niente pubblicità, niente tracker. Elimina il tuo account nell'app, da Account, oppure vedi kiff.dev/it/privacy#delete-account.

PIANI
Solo, con 500 crediti al mese, e pacchetti di crediti che non scadono mai, entrambi acquistati nell'app tramite l'App Store. Ogni esecuzione di un taf usa crediti per l'IA che ha usato. Un controllo tranquillo, quando non c'è niente di nuovo, non costa nulla. Coach è incluso: abbastanza per configurare circa 4 nuovi lavori al mese. Oltre questa soglia, l'aiuto di Coach usa i tuoi crediti. Non c'è una prova gratuita.

Condizioni d'uso (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
Condizioni di tafs: https://kiff.dev/it/terms
Privacy: https://kiff.dev/it/privacy
```

### Google Play

**Title** (27/30): tafs: agenti IA per aziende

**Short description** (79/80): Affida rimborsi, contestazioni e fatture ad agenti IA. Il controllo resta a te.

**Full description** (3377/4000):

```
They taf. You kiff.
Automatizza la tua attività senza rinunciare al controllo.

Ogni giorno si accumulano le stesse attività. Un rimborso lasciato fino a domani può diventare un chargeback, con una commissione che potresti pagare anche se vinci. Una contestazione a cui nessuno risponde in tempo è persa. Chi paga in ritardo continua a farlo. Un lead che aspetta un giorno compra altrove.

Con tafs, affidi queste attività. Descrivi l'attività a Coach con parole tue, o scegline una pronta. Coach, l'assistente IA di tafs, collega le tue app e crea un taf: un agente IA, o collega IA, che fa quell'attività ogni giorno. Così dedichi il tuo tempo al lavoro che ti piace davvero.

PERCHÉ PUOI LASCIARLO FARE
Ogni taf lavora con una Card che firmi tu: cosa fa da solo, cosa ti chiede prima e cosa non fa mai. Ogni azione viene verificata da KIFF rispetto alla sua Card, fuori dall'IA, prima che raggiunga le tue app. Nessun prompt cambia un limite, nemmeno un trucco in un'email. Tutto ciò che conta aspetta il tuo sì sul telefono, con l'impronta o il volto. Nessuna risposta vuol dire no.

ATTIVITÀ DA AFFIDARE GIÀ OGGI
• Nessun rimborso aspetta fino a domani: risponde alle richieste di rimborso dall'assistenza e rimborsa nei limiti della Card, per esempio fino a 150 € ciascuno e 2.000 € al giorno.
• Smetti di perdere contestazioni che potresti vincere: risponde alle contestazioni sulle carte entro la scadenza con l'ordine, la prova di consegna e i messaggi del cliente.
• Fatti pagare senza rincorrere nessuno: ricorda ai clienti le fatture scadute secondo un calendario e non muove denaro.
• Non perdere più un contatto per una risposta lenta: trasforma ogni nuova compilazione del modulo in un'opportunità e invia una prima risposta entro un'ora.
• Basta rispondere a «dov'è il mio ordine?»: risponde alle domande sugli ordini controllando il negozio e ti passa tutto ciò che riguarda i soldi.
Altre sono già pronte: prenotazioni, ricevute e trattenere i clienti che disdicono. Ogni limite lo puoi cambiare tu.

COSA VEDI
• Ti aspetta: ciò che aspetta il tuo sì.
• Registro: tutto ciò che hanno fatto i tuoi tafs, dal più recente, con una ricevuta per ogni azione andata a buon fine.
• Chat: chiedi a un taf cosa ha fatto, o digli cosa fare in modo diverso, e segui il suo lavoro mentre procede.
• Decidi tu cosa è successo davvero: quando un'app non riesce a confermare che un pagamento è andato a buon fine, tafs ti chiede «È successo?». Niente viene considerato fatto per supposizione.

I TUOI DATI
Il taf non vede mai le tue password né i tuoi token. Le approvazioni sono firmate con una chiave custodita nell'hardware sicuro del tuo telefono. Blocca ferma un taf, o tutti, con un tocco. Niente pubblicità, niente tracker. Elimina il tuo account nell'app, da Account, oppure vedi kiff.dev/it/tafs/privacy#rights.

PIANI
Solo, con 500 crediti al mese, e pacchetti di crediti che non scadono mai, entrambi acquistati nell'app tramite Google Play. Ogni esecuzione di un taf usa crediti per l'IA che ha usato. Un controllo tranquillo, quando non c'è niente di nuovo, non costa nulla. Coach è incluso: abbastanza per configurare circa 4 nuovi lavori al mese. Oltre questa soglia, l'aiuto di Coach usa i tuoi crediti. Non c'è una prova gratuita. Piani e prezzi: kiff.dev/it/tafs

Condizioni di tafs: https://kiff.dev/it/tafs/terms
Privacy: https://kiff.dev/it/tafs/privacy
```

## Portuguese (pt)

### App Store

**Name** (30/30): tafs: agentes IA para empresas

**Subtitle** (23/30): Recupere as suas manhãs

**Promotional text** (167/170):

```
Tickets de suporte, novos contactos, encomendas atrasadas: entregue-os a um taf e recupere as suas manhãs. Trabalha nos seus limites, e o importante aguarda o seu sim.
```

**Keywords** (93/100):

```
automatização,assistente,suporte,tickets,contactos,encomendas,clientes,aprovação,fluxo,rotina
```

**Description** (3112/4000):

```
They taf. You kiff.
Automatize o seu negócio sem perder o controlo.

Todos os dias acumulam-se as mesmas tarefas. Um ticket deixado para o dia seguinte torna-se um cliente zangado. Um contacto que espera um dia compra noutro lado. Uma encomenda atrasada de que ninguém fala torna-se um pedido de reembolso.

Com tafs, entrega essas tarefas. Diga ao Coach a tarefa por palavras suas, ou escolha uma já pronta. O Coach, o assistente de IA do tafs, liga as suas apps e cria um taf: um agente de IA, ou colega de IA, que faz essa tarefa todos os dias. Assim dedica o seu tempo ao trabalho de que realmente gosta.

PORQUE PODE DEIXÁ-LO TRABALHAR
Cada taf trabalha com um Card que assina: o que faz sozinho, o que lhe pergunta primeiro e o que nunca faz. O KIFF verifica cada ação face ao seu Card, fora da IA, antes de chegar às suas apps. Nenhum prompt muda um limite, nem sequer um truque num email. Tudo o que é importante aguarda o seu sim no seu telemóvel, com Face ID ou Touch ID. Sem resposta, é não.

TAREFAS QUE PODE ENTREGAR HOJE
• Nenhum ticket fica à espera: confirma a receção de cada ticket de suporte no próprio dia e passa-lhe tudo o que prometa dinheiro.
• Nunca perca um contacto por responder tarde: transforma cada novo preenchimento do formulário numa oportunidade e envia uma primeira resposta em menos de uma hora.
• Avise os clientes antes de perguntarem: avisa-os quando uma encomenda se atrasa. Um reenvio aguarda o seu sim.
• Mantenha os clientes que cancelam: oferece o que o seu Card permite, e tudo o resto aguarda por si.
Há mais a caminho: responder a perguntas sobre encomendas da sua loja, dar as apps a quem entra e retirá-las a quem sai, marcações e recibos. Cada limite é seu para mudar.

O QUE VÊ
• Precisa de si: o que espera pelo seu sim.
• Registo: tudo o que os seus tafs fizeram, do mais recente para o mais antigo, com um recibo por cada ação que avançou.
• Chat: pergunte a um taf o que fez, ou diga-lhe o que fazer de outra forma, e acompanhe o trabalho enquanto decorre.
• Decide o que aconteceu mesmo: quando uma app não consegue confirmar que uma ação foi feita, o tafs pergunta-lhe «Isto aconteceu?». Nada é dado como feito por palpite.

OS SEUS DADOS
O taf nunca vê as suas palavras-passe nem os seus tokens. As aprovações são assinadas com uma chave guardada no hardware seguro do seu telemóvel. Bloquear para um taf, ou todos, com um toque. Sem anúncios, sem rastreadores. Elimine a sua conta na app, em Conta, ou consulte kiff.dev/pt/privacy#delete-account.

PLANOS
Solo, com 500 créditos por mês, e pacotes de créditos que nunca expiram, ambos comprados na app através da App Store. Cada execução de um taf usa créditos pela IA que utilizou. Uma verificação calma, quando não há nada de novo, não custa nada. O Coach está incluído: o suficiente para configurar cerca de 4 novos trabalhos por mês. Depois disso, a ajuda do Coach usa os seus créditos. Não há período de teste gratuito.

Termos de Utilização (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
Termos do tafs: https://kiff.dev/pt/terms
Privacidade: https://kiff.dev/pt/privacy
```

### Google Play

**Title** (30/30): tafs: agentes IA para empresas

**Short description** (74/80): Entregue reembolsos, disputas e faturas a agentes de IA. O controlo é seu.

**Full description** (3335/4000):

```
They taf. You kiff.
Automatize o seu negócio sem perder o controlo.

Todos os dias acumulam-se as mesmas tarefas. Um reembolso que fica para o dia seguinte pode acabar num estorno, com uma taxa que pode pagar mesmo que ganhe. Uma disputa a que ninguém responde a tempo está perdida. Quem paga tarde continua a pagar tarde. Um contacto que espera um dia compra noutro lado.

Com tafs, entrega essas tarefas. Diga ao Coach a tarefa por palavras suas, ou escolha uma já pronta. O Coach, o assistente de IA do tafs, liga as suas apps e cria um taf: um agente de IA, ou colega de IA, que faz essa tarefa todos os dias. Assim dedica o seu tempo ao trabalho de que realmente gosta.

PORQUE PODE DEIXÁ-LO TRABALHAR
Cada taf trabalha com um Card que assina: o que faz sozinho, o que lhe pergunta primeiro e o que nunca faz. O KIFF verifica cada ação face ao seu Card, fora da IA, antes de chegar às suas apps. Nenhum prompt muda um limite, nem sequer um truque num email. Tudo o que é importante aguarda o seu sim no seu telemóvel, com a sua impressão digital ou o seu rosto. Sem resposta, é não.

TAREFAS QUE PODE ENTREGAR HOJE
• Nenhum reembolso fica para amanhã: responde aos pedidos de reembolso do apoio ao cliente e reembolsa dentro do Card, por exemplo até 150 € cada e 2000 € por dia.
• Deixe de perder disputas que podia ganhar: responde às disputas de cartão dentro do prazo com a encomenda, a prova de entrega e as mensagens do cliente.
• Receba sem andar atrás de ninguém: lembra os clientes das faturas em atraso segundo um calendário e não movimenta dinheiro.
• Nunca perca um contacto por responder tarde: transforma cada novo preenchimento do formulário numa oportunidade e envia uma primeira resposta em menos de uma hora.
• Deixe de responder a «onde está a minha encomenda?»: responde às perguntas sobre encomendas consultando a loja e passa-lhe tudo o que envolva dinheiro.
Há mais prontas: marcações, recibos e manter clientes que cancelam. Cada limite pode ser alterado por si.

O QUE VÊ
• Precisa de si: o que espera pelo seu sim.
• Registo: tudo o que os seus tafs fizeram, do mais recente para o mais antigo, com um recibo por cada ação que avançou.
• Chat: pergunte a um taf o que fez, ou diga-lhe o que fazer de outra forma, e acompanhe o trabalho enquanto decorre.
• Decide o que aconteceu mesmo: quando uma app não consegue confirmar que um pagamento foi feito, o tafs pergunta-lhe «Isto aconteceu?». Nada é dado como feito por palpite.

OS SEUS DADOS
O taf nunca vê as suas palavras-passe nem os seus tokens. As aprovações são assinadas com uma chave guardada no hardware seguro do seu telemóvel. Bloquear para um taf, ou todos, com um toque. Sem anúncios, sem rastreadores. Elimine a sua conta na app, em Conta, ou consulte kiff.dev/pt/tafs/privacy#rights.

PLANOS
Solo, com 500 créditos por mês, e pacotes de créditos que nunca expiram, ambos comprados na app através do Google Play. Cada execução de um taf usa créditos pela IA que utilizou. Uma verificação calma, quando não há nada de novo, não custa nada. O Coach está incluído: o suficiente para configurar cerca de 4 novos trabalhos por mês. Depois disso, a ajuda do Coach usa os seus créditos. Não há período de teste gratuito. Planos e preços: kiff.dev/pt/tafs

Termos do tafs: https://kiff.dev/pt/tafs/terms
Privacidade: https://kiff.dev/pt/tafs/privacy
```

## Spanish, Latin America (es-MX)

**Wave 1 (Latin America), App Store only, repo only:** a new localization, Spanish (Mexico), which App Store Connect doesn't have
yet (mac-local's read, 21 Nov). Adapted from es (es-ES), above, with the same claims and limits, in Mexican usage ("celular",
"envío", "molesto", "vencen"). The names it quotes are the app's own, as a phone in Mexico shows them in Spanish (Te necesita,
Registro, ¿Ocurrió esto?, Cuenta, Bloquear). Its words are read by the language reviewer before it's
applied. Applying it waits for the owner's Wave 1 yes. Play's Latin American listing is es-419, dev-android's.

### App Store

**Name** (30/30): tafs: agentes IA para empresas

**Subtitle** (20/30): Recupera tus mañanas

**Promotional text** (160/170):

```
Tickets de soporte, contactos nuevos, envíos retrasados: pásaselos a un taf y recupera tus mañanas. Trabaja dentro de tus límites, y lo importante espera tu sí.
```

**Keywords** (90/100):

```
automatización,asistente,soporte,tickets,contactos,envíos,clientes,aprobación,flujo,rutina
```

**Description** (3027/4000):

```
They taf. You kiff.
Automatiza tu negocio sin perder el control.

Cada día se acumulan las mismas tareas. Un ticket que se queda sin respuesta de un día para otro acaba en un cliente molesto. Un contacto que espera un día le compra a alguien más. Un envío retrasado del que nadie avisa acaba en una solicitud de reembolso.

Con tafs, delegas esas tareas. Cuéntale a Coach la tarea con tus palabras o elige una ya hecha. Coach, el asistente de IA de tafs, conecta tus apps y crea un taf: un agente de IA, o compañero de IA, que hace esa tarea cada día. Así dedicas tu tiempo al trabajo que de verdad te gusta.

POR QUÉ PUEDES DEJARLO HACER
Cada taf trabaja con una Card que tú firmas: lo que hace por su cuenta, lo que te pregunta primero y lo que nunca hace. KIFF comprueba cada acción contra su Card, fuera de la IA, antes de que llegue a tus apps. Ningún prompt cambia un límite, ni siquiera un truco en un correo. Todo lo importante espera tu sí en tu celular, con Face ID o Touch ID. Sin respuesta, es un no.

TAREAS QUE PUEDES DELEGAR HOY
• Ningún ticket espera: acusa recibo de cada ticket de soporte el mismo día y te pasa todo lo que prometa dinero.
• No pierdas ni un contacto por responder tarde: convierte cada nueva entrada del formulario en una oportunidad y envía una primera respuesta en menos de una hora.
• Avisa a los clientes antes de que pregunten: les avisa cuando un envío se retrasa. Un reenvío espera tu sí.
• Conserva a los clientes que cancelan: ofrece lo que tu Card permite, y todo lo demás te espera.
Vienen más: responder preguntas sobre pedidos de tu tienda, dar sus apps a quien entra y quitárselas a quien se va, reservas y recibos. Cada límite lo cambias tú.

LO QUE VES
• Te necesita: lo que espera tu sí.
• Registro: todo lo que hicieron tus tafs, lo más reciente primero, con un recibo por cada acción que salió adelante.
• Chat: pregúntale a un taf qué hizo, o dile qué hacer de otra forma, y sigue su trabajo mientras avanza.
• Tú decides qué pasó de verdad: cuando una app no puede confirmar que una acción se hizo, tafs te pregunta «¿Ocurrió esto?». Nada se da por hecho a ciegas.

TUS DATOS
El taf nunca ve tus contraseñas ni tus tokens. Las aprobaciones se firman con una clave guardada en el hardware seguro de tu celular. Bloquear detiene un taf, o todos, con un toque. Sin anuncios, sin rastreadores. Borra tu cuenta en la app, en Cuenta, o consulta kiff.dev/es/privacy#delete-account.

PLANES
Solo, con 500 créditos al mes, y paquetes de créditos que nunca vencen, ambos comprados en la app a través del App Store. Cada ejecución de un taf usa créditos por la IA que utilizó. Una revisión tranquila, cuando no hay nada nuevo, no cuesta nada. Coach está incluido: lo suficiente para configurar unos 4 trabajos nuevos al mes. Después de eso, la ayuda de Coach usa tus créditos. No hay prueba gratuita.

Condiciones de uso (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
Condiciones de tafs: https://kiff.dev/es/terms
Privacidad: https://kiff.dev/es/privacy
```

## Portuguese, Brazil (pt-BR)

**Wave 1 (Brazil), App Store only, repo only:** a new localization, Portuguese (Brazil), which App Store Connect doesn't have
yet (mac-local's read, 21 Nov). Adapted from pt (pt-PT), above, with the same claims and limits, in Brazilian usage ("você",
"celular", "contato", "controle", "senhas", "app" masculine). The names it quotes are the app's own, as a phone in Brazil shows
them in the app's Brazilian Portuguese (Precisa de você, Registro, Isto aconteceu?, Conta, Bloquear; tafs#1629), so the listing and the screens match.
Its words are read by the language reviewer before it's applied. Applying it waits for the owner's
Wave 1 yes. Play's pt-BR listing is dev-android's.

### App Store

**Name** (30/30): tafs: agentes IA para empresas

**Subtitle** (20/30): Recupere suas manhãs

**Promotional text** (164/170):

```
Tickets de suporte, novos contatos, entregas atrasadas: passe tudo para um taf e recupere suas manhãs. Ele trabalha nos seus limites, e o importante espera seu sim.
```

**Keywords** (86/100):

```
automação,assistente,suporte,tickets,contatos,entregas,clientes,aprovação,fluxo,rotina
```

**Description** (3083/4000):

```
They taf. You kiff.
Automatize seu negócio sem perder o controle.

Todo dia as mesmas tarefas se acumulam. Um ticket deixado para o dia seguinte vira um cliente irritado. Um contato que espera um dia compra em outro lugar. Uma entrega atrasada sobre a qual ninguém avisa vira um pedido de reembolso.

Com o tafs, você delega essas tarefas. Conte ao Coach a tarefa com suas palavras, ou escolha uma pronta. O Coach, o assistente de IA do tafs, conecta seus apps e cria um taf: um agente de IA, ou colega de IA, que faz essa tarefa todos os dias. Assim você dedica seu tempo ao trabalho de que realmente gosta.

POR QUE VOCÊ PODE DEIXÁ-LO TRABALHAR
Cada taf trabalha com um Card que você assina: o que ele faz sozinho, o que pergunta antes e o que nunca faz. O KIFF confere cada ação com o Card, fora da IA, antes que ela chegue aos seus apps. Nenhum prompt muda um limite, nem mesmo um truque em um email. Tudo o que é importante espera seu sim no seu celular, com Face ID ou Touch ID. Sem resposta, é não.

TAREFAS QUE VOCÊ PODE DELEGAR HOJE
• Nenhum ticket fica esperando: confirma o recebimento de cada ticket de suporte no mesmo dia e passa para você tudo o que prometa dinheiro.
• Nunca perca um contato por responder tarde: transforma cada novo envio do formulário em uma oportunidade e manda uma primeira resposta em menos de uma hora.
• Avise os clientes antes que perguntem: avisa quando uma entrega atrasa. Um reenvio espera seu sim.
• Mantenha os clientes que cancelam: oferece o que o seu Card permite, e todo o resto espera por você.
Vem mais por aí: responder a perguntas sobre pedidos da sua loja, dar os apps a quem entra e tirá-los de quem sai, agendamentos e recibos. Cada limite é seu para mudar.

O QUE VOCÊ VÊ
• Precisa de você: o que espera seu sim.
• Registro: tudo o que seus tafs fizeram, do mais recente ao mais antigo, com um recibo para cada ação que foi adiante.
• Chat: pergunte a um taf o que ele fez, ou diga como fazer de outro jeito, e acompanhe o trabalho enquanto acontece.
• Você decide o que aconteceu de verdade: quando um app não consegue confirmar que uma ação foi feita, o tafs pergunta «Isto aconteceu?». Nada é dado como feito por suposição.

SEUS DADOS
O taf nunca vê suas senhas nem seus tokens. As aprovações são assinadas com uma chave guardada no hardware seguro do seu celular. Bloquear interrompe um taf, ou todos, com um toque. Sem anúncios, sem rastreadores. Exclua sua conta no app, em Conta, ou consulte kiff.dev/pt/privacy#delete-account.

PLANOS
Solo, com 500 créditos por mês, e pacotes de créditos que nunca expiram, ambos comprados no app pela App Store. Cada execução de um taf usa créditos pela IA que usou. Uma verificação tranquila, quando não há nada de novo, não custa nada. O Coach está incluído: o suficiente para configurar cerca de 4 novos trabalhos por mês. Depois disso, a ajuda do Coach usa seus créditos. Não há período de teste gratuito.

Termos de Uso (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
Termos do tafs: https://kiff.dev/pt/terms
Privacidade: https://kiff.dev/pt/privacy
```

## Dutch (nl)

### App Store

**Name** (30/30): tafs: AI-agents voor bedrijven

**Subtitle** (24/30): Krijg je ochtenden terug

**Promotional text** (168/170):

```
Supporttickets, nieuwe leads, vertraagde pakketten: geef ze aan een taf en krijg je ochtenden terug. Het werkt binnen jouw limieten, en alles wat telt wacht op jouw ja.
```

**Keywords** (93/100):

```
automatisering,assistent,support,tickets,leads,pakketten,klanten,goedkeuring,workflow,routine
```

**Description** (3091/4000):

```
They taf. You kiff.
Automatiseer je bedrijf zonder de controle uit handen te geven.

Elke dag stapelen dezelfde taken zich op. Een ticket dat een nacht blijft liggen, wordt een boze klant. Een lead die een dag wacht, koopt ergens anders. Een vertraagd pakket waar niemand iets over zegt, wordt een terugbetalingsverzoek.

Met tafs geef je die taken uit handen. Vertel Coach de taak in je eigen woorden, of kies een kant-en-klare. Coach, de AI-assistent in tafs, koppelt je apps en bouwt een taf: een AI-agent, of AI-collega, die die taak elke dag doet. Zo houd je tijd over voor het werk dat je echt leuk vindt.

WAAROM JE HET KUNT LATEN DOEN
Elk taf werkt met een Card die jij ondertekent: wat het zelf doet, wat het je eerst vraagt en wat het nooit doet. KIFF toetst elke actie aan zijn Card, buiten de AI, voordat die je apps bereikt. Geen prompt verandert een limiet, ook geen truc in een e-mail. Alles wat groot is, wacht op je telefoon op jouw ja, met Face ID of Touch ID. Geen antwoord betekent nee.

TAKEN DIE JE VANDAAG UIT HANDEN KUNT GEVEN
• Geen ticket blijft liggen: bevestigt elk supportticket nog dezelfde dag en geeft alles wat geld belooft aan jou door.
• Verlies nooit meer een lead door een trage reactie: maakt van elke nieuwe formulierinzending een deal en stuurt binnen een uur een eerste antwoord.
• Vertel het klanten voordat ze het vragen: waarschuwt ze als een pakket te laat is. Opnieuw verzenden wacht op jouw ja.
• Houd klanten die opzeggen: biedt aan wat je Card toestaat, en al het andere wacht op jou.
Er komen er meer: vragen over bestellingen uit je winkel beantwoorden, nieuwe mensen hun apps geven en ze weer afnemen van wie vertrekt, afspraken en bonnetjes. Elke limiet pas je zelf aan.

WAT JE ZIET
• Wacht op jou: wat op jouw ja wacht.
• Logboek: alles wat je tafs gedaan hebben, het nieuwste eerst, met een bewijs voor elke actie die doorging.
• Chat: vraag een taf wat het gedaan heeft, of zeg het wat het anders moet doen, en volg zijn werk terwijl het gebeurt.
• Jij bepaalt wat er echt gebeurd is: als een app niet kan bevestigen dat een actie is gelukt, vraagt tafs je „Is dit gebeurd?” Niets telt op goed geluk als gedaan.

JE GEGEVENS
Het taf ziet nooit je wachtwoorden of tokens. Goedkeuringen worden ondertekend met een sleutel in de beveiligde hardware van je telefoon. Stilzetten zet één taf, of allemaal, stil met één tik. Geen advertenties, geen trackers. Verwijder je account in de app, onder Account, of zie kiff.dev/nl/privacy#delete-account.

ABONNEMENTEN
Solo, met 500 credits per maand, en creditpakketten die nooit verlopen, allebei in de app gekocht via de App Store. Elke run van een taf gebruikt credits voor de AI die het gebruikte. Een rustige controle, als er niets nieuws is, kost niets. Coach is inbegrepen: genoeg om zo'n 4 nieuwe taken per maand in te stellen. Daarboven gebruikt de hulp van Coach je credits. Er is geen gratis proefperiode.

Gebruiksvoorwaarden (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
Voorwaarden van tafs: https://kiff.dev/nl/terms
Privacy: https://kiff.dev/nl/privacy
```

### Google Play

**Title** (30/30): tafs: AI-agents voor bedrijven

**Short description** (79/80): Geef terugbetalingen, geschillen en facturen aan AI-agents. Jij houdt controle.

**Full description** (3384/4000):

```
They taf. You kiff.
Automatiseer je bedrijf zonder de controle uit handen te geven.

Elke dag stapelen dezelfde taken zich op. Een terugbetaling die een nacht blijft liggen, kan een terugboeking worden, met kosten die je soms ook betaalt als je wint. Een geschil dat niemand op tijd beantwoordt, is verloren. Wie laat betaalt, blijft laat betalen. Een lead die een dag wacht, koopt ergens anders.

Met tafs geef je die taken uit handen. Vertel Coach de taak in je eigen woorden, of kies een kant-en-klare. Coach, de AI-assistent in tafs, koppelt je apps en bouwt een taf: een AI-agent, of AI-collega, die die taak elke dag doet. Zo houd je tijd over voor het werk dat je echt leuk vindt.

WAAROM JE HET KUNT LATEN DOEN
Elk taf werkt met een Card die jij ondertekent: wat het zelf doet, wat het je eerst vraagt en wat het nooit doet. KIFF toetst elke actie aan zijn Card, buiten de AI, voordat die je apps bereikt. Geen prompt verandert een limiet, ook geen truc in een e-mail. Alles wat groot is, wacht op je telefoon op jouw ja, met je vingerafdruk of gezicht. Geen antwoord betekent nee.

TAKEN DIE JE VANDAAG UIT HANDEN KUNT GEVEN
• Geen terugbetaling wacht tot morgen: beantwoordt terugbetalingsverzoeken uit de helpdesk en betaalt terug binnen de Card, bijvoorbeeld tot € 150 per stuk en € 2.000 per dag.
• Verlies geen geschillen meer die je kunt winnen: beantwoordt kaartgeschillen voor de deadline met de bestelling, het afleverbewijs en de berichten van de klant.
• Krijg betaald zonder achter mensen aan te gaan: herinnert klanten volgens een schema aan achterstallige facturen en verplaatst geen geld.
• Verlies nooit meer een lead door een trage reactie: maakt van elke nieuwe formulierinzending een deal en stuurt binnen een uur een eerste antwoord.
• Nooit meer „waar is mijn bestelling?” beantwoorden: beantwoordt vragen over bestellingen door in de winkel te kijken en geeft alles wat met geld te maken heeft aan jou door.
Er zijn er meer kant-en-klaar: afspraken, bonnetjes en klanten behouden die opzeggen. Elke limiet kun je zelf aanpassen.

WAT JE ZIET
• Wacht op jou: wat op jouw ja wacht.
• Logboek: alles wat je tafs gedaan hebben, het nieuwste eerst, met een bewijs voor elke actie die doorging.
• Chat: vraag een taf wat het gedaan heeft, of zeg het wat het anders moet doen, en volg zijn werk terwijl het gebeurt.
• Jij bepaalt wat er echt gebeurd is: als een app niet kan bevestigen dat een betaling is gelukt, vraagt tafs je „Is dit gebeurd?” Niets telt op goed geluk als gedaan.

JE GEGEVENS
Het taf ziet nooit je wachtwoorden of tokens. Goedkeuringen worden ondertekend met een sleutel in de beveiligde hardware van je telefoon. Stilzetten zet één taf, of allemaal, stil met één tik. Geen advertenties, geen trackers. Verwijder je account in de app, onder Account, of zie kiff.dev/nl/tafs/privacy#rights.

ABONNEMENTEN
Solo, met 500 credits per maand, en creditpakketten die nooit verlopen, allebei in de app gekocht via Google Play. Elke run van een taf gebruikt credits voor de AI die het gebruikte. Een rustige controle, als er niets nieuws is, kost niets. Coach is inbegrepen: genoeg om zo'n 4 nieuwe taken per maand in te stellen. Daarboven gebruikt de hulp van Coach je credits. Er is geen gratis proefperiode. Abonnementen en prijzen: kiff.dev/nl/tafs

Voorwaarden van tafs: https://kiff.dev/nl/tafs/terms
Privacy: https://kiff.dev/nl/tafs/privacy
```

## Polish (pl)

### App Store

**Name** (24/30): tafs: agenci AI dla firm

**Subtitle** (22/30): Odzyskaj swoje poranki

**Promotional text** (163/170):

```
Zgłoszenia do supportu, nowe leady, spóźnione paczki: oddaj je tafowi i odzyskaj swoje poranki. Działa w Twoich limitach, a wszystko, co ważne, czeka na Twoje tak.
```

**Keywords** (92/100):

```
automatyzacja,asystent,support,zgłoszenia,leady,paczki,klienci,zatwierdzanie,workflow,rutyna
```

**Description** (3116/4000):

```
They taf. You kiff.
Automatyzuj firmę, nie oddając kontroli.

Każdego dnia piętrzą się te same zadania. Zgłoszenie zostawione na noc zamienia się w zdenerwowanego klienta. Lead, który czeka dzień, kupuje gdzie indziej. Spóźniona paczka, o której nikt nie wspomni, zamienia się w prośbę o zwrot pieniędzy.

Z tafs oddajesz te zadania. Opisz Coachowi zadanie własnymi słowami albo wybierz gotowe. Coach, asystent AI w tafs, łączy Twoje aplikacje i tworzy tafa: agenta AI, czyli współpracownika AI, który codziennie wykonuje to zadanie. Ty poświęcasz czas pracy, którą naprawdę lubisz.

DLACZEGO MOŻESZ MU ZAUFAĆ
Każdy taf działa według Card, którą podpisujesz: co robi sam, o co najpierw pyta Ciebie i czego nigdy nie robi. KIFF sprawdza każdą akcję względem jego Card, poza AI, zanim dotrze ona do Twoich aplikacji. Żaden prompt nie zmienia limitu, nawet podstęp w e-mailu. Wszystko, co ważne, czeka na Twoje tak na telefonie, za pomocą Face ID lub Touch ID. Brak odpowiedzi oznacza nie.

ZADANIA, KTÓRE ODDASZ JUŻ DZIŚ
• Żadne zgłoszenie nie czeka: potwierdza każde zgłoszenie do supportu tego samego dnia, a wszystko, co obiecuje pieniądze, przekazuje Tobie.
• Nie trać leadów przez wolną odpowiedź: z każdego nowego zgłoszenia z formularza tworzy szansę sprzedaży i wysyła pierwszą odpowiedź w ciągu godziny.
• Uprzedź klientów, zanim zapytają: ostrzega ich, gdy paczka się spóźnia. Ponowna wysyłka czeka na Twoje tak.
• Zatrzymaj klientów, którzy rezygnują: proponuje to, na co pozwala Twoja Card, a wszystko więcej czeka na Ciebie.
Kolejne w drodze: odpowiadanie na pytania o zamówienia z Twojego sklepu, nadawanie aplikacji nowym osobom i odbieranie ich odchodzącym, rezerwacje i paragony. Każdy limit zmieniasz sam.

CO WIDZISZ
• Czeka na Ciebie: to, co czeka na Twoje tak.
• Rejestr: wszystko, co zrobiły Twoje tafs, od najnowszych, z potwierdzeniem dla każdej akcji, która przeszła.
• Czat: zapytaj tafa, co zrobił, albo powiedz mu, co ma robić inaczej, i śledź jego pracę na bieżąco.
• To Ty decydujesz, co naprawdę się stało: gdy aplikacja nie może potwierdzić, że akcja przeszła, tafs pyta Cię „Czy to się wydarzyło?”. Nic nie jest uznawane za wykonane na podstawie domysłów.

TWOJE DANE
Taf nigdy nie widzi Twoich haseł ani tokenów. Zatwierdzenia są podpisywane kluczem przechowywanym w bezpiecznym sprzęcie Twojego telefonu. Zablokuj zatrzymuje jednego tafa albo wszystkie jednym dotknięciem. Bez reklam, bez trackerów. Usuń konto w aplikacji, w sekcji Konto, albo zobacz kiff.dev/pl/privacy#delete-account.

PLANY
Solo z 500 kredytami miesięcznie oraz pakiety kredytów, które nigdy nie wygasają, oba kupowane w aplikacji przez App Store. Każde uruchomienie tafa zużywa kredyty za AI, której użyło. Spokojne sprawdzenie, gdy nie ma nic nowego, nic nie kosztuje. Coach jest w cenie: wystarczy, by skonfigurować około 4 nowych zadań miesięcznie. Powyżej tego pomoc Coacha używa Twoich kredytów. Nie ma bezpłatnego okresu próbnego.

Warunki korzystania (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
Warunki tafs: https://kiff.dev/pl/terms
Prywatność: https://kiff.dev/pl/privacy
```

### Google Play

**Title** (24/30): tafs: agenci AI dla firm

**Short description** (70/80): Oddaj zwroty, spory i faktury agentom AI. Kontrola zostaje przy Tobie.

**Full description** (3291/4000):

```
They taf. You kiff.
Automatyzuj firmę, nie oddając kontroli.

Codziennie piętrzą się te same zadania. Zwrot, który czeka do jutra, może skończyć się obciążeniem zwrotnym, z opłatą, którą możesz zapłacić nawet po wygranej. Spór, na który nikt nie odpowie na czas, przepada. Kto płaci z opóźnieniem, dalej płaci z opóźnieniem. Lead, który czeka dzień, kupuje gdzie indziej.

Z tafs oddajesz te zadania. Opisz Coachowi zadanie własnymi słowami albo wybierz gotowe. Coach, asystent AI w tafs, łączy Twoje aplikacje i tworzy tafa: agenta AI, czyli współpracownika AI, który codziennie wykonuje to zadanie. Ty poświęcasz czas pracy, którą naprawdę lubisz.

DLACZEGO MOŻESZ MU ZAUFAĆ
Każdy taf działa według Card, którą podpisujesz: co robi sam, o co najpierw pyta Ciebie i czego nigdy nie robi. KIFF sprawdza każdą akcję względem jego Card, poza AI, zanim dotrze ona do Twoich aplikacji. Żaden prompt nie zmienia limitu, nawet podstęp w e-mailu. Wszystko, co ważne, czeka na Twoje tak na telefonie, odciskiem palca lub twarzą. Brak odpowiedzi oznacza nie.

ZADANIA, KTÓRE ODDASZ JUŻ DZIŚ
• Żaden zwrot nie czeka do jutra: odpowiada na prośby o zwrot z helpdesku i zwraca pieniądze w ramach Card, na przykład do 150 € każdy i 2000 € dziennie.
• Przestań przegrywać spory, które możesz wygrać: odpowiada na spory kartowe przed terminem, dołączając zamówienie, dowód dostawy i wiadomości klienta.
• Dostawaj pieniądze bez ganiania za nikim: przypomina klientom o zaległych fakturach według harmonogramu i nie przelewa pieniędzy.
• Nie trać leadów przez wolną odpowiedź: z każdego nowego zgłoszenia z formularza tworzy szansę sprzedaży i wysyła pierwszą odpowiedź w ciągu godziny.
• Koniec z odpowiadaniem na „gdzie jest moje zamówienie?”: odpowiada na pytania o zamówienia, sprawdzając sklep, a wszystko, co dotyczy pieniędzy, przekazuje Tobie.
Kolejne są gotowe: rezerwacje, paragony i zatrzymywanie klientów, którzy rezygnują. Każdy limit możesz zmienić.

CO WIDZISZ
• Czeka na Ciebie: to, co czeka na Twoje tak.
• Rejestr: wszystko, co zrobiły Twoje tafs, od najnowszych, z potwierdzeniem dla każdej akcji, która przeszła.
• Czat: zapytaj tafa, co zrobił, albo powiedz mu, co ma robić inaczej, i śledź jego pracę na bieżąco.
• To Ty decydujesz, co naprawdę się stało: gdy aplikacja nie może potwierdzić, że płatność przeszła, tafs pyta Cię „Czy to się wydarzyło?”. Nic nie jest uznawane za wykonane na podstawie domysłów.

TWOJE DANE
Taf nigdy nie widzi Twoich haseł ani tokenów. Zatwierdzenia są podpisywane kluczem przechowywanym w bezpiecznym sprzęcie Twojego telefonu. Zablokuj zatrzymuje jednego tafa albo wszystkie jednym dotknięciem. Bez reklam, bez trackerów. Usuń konto w aplikacji, w sekcji Konto, albo zobacz kiff.dev/pl/tafs/privacy#rights.

PLANY
Solo z 500 kredytami miesięcznie oraz pakiety kredytów, które nigdy nie wygasają, oba kupowane w aplikacji przez Google Play. Każde uruchomienie tafa zużywa kredyty za AI, której użyło. Spokojne sprawdzenie, gdy nie ma nic nowego, nic nie kosztuje. Coach jest w cenie: wystarczy, by skonfigurować około 4 nowych zadań miesięcznie. Powyżej tego pomoc Coacha używa Twoich kredytów. Nie ma bezpłatnego okresu próbnego. Plany i ceny: kiff.dev/pl/tafs

Warunki tafs: https://kiff.dev/pl/tafs/terms
Prywatność: https://kiff.dev/pl/tafs/privacy
```

## Korean (ko)

Google Play only, for Korean testers once tafs ships Korean (the App Store listing in Korean is the
owner's call). The tafs pages aren't served in Korean, so the legal lines are marked as in English,
as the app marks them.

### Google Play

**Title** (22/30): tafs: 비즈니스를 위한 AI 에이전트

**Short description** (46/80): 환불, 분쟁, 청구서 업무를 AI 에이전트에게 맡기세요. 통제는 계속 내가 합니다.

**Full description** (1759/4000):

```
They taf. You kiff.
통제권을 내려놓지 않고 비즈니스를 자동화하세요.

매일 같은 업무가 쌓입니다. 하룻밤 방치된 환불 요청은 지불 거절(차지백)로 이어질 수 있고, 이기더라도 수수료를 내야 할 수 있습니다. 제때 답하지 않은 분쟁은 지게 됩니다. 늦게 내는 고객은 계속 늦게 냅니다. 하루를 기다린 잠재 고객은 다른 곳에서 구매합니다.

tafs로 이런 업무를 맡기세요. Coach에게 업무를 내 말로 설명하거나, 준비된 업무를 고르세요. tafs의 AI 어시스턴트인 Coach가 내 앱을 연결하고 taf를 만듭니다. taf는 그 업무를 매일 처리하는 AI 에이전트, 곧 AI 팀원입니다. 덕분에 나는 정말 좋아하는 일에 시간을 쓸 수 있습니다.

믿고 맡길 수 있는 이유
모든 taf는 내가 서명한 Card 안에서 일합니다. Card에는 스스로 하는 일, 먼저 나에게 묻는 일, 절대 하지 않는 일이 적혀 있습니다. 모든 작업은 내 앱에 닿기 전에 AI 밖에서 KIFF가 Card와 대조해 확인합니다. 이메일 속 속임수나 교묘한 프롬프트로도 내가 정한 한도를 넘길 수 없습니다. 중요한 일은 휴대폰에서 지문이나 얼굴로 내가 승인할 때까지 기다립니다. 답이 없으면 거절입니다.

지금 맡길 수 있는 업무
• 환불을 하룻밤 묵히지 않기: 고객 지원 대기열의 환불 요청에 답하고 Card 범위 안에서 환불합니다(예: 건당 최대 150유로, 하루 최대 2,000유로).
• 이길 수 있는 분쟁은 지키기: 기한 전에 주문 내역, 배송 증빙, 고객 메시지를 갖춰 카드 분쟁에 답합니다.
• 독촉 없이 대금 받기: 연체된 청구서를 일정에 따라 고객에게 알리며, 돈은 옮기지 않습니다.
• 느린 답장으로 잠재 고객을 놓치지 않기: 새 양식 제출을 거래로 등록하고 한 시간 안에 첫 답장을 보냅니다.
• "주문이 어디 있나요?"에 더는 답하지 않기: 쇼핑몰 정보로 주문 문의에 답하고, 돈에 관한 일은 나에게 넘깁니다.
예약, 영수증, 해지하려는 고객 붙잡기 등 더 많은 업무가 준비되어 있습니다. 모든 한도는 내가 바꿀 수 있습니다.

내가 보는 것
• 확인 필요: 내 승인을 기다리는 일.
• 기록: 내 taf가 한 모든 일을 최신순으로, 처리된 작업마다 영수증과 함께.
• 채팅: taf에게 무엇을 했는지 묻거나 다르게 할 일을 알려 주고, 일하는 과정을 지켜보세요.
• 실제로 일어난 일은 내가 정합니다: 앱이 결제 완료를 확인하지 못하면 tafs가 "이 일이 일어났나요?"라고 묻습니다. 추측으로 완료 처리되는 일은 없습니다.

내 데이터
taf는 내 비밀번호나 토큰을 절대 보지 않습니다. 승인은 휴대폰의 보안 하드웨어에 보관된 키로 서명됩니다. 차단을 한 번 탭하면 taf 하나 또는 전체가 멈춥니다. 광고도 추적기도 없습니다. 계정은 앱의 계정 화면에서 삭제하거나 kiff.dev/tafs/privacy#rights를 참고하세요.

요금제
매월 500 크레딧이 포함된 Solo와, 만료되지 않는 크레딧 팩이 있으며, 둘 다 앱에서 Google Play로 구매합니다. taf는 실행할 때마다 사용한 AI만큼 크레딧을 씁니다. 새로운 것이 없는 조용한 확인은 무료입니다. Coach가 포함되어 있어 한 달에 새 업무를 약 4개 설정할 수 있습니다. 그 이상은 Coach의 도움에 내 크레딧이 사용됩니다. 무료 체험은 없습니다. 요금제 및 가격: kiff.dev/tafs

tafs 서비스 약관(영어): https://kiff.dev/tafs/terms
개인정보처리방침(영어): https://kiff.dev/tafs/privacy
```

## Spanish, Latin America (es-419)

**Draft, Google Play only, not yet in Play Console.** Adapted from es (es-ES)'s Play fields, above, to Latin
American Spanish: the same claims and limits, with the region's everyday words (for example *huella digital*,
*rostro*, *vencen*). Screen names stay the app's own (*Te necesita*, *Registro*, *Cuenta*, *Bloquear*, "¿Ocurrió esto?"): the app
has no es-419 strings, so Android shows it in es. Amounts stay in euros, written out with no thousands separator,
since the region writes them both ways. The Plans link opens kiff.dev's euro prices; in the app, Google Play charges the
local price. It goes into Play Console when this market opens. The App Store's es-MX is drafted separately.

### Google Play

**Title** (30/30): tafs: agentes IA para empresas

**Short description** (78/80): Delega reembolsos, disputas y facturas en agentes de IA. Tú tienes el control.

**Full description** (3279/4000):

```
They taf. You kiff.
Automatiza tu negocio sin perder el control.

Cada día se acumulan las mismas tareas. Un reembolso que espera hasta mañana puede terminar en un contracargo, con una comisión que quizá pagues aunque ganes. Una disputa que nadie responde a tiempo se pierde. Quien paga tarde sigue pagando tarde. Un lead que espera un día compra en otro lado.

Con tafs, delegas esas tareas. Cuéntale a Coach la tarea con tus palabras o elige una ya lista. Coach, el asistente de IA de tafs, conecta tus apps y crea un taf: un agente de IA, o compañero de IA, que hace esa tarea todos los días. Así dedicas tu tiempo al trabajo que de verdad te gusta.

POR QUÉ PUEDES DEJARLO TRABAJAR
Cada taf trabaja con una Card que tú firmas: lo que hace por su cuenta, lo que te pregunta primero y lo que nunca hace. KIFF verifica cada acción contra su Card, fuera de la IA, antes de que llegue a tus apps. Ningún prompt cambia un límite, ni siquiera un truco en un correo. Todo lo importante espera tu sí en tu teléfono, con tu huella digital o tu rostro. Sin respuesta, es un no.

TAREAS QUE PUEDES DELEGAR HOY
• Ningún reembolso espera hasta mañana: responde las solicitudes de reembolso de atención al cliente y reembolsa dentro de la Card, por ejemplo hasta 150 euros cada uno y 2000 euros al día.
• Deja de perder disputas que podrías ganar: responde las disputas de tarjeta antes del plazo con el pedido, el comprobante de entrega y los mensajes del cliente.
• Cobra sin perseguir a nadie: recuerda a los clientes sus facturas vencidas según un calendario y no mueve dinero.
• No pierdas ni un contacto por responder tarde: convierte cada nuevo envío del formulario en una oportunidad y manda una primera respuesta en menos de una hora.
• Deja de responder “¿dónde está mi pedido?”: responde las preguntas sobre pedidos consultando la tienda y te pasa todo lo que tenga que ver con dinero.
Vienen más ya listas: reservas, recibos y retener a clientes que cancelan. Cada límite lo puedes cambiar tú.

LO QUE VES
• Te necesita: lo que espera tu sí.
• Registro: todo lo que hicieron tus tafs, lo más reciente primero, con un recibo por cada acción que salió adelante.
• Chat: pregúntale a un taf qué hizo, o dile qué hacer de otra forma, y sigue su trabajo mientras avanza.
• Tú decides qué pasó de verdad: cuando una app no puede confirmar que un pago se hizo, tafs te pregunta “¿Ocurrió esto?”. Nada se da por hecho a ciegas.

TUS DATOS
El taf nunca ve tus contraseñas ni tus tokens. Las aprobaciones se firman con una clave guardada en el hardware seguro de tu teléfono. Bloquear detiene un taf, o todos, con un toque. Sin anuncios, sin rastreadores. Elimina tu cuenta en la app, en Cuenta, o consulta kiff.dev/es/tafs/privacy#rights.

PLANES
Solo, con 500 créditos al mes, y paquetes de créditos que nunca vencen, ambos comprados en la app a través de Google Play. Cada ejecución de un taf usa créditos por la IA que utilizó. Una revisión tranquila, cuando no hay nada nuevo, no cuesta nada. Coach está incluido: lo suficiente para configurar unos 4 trabajos nuevos al mes. Después de eso, la ayuda de Coach usa tus créditos. No hay prueba gratuita. Planes y precios: kiff.dev/es/tafs

Condiciones de tafs: https://kiff.dev/es/tafs/terms
Privacidad: https://kiff.dev/es/tafs/privacy
```

## Portuguese, Brazil (pt-BR)

**Draft, Google Play only, not yet in Play Console.** Adapted from pt (pt-PT)'s Play fields, above, to Brazilian
Portuguese: the same claims and limits, with *você*, *celular*, *apps* and Brazil's everyday words (for example
*contato*, *controle*, *exclua*). Screen names stay the app's own, in its Brazilian Portuguese (*Precisa de você*, *Registro*, *Conta*, *Bloquear*, "Isto
aconteceu?"; tafs#1629).
Amounts stay in euros, written the Brazilian way. The Plans link opens kiff.dev's euro prices; in the app, Google Play
charges the local price. It goes into Play Console when this market opens. The App Store's pt-BR is drafted separately.

### Google Play

**Title** (30/30): tafs: agentes IA para empresas

**Short description** (73/80): Delegue reembolsos, disputas e faturas a agentes de IA. O controle é seu.

**Full description** (3349/4000):

```
They taf. You kiff.
Automatize seu negócio sem perder o controle.

Todo dia as mesmas tarefas se acumulam. Um reembolso que fica para o dia seguinte pode virar um chargeback, com uma taxa que você talvez pague mesmo ganhando. Uma disputa que ninguém responde a tempo está perdida. Quem paga atrasado continua pagando atrasado. Um contato que espera um dia compra em outro lugar.

Com o tafs, você delega essas tarefas. Conte ao Coach a tarefa com suas palavras ou escolha uma já pronta. O Coach, o assistente de IA do tafs, conecta seus apps e cria um taf: um agente de IA, ou colega de IA, que faz essa tarefa todos os dias. Assim você dedica seu tempo ao trabalho de que realmente gosta.

POR QUE VOCÊ PODE DEIXÁ-LO TRABALHAR
Cada taf trabalha com um Card que você assina: o que ele faz sozinho, o que pergunta antes e o que nunca faz. O KIFF verifica cada ação em relação ao Card, fora da IA, antes que ela chegue aos seus apps. Nenhum prompt muda um limite, nem mesmo um truque em um e-mail. Tudo o que é importante espera o seu sim no seu celular, com sua impressão digital ou seu rosto. Sem resposta, é não.

TAREFAS QUE VOCÊ PODE DELEGAR HOJE
• Nenhum reembolso fica para amanhã: responde aos pedidos de reembolso do atendimento ao cliente e reembolsa dentro do Card, por exemplo até 150 euros cada e 2.000 euros por dia.
• Pare de perder disputas que você poderia ganhar: responde às disputas de cartão dentro do prazo com o pedido, o comprovante de entrega e as mensagens do cliente.
• Receba sem correr atrás de ninguém: lembra os clientes das faturas em atraso seguindo um cronograma e não movimenta dinheiro.
• Nunca perca um contato por responder tarde: transforma cada novo envio do formulário em uma oportunidade e manda uma primeira resposta em menos de uma hora.
• Pare de responder “cadê meu pedido?”: responde às perguntas sobre pedidos consultando a loja e passa para você tudo o que envolve dinheiro.
Há mais prontas: agendamentos, recibos e reter clientes que cancelam. Você pode alterar cada limite.

O QUE VOCÊ VÊ
• Precisa de você: o que espera o seu sim.
• Registro: tudo o que seus tafs fizeram, do mais recente para o mais antigo, com um recibo para cada ação que foi adiante.
• Chat: pergunte a um taf o que ele fez, ou diga como fazer de outro jeito, e acompanhe o trabalho enquanto ele acontece.
• Você decide o que aconteceu de verdade: quando um app não consegue confirmar que um pagamento foi feito, o tafs pergunta “Isto aconteceu?”. Nada é dado como feito às cegas.

SEUS DADOS
O taf nunca vê suas senhas nem seus tokens. As aprovações são assinadas com uma chave guardada no hardware seguro do seu celular. Bloquear interrompe um taf, ou todos, com um toque. Sem anúncios, sem rastreadores. Exclua sua conta no app, em Conta, ou veja kiff.dev/pt/tafs/privacy#rights.

PLANOS
Solo, com 500 créditos por mês, e pacotes de créditos que nunca expiram, ambos comprados no app pelo Google Play. Cada execução de um taf usa créditos pela IA que usou. Uma verificação tranquila, quando não há nada de novo, não custa nada. O Coach está incluído: o suficiente para configurar cerca de 4 novas tarefas por mês. Depois disso, a ajuda do Coach usa seus créditos. Não há período de teste gratuito. Planos e preços: kiff.dev/pt/tafs

Termos do tafs: https://kiff.dev/pt/tafs/terms
Privacidade: https://kiff.dev/pt/tafs/privacy
```
