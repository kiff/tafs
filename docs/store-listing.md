# Store listing

The App Store and Play Store listings, in English (the source) and the seven other languages tafs
speaks ([languages.md](languages.md)). Each language has its App Store fields and its Play fields,
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
  credit pack, Approve once, Refuse, held and Settings use the glossary's term in each language.
- **Revoke is not Halt.** "Revoke the Card" ends the Card itself, so no new action goes through
  under it. It isn't the glossary's Halt, which blocks a taf in every system it works through, so
  each language uses its own word for revoke (widerrufen, révoquer, revocar, revocare, revogar,
  intrekken, odwołać) and never Halt's word.
- **Amounts in euros,** written the language's way.
- **Approving on the device, per store.** The App Store listing says Face ID or Touch ID; the Play
  listing says fingerprint or face.
- **The store's name, per store.** Where a sentence names the store (the Plans paragraph), the App
  Store listing says App Store and the Play listing says Google Play.
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
- **`{SOLO_MONTHLY_CREDITS}`** is Solo's monthly credits. The figure lives in KIFF's config, not in
  this repository; put it in when copying a listing into a store.

## English

### App Store

**Name** (28/30): tafs: AI Agents for Business

**Subtitle** (22/30): Get your mornings back

**Promotional text** (168/170):

```
Refunds, disputes, late invoices, leads waiting for a reply: hand them to a taf and get your mornings back. It works within limits you sign. Anything big waits for you.
```

**Keywords** (100/100):

```
automation,refunds,disputes,chargebacks,invoices,leads,assistant,teammate,approvals,workflow,routine
```

**Description** (2809/4000):

```
They taf. You kiff.
Automate your business without giving up control.

Every day the same jobs pile up. A refund left overnight can turn into a chargeback, with a fee you may pay even if you win. A dispute nobody answers in time is lost. Late payers stay late. A lead that waits a day buys elsewhere.

With tafs, you hand those jobs off. Tell Coach the job in your own words, or pick a ready-made one. Coach, the AI assistant in tafs, connects your apps and builds a taf: an AI agent, or AI teammate, that does that job every day, so you spend your time on the work you actually enjoy.

WHY YOU CAN LET IT
Every taf works within a Card you sign: what it does on its own, what it asks you first, and what it never does. Every action is checked against its Card by KIFF, outside the AI, before it reaches your apps. No email trick or clever prompt can push it past your limits. Anything big waits for your yes on your phone, with Face ID or Touch ID. No answer means no.

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
The taf never sees your passwords or tokens. Approvals are signed with a key kept in your phone's secure hardware. Halt stops one taf, or all of them, with one tap. No ads, no trackers. Delete your account in Settings, or see kiff.dev/privacy#delete-account.

PLANS
Solo, with {SOLO_MONTHLY_CREDITS} credits a month, and packs of credits that never expire, both bought in the app through the App Store. Each run of a taf uses credits for the AI it used. A quiet check, when nothing is new, costs nothing. Coach is included: enough to set up about 4 new jobs a month. Past that, Coach's help uses your credits. There's no free trial. Plans and prices: kiff.dev/tafs
```

### Google Play

**Title** (28/30): tafs: AI Agents for Business

**Short description** (71/80): Hand off refunds, disputes and invoices to AI agents. You keep control.

**Full description** (2812/4000):

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
The taf never sees your passwords or tokens. Approvals are signed with a key kept in your phone's secure hardware. Halt stops one taf, or all of them, with one tap. No ads, no trackers. Delete your account in Settings, or see kiff.dev/privacy#delete-account.

PLANS
Solo, with {SOLO_MONTHLY_CREDITS} credits a month, and packs of credits that never expire, both bought in the app through Google Play. Each run of a taf uses credits for the AI it used. A quiet check, when nothing is new, costs nothing. Coach is included: enough to set up about 4 new jobs a month. Past that, Coach's help uses your credits. There's no free trial. Plans and prices: kiff.dev/tafs
```

## German (de)

### App Store

**Name** (27/30): tafs: KI-Agenten für Firmen

**Subtitle** (23/30): Deine Vormittage zurück

**Promotional text** (161/170):

```
Erstattungen, Streitfälle, offene Rechnungen, wartende Leads: Gib sie an einen taf ab. Er arbeitet in Limits, die du unterschreibst. Alles Große wartet auf dich.
```

**Keywords** (85/100):

```
Automatisierung,Erstattungen,Streitfälle,Rechnungen,Leads,Assistent,Freigabe,Workflow
```

**Description** (3357/4000):

```
They taf. You kiff.
Automatisiere dein Unternehmen, ohne die Kontrolle abzugeben.

Jeden Tag stapeln sich dieselben Aufgaben. Eine Erstattung, die über Nacht liegen bleibt, kann zur Rückbuchung werden, mit einer Gebühr, die du womöglich sogar zahlst, wenn du gewinnst. Ein Streitfall, den niemand rechtzeitig beantwortet, ist verloren. Wer spät zahlt, zahlt weiter spät. Ein Lead, der einen Tag wartet, kauft woanders.

Mit tafs gibst du diese Aufgaben ab. Sag Coach die Aufgabe in deinen eigenen Worten oder wähle eine fertige. Coach, der KI-Assistent in tafs, verbindet deine Apps und baut einen taf: einen KI-Agenten oder KI-Kollegen, der diese Aufgabe jeden Tag erledigt. So bleibt dir Zeit für die Arbeit, die dir wirklich Freude macht.

WARUM DU IHN MACHEN LASSEN KANNST
Jeder taf arbeitet mit einer Card, die du unterschreibst: was er selbst macht, was er dich zuerst fragt und was er nie macht. KIFF prüft jede Aktion gegen ihre Card, außerhalb der KI, bevor sie deine App erreicht. Kein Prompt ändert ein Limit, auch kein Trick in einer E-Mail. Alles Große wartet auf deinem Handy auf dein Ja, mit Face ID oder Touch ID. Keine Antwort heißt Nein.

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
Der taf sieht nie deine Passwörter oder Tokens. Genehmigungen werden mit einem Schlüssel signiert, der in der Sicherheitshardware deines Handys liegt. Mit Anhalten stoppst du einen taf oder alle mit einem Tippen. Keine Werbung, keine Tracker. Lösch dein Konto in den Einstellungen, oder siehe kiff.dev/privacy#delete-account.

TARIFE
Solo mit {SOLO_MONTHLY_CREDITS} Credits im Monat und Credit-Pakete, die nie verfallen, beides in der App über den App Store gekauft. Jeder Lauf eines taf verbraucht Credits für die KI, die er genutzt hat. Eine ruhige Prüfung, wenn nichts neu ist, kostet nichts. Coach ist inklusive: genug, um etwa 4 neue Aufgaben im Monat einzurichten. Darüber hinaus nutzt die Hilfe von Coach deine Credits. Es gibt keine kostenlose Testphase. Tarife und Preise: kiff.dev/de/tafs
```

### Google Play

**Title** (27/30): tafs: KI-Agenten für Firmen

**Short description** (79/80): KI-Agenten für Erstattungen, Streitfälle und Rechnungen. Du hast die Kontrolle.

**Full description** (3360/4000):

```
They taf. You kiff.
Automatisiere dein Unternehmen, ohne die Kontrolle abzugeben.

Jeden Tag stapeln sich dieselben Aufgaben. Eine Erstattung, die über Nacht liegen bleibt, kann zur Rückbuchung werden, mit einer Gebühr, die du womöglich sogar zahlst, wenn du gewinnst. Ein Streitfall, den niemand rechtzeitig beantwortet, ist verloren. Wer spät zahlt, zahlt weiter spät. Ein Lead, der einen Tag wartet, kauft woanders.

Mit tafs gibst du diese Aufgaben ab. Sag Coach die Aufgabe in deinen eigenen Worten oder wähle eine fertige. Coach, der KI-Assistent in tafs, verbindet deine Apps und baut einen taf: einen KI-Agenten oder KI-Kollegen, der diese Aufgabe jeden Tag erledigt. So bleibt dir Zeit für die Arbeit, die dir wirklich Freude macht.

WARUM DU IHN MACHEN LASSEN KANNST
Jeder taf arbeitet mit einer Card, die du unterschreibst: was er selbst macht, was er dich zuerst fragt und was er nie macht. KIFF prüft jede Aktion gegen ihre Card, außerhalb der KI, bevor sie deine App erreicht. Kein Prompt ändert ein Limit, auch kein Trick in einer E-Mail. Alles Große wartet auf deinem Handy auf dein Ja, mit Fingerabdruck oder Gesicht. Keine Antwort heißt Nein.

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
Der taf sieht nie deine Passwörter oder Tokens. Genehmigungen werden mit einem Schlüssel signiert, der in der Sicherheitshardware deines Handys liegt. Mit Anhalten stoppst du einen taf oder alle mit einem Tippen. Keine Werbung, keine Tracker. Lösch dein Konto in den Einstellungen, oder siehe kiff.dev/privacy#delete-account.

TARIFE
Solo mit {SOLO_MONTHLY_CREDITS} Credits im Monat und Credit-Pakete, die nie verfallen, beides in der App über Google Play gekauft. Jeder Lauf eines taf verbraucht Credits für die KI, die er genutzt hat. Eine ruhige Prüfung, wenn nichts neu ist, kostet nichts. Coach ist inklusive: genug, um etwa 4 neue Aufgaben im Monat einzurichten. Darüber hinaus nutzt die Hilfe von Coach deine Credits. Es gibt keine kostenlose Testphase. Tarife und Preise: kiff.dev/de/tafs
```

## French (fr)

### App Store

**Name** (28/30): tafs : agents IA entreprises

**Subtitle** (22/30): Retrouvez vos matinées

**Promotional text** (167/170):

```
Remboursements, litiges, factures impayées, prospects en attente : confiez-les à un taf. Il agit dans les limites que vous signez. Tout ce qui compte attend votre oui.
```

**Keywords** (86/100):

```
automatisation,remboursements,litiges,factures,prospects,assistant,validation,workflow
```

**Description** (3463/4000):

```
They taf. You kiff.
Automatisez votre entreprise sans perdre le contrôle.

Chaque jour, les mêmes tâches s'accumulent. Un remboursement laissé jusqu'au lendemain peut devenir une rétrofacturation, avec des frais que vous payez parfois même si vous gagnez. Un litige auquel personne ne répond à temps est perdu. Les mauvais payeurs restent en retard. Un prospect qui attend un jour achète ailleurs.

Avec tafs, vous confiez ces tâches. Décrivez la tâche à Coach avec vos mots, ou choisissez-en une toute prête. Coach, l'assistant IA de tafs, connecte vos apps et construit un taf : un agent IA, ou coéquipier IA, qui fait cette tâche chaque jour. Vous gardez votre temps pour le travail qui vous plaît vraiment.

POURQUOI VOUS POUVEZ LUI CONFIER
Chaque taf travaille avec une Card que vous signez : ce qu'il fait seul, ce qu'il vous demande d'abord, et ce qu'il ne fait jamais. Chaque action est vérifiée par KIFF au regard de sa Card, en dehors de l'IA, avant d'atteindre votre app. Aucun prompt ne change une limite, pas même une ruse dans un e-mail. Tout ce qui est important attend votre oui sur votre téléphone, avec Face ID ou Touch ID. Pas de réponse veut dire non.

DES TÂCHES À CONFIER DÈS AUJOURD'HUI
• Aucun remboursement n'attend jusqu'au lendemain : répond aux demandes de remboursement du support et rembourse dans les limites de la Card, par exemple jusqu'à 150 € chacun et 2 000 € par jour.
• Arrêtez de perdre des litiges que vous pourriez gagner : répond aux litiges sur carte avant l'échéance avec la commande, la preuve de livraison et les messages du client.
• Soyez payé sans relancer : relance les clients pour les factures en retard selon un calendrier et ne déplace aucun argent.
• Ne perdez plus un prospect à cause d'une réponse lente : transforme chaque nouvelle entrée de formulaire en affaire et envoie une première réponse dans l'heure.
• Fini le « où est ma commande ? » : répond aux questions sur les commandes en consultant la boutique, et vous transmet tout ce qui touche à l'argent.
D'autres sont prêtes aussi : rendez-vous, justificatifs et clients à retenir quand ils résilient. Chaque limite est à vous de la changer.

CE QUE VOUS VOYEZ
• Vous attend : ce qui attend votre oui.
• Registre : tout ce que vos tafs ont fait, du plus récent au plus ancien, avec un reçu pour chaque action passée.
• Chat : demandez à un taf ce qu'il a fait, ou dites-lui quoi faire autrement, et suivez son travail en direct.
• Vous décidez de ce qui s'est vraiment passé : quand une app ne peut pas confirmer qu'un paiement est passé, tafs vous demande « Est-ce que c'est arrivé ? » Rien n'est compté comme fait sur une supposition.

VOS DONNÉES
Le taf ne voit jamais vos mots de passe ni vos jetons. Les approbations sont signées avec une clé conservée dans le matériel sécurisé de votre téléphone. Bloquer arrête un taf, ou tous, d'un geste. Pas de publicité, pas de traceurs. Supprimez votre compte dans Réglages, ou voir kiff.dev/privacy#delete-account.

FORFAITS
Solo, avec {SOLO_MONTHLY_CREDITS} crédits par mois, et des packs de crédits qui n'expirent jamais, tous deux achetés dans l'app via l'App Store. Chaque exécution d'un taf utilise des crédits pour l'IA qu'elle a utilisée. Une vérification calme, quand rien n'est nouveau, ne coûte rien. Coach est inclus : de quoi mettre en place environ 4 nouvelles tâches par mois. Au-delà, l'aide de Coach utilise vos crédits. Il n'y a pas d'essai gratuit. Forfaits et prix : kiff.dev/fr/tafs
```

### Google Play

**Title** (28/30): tafs : agents IA entreprises

**Short description** (76/80): Confiez remboursements, litiges et factures à des agents IA. Gardez la main.

**Full description** (3475/4000):

```
They taf. You kiff.
Automatisez votre entreprise sans perdre le contrôle.

Chaque jour, les mêmes tâches s'accumulent. Un remboursement laissé jusqu'au lendemain peut devenir une rétrofacturation, avec des frais que vous payez parfois même si vous gagnez. Un litige auquel personne ne répond à temps est perdu. Les mauvais payeurs restent en retard. Un prospect qui attend un jour achète ailleurs.

Avec tafs, vous confiez ces tâches. Décrivez la tâche à Coach avec vos mots, ou choisissez-en une toute prête. Coach, l'assistant IA de tafs, connecte vos apps et construit un taf : un agent IA, ou coéquipier IA, qui fait cette tâche chaque jour. Vous gardez votre temps pour le travail qui vous plaît vraiment.

POURQUOI VOUS POUVEZ LUI CONFIER
Chaque taf travaille avec une Card que vous signez : ce qu'il fait seul, ce qu'il vous demande d'abord, et ce qu'il ne fait jamais. Chaque action est vérifiée par KIFF au regard de sa Card, en dehors de l'IA, avant d'atteindre votre app. Aucun prompt ne change une limite, pas même une ruse dans un e-mail. Tout ce qui est important attend votre oui sur votre téléphone, avec votre empreinte ou votre visage. Pas de réponse veut dire non.

DES TÂCHES À CONFIER DÈS AUJOURD'HUI
• Aucun remboursement n'attend jusqu'au lendemain : répond aux demandes de remboursement du support et rembourse dans les limites de la Card, par exemple jusqu'à 150 € chacun et 2 000 € par jour.
• Arrêtez de perdre des litiges que vous pourriez gagner : répond aux litiges sur carte avant l'échéance avec la commande, la preuve de livraison et les messages du client.
• Soyez payé sans relancer : relance les clients pour les factures en retard selon un calendrier et ne déplace aucun argent.
• Ne perdez plus un prospect à cause d'une réponse lente : transforme chaque nouvelle entrée de formulaire en affaire et envoie une première réponse dans l'heure.
• Fini le « où est ma commande ? » : répond aux questions sur les commandes en consultant la boutique, et vous transmet tout ce qui touche à l'argent.
D'autres sont prêtes aussi : rendez-vous, justificatifs et clients à retenir quand ils résilient. Chaque limite est à vous de la changer.

CE QUE VOUS VOYEZ
• Vous attend : ce qui attend votre oui.
• Registre : tout ce que vos tafs ont fait, du plus récent au plus ancien, avec un reçu pour chaque action passée.
• Chat : demandez à un taf ce qu'il a fait, ou dites-lui quoi faire autrement, et suivez son travail en direct.
• Vous décidez de ce qui s'est vraiment passé : quand une app ne peut pas confirmer qu'un paiement est passé, tafs vous demande « Est-ce que c'est arrivé ? » Rien n'est compté comme fait sur une supposition.

VOS DONNÉES
Le taf ne voit jamais vos mots de passe ni vos jetons. Les approbations sont signées avec une clé conservée dans le matériel sécurisé de votre téléphone. Bloquer arrête un taf, ou tous, d'un geste. Pas de publicité, pas de traceurs. Supprimez votre compte dans Réglages, ou voir kiff.dev/privacy#delete-account.

FORFAITS
Solo, avec {SOLO_MONTHLY_CREDITS} crédits par mois, et des packs de crédits qui n'expirent jamais, tous deux achetés dans l'app via Google Play. Chaque exécution d'un taf utilise des crédits pour l'IA qu'elle a utilisée. Une vérification calme, quand rien n'est nouveau, ne coûte rien. Coach est inclus : de quoi mettre en place environ 4 nouvelles tâches par mois. Au-delà, l'aide de Coach utilise vos crédits. Il n'y a pas d'essai gratuit. Forfaits et prix : kiff.dev/fr/tafs
```

## Spanish (es)

### App Store

**Name** (30/30): tafs: agentes IA para empresas

**Subtitle** (20/30): Recupera tus mañanas

**Promotional text** (158/170):

```
Reembolsos, disputas, facturas impagadas, leads esperando respuesta: pásaselos a un taf. Trabaja dentro de los límites que firmas. Lo importante espera tu sí.
```

**Keywords** (76/100):

```
automatización,reembolsos,disputas,facturas,leads,asistente,aprobación,flujo
```

**Description** (3159/4000):

```
They taf. You kiff.
Automatiza tu negocio sin perder el control.

Cada día se acumulan las mismas tareas. Un reembolso que espera hasta mañana puede acabar en un contracargo, con una comisión que quizá pagues aunque ganes. Una disputa que nadie responde a tiempo se pierde. Quien paga tarde sigue pagando tarde. Un lead que espera un día compra en otra parte.

Con tafs, delegas esas tareas. Cuéntale a Coach la tarea con tus palabras o elige una ya hecha. Coach, el asistente de IA de tafs, conecta tus apps y crea un taf: un agente de IA, o compañero de IA, que hace esa tarea cada día. Así dedicas tu tiempo al trabajo que de verdad te gusta.

POR QUÉ PUEDES DEJARLO HACER
Cada taf trabaja con una Card que tú firmas: lo que hace por su cuenta, lo que te pregunta primero y lo que nunca hace. KIFF comprueba cada acción contra su Card, fuera de la IA, antes de que llegue a tu app. Ningún prompt cambia un límite, ni siquiera un truco en un correo. Todo lo importante espera tu sí en tu teléfono, con Face ID o Touch ID. Sin respuesta, es un no.

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
El taf nunca ve tus contraseñas ni tus tokens. Las aprobaciones se firman con una clave guardada en el hardware seguro de tu teléfono. Bloquear detiene un taf, o todos, con un toque. Sin anuncios, sin rastreadores. Borra tu cuenta en Ajustes, o consulta kiff.dev/privacy#delete-account.

PLANES
Solo, con {SOLO_MONTHLY_CREDITS} créditos al mes, y paquetes de créditos que no caducan nunca, ambos comprados en la app a través del App Store. Cada ejecución de un taf usa créditos por la IA que utilizó. Una comprobación tranquila, cuando no hay nada nuevo, no cuesta nada. Coach está incluido: lo suficiente para configurar unos 4 trabajos nuevos al mes. Pasado eso, la ayuda de Coach usa tus créditos. No hay prueba gratuita. Planes y precios: kiff.dev/es/tafs
```

### Google Play

**Title** (30/30): tafs: agentes IA para empresas

**Short description** (78/80): Delega reembolsos, disputas y facturas en agentes de IA. Tú tienes el control.

**Full description** (3161/4000):

```
They taf. You kiff.
Automatiza tu negocio sin perder el control.

Cada día se acumulan las mismas tareas. Un reembolso que espera hasta mañana puede acabar en un contracargo, con una comisión que quizá pagues aunque ganes. Una disputa que nadie responde a tiempo se pierde. Quien paga tarde sigue pagando tarde. Un lead que espera un día compra en otra parte.

Con tafs, delegas esas tareas. Cuéntale a Coach la tarea con tus palabras o elige una ya hecha. Coach, el asistente de IA de tafs, conecta tus apps y crea un taf: un agente de IA, o compañero de IA, que hace esa tarea cada día. Así dedicas tu tiempo al trabajo que de verdad te gusta.

POR QUÉ PUEDES DEJARLO HACER
Cada taf trabaja con una Card que tú firmas: lo que hace por su cuenta, lo que te pregunta primero y lo que nunca hace. KIFF comprueba cada acción contra su Card, fuera de la IA, antes de que llegue a tu app. Ningún prompt cambia un límite, ni siquiera un truco en un correo. Todo lo importante espera tu sí en tu teléfono, con tu huella o tu cara. Sin respuesta, es un no.

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
El taf nunca ve tus contraseñas ni tus tokens. Las aprobaciones se firman con una clave guardada en el hardware seguro de tu teléfono. Bloquear detiene un taf, o todos, con un toque. Sin anuncios, sin rastreadores. Borra tu cuenta en Ajustes, o consulta kiff.dev/privacy#delete-account.

PLANES
Solo, con {SOLO_MONTHLY_CREDITS} créditos al mes, y paquetes de créditos que no caducan nunca, ambos comprados en la app a través de Google Play. Cada ejecución de un taf usa créditos por la IA que utilizó. Una comprobación tranquila, cuando no hay nada nuevo, no cuesta nada. Coach está incluido: lo suficiente para configurar unos 4 trabajos nuevos al mes. Pasado eso, la ayuda de Coach usa tus créditos. No hay prueba gratuita. Planes y precios: kiff.dev/es/tafs
```

## Italian (it)

### App Store

**Name** (27/30): tafs: agenti IA per aziende

**Subtitle** (25/30): Riprenditi le tue mattine

**Promotional text** (162/170):

```
Rimborsi, contestazioni, fatture non pagate, lead in attesa di risposta: affidali a un taf. Lavora nei limiti che firmi tu. Tutto ciò che conta aspetta il tuo sì.
```

**Keywords** (80/100):

```
automazione,rimborsi,contestazioni,fatture,lead,assistente,approvazione,workflow
```

**Description** (3294/4000):

```
They taf. You kiff.
Automatizza la tua attività senza rinunciare al controllo.

Ogni giorno si accumulano le stesse attività. Un rimborso lasciato fino a domani può diventare un chargeback, con una commissione che potresti pagare anche se vinci. Una contestazione a cui nessuno risponde in tempo è persa. Chi paga in ritardo continua a farlo. Un lead che aspetta un giorno compra altrove.

Con tafs, affidi queste attività. Descrivi l'attività a Coach con parole tue, o scegline una pronta. Coach, l'assistente IA di tafs, collega le tue app e crea un taf: un agente IA, o collega IA, che fa quell'attività ogni giorno. Così dedichi il tuo tempo al lavoro che ti piace davvero.

PERCHÉ PUOI LASCIARLO FARE
Ogni taf lavora con una Card che firmi tu: cosa fa da solo, cosa ti chiede prima e cosa non fa mai. Ogni azione viene verificata da KIFF rispetto alla sua Card, fuori dall'IA, prima che raggiunga la tua app. Nessun prompt cambia un limite, nemmeno un trucco in un'email. Tutto ciò che conta aspetta il tuo sì sul telefono, con Face ID o Touch ID. Nessuna risposta vuol dire no.

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
Il taf non vede mai le tue password né i tuoi token. Le approvazioni sono firmate con una chiave custodita nell'hardware sicuro del tuo telefono. Blocca ferma un taf, o tutti, con un tocco. Niente pubblicità, niente tracker. Elimina il tuo account in Impostazioni, oppure vedi kiff.dev/privacy#delete-account.

PIANI
Solo, con {SOLO_MONTHLY_CREDITS} crediti al mese, e pacchetti di crediti che non scadono mai, entrambi acquistati nell'app tramite l'App Store. Ogni esecuzione di un taf usa crediti per l'IA che ha usato. Un controllo tranquillo, quando non c'è niente di nuovo, non costa nulla. Coach è incluso: abbastanza per configurare circa 4 nuovi lavori al mese. Oltre questa soglia, l'aiuto di Coach usa i tuoi crediti. Non c'è una prova gratuita. Piani e prezzi: kiff.dev/it/tafs
```

### Google Play

**Title** (27/30): tafs: agenti IA per aziende

**Short description** (79/80): Affida rimborsi, contestazioni e fatture ad agenti IA. Il controllo resta a te.

**Full description** (3297/4000):

```
They taf. You kiff.
Automatizza la tua attività senza rinunciare al controllo.

Ogni giorno si accumulano le stesse attività. Un rimborso lasciato fino a domani può diventare un chargeback, con una commissione che potresti pagare anche se vinci. Una contestazione a cui nessuno risponde in tempo è persa. Chi paga in ritardo continua a farlo. Un lead che aspetta un giorno compra altrove.

Con tafs, affidi queste attività. Descrivi l'attività a Coach con parole tue, o scegline una pronta. Coach, l'assistente IA di tafs, collega le tue app e crea un taf: un agente IA, o collega IA, che fa quell'attività ogni giorno. Così dedichi il tuo tempo al lavoro che ti piace davvero.

PERCHÉ PUOI LASCIARLO FARE
Ogni taf lavora con una Card che firmi tu: cosa fa da solo, cosa ti chiede prima e cosa non fa mai. Ogni azione viene verificata da KIFF rispetto alla sua Card, fuori dall'IA, prima che raggiunga la tua app. Nessun prompt cambia un limite, nemmeno un trucco in un'email. Tutto ciò che conta aspetta il tuo sì sul telefono, con l'impronta o il volto. Nessuna risposta vuol dire no.

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
Il taf non vede mai le tue password né i tuoi token. Le approvazioni sono firmate con una chiave custodita nell'hardware sicuro del tuo telefono. Blocca ferma un taf, o tutti, con un tocco. Niente pubblicità, niente tracker. Elimina il tuo account in Impostazioni, oppure vedi kiff.dev/privacy#delete-account.

PIANI
Solo, con {SOLO_MONTHLY_CREDITS} crediti al mese, e pacchetti di crediti che non scadono mai, entrambi acquistati nell'app tramite Google Play. Ogni esecuzione di un taf usa crediti per l'IA che ha usato. Un controllo tranquillo, quando non c'è niente di nuovo, non costa nulla. Coach è incluso: abbastanza per configurare circa 4 nuovi lavori al mese. Oltre questa soglia, l'aiuto di Coach usa i tuoi crediti. Non c'è una prova gratuita. Piani e prezzi: kiff.dev/it/tafs
```

## Portuguese (pt)

### App Store

**Name** (30/30): tafs: agentes IA para empresas

**Subtitle** (23/30): Recupere as suas manhãs

**Promotional text** (168/170):

```
Reembolsos, disputas, faturas por pagar, contactos à espera de resposta: entregue-os a um taf. Trabalha nos limites que assina. Tudo o que é grande espera pelo seu sim.
```

**Keywords** (74/100):

```
automatização,reembolsos,disputas,faturas,leads,assistente,aprovação,fluxo
```

**Description** (3233/4000):

```
They taf. You kiff.
Automatize o seu negócio sem perder o controlo.

Todos os dias acumulam-se as mesmas tarefas. Um reembolso que fica para o dia seguinte pode acabar num estorno, com uma taxa que pode pagar mesmo que ganhe. Uma disputa a que ninguém responde a tempo está perdida. Quem paga tarde continua a pagar tarde. Um contacto que espera um dia compra noutro lado.

Com tafs, entrega essas tarefas. Diga ao Coach a tarefa por palavras suas, ou escolha uma já pronta. O Coach, o assistente de IA do tafs, liga as suas apps e cria um taf: um agente de IA, ou colega de IA, que faz essa tarefa todos os dias. Assim dedica o seu tempo ao trabalho de que realmente gosta.

PORQUE PODE DEIXÁ-LO TRABALHAR
Cada taf trabalha com um Card que assina: o que faz sozinho, o que lhe pergunta primeiro e o que nunca faz. O KIFF verifica cada ação face ao seu Card, fora da IA, antes de chegar à sua app. Nenhum prompt muda um limite, nem sequer um truque num email. Tudo o que é importante aguarda o seu sim no seu telemóvel, com Face ID ou Touch ID. Sem resposta, é não.

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
O taf nunca vê as suas palavras-passe nem os seus tokens. As aprovações são assinadas com uma chave guardada no hardware seguro do seu telemóvel. Bloquear para um taf, ou todos, com um toque. Sem anúncios, sem rastreadores. Elimine a sua conta em Definições, ou consulte kiff.dev/privacy#delete-account.

PLANOS
Solo, com {SOLO_MONTHLY_CREDITS} créditos por mês, e pacotes de créditos que nunca expiram, ambos comprados na app através da App Store. Cada execução de um taf usa créditos pela IA que utilizou. Uma verificação calma, quando não há nada de novo, não custa nada. O Coach está incluído: o suficiente para configurar cerca de 4 novos trabalhos por mês. Depois disso, a ajuda do Coach usa os seus créditos. Não há período de teste gratuito. Planos e preços: kiff.dev/pt/tafs
```

### Google Play

**Title** (30/30): tafs: agentes IA para empresas

**Short description** (74/80): Entregue reembolsos, disputas e faturas a agentes de IA. O controlo é seu.

**Full description** (3254/4000):

```
They taf. You kiff.
Automatize o seu negócio sem perder o controlo.

Todos os dias acumulam-se as mesmas tarefas. Um reembolso que fica para o dia seguinte pode acabar num estorno, com uma taxa que pode pagar mesmo que ganhe. Uma disputa a que ninguém responde a tempo está perdida. Quem paga tarde continua a pagar tarde. Um contacto que espera um dia compra noutro lado.

Com tafs, entrega essas tarefas. Diga ao Coach a tarefa por palavras suas, ou escolha uma já pronta. O Coach, o assistente de IA do tafs, liga as suas apps e cria um taf: um agente de IA, ou colega de IA, que faz essa tarefa todos os dias. Assim dedica o seu tempo ao trabalho de que realmente gosta.

PORQUE PODE DEIXÁ-LO TRABALHAR
Cada taf trabalha com um Card que assina: o que faz sozinho, o que lhe pergunta primeiro e o que nunca faz. O KIFF verifica cada ação face ao seu Card, fora da IA, antes de chegar à sua app. Nenhum prompt muda um limite, nem sequer um truque num email. Tudo o que é importante aguarda o seu sim no seu telemóvel, com a sua impressão digital ou o seu rosto. Sem resposta, é não.

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
O taf nunca vê as suas palavras-passe nem os seus tokens. As aprovações são assinadas com uma chave guardada no hardware seguro do seu telemóvel. Bloquear para um taf, ou todos, com um toque. Sem anúncios, sem rastreadores. Elimine a sua conta em Definições, ou consulte kiff.dev/privacy#delete-account.

PLANOS
Solo, com {SOLO_MONTHLY_CREDITS} créditos por mês, e pacotes de créditos que nunca expiram, ambos comprados na app através do Google Play. Cada execução de um taf usa créditos pela IA que utilizou. Uma verificação calma, quando não há nada de novo, não custa nada. O Coach está incluído: o suficiente para configurar cerca de 4 novos trabalhos por mês. Depois disso, a ajuda do Coach usa os seus créditos. Não há período de teste gratuito. Planos e preços: kiff.dev/pt/tafs
```

## Dutch (nl)

### App Store

**Name** (30/30): tafs: AI-agents voor bedrijven

**Subtitle** (24/30): Krijg je ochtenden terug

**Promotional text** (169/170):

```
Terugbetalingen, geschillen, onbetaalde facturen, leads die wachten: geef ze aan een taf. Het werkt binnen limieten die jij ondertekent. Alles wat groot is wacht op jou.
```

**Keywords** (87/100):

```
automatisering,terugbetalingen,geschillen,facturen,leads,assistent,goedkeuring,workflow
```

**Description** (3286/4000):

```
They taf. You kiff.
Automatiseer je bedrijf zonder de controle uit handen te geven.

Elke dag stapelen dezelfde taken zich op. Een terugbetaling die een nacht blijft liggen, kan een terugboeking worden, met kosten die je soms ook betaalt als je wint. Een geschil dat niemand op tijd beantwoordt, is verloren. Wie laat betaalt, blijft laat betalen. Een lead die een dag wacht, koopt ergens anders.

Met tafs geef je die taken uit handen. Vertel Coach de taak in je eigen woorden, of kies een kant-en-klare. Coach, de AI-assistent in tafs, koppelt je apps en bouwt een taf: een AI-agent, of AI-collega, die die taak elke dag doet. Zo houd je tijd over voor het werk dat je echt leuk vindt.

WAAROM JE HET KUNT LATEN DOEN
Elk taf werkt met een Card die jij ondertekent: wat het zelf doet, wat het je eerst vraagt en wat het nooit doet. KIFF toetst elke actie aan zijn Card, buiten de AI, voordat die je app bereikt. Geen prompt verandert een limiet, ook geen truc in een e-mail. Alles wat groot is, wacht op je telefoon op jouw ja, met Face ID of Touch ID. Geen antwoord betekent nee.

TAKEN DIE JE VANDAAG UIT HANDEN GEEFT
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
• Jij bepaalt wat er echt gebeurd is: als een app niet kan bevestigen dat een betaling is gelukt, vraagt tafs je "Is dit gebeurd?" Niets telt op goed geluk als gedaan.

JE GEGEVENS
Het taf ziet nooit je wachtwoorden of tokens. Goedkeuringen worden ondertekend met een sleutel in de beveiligde hardware van je telefoon. Stilzetten zet één taf, of allemaal, stil met één tik. Geen advertenties, geen trackers. Verwijder je account in Instellingen, of zie kiff.dev/privacy#delete-account.

ABONNEMENTEN
Solo, met {SOLO_MONTHLY_CREDITS} credits per maand, en creditpakketten die nooit verlopen, allebei in de app gekocht via de App Store. Elke run van een taf gebruikt credits voor de AI die het gebruikte. Een rustige controle, als er niets nieuws is, kost niets. Coach is inbegrepen: genoeg om zo'n 4 nieuwe taken per maand in te stellen. Daarboven gebruikt de hulp van Coach je credits. Er is geen gratis proefperiode. Abonnementen en prijzen: kiff.dev/nl/tafs
```

### Google Play

**Title** (30/30): tafs: AI-agents voor bedrijven

**Short description** (79/80): Geef terugbetalingen, geschillen en facturen aan AI-agents. Jij houdt controle.

**Full description** (3292/4000):

```
They taf. You kiff.
Automatiseer je bedrijf zonder de controle uit handen te geven.

Elke dag stapelen dezelfde taken zich op. Een terugbetaling die een nacht blijft liggen, kan een terugboeking worden, met kosten die je soms ook betaalt als je wint. Een geschil dat niemand op tijd beantwoordt, is verloren. Wie laat betaalt, blijft laat betalen. Een lead die een dag wacht, koopt ergens anders.

Met tafs geef je die taken uit handen. Vertel Coach de taak in je eigen woorden, of kies een kant-en-klare. Coach, de AI-assistent in tafs, koppelt je apps en bouwt een taf: een AI-agent, of AI-collega, die die taak elke dag doet. Zo houd je tijd over voor het werk dat je echt leuk vindt.

WAAROM JE HET KUNT LATEN DOEN
Elk taf werkt met een Card die jij ondertekent: wat het zelf doet, wat het je eerst vraagt en wat het nooit doet. KIFF toetst elke actie aan zijn Card, buiten de AI, voordat die je app bereikt. Geen prompt verandert een limiet, ook geen truc in een e-mail. Alles wat groot is, wacht op je telefoon op jouw ja, met je vingerafdruk of gezicht. Geen antwoord betekent nee.

TAKEN DIE JE VANDAAG UIT HANDEN GEEFT
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
• Jij bepaalt wat er echt gebeurd is: als een app niet kan bevestigen dat een betaling is gelukt, vraagt tafs je "Is dit gebeurd?" Niets telt op goed geluk als gedaan.

JE GEGEVENS
Het taf ziet nooit je wachtwoorden of tokens. Goedkeuringen worden ondertekend met een sleutel in de beveiligde hardware van je telefoon. Stilzetten zet één taf, of allemaal, stil met één tik. Geen advertenties, geen trackers. Verwijder je account in Instellingen, of zie kiff.dev/privacy#delete-account.

ABONNEMENTEN
Solo, met {SOLO_MONTHLY_CREDITS} credits per maand, en creditpakketten die nooit verlopen, allebei in de app gekocht via Google Play. Elke run van een taf gebruikt credits voor de AI die het gebruikte. Een rustige controle, als er niets nieuws is, kost niets. Coach is inbegrepen: genoeg om zo'n 4 nieuwe taken per maand in te stellen. Daarboven gebruikt de hulp van Coach je credits. Er is geen gratis proefperiode. Abonnementen en prijzen: kiff.dev/nl/tafs
```

## Polish (pl)

### App Store

**Name** (24/30): tafs: agenci AI dla firm

**Subtitle** (22/30): Odzyskaj swoje poranki

**Promotional text** (156/170):

```
Zwroty, spory, zaległe faktury, leady czekające na odpowiedź: oddaj je tafowi. Działa w limitach, które podpisujesz. Wszystko, co ważne, czeka na Twoje tak.
```

**Keywords** (72/100):

```
automatyzacja,zwroty,spory,faktury,leady,asystent,zatwierdzanie,workflow
```

**Description** (3203/4000):

```
They taf. You kiff.
Automatyzuj firmę, nie oddając kontroli.

Codziennie piętrzą się te same zadania. Zwrot, który czeka do jutra, może skończyć się obciążeniem zwrotnym, z opłatą, którą możesz zapłacić nawet po wygranej. Spór, na który nikt nie odpowie na czas, przepada. Kto płaci z opóźnieniem, dalej płaci z opóźnieniem. Lead, który czeka dzień, kupuje gdzie indziej.

Z tafs oddajesz te zadania. Opisz Coachowi zadanie własnymi słowami albo wybierz gotowe. Coach, asystent AI w tafs, łączy Twoje aplikacje i tworzy tafa: agenta AI, czyli współpracownika AI, który codziennie wykonuje to zadanie. Ty poświęcasz czas pracy, którą naprawdę lubisz.

DLACZEGO MOŻESZ GO ZOSTAWIĆ
Każdy taf działa według Card, którą podpisujesz: co robi sam, o co najpierw pyta Ciebie i czego nigdy nie robi. KIFF sprawdza każdą akcję względem jej Card, poza AI, zanim dotrze ona do Twojej aplikacji. Żaden prompt nie zmienia limitu, nawet podstęp w e-mailu. Wszystko, co ważne, czeka na Twoje tak na telefonie, za pomocą Face ID lub Touch ID. Brak odpowiedzi oznacza nie.

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
• To Ty decydujesz, co naprawdę się stało: gdy aplikacja nie może potwierdzić, że płatność przeszła, tafs pyta Cię „Czy to się stało?”. Nic nie jest uznawane za wykonane na podstawie domysłów.

TWOJE DANE
Taf nigdy nie widzi Twoich haseł ani tokenów. Zatwierdzenia są podpisywane kluczem przechowywanym w bezpiecznym sprzęcie Twojego telefonu. Zablokuj zatrzymuje jednego tafa albo wszystkie jednym dotknięciem. Bez reklam, bez trackerów. Usuń konto w Ustawieniach albo zobacz kiff.dev/privacy#delete-account.

PLANY
Solo z {SOLO_MONTHLY_CREDITS} kredytami miesięcznie oraz pakiety kredytów, które nigdy nie wygasają, oba kupowane w aplikacji przez App Store. Każde uruchomienie tafa zużywa kredyty za AI, której użył. Spokojne sprawdzenie, gdy nie ma nic nowego, nic nie kosztuje. Coach jest w cenie: wystarczy, by skonfigurować około 4 nowych zadań miesięcznie. Powyżej tego pomoc Coacha używa Twoich kredytów. Nie ma bezpłatnego okresu próbnego. Plany i ceny: kiff.dev/pl/tafs
```

### Google Play

**Title** (24/30): tafs: agenci AI dla firm

**Short description** (70/80): Oddaj zwroty, spory i faktury agentom AI. Kontrola zostaje przy Tobie.

**Full description** (3201/4000):

```
They taf. You kiff.
Automatyzuj firmę, nie oddając kontroli.

Codziennie piętrzą się te same zadania. Zwrot, który czeka do jutra, może skończyć się obciążeniem zwrotnym, z opłatą, którą możesz zapłacić nawet po wygranej. Spór, na który nikt nie odpowie na czas, przepada. Kto płaci z opóźnieniem, dalej płaci z opóźnieniem. Lead, który czeka dzień, kupuje gdzie indziej.

Z tafs oddajesz te zadania. Opisz Coachowi zadanie własnymi słowami albo wybierz gotowe. Coach, asystent AI w tafs, łączy Twoje aplikacje i tworzy tafa: agenta AI, czyli współpracownika AI, który codziennie wykonuje to zadanie. Ty poświęcasz czas pracy, którą naprawdę lubisz.

DLACZEGO MOŻESZ GO ZOSTAWIĆ
Każdy taf działa według Card, którą podpisujesz: co robi sam, o co najpierw pyta Ciebie i czego nigdy nie robi. KIFF sprawdza każdą akcję względem jej Card, poza AI, zanim dotrze ona do Twojej aplikacji. Żaden prompt nie zmienia limitu, nawet podstęp w e-mailu. Wszystko, co ważne, czeka na Twoje tak na telefonie, odciskiem palca lub twarzą. Brak odpowiedzi oznacza nie.

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
• To Ty decydujesz, co naprawdę się stało: gdy aplikacja nie może potwierdzić, że płatność przeszła, tafs pyta Cię „Czy to się stało?”. Nic nie jest uznawane za wykonane na podstawie domysłów.

TWOJE DANE
Taf nigdy nie widzi Twoich haseł ani tokenów. Zatwierdzenia są podpisywane kluczem przechowywanym w bezpiecznym sprzęcie Twojego telefonu. Zablokuj zatrzymuje jednego tafa albo wszystkie jednym dotknięciem. Bez reklam, bez trackerów. Usuń konto w Ustawieniach albo zobacz kiff.dev/privacy#delete-account.

PLANY
Solo z {SOLO_MONTHLY_CREDITS} kredytami miesięcznie oraz pakiety kredytów, które nigdy nie wygasają, oba kupowane w aplikacji przez Google Play. Każde uruchomienie tafa zużywa kredyty za AI, której użył. Spokojne sprawdzenie, gdy nie ma nic nowego, nic nie kosztuje. Coach jest w cenie: wystarczy, by skonfigurować około 4 nowych zadań miesięcznie. Powyżej tego pomoc Coacha używa Twoich kredytów. Nie ma bezpłatnego okresu próbnego. Plany i ceny: kiff.dev/pl/tafs
```
