# Languages

tafs speaks eight languages: English, German, French, Spanish, Italian, Portuguese, Dutch and Polish.
Someone whose phone is set to one of them uses tafs in it: every screen, every line tafs cloud and
KIFF write, Coach's replies, and the store listing. Any other language falls back to English.

This page is the one glossary. The apps, tafs cloud, KIFF and Coach use these words and no others for
the terms below, so a word on a button is the word in a notification, an email and Coach's reply.

## Rules

- **Locale only, never a rule.** A language changes words. It never changes what a Card allows, what
  counts against a limit or how an amount is checked.
- **Amounts and dates format by the phone's locale,** as they do now: `1.240,00 €` in German,
  `1 240,00 €` in French, `€1,240.00` in English.
- **The binding texts stay in English:** Terms, Privacy and the DPA, until the owner says otherwise.
- **Developers' words stay in English:** logs, and an API's `error` field. What a person reads comes in
  `words`, `line`, titles and bodies, and those are translated.
- **Before a language reaches a store listing,** a native speaker (or the owner) checks its key screens.
  Until then it ships behind the phone's language, with no listing.

## Which language

- **The apps decide,** from the languages they ship: an app sends its own resolved languages as
  `Accept-Language` (up to 4, then `en`) to KIFF and tafs cloud. An app that ships only English
  sends `en` whatever the phone says, so a server's words always match the screen's.
- **A server picks** the best of the eight by `q`, else English.
- **With no request** (an email, an alert for a scheduled run, Coach's line once a task ends), a server
  uses the person's last language, kept from their signed-in requests. English until one is seen.

## The voice

The same as English: plain, short, specific. Say what happened and what the person can do.

| Language | Code | Address | Notes |
|---|---|---|---|
| English | `en` | you | The source of every line. |
| German | `de` | du | Informal, as most business apps people use daily. Nouns capitalised as German requires. |
| French | `fr` | vous | French business software addresses people as vous. |
| Spanish | `es` | tú | Neutral Spanish, understood in Spain and Latin America. |
| Italian | `it` | tu | Informal, as business apps in Italian do. |
| Portuguese | `pt` | você | **To decide:** European (`pt-PT`) or Brazilian (`pt-BR`). Proposed: European, since the customers are in Europe, with `pt-BR` phones reading it too. |
| Dutch | `nl` | je | Informal, as Dutch apps do. |
| Polish | `pl` | Ty | Buttons in the infinitive or imperative, sentences addressing Ty. |

## Names that stay as they are

**taf, Coach and Card** are names, not words to translate:
- they're on the store listings, in KIFF's API and in support, so a person searching for help finds the
  same word;
- a translated "Card" would read as a payment card in several languages (Karte, carte, tarjeta).

In running text they take the language's grammar: "zwei tafs", "deux tafs", "una Card", "twoja Card".
A taf's own name ("Refunds") is the owner's and is never translated.

## Terms

| English | German | French | Spanish | Italian | Portuguese | Dutch | Polish |
|---|---|---|---|---|---|---|---|
| Needs you | Braucht dich | Vous attend | Te necesita | Ti aspetta | Precisa de si | Wacht op jou | Czeka na Ciebie |
| Approve | Genehmigen | Approuver | Aprobar | Approva | Aprovar | Goedkeuren | Zatwierdź |
| Approve once | Einmal genehmigen | Approuver une fois | Aprobar una vez | Approva una volta | Aprovar uma vez | Eenmalig goedkeuren | Zatwierdź raz |
| Refuse | Ablehnen | Refuser | Rechazar | Rifiuta | Recusar | Weigeren | Odrzuć |
| held (a call held for approval) | zurückgehalten | retenu | retenida | trattenuta | retida | vastgehouden | oczekujące |
| Halt (stop everything) | Anhalten | Bloquer | Bloquear | Blocca | Bloquear | Stilzetten | Zablokuj |
| Pause | Pausieren | Mettre en pause | Pausar | Metti in pausa | Pausar | Pauzeren | Wstrzymaj |
| Resume | Fortsetzen | Reprendre | Reanudar | Riprendi | Retomar | Hervatten | Wznów |
| Run now | Jetzt ausführen | Lancer maintenant | Ejecutar ahora | Esegui ora | Executar agora | Nu uitvoeren | Uruchom teraz |
| credits | Credits | crédits | créditos | crediti | créditos | credits | kredyty |
| spend limit | Ausgabenlimit | plafond de dépenses | límite de gasto | limite di spesa | limite de gastos | uitgavenlimiet | limit wydatków |
| Plan & usage | Tarif & Nutzung | Forfait et utilisation | Plan y uso | Piano e utilizzo | Plano e utilização | Abonnement en gebruik | Plan i wykorzystanie |
| door | Tür | porte | puerta | porta | porta | deur | drzwi |
| Record (KIFF's record) | Protokoll | Registre | Registro | Registro | Registo | Logboek | Rejestr |
| Connect apps | Apps verbinden | Connecter des apps | Conectar apps | Collega app | Ligar apps | Apps koppelen | Połącz aplikacje |
| connection | Verbindung | connexion | conexión | collegamento | ligação | koppeling | połączenie |
| Sign in again | Erneut anmelden | Se reconnecter | Volver a iniciar sesión | Accedi di nuovo | Iniciar sessão novamente | Opnieuw inloggen | Zaloguj się ponownie |
| Disconnect | Trennen | Déconnecter | Desconectar | Scollega | Desligar | Ontkoppelen | Rozłącz |
| Try again | Erneut versuchen | Réessayer | Reintentar | Riprova | Tentar novamente | Opnieuw proberen | Spróbuj ponownie |
| Ask Coach | Coach fragen | Demander à Coach | Preguntar a Coach | Chiedi a Coach | Perguntar ao Coach | Coach vragen | Zapytaj Coacha |
| run (one piece of a taf's work) | Lauf | exécution | ejecución | esecuzione | execução | run | uruchomienie |
| request (what a taf was asked to do) | Anfrage | demande | solicitud | richiesta | pedido | verzoek | prośba |
| schedule | Zeitplan | planning | programación | pianificazione | agendamento | planning | harmonogram |
| follow-up (one a taf set for itself) | Nachfassen | relance | seguimiento | promemoria | acompanhamento | opvolging | przypomnienie |
| Working on it | Wird bearbeitet | En cours | Trabajando en ello | In corso | Em curso | Bezig | W toku |
| Done | Erledigt | Terminé | Hecho | Fatto | Concluído | Klaar | Gotowe |
| Failed | Fehlgeschlagen | Échec | Falló | Non riuscito | Falhou | Mislukt | Nie powiodło się |
| Stopped | Gestoppt | Arrêté | Detenido | Fermato | Parado | Gestopt | Zatrzymane |
| Paused | Pausiert | En pause | En pausa | In pausa | Em pausa | Gepauzeerd | Wstrzymane |
| Halted | Angehalten | Bloqué | Bloqueado | Bloccato | Bloqueado | Stilgezet | Zablokowane |
| credit pack | Credit-Paket | pack de crédits | paquete de créditos | pacchetto di crediti | pacote de créditos | creditpakket | pakiet kredytów |
| Add credits | Credits hinzufügen | Ajouter des crédits | Añadir créditos | Aggiungi crediti | Adicionar créditos | Credits toevoegen | Dodaj kredyty |
| Raise the limit | Limit erhöhen | Relever le plafond | Subir el límite | Aumenta il limite | Aumentar o limite | Limiet verhogen | Podnieś limit |
| Ask an admin | Admin fragen | Demander à un administrateur | Pedir a un administrador | Chiedi a un amministratore | Pedir a um administrador | Een beheerder vragen | Poproś administratora |
| version | Version | version | versión | versione | versão | versie | wersja |
| Make it live | Live schalten | Mettre en service | Activar | Rendi attiva | Pôr em produção | Live zetten | Uruchom na żywo |
| Try it | Ausprobieren | Essayer | Probar | Prova | Experimentar | Uitproberen | Wypróbuj |
| knock (a message from someone not paired) | Klopfen | demande d'accès | llamada | bussata | pedido de contacto | klop | pukanie |
| Pair (a sender with a door) | Koppeln | Associer | Vincular | Associa | Associar | Koppelen | Powiąż |
| Send again | Erneut senden | Renvoyer | Volver a enviar | Invia di nuovo | Enviar novamente | Opnieuw verzenden | Wyślij ponownie |
| Not sent | Nicht gesendet | Non envoyé | No enviado | Non inviato | Não enviado | Niet verzonden | Nie wysłano |
| Activity | Aktivität | Activité | Actividad | Attività | Atividade | Activiteit | Aktywność |
| Settings | Einstellungen | Réglages | Ajustes | Impostazioni | Definições | Instellingen | Ustawienia |
| Home | Start | Accueil | Inicio | Home | Início | Start | Start |

Halted and Stopped are different words in every language: a halt blocks a taf in every system it works
through, while a stopped request is one run that ended. A word not in this table is translated in the voice above, the same way every time it appears. A new
term that recurs is added here first, then used.

## The AI disclosure

What a taf sends outside the company starts with its disclosure line, in the message's language
(`disclosureLines` in `runtime/src/jobs.ts`, mirrored by kiff/agents). It's in English, Spanish, French,
German, Portuguese and Italian today. **Dutch and Polish come with the language work:** "Geschreven door
een AI-agent voor <writes_for>." and "Napisane przez agenta AI dla <writes_for>.", each with the words
that tell a message's language, in tafs and kiff/agents together.

## How it's built

- **tafs cloud:** a catalog per language in `runtime/src/cloud/words/`, English the source (`en.ts`),
  each language typed against it, and a test that fails on a key missing from any language, an empty
  line, or a line left in English. A run code tafs cloud knows (`ai_unavailable`) is worded from the
  catalog; one it doesn't know passes kiff/agents' words through. The first lines in it are a taf's work
  lines; the rest of tafs cloud's lines, and the language Coach and the tafs are told, come next.
- **KIFF:** one catalog per language (`internal/words`), the same test.
- **Android:** `strings.xml` with a `values-xx` set per language; **iPhone and iPad:** a String Catalog.
- **Coach and the tafs** are told the person's language by tafs cloud with each turn, and a taf's
  scheduled reports use its owner's last language.
