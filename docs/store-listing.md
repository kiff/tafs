# Store listing

The Play Store listing, in English (the source) and the seven other languages tafs speaks
([languages.md](languages.md)). The Play Console's default listing is copied from here, and each
language's listing from its section. The App Store listing will follow the same text once Apple's
business account is in place.

**Each language is reviewed against the glossary before it's added in the Console,** as
[languages.md](languages.md) says. No language waits for a native speaker: fixes go forward after
release.

## Rules for every language

- **The same claims and limits as English.** Nothing added, nothing stronger, nothing left out.
- **The glossary's words.** taf, Coach and Card stay as names. Needs you, Record, door, credits,
  credit pack, Approve once, Refuse and Settings use the glossary's term in each language.
- **Revoke is not Halt.** "Revoke the Card" ends the Card itself, so no new action goes through
  under it. It isn't the glossary's Halt, which blocks a taf in every system it works through, so
  each language uses its own word for revoke (widerrufen, révoquer, revocar, revocare, revogar,
  intrekken, odwołać) and never Halt's word.
- **Amounts in euros,** written the language's way.
- **Lengths:** title up to 30 characters, short description up to 80, full description up to 4,000.
- **Search.** Play ranks on the title, the short description and the full description. The short
  description names AI agents in the language's usual form (AI agents, KI-Agenten, agents IA, agentes
  de IA, agenti AI, AI-agents, agenci AI). The full description names them, automation and the jobs
  where the sentence needs them, never as a repeated keyword. "AI teammate" and "AI assistant" appear
  once each in the full description, beside AI agents, never in the title, the short description or a
  heading: the Card leads. "Autonomous" only with its limit (a taf never acts past its Card).
- **`{SOLO_MONTHLY_CREDITS}`** is Solo's monthly credits. The figure lives in KIFF's config, not in
  this repository; put it in when copying a listing into the Console.

## English

**Title:** tafs: AI Agents for Business

**Short description:** AI agents for critical work, under your control: open code, privacy first.

**Full description:**

```
tafs is where a business hands jobs to AI agents, or AI teammates, called tafs, and gives each one a Card: the limits it works inside.

Use it to automate refunds, bill pay, access and deploys. Each AI agent works on its own inside its Card, and never past it.

Inside its Card, a taf acts and leaves a receipt. Outside it, the taf waits for your answer on your phone. Revoke the Card and no new action goes through.

WHY YOU CAN HAND IT THE WORK
• Limits it can't talk its way past: every action is checked against its Card by KIFF, outside the AI, before it reaches your app. No prompt changes a limit.
• Your sign-ins stay sealed: the taf never sees your passwords or tokens. KIFF's connection service and its sign-in provider hold them, never the phone.
• Your fingerprint for what matters: routine work goes ahead within its limits. Anything past its Card is refused, or, if its Card says Ask, waits for you, approved with your fingerprint or face.
• You decide what really happened: when an app can't confirm a payment went through, tafs asks you "Did this happen?" Nothing is counted as done on a guess.

JOBS A TAF CAN TAKE, FOR EXAMPLE
• Refunds: settles refund and credit requests from the support queue, for example up to €150 each and €2,000 a day.
• Bill pay: pays vendor bills that match a purchase order; wires always ask.
• Access: gives joiners their apps and removes leavers; elevated access always asks.
• Deploys: ships fixes when CI is green and rolls back; migrations always ask.
• Chargebacks: answers card disputes with evidence.

HOW IT WORKS
• Coach: say the job in your own words. Coach, the AI assistant in tafs, connects your apps, drafts the taf and its Card, and you confirm.
• Needs you: when a taf wants to go past its Card, the call is held. Approve it once or refuse it, with your fingerprint or face unlock.
• Chat: ask a taf what it did, or tell it what to do differently, and watch its work as it goes.
• Record: everything your tafs did, newest first, with a receipt for each action that went through.
• Doors: let customers or colleagues reach a taf by email or chat.

YOUR DATA
Approvals are signed with a key kept in your phone's secure hardware. No ads, no trackers. Delete your account in Settings, or see kiff.dev/privacy#delete-account.

Plans: Solo, with {SOLO_MONTHLY_CREDITS} credits a month, and packs of credits that never expire, both bought in the app through Google Play. Each run of a taf uses credits for the AI it used. There's no free trial.
```

## German (de)

**Title:** tafs: KI-Agenten für Firmen

**Short description:** KI-Agenten für kritische Arbeit, unter Kontrolle: offener Code, Datenschutz.

**Full description:**

```
Mit tafs übergibt ein Unternehmen Aufgaben an KI-Agenten oder KI-Kollegen, die tafs heißen, und gibt jedem eine Card: die Grenzen, in denen er arbeitet.

Damit automatisierst du Erstattungen, Rechnungszahlungen, Zugänge und Deploys. Jeder KI-Agent arbeitet selbstständig innerhalb seiner Card und nie darüber hinaus.

Innerhalb seiner Card handelt ein taf und hinterlässt einen Beleg. Außerhalb wartet der taf auf deinem Handy auf deine Antwort. Widerrufe die Card, und keine neue Aktion geht mehr durch.

WARUM DU IHM DIE ARBEIT ÜBERGEBEN KANNST
• Grenzen, die er sich nicht wegreden kann: KIFF prüft jede Aktion gegen ihre Card, außerhalb der KI, bevor sie deine App erreicht. Kein Prompt ändert ein Limit.
• Deine Anmeldungen bleiben versiegelt: Der taf sieht nie deine Passwörter oder Tokens. Der Verbindungsdienst von KIFF und sein Anmeldeanbieter verwahren sie, nie das Handy.
• Dein Fingerabdruck für das, was zählt: Routinearbeit läuft innerhalb ihrer Grenzen. Alles jenseits der Card wird abgelehnt oder wartet, wenn die Card „Fragen“ sagt, auf dich und wird mit Fingerabdruck oder Gesicht genehmigt.
• Du entscheidest, was wirklich passiert ist: Wenn eine App nicht bestätigen kann, dass eine Zahlung durchging, fragt dich tafs „Ist das passiert?“ Nichts zählt auf Verdacht als erledigt.

AUFGABEN, DIE EIN TAF ÜBERNEHMEN KANN, ZUM BEISPIEL
• Erstattungen: erledigt Erstattungs- und Gutschriftanfragen aus der Support-Warteschlange, zum Beispiel bis 150 € pro Fall und 2.000 € am Tag.
• Rechnungen: bezahlt Lieferantenrechnungen, die zu einer Bestellung passen; Überweisungen fragen immer.
• Zugänge: gibt Neuen ihre Apps und entzieht sie Ausscheidenden; erweiterte Rechte fragen immer.
• Deploys: liefert Fixes aus, wenn die CI grün ist, und rollt zurück; Migrationen fragen immer.
• Chargebacks: beantwortet Kartenreklamationen mit Belegen.

SO FUNKTIONIERT ES
• Coach: Beschreib die Aufgabe in deinen eigenen Worten. Coach, der KI-Assistent von tafs, verbindet deine Apps, entwirft den taf und seine Card, und du bestätigst.
• Braucht dich: Wenn ein taf über seine Card hinaus will, wird der Aufruf zurückgehalten. Einmal genehmigen oder ablehnen, mit Fingerabdruck oder Gesichtsentsperrung.
• Chat: Frag einen taf, was er getan hat, oder sag ihm, was er anders machen soll, und verfolge seine Arbeit live.
• Protokoll: alles, was deine tafs getan haben, das Neueste zuerst, mit einem Beleg für jede Aktion, die durchging.
• Türen: Lass Kunden oder Kollegen einen taf per E-Mail oder Chat erreichen.

DEINE DATEN
Genehmigungen werden mit einem Schlüssel signiert, der in der Sicherheitshardware deines Handys liegt. Keine Werbung, keine Tracker. Lösch dein Konto in den Einstellungen, oder siehe kiff.dev/privacy#delete-account.

Tarife: Solo mit {SOLO_MONTHLY_CREDITS} Credits im Monat und Credit-Pakete, die nie verfallen, beides in der App über Google Play gekauft. Jeder Lauf eines taf verbraucht Credits für die KI, die er genutzt hat. Es gibt keine kostenlose Testphase.
```

## French (fr)

**Title:** tafs : agents IA entreprises

**Short description:** Agents IA pour le travail critique, sous contrôle : code ouvert, vie privée.

**Full description:**

```
tafs, c'est là qu'une entreprise confie des tâches à des agents IA, ou coéquipiers IA, appelés tafs, et donne à chacun une Card : les limites dans lesquelles il travaille.

Avec tafs, automatisez les remboursements, le paiement des factures, les accès et les déploiements. Chaque agent IA travaille en autonomie dans sa Card, et jamais au-delà.

Dans sa Card, un taf agit et laisse un reçu. En dehors, le taf attend votre réponse sur votre téléphone. Révoquez la Card et plus aucune nouvelle action ne passe.

POURQUOI VOUS POUVEZ LUI CONFIER LE TRAVAIL
• Des limites qu'il ne peut pas contourner par la parole : chaque action est vérifiée par KIFF au regard de sa Card, en dehors de l'IA, avant d'atteindre votre app. Aucun prompt ne change une limite.
• Vos identifiants restent scellés : le taf ne voit jamais vos mots de passe ni vos jetons. Le service de connexion de KIFF et son fournisseur d'identification les conservent, jamais le téléphone.
• Votre empreinte pour ce qui compte : le travail courant avance dans ses limites. Tout ce qui dépasse sa Card est refusé ou, si sa Card dit « Demander », vous attend et s'approuve avec votre empreinte ou votre visage.
• Vous décidez de ce qui s'est vraiment passé : quand une app ne peut pas confirmer qu'un paiement est passé, tafs vous demande « Est-ce que c'est arrivé ? » Rien n'est compté comme fait sur une supposition.

DES TÂCHES QU'UN TAF PEUT PRENDRE, PAR EXEMPLE
• Remboursements : traite les demandes de remboursement et d'avoir de la file du support, par exemple jusqu'à 150 € chacune et 2 000 € par jour.
• Factures : paie les factures fournisseurs qui correspondent à un bon de commande ; les virements demandent toujours.
• Accès : donne leurs apps aux arrivants et les retire aux partants ; les accès élevés demandent toujours.
• Déploiements : livre des correctifs quand la CI est verte et revient en arrière ; les migrations demandent toujours.
• Rétrofacturations : répond aux litiges de carte avec des preuves.

COMMENT ÇA MARCHE
• Coach : décrivez la tâche avec vos propres mots. Coach, l'assistant IA de tafs, connecte vos apps, prépare le taf et sa Card, et vous confirmez.
• Vous attend : quand un taf veut dépasser sa Card, l'appel est retenu. Approuvez-le une fois ou refusez-le, avec votre empreinte ou le déverrouillage par le visage.
• Chat : demandez à un taf ce qu'il a fait, ou dites-lui quoi faire autrement, et suivez son travail en direct.
• Registre : tout ce que vos tafs ont fait, du plus récent au plus ancien, avec un reçu pour chaque action passée.
• Portes : laissez vos clients ou vos collègues joindre un taf par e-mail ou par chat.

VOS DONNÉES
Les approbations sont signées avec une clé conservée dans le matériel sécurisé de votre téléphone. Pas de publicité, pas de traceurs. Supprimez votre compte dans Réglages, ou voir kiff.dev/privacy#delete-account.

Forfaits : Solo, avec {SOLO_MONTHLY_CREDITS} crédits par mois, et des packs de crédits qui n'expirent jamais, tous deux achetés dans l'app via Google Play. Chaque exécution d'un taf utilise des crédits pour l'IA qu'elle a utilisée. Il n'y a pas d'essai gratuit.
```

## Spanish (es)

**Title:** tafs: agentes IA para empresas

**Short description:** Agentes de IA para trabajo crítico, bajo tu control: código abierto, privacidad.

**Full description:**

```
tafs es donde una empresa encarga tareas a agentes de IA, o compañeros de IA, llamados tafs, y le da a cada uno una Card: los límites dentro de los que trabaja.

Úsalo para automatizar reembolsos, pago de facturas, accesos y despliegues. Cada agente de IA trabaja por su cuenta dentro de su Card, y nunca más allá.

Dentro de su Card, un taf actúa y deja un recibo. Fuera de ella, el taf espera tu respuesta en tu teléfono. Revoca la Card y ninguna acción nueva sale adelante.

POR QUÉ PUEDES ENCARGARLE EL TRABAJO
• Límites que no puede saltarse hablando: KIFF comprueba cada acción contra su Card, fuera de la IA, antes de que llegue a tu app. Ningún prompt cambia un límite.
• Tus accesos siguen sellados: el taf nunca ve tus contraseñas ni tus tokens. Los guardan el servicio de conexión de KIFF y su proveedor de inicio de sesión, nunca el teléfono.
• Tu huella para lo importante: el trabajo rutinario avanza dentro de sus límites. Lo que va más allá de su Card se rechaza o, si su Card dice «Preguntar», te espera y se aprueba con tu huella o tu cara.
• Tú decides qué pasó de verdad: cuando una app no puede confirmar que un pago se hizo, tafs te pregunta «¿Ocurrió esto?». Nada se da por hecho a ciegas.

TAREAS QUE PUEDE ASUMIR UN TAF, POR EJEMPLO
• Reembolsos: resuelve solicitudes de reembolso y de abono de la cola de soporte, por ejemplo hasta 150 € cada una y 2.000 € al día.
• Pago de facturas: paga facturas de proveedores que coinciden con una orden de compra; las transferencias siempre preguntan.
• Accesos: da sus apps a quien entra y se las quita a quien sale; los accesos elevados siempre preguntan.
• Despliegues: publica correcciones cuando la CI está en verde y revierte; las migraciones siempre preguntan.
• Contracargos: responde a las disputas de tarjeta con pruebas.

CÓMO FUNCIONA
• Coach: di la tarea con tus palabras. Coach, el asistente de IA de tafs, conecta tus apps, prepara el taf y su Card, y tú confirmas.
• Te necesita: cuando un taf quiere ir más allá de su Card, la llamada queda retenida. Apruébala una vez o recházala, con tu huella o el desbloqueo facial.
• Chat: pregunta a un taf qué hizo, o dile qué hacer de otra forma, y sigue su trabajo mientras avanza.
• Registro: todo lo que hicieron tus tafs, lo más reciente primero, con un recibo por cada acción que salió adelante.
• Puertas: deja que clientes o compañeros contacten con un taf por correo o chat.

TUS DATOS
Las aprobaciones se firman con una clave guardada en el hardware seguro de tu teléfono. Sin anuncios, sin rastreadores. Borra tu cuenta en Ajustes, o consulta kiff.dev/privacy#delete-account.

Planes: Solo, con {SOLO_MONTHLY_CREDITS} créditos al mes, y paquetes de créditos que no caducan nunca, ambos comprados en la app a través de Google Play. Cada ejecución de un taf usa créditos por la IA que utilizó. No hay prueba gratuita.
```

## Italian (it)

**Title:** tafs: agenti IA per aziende

**Short description:** Agenti AI per il lavoro critico, sotto controllo: codice aperto, privacy.

**Full description:**

```
tafs è il posto in cui un'azienda affida lavori ad agenti IA, o colleghi IA, chiamati tafs, e dà a ciascuno una Card: i limiti entro cui lavora.

Usalo per automatizzare rimborsi, pagamento delle fatture, accessi e deploy. Ogni agente IA lavora in autonomia dentro la sua Card, e mai oltre.

Dentro la sua Card, un taf agisce e lascia una ricevuta. Fuori, il taf aspetta la tua risposta sul telefono. Revoca la Card e nessuna nuova azione va a buon fine.

PERCHÉ PUOI AFFIDARGLI IL LAVORO
• Limiti che non può aggirare a parole: ogni azione viene verificata da KIFF rispetto alla sua Card, fuori dall'IA, prima che raggiunga la tua app. Nessun prompt cambia un limite.
• I tuoi accessi restano sigillati: il taf non vede mai le tue password né i tuoi token. Li custodiscono il servizio di connessione di KIFF e il suo fornitore di accesso, mai il telefono.
• La tua impronta per ciò che conta: il lavoro di routine va avanti entro i suoi limiti. Tutto ciò che va oltre la sua Card viene rifiutato oppure, se la sua Card dice «Chiedi», ti aspetta e si approva con l'impronta o il volto.
• Decidi tu cosa è successo davvero: quando un'app non riesce a confermare che un pagamento è andato a buon fine, tafs ti chiede «È successo?». Niente viene considerato fatto per supposizione.

LAVORI CHE UN TAF PUÒ PRENDERE, PER ESEMPIO
• Rimborsi: gestisce le richieste di rimborso e di accredito dalla coda dell'assistenza, per esempio fino a 150 € ciascuna e 2.000 € al giorno.
• Pagamento fatture: paga le fatture dei fornitori che corrispondono a un ordine d'acquisto; i bonifici chiedono sempre.
• Accessi: dà le app a chi arriva e le toglie a chi se ne va; gli accessi elevati chiedono sempre.
• Deploy: rilascia correzioni quando la CI è verde e torna indietro; le migrazioni chiedono sempre.
• Chargeback: risponde alle contestazioni sulle carte con le prove.

COME FUNZIONA
• Coach: descrivi il lavoro con parole tue. Coach, l'assistente IA di tafs, collega le tue app, prepara il taf e la sua Card, e tu confermi.
• Ti aspetta: quando un taf vuole andare oltre la sua Card, la chiamata viene trattenuta. Approvala una volta o rifiutala, con l'impronta o lo sblocco con il volto.
• Chat: chiedi a un taf cosa ha fatto, o digli cosa fare in modo diverso, e segui il suo lavoro mentre procede.
• Registro: tutto ciò che hanno fatto i tuoi tafs, dal più recente, con una ricevuta per ogni azione andata a buon fine.
• Porte: fai raggiungere un taf da clienti o colleghi via email o chat.

I TUOI DATI
Le approvazioni sono firmate con una chiave custodita nell'hardware sicuro del tuo telefono. Niente pubblicità, niente tracker. Elimina il tuo account in Impostazioni, oppure vedi kiff.dev/privacy#delete-account.

Piani: Solo, con {SOLO_MONTHLY_CREDITS} crediti al mese, e pacchetti di crediti che non scadono mai, entrambi acquistati nell'app tramite Google Play. Ogni esecuzione di un taf usa crediti per l'IA che ha usato. Non c'è una prova gratuita.
```

## Portuguese (pt)

European Portuguese (`pt-PT`), as [languages.md](languages.md) proposes, addressing the reader as você.

**Title:** tafs: agentes IA para empresas

**Short description:** Agentes de IA para trabalho crítico, sob controlo: código aberto, privacidade.

**Full description:**

```
O tafs é onde uma empresa entrega tarefas a agentes de IA, ou colegas de IA, chamados tafs, e dá a cada um um Card: os limites dentro dos quais trabalha.

Use-o para automatizar reembolsos, pagamento de faturas, acessos e implementações. Cada agente de IA trabalha de forma autónoma dentro do seu Card, e nunca para além dele.

Dentro do seu Card, um taf age e deixa um recibo. Fora dele, o taf aguarda a sua resposta no seu telemóvel. Revogue o Card e nenhuma ação nova avança.

PORQUE PODE ENTREGAR-LHE O TRABALHO
• Limites que não consegue contornar com conversa: cada ação é verificada pelo KIFF face ao seu Card, fora da IA, antes de chegar à sua app. Nenhum prompt altera um limite.
• Os seus acessos ficam selados: o taf nunca vê as suas palavras-passe nem os seus tokens. São o serviço de ligação do KIFF e o seu fornecedor de início de sessão que os guardam, nunca o telemóvel.
• A sua impressão digital para o que importa: o trabalho de rotina avança dentro dos seus limites. O que vai além do seu Card é recusado ou, se o seu Card disser «Perguntar», aguarda por si e é aprovado com a sua impressão digital ou o seu rosto.
• É você quem decide o que aconteceu de facto: quando uma app não consegue confirmar que um pagamento foi feito, o tafs pergunta-lhe «Isto aconteceu?». Nada é dado como feito por suposição.

TAREFAS QUE UM TAF PODE ASSUMIR, POR EXEMPLO
• Reembolsos: resolve pedidos de reembolso e de crédito da fila de suporte, por exemplo até 150 € cada e 2000 € por dia.
• Pagamento de faturas: paga faturas de fornecedores que correspondem a uma nota de encomenda; as transferências perguntam sempre.
• Acessos: dá as apps a quem entra e retira-as a quem sai; os acessos elevados perguntam sempre.
• Implementações: publica correções quando a CI está verde e reverte; as migrações perguntam sempre.
• Estornos: responde a disputas de cartão com provas.

COMO FUNCIONA
• Coach: descreva a tarefa por palavras suas. O Coach, o assistente de IA do tafs, liga as suas apps, prepara o taf e o seu Card, e você confirma.
• Precisa de si: quando um taf quer ir além do seu Card, a chamada fica retida. Aprove-a uma vez ou recuse-a, com a sua impressão digital ou o desbloqueio facial.
• Chat: pergunte a um taf o que fez, ou diga-lhe o que fazer de outra forma, e acompanhe o trabalho enquanto decorre.
• Registo: tudo o que os seus tafs fizeram, do mais recente para o mais antigo, com um recibo por cada ação que avançou.
• Portas: deixe clientes ou colegas contactarem um taf por e-mail ou chat.

OS SEUS DADOS
As aprovações são assinadas com uma chave guardada no hardware seguro do seu telemóvel. Sem anúncios, sem rastreadores. Elimine a sua conta em Definições, ou consulte kiff.dev/privacy#delete-account.

Planos: Solo, com {SOLO_MONTHLY_CREDITS} créditos por mês, e pacotes de créditos que nunca expiram, ambos comprados na app através do Google Play. Cada execução de um taf usa créditos pela IA que utilizou. Não há período de avaliação gratuito.
```

## Dutch (nl)

**Title:** tafs: AI-agents voor bedrijven

**Short description:** AI-agents voor bedrijfskritisch werk, onder controle: open code, privacy voorop.

**Full description:**

```
tafs is waar een bedrijf taken overdraagt aan AI-agents, of AI-collega's, tafs genoemd, en elk een Card geeft: de grenzen waarbinnen het werkt.

Automatiseer hiermee terugbetalingen, het betalen van facturen, toegang en deploys. Elke AI-agent werkt zelfstandig binnen zijn Card, en nooit daarbuiten.

Binnen zijn Card handelt een taf en laat het een bewijs achter. Daarbuiten wacht het taf op je antwoord op je telefoon. Trek de Card in en er gaat geen nieuwe actie meer door.

WAAROM JE HEM HET WERK KUNT GEVEN
• Grenzen waar het zich niet uit kan praten: KIFF toetst elke actie aan zijn Card, buiten de AI, voordat die je app bereikt. Geen prompt verandert een limiet.
• Je inloggegevens blijven verzegeld: het taf ziet nooit je wachtwoorden of tokens. De koppelingsdienst van KIFF en zijn inlogprovider bewaren ze, nooit de telefoon.
• Je vingerafdruk voor wat ertoe doet: routinewerk gaat door binnen zijn grenzen. Alles buiten zijn Card wordt geweigerd of, als zijn Card 'Vragen' zegt, wacht op jou en wordt goedgekeurd met je vingerafdruk of gezicht.
• Jij bepaalt wat er echt gebeurd is: als een app niet kan bevestigen dat een betaling is gelukt, vraagt tafs je "Is dit gebeurd?" Niets telt op goed geluk als gedaan.

TAKEN DIE EEN TAF KAN OPPAKKEN, BIJVOORBEELD
• Terugbetalingen: handelt verzoeken om terugbetaling en tegoed uit de supportwachtrij af, bijvoorbeeld tot € 150 per stuk en € 2.000 per dag.
• Facturen betalen: betaalt leveranciersfacturen die bij een inkooporder passen; overboekingen vragen altijd.
• Toegang: geeft nieuwkomers hun apps en haalt ze weg bij vertrekkers; verhoogde rechten vragen altijd.
• Deploys: levert fixes uit als de CI groen is en draait terug; migraties vragen altijd.
• Chargebacks: beantwoordt kaartgeschillen met bewijs.

ZO WERKT HET
• Coach: zeg de taak in je eigen woorden. Coach, de AI-assistent van tafs, koppelt je apps, maakt een opzet van het taf en zijn Card, en jij bevestigt.
• Wacht op jou: als een taf buiten zijn Card wil gaan, wordt de aanroep vastgehouden. Keur hem eenmalig goed of weiger hem, met je vingerafdruk of gezichtsontgrendeling.
• Chat: vraag een taf wat het gedaan heeft, of zeg het wat het anders moet doen, en volg zijn werk terwijl het gebeurt.
• Logboek: alles wat je tafs gedaan hebben, het nieuwste eerst, met een bewijs voor elke actie die doorging.
• Deuren: laat klanten of collega's een taf bereiken via e-mail of chat.

JE GEGEVENS
Goedkeuringen worden ondertekend met een sleutel in de beveiligde hardware van je telefoon. Geen advertenties, geen trackers. Verwijder je account in Instellingen, of zie kiff.dev/privacy#delete-account.

Abonnementen: Solo, met {SOLO_MONTHLY_CREDITS} credits per maand, en creditpakketten die nooit verlopen, allebei in de app gekocht via Google Play. Elke run van een taf gebruikt credits voor de AI die het gebruikte. Er is geen gratis proefperiode.
```

## Polish (pl)

**Title:** tafs: agenci AI dla firm

**Short description:** Agenci AI do krytycznej pracy pod Twoją kontrolą: otwarty kod, prywatność.

**Full description:**

```
tafs to miejsce, w którym firma powierza zadania agentom AI lub współpracownikom AI, nazywanym tafs, i daje każdemu Card: granice, w których pracuje.

Zautomatyzujesz nim zwroty, opłacanie faktur, nadawanie dostępów i wdrożenia. Każdy agent AI działa samodzielnie w ramach swojej Card i nigdy poza nią.

W ramach swojej Card taf działa i zostawia potwierdzenie. Poza nią taf czeka na Twoją odpowiedź na Twoim telefonie. Odwołaj Card, a żadna nowa akcja już nie przejdzie.

DLACZEGO MOŻESZ POWIERZYĆ MU PRACĘ
• Granice, których nie obejdzie słowami: KIFF sprawdza każdą akcję względem jej Card, poza AI, zanim dotrze ona do Twojej aplikacji. Żaden prompt nie zmienia limitu.
• Twoje dane logowania pozostają zapieczętowane: taf nigdy nie widzi Twoich haseł ani tokenów. Przechowują je usługa połączeń KIFF i jej dostawca logowania, nigdy telefon.
• Twój odcisk palca do tego, co ważne: rutynowa praca idzie naprzód w swoich granicach. Wszystko poza jego Card zostaje odrzucone albo, jeśli jego Card mówi „Pytaj”, czeka na Ciebie i jest zatwierdzane odciskiem palca lub twarzą.
• To Ty decydujesz, co naprawdę się stało: gdy aplikacja nie może potwierdzić, że płatność przeszła, tafs pyta Cię „Czy to się stało?”. Nic nie jest uznawane za wykonane na podstawie domysłów.

ZADANIA, KTÓRE TAF MOŻE PRZEJĄĆ, NA PRZYKŁAD
• Zwroty: rozpatruje prośby o zwrot i uznanie z kolejki wsparcia, na przykład do 150 € każda i 2000 € dziennie.
• Płatność faktur: opłaca faktury dostawców zgodne z zamówieniem; przelewy zawsze pytają.
• Dostępy: daje nowym osobom ich aplikacje i odbiera je odchodzącym; podwyższone uprawnienia zawsze pytają.
• Wdrożenia: wdraża poprawki, gdy CI jest zielone, i cofa zmiany; migracje zawsze pytają.
• Chargebacki: odpowiada na spory kartowe dowodami.

JAK TO DZIAŁA
• Coach: opisz zadanie własnymi słowami. Coach, asystent AI w tafs, łączy Twoje aplikacje, przygotowuje tafa i jego Card, a Ty zatwierdzasz.
• Czeka na Ciebie: gdy taf chce wyjść poza swoją Card, wywołanie trafia do oczekujących. Zatwierdź je raz albo odrzuć, odciskiem palca lub odblokowaniem twarzą.
• Czat: zapytaj tafa, co zrobił, albo powiedz mu, co ma zrobić inaczej, i śledź jego pracę na bieżąco.
• Rejestr: wszystko, co zrobiły Twoje tafs, od najnowszych, z potwierdzeniem dla każdej akcji, która przeszła.
• Drzwi: pozwól klientom lub współpracownikom kontaktować się z tafem przez e-mail lub czat.

TWOJE DANE
Zatwierdzenia są podpisywane kluczem przechowywanym w bezpiecznym sprzęcie Twojego telefonu. Bez reklam, bez trackerów. Usuń konto w Ustawieniach albo zobacz kiff.dev/privacy#delete-account.

Plany: Solo z {SOLO_MONTHLY_CREDITS} kredytami miesięcznie oraz pakiety kredytów, które nigdy nie wygasają, oba kupowane w aplikacji przez Google Play. Każde uruchomienie tafa zużywa kredyty za AI, której użył. Nie ma bezpłatnego okresu próbnego.
```
