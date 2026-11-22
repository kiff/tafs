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
- **The binding texts are in English:** Terms, Privacy and the DPA. Their translations, and each country's section,
  are published in the country's language beside English; the English text binds, except where local law requires
  the local-language text to bind (Quebec's Charter of the French language). The owner's ruling, 20 Nov.
- **Developers' words stay in English:** logs, and an API's `error` field. What a person reads comes in
  `words`, `line`, titles and bodies, and those are translated.
- **Each language is reviewed against this page's glossary,** every catalog and every listing, before it
  ships. No language waits for a native speaker: fixes go forward after release. The repos are public,
  so anyone can report a wording.

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
| Portuguese | `pt` | você | European Portuguese (`pt-PT`), decided: the customers are in Europe. Brazil reads its own regional variant, `pt-BR` (below, "Regional variants"). |
| Dutch | `nl` | je | Informal, as Dutch apps do. |
| Polish | `pl` | Ty | Buttons in the infinitive or imperative, sentences addressing Ty. |

**A time with a weekday** reads as each language says "at" it: "Done at Fri 09:00", "Erledigt am Fr. um 09:00",
"Terminé le ven. à 09:00", "Hecho el vie. a las 09:00". Today's times have no weekday: "um 09:00", "à 09:00".

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
| Coach mode | Coach-Modus | mode Coach | modo Coach | modalità Coach | modo Coach | Coach-modus | tryb Coach |
| run (one piece of a taf's work) | Lauf | exécution | ejecución | esecuzione | execução | run | uruchomienie |
| request (what a taf was asked to do) | Anfrage | demande | solicitud | richiesta | pedido | verzoek | prośba |
| scheduled run | geplanter Lauf | exécution planifiée | ejecución programada | esecuzione pianificata | execução agendada | geplande run | zaplanowane uruchomienie |
| schedule | Zeitplan | planning | programación | pianificazione | agendamento | planning | harmonogram |
| follow-up (one a taf set for itself) | Nachfassen | relance | seguimiento | promemoria | acompanhamento | opvolging | przypomnienie |
| Working on it | Wird bearbeitet | En cours | Trabajando en ello | In corso | Em curso | Bezig | W toku |
| Done | Erledigt | Terminé | Hecho | Fatto | Concluído | Klaar | Gotowe |
| Your step (a step a taf handed its owner) | Dein Schritt | Votre étape | Tu paso | Il tuo passaggio | O seu passo | Jouw stap | Twój krok |
| Your brief (the tab with the brief) | Dein Briefing | Votre briefing | Tu resumen | Il tuo riepilogo | O seu resumo | Jouw briefing | Twój przegląd |
| brief (the summary tafs cloud writes twice a day: decisions, what was done) | Briefing | briefing | resumen | riepilogo | resumo | briefing | przegląd |
| mission's log (a mission's read-only history, not KIFF's Record) | Missionsverlauf | historique de la mission | historial de la misión | cronologia della missione | histórico da missão | missiegeschiedenis | historia misji |
| Skip it | Überspringen | Passer | Omitir | Salta | Saltar | Overslaan | Pomiń |
| Failed | Fehlgeschlagen | Échec | Falló | Non riuscito | Falhou | Mislukt | Nie powiodło się |
| Stopped | Gestoppt | Arrêté | Detenido | Fermato | Parado | Gestopt | Zatrzymane |
| ran out of room (a reply cut off at its length limit) | hatte keinen Platz mehr | a manqué de place | se quedó sin espacio | ha esaurito lo spazio | ficou sem espaço | had geen ruimte meer | zabrakło miejsca |
| declined to continue (the AI, ending a run) | hat es abgelehnt, fortzufahren | a refusé de continuer | se negó a continuar | si è rifiutata di continuare | recusou-se a continuar | weigerde verder te gaan | odmówiła kontynuowania |
| Paused | Pausiert | En pause | En pausa | In pausa | Em pausa | Gepauzeerd | Wstrzymane |
| Halted | Angehalten | Bloqué | Bloqueado | Bloccato | Bloqueado | Stilgezet | Zablokowane |
| credit pack | Credit-Paket | pack de crédits | paquete de créditos | pacchetto di crediti | pacote de créditos | creditpakket | pakiet kredytów |
| Add credits | Credits hinzufügen | Ajouter des crédits | Añadir créditos | Aggiungi crediti | Adicionar créditos | Credits toevoegen | Dodaj kredyty |
| Raise the limit | Limit erhöhen | Relever le plafond | Subir el límite | Aumenta il limite | Aumentar o limite | Limiet verhogen | Podnieś limit |
| Ask an admin | Admin fragen | Demander à un administrateur | Pedir a un administrador | Chiedi a un amministratore | Pedir a um administrador | Een beheerder vragen | Poproś administratora |
| admin (a role on the account) | Admin | administrateur | administrador | amministratore | administrador | beheerder | administrator |
| editor (a role on the account) | Bearbeiter | éditeur | editor | editor | editor | bewerker | edytor |
| approver (a role on the account) | Genehmiger | approbateur | aprobador | approvatore | aprovador | goedkeurder | zatwierdzający |
| viewer (a role on the account) | Betrachter | lecteur | lector | visualizzatore | leitor | kijker | przeglądający |
| version | Version | version | versión | versione | versão | versie | wersja |
| trial (a version tried before it goes live) | Test | essai | prueba | prova | experiência | proef | próba |
| Make it live | Live schalten | Mettre en service | Activar | Rendi attiva | Pôr em produção | Live zetten | Uruchom na żywo |
| Try it | Ausprobieren | Essayer | Probar | Prova | Experimentar | Uitproberen | Wypróbuj |
| knock (a message from someone not paired) | Klopfen | demande d'accès | llamada | bussata | pedido de contacto | klop | pukanie |
| Pair (a sender with a door) | Koppeln | Associer | Vincular | Associa | Associar | Koppelen | Powiąż |
| paired senders (the people a door takes work from) | gekoppelte Absender | expéditeurs associés | remitentes vinculados | mittenti associati | remetentes associados | gekoppelde afzenders | powiązani nadawcy |
| Send again | Erneut senden | Renvoyer | Volver a enviar | Invia di nuovo | Enviar novamente | Opnieuw verzenden | Wyślij ponownie |
| Not sent | Nicht gesendet | Non envoyé | No enviado | Non inviato | Não enviado | Niet verzonden | Nie wysłano |
| Activity | Aktivität | Activité | Actividad | Attività | Atividade | Activiteit | Aktywność |
| Settings | Einstellungen | Réglages | Ajustes | Impostazioni | Definições | Instellingen | Ustawienia |
| Home | Start | Accueil | Inicio | Home | Início | Start | Start |
| security key (a USB key that approves) | Sicherheitsschlüssel | clé de sécurité | llave de seguridad | chiave di sicurezza | chave de segurança | beveiligingssleutel | klucz bezpieczeństwa |
| PIN (a security key's) | PIN | code PIN | PIN | PIN | PIN | pincode | PIN |

Halted and Stopped are different words in every language: a halt blocks a taf in every system it works
through, while a stopped request is one run that ended. A word not in this table is translated in the voice above, the same way every time it appears. A new
term that recurs is added here first, then used.

## Korean and Japanese (apart from the Terms table)

`ko` ships (tafs#1572), and `ja` with it since 21 Nov. tafs cloud speaks `ko` and `ja` too (`LANGUAGES` in `runtime/src/cloud/words`): its own lines, alerts and the
language Coach and the tafs are told. A recipe's words are kept per base language and none carries `ko` or `ja` yet, so a
Korean or Japanese phone reads them in English (`recipesFor`). Their terms are kept here, apart from the Terms table above, which KIFF checks by its digest, and
`test_strings` holds a shipped language to its table here until its column joins the Terms table.

**Small section headings** (iOS's and Android's `Eyebrow`) are monospaced and letter-spaced in the Latin languages. A heading
in Hangul, kana or Han keeps the plain face, untracked, since the monospaced face's wide spaces and the tracking pull its words
apart ("내   TAFS"): `Eyebrow.ideographic` on iOS, `ideographic`/`eyebrowStyle` on Android (`EyebrowTest`).

| Language | Code | Address | Notes |
|---|---|---|---|
| Korean | `ko` | 합니다 / 하세요 | Statements end in -습니다/-ㅂ니다, requests in -세요, as Korean business apps do. "나" for the person in labels ("내 코드"). A particle after a placeholder that may end in a consonant is written 을(를), 이(가), 은(는). |
| Japanese | `ja` | です / ます | Polite です/ます throughout, as Japanese business apps write; buttons are plain verbs or nouns (承認, 拒否, 今すぐ実行). No "you" (あなた) where the sentence reads without it. A Mac sentence may end with 。 as well as a stop. Numbers with their counter after the figure ({count}回, {count}件). |

| English | Korean |
|---|---|
| Needs you | 확인 필요 |
| Approve / Approve once | 승인 / 한 번 승인 |
| Refuse | 거절 |
| held | 보류됨 |
| Halt / Halted | 차단 / 차단됨 |
| Stopped (one run that ended) | 멈춤 |
| Pause / Paused | 일시 중지 / 일시 중지됨 |
| Resume | 재개 |
| Run now | 지금 실행 |
| run / scheduled run | 실행 / 예약 실행 |
| credits / spend limit | 크레딧 / 지출 한도 |
| door | 창구 |
| Record | 기록 |
| Connect apps / connection | 앱 연결 / 연결 |
| Sign in again / Sign in with KIFF | 다시 로그인 / KIFF로 로그인 |
| Try again | 다시 시도 |
| Not now / Later | 지금은 안 함 / 나중에 |
| Ask Coach | Coach에게 묻기 |
| Settings / Home / Activity | 설정 / 홈 / 활동 |
| Terms of Service / Privacy Policy | 서비스 약관 / 개인정보처리방침 |

| English | Japanese |
|---|---|
| Needs you | 要確認 |
| Approve / Approve once | 承認 / 1回だけ承認 |
| Refuse | 拒否 |
| held | 保留中 |
| Halt / Halted | 遮断 / 遮断済み |
| Stopped (one run that ended) | 中止 |
| Pause / Paused | 一時停止 / 一時停止中 |
| Resume | 再開 |
| Run now | 今すぐ実行 |
| credits | クレジット |
| door | 窓口 |
| Record | 記録 |
| Connect apps | アプリを接続 |
| Sign in again / Sign in with KIFF | もう一度サインイン / KIFFでサインイン |
| Try again | もう一度試す |
| Not now / Later | 今はしない / 後で |
| Ask Coach | Coachに質問 |
| Setup | セットアップ |
| Activity | アクティビティ |
| Terms / Privacy Policy | 利用規約 / プライバシーポリシー |

## Batch 1: Greek, Estonian, Latvian and Lithuanian

For the countries on sale without their language's app screens (GR, CY, EE, LV, LT). Estonian, Lithuanian and Latvian
ship, and kiff.dev serves their legal pages. Greek is in `all`, not `shipped`, until its review: no app shows it yet.
Until a shipped language's column joins the Terms table, `test_strings` holds it to its own table below. Where the legal drafts (kiff/cloud `web/legal-drafts/<l>`) decided a
word, the app uses the same one. The legal pages address people formally; the app's screens use the form below.

### Estonian (et)

Addressed as **sina**; buttons are sina imperatives. taf, Coach and Card take Estonian endings with no apostrophe (tafi,
tafsi, Cardi, Coachilt). A value (`{taf}`, `{device}`, `{vendor}`) never takes an ending, so a sentence stands it in the
nominative ("tafi {taf} Card", "see {device}").

| English | Estonian |
|---|---|
| Needs you | Vajab sind |
| Approve / Approve once | Kinnita / Kinnita üks kord |
| Refuse | Keeldu |
| held | kinni peetud |
| Halt / Halted | Peata / Peatatud |
| Stopped | Lõpetatud |
| Pause / Paused | Pausile / Pausil |
| Resume | Jätka |
| Revoke | Tühista |
| Record | Logi |
| Connect / connection | Ühenda / ühendus |
| Pair | Seo |
| Account | Konto |
| limit / spend limit | limiit / kululimiit |
| credits / credit pack | krediidid / krediidipakett |
| Sign in / Sign out | Logi sisse / Logi välja |
| Inbox | Postkast |
| Overview | Ülevaade |
| Calendar | Kalender |
| Setup | Seadistus |
| Usage | Kasutus |
| plan (billing) | pakett |
| Cancel / Continue | Loobu / Edasi |

### Greek (el)

Addressed as **εσύ**. The glossary's buttons are nouns (Έγκριση, Άρνηση, Παύση, Συνέχιση); other buttons are
imperatives (Δοκίμασε ξανά). In running text the names take an article: η Card / οι Cards (as the legal drafts write
it), ο Coach, η KIFF, το taf / τα tafs. Every device word is neuter (τηλέφωνο, tablet, Mac, PC), so "αυτό το {device}"
always agrees. A line that fills "από {who}" is written in the accusative.

| English | Greek |
|---|---|
| Needs you | Σε χρειάζεται |
| Approve / Approve once | Έγκριση / Έγκριση μία φορά |
| Refuse | Άρνηση |
| held | σε αναμονή |
| Halt / Halted | Σταμάτημα / Σταματημένο |
| Stopped | Διακόπηκε |
| Pause / Paused | Παύση / Σε παύση |
| Resume | Συνέχιση |
| Revoke | Ανάκληση |
| Record | Ιστορικό |
| Connect / connection | Σύνδεση / σύνδεση |
| Pair | Σύζευξη |
| Account | Λογαριασμός |
| limit / spend limit | όριο / όριο δαπανών |
| credits / credit pack | μονάδες / πακέτο μονάδων |
| Sign in / Sign out | Σύνδεση / Αποσύνδεση |
| Inbox | Εισερχόμενα |
| Overview | Επισκόπηση |
| Calendar | Ημερολόγιο |
| Setup (a taf's screen) | Διαμόρφωση |
| Usage / plan (the subscription) | Χρήση / πρόγραμμα |
| Coach's plan (its steps) | Σχέδιο |
| Decline | Απόρριψη |

### Lithuanian (lt)

Addressed as **jūs**; buttons are infinitives ("Patvirtinti", "Bandyti dar kartą"). taf, Coach, Card, KIFF, Setup and
tafs cloud are never declined, and "taf" is the same in the plural ("{count} taf"), as the legal drafts write it. A value
(`{taf}`, `{device}`, `{biometry}`) stands alone in the nominative ("taf {taf} Card", "Patvirtinti: {biometry}").

| English | Lithuanian |
|---|---|
| Needs you | Laukia jūsų |
| Approve / Approve once | Patvirtinti / Patvirtinti vieną kartą |
| Refuse | Atmesti |
| held | sulaikyta |
| Halt / Halted | Sustabdyti / Sustabdyta |
| Stopped | Nutraukta |
| Pause / Paused | Pristabdyti / Pristabdyta |
| Resume | Tęsti |
| Revoke | Atšaukti |
| Record | Žurnalas |
| Connect / connection | Prijungti / ryšys |
| Pair / Unpair | Susieti / Atsieti |
| Account | Paskyra |
| limit / spend limit | limitas / išlaidų limitas |
| credits / credit pack | kreditai / kreditų paketas |
| Sign in / Sign out | Prisijungti / Atsijungti |
| Inbox | Gautieji |
| Overview | Apžvalga |
| Calendar | Kalendorius |
| Setup | Setup (a name, never declined) |
| Usage | Naudojimas |
| plan | planas |
| door / knock | durys / beldimas |

### Latvian (lv)

Addressed as **tu**; buttons are infinitives ("Apstiprināt", "Atteikt"), sentences tu-imperatives ("Pieskaries"). taf,
Card and Coach take no endings, and the plural is "taf" ("{count} taf"), as the legal drafts write it; Setup stays a
name. A value stands alone ("šajā ierīcē ({device})", "Durvis: {door}"), and a line another line fills is written in
the case its sentence needs (`approval.biometry.*` accusative, `usage.month`… locative).

| English | Latvian |
|---|---|
| Needs you | Gaida tevi |
| Approve / Approve once | Apstiprināt / Apstiprināt vienreiz |
| Refuse | Atteikt |
| held | aizturēts |
| Halt / Halted | Apturēt / Apturēts |
| Stopped | Pārtraukts |
| Pause / Paused | Iepauzēt / Iepauzēts |
| Resume | Turpināt |
| Revoke | Atsaukt |
| Record | Žurnāls |
| Connect / connection | Pievienot / savienojums |
| Pair | Savienot pārī |
| Account | Konts |
| limit / spend limit | limits / tēriņu limits |
| credits / credit pack | kredīti / kredītu pakete |
| Sign in / Sign out | Pierakstīties / Izrakstīties |
| Inbox | Iesūtne |
| Overview | Pārskats |
| Calendar | Kalendārs |
| Usage | Lietojums |
| plan | plāns |
| door / knock | durvis / klauvējiens |

## Batch 2: Czech, Slovak, Slovenian, Hungarian, Romanian, Bulgarian and Croatian

For the countries on sale without their language's app screens (CZ, SK, SI, HU, RO, BG, HR). All seven ship, and kiff.dev
serves their legal pages (kiff/cloud#77). `test_strings` holds each to its own table below until its column joins the
Terms table. Where the legal pages decided a word, the app uses the same one.

### Czech (cs)

Addressed as **vy** (plural verb forms, as Czech software does); buttons are infinitives ("Schválit", "Zkusit znovu"). taf
declines as the legal pages write it (taf, tafu, tafy, tafů); Card, Coach, KIFF and Setup are names and stay as they are
("jeho Card", "Card tafu {taf}"). A device word is masculine in every case (telefon, tablet, počítač, Mac), so "tento
{device}" agrees; elsewhere a value stands alone ("na tomto zařízení ({device})"). A door is "dveře", plural only:
"dveře {door}". The plural forms are one, few (2–4) and other.

| English | Czech |
|---|---|
| Needs you | Čeká na vás |
| Approve / Approve once | Schválit / Schválit jednou |
| Refuse | Odmítnout |
| held | zadrženo |
| Halt / Halted | Zastavit / Zastaveno |
| Stopped | Ukončeno |
| Pause / Paused | Pozastavit / Pozastaveno |
| Resume | Obnovit |
| Revoke | Odvolat |
| Record | Záznam |
| Connect / connection | Připojit / připojení |
| Pair / Unpair | Spárovat / Zrušit spárování |
| Account | Účet |
| limit / spend limit | limit / limit útraty |
| credits / credit pack | kredity / balíček kreditů |
| Sign in / Sign out | Přihlásit se / Odhlásit se |
| Inbox | Doručené (v Doručených) |
| Overview | Přehled |
| Calendar | Kalendář |
| Usage | Využití |
| plan (billing) | tarif |
| Cancel / Continue | Zrušit / Pokračovat |

### Slovak (sk)

Addressed as **vy**, as Czech is; buttons are infinitives ("Schváliť", "Skúsiť znova"). taf declines (taf, tafu, tafy,
tafov); Card, Coach, KIFF and Setup stay as names. Every device word is masculine (telefón, tablet, počítač, Mac), so
"tento {device}" agrees; elsewhere a value stands alone ("na tomto zariadení ({device})"). A door is "dvere", plural
only. The plural forms are one, few (2–4) and other.

| English | Slovak |
|---|---|
| Needs you | Čaká na vás |
| Approve / Approve once | Schváliť / Schváliť raz |
| Refuse | Odmietnuť |
| held | pozdržané |
| Halt / Halted | Zastaviť / Zastavené |
| Stopped | Ukončené |
| Pause / Paused | Pozastaviť / Pozastavené |
| Resume | Obnoviť |
| Revoke | Odvolať |
| Record | Záznam |
| Connect / connection | Pripojiť / pripojenie |
| Pair / Unpair | Spárovať / Zrušiť spárovanie |
| Account | Účet |
| limit / spend limit | limit / limit výdavkov |
| credits / credit pack | kredity / balík kreditov |
| Sign in / Sign out | Prihlásiť sa / Odhlásiť sa |
| Inbox | Doručené (v Doručených) |
| chat | chat (not "čet") |
| Overview | Prehľad |
| Calendar | Kalendár |
| Usage | Využitie |
| plan (billing) | tarif |
| Cancel / Continue | Zrušiť / Pokračovať |

### Slovenian (sl)

Addressed as **vi**; buttons are the short imperative Slovenian software uses ("Odobri", "Zavrni", "Prekliči"). taf
declines with the dual (taf, tafa, tafi, tafov); Card, Coach, KIFF and Setup stay as names. Every device word is
masculine (telefon, tablični računalnik, računalnik, Mac), so "ta {device}" agrees; elsewhere a value stands alone ("na
tej napravi ({device})"). A door is "vrata", plural only. iOS and macOS have no Slovenian, so their menu paths stay as
the system shows them, in English; Windows' are in Slovenian. The plural forms are one, two, few (3–4) and other.

| English | Slovenian |
|---|---|
| Needs you | Čaka na vas |
| Approve / Approve once | Odobri / Odobri enkrat |
| Refuse | Zavrni |
| held | zadržano |
| Halt / Halted | Ustavi / Ustavljeno |
| Stopped | Končano |
| Pause / Paused | Začasno ustavi / Začasno ustavljeno |
| Resume | Nadaljuj |
| Revoke | Odvzemi |
| Record | Dnevnik |
| Connect / connection | Poveži / povezava |
| Pair / Unpair | Seznani / Prekini seznanitev |
| Account | Račun |
| limit / spend limit | omejitev / omejitev porabe |
| credits / credit pack | krediti / paket kreditov |
| Sign in / Sign out | Prijava / Odjava |
| Inbox | Nabiralnik |
| Overview | Pregled |
| Calendar | Koledar |
| Usage | Poraba |
| plan (billing) | paket |
| Cancel / Continue | Prekliči / Naprej |

### Hungarian (hu)

Addressed as **te**, the way Hungarian apps speak to people; buttons are nouns ("Jóváhagyás", "Elutasítás", "Mégse",
"Tovább"). taf takes the suffixes a back-vowel word does (tafot, tafok, tafja); Card, Coach, KIFF and Setup stay as
names. A value that would need a case ending stands apart, after a colon or in brackets ("ezen az eszközön
({device})", "Megnyitja: {taf}"), and "a(z)" goes before a value that may start with a vowel. A noun after a number stays
singular ("3 hívás"), so both plural forms read the same. Apple and Windows menu paths are the Hungarian ones
("Beállítások → Face ID és jelkód", "Gépház → Fiókok → Bejelentkezési lehetőségek"). The plural forms are one and other.

| English | Hungarian |
|---|---|
| Needs you | Rád vár |
| Approve / Approve once | Jóváhagyás / Egyszeri jóváhagyás |
| Refuse | Elutasítás |
| held | visszatartva |
| Halt / Halted | Leállítás / Leállítva |
| Stopped | Leállt |
| Pause / Paused | Szüneteltetés / Szüneteltetve |
| Resume | Folytatás |
| Revoke | Visszavonás |
| Record | Napló |
| Connect / connection | Csatlakoztatás / kapcsolat |
| Pair / Unpair | Párosítás / Párosítás megszüntetése |
| Account | Fiók |
| limit / spend limit | limit / költési limit |
| credits / credit pack | kreditek / kreditcsomag |
| passkey | jelkulcs |
| Sign in / Sign out | Bejelentkezés / Kijelentkezés |
| Inbox | Bejövő |
| Overview | Áttekintés |
| Calendar | Naptár |
| Usage | Használat |
| plan (billing) | csomag |
| Cancel / Continue | Mégse / Tovább |

### Romanian (ro)

Addressed as **dumneavoastră** (dvs.), as Romanian software does; buttons are the polite imperative ("Aprobați",
"Refuzați", "Anulați", "Continuați"). taf is neuter (taful, tafuri, tafurile); Card, Coach, KIFF and Setup stay as names
and take the article directly (Cardul). A device word changes gender (telefon, tabletă), so a value stands
in brackets ("pe acest dispozitiv ({device})"). Twenty and up take "de" ("20 de apeluri"). Apple's menu paths are the
Romanian ones ("Configurări", "Configurări sistem"); Windows' are "Setări". The plural forms are one, few (0, 2–19) and
other (20 and up).

| English | Romanian |
|---|---|
| Needs you | Vă așteaptă |
| Approve / Approve once | Aprobați / Aprobați o dată |
| Refuse | Refuzați |
| held | reținut |
| Halt / Halted | Opriți / Oprit |
| Stopped | Încheiat |
| Pause / Paused | Întrerupeți / Întrerupt |
| Resume | Reluați |
| Revoke | Revocați |
| Record | Jurnal |
| Connect / connection | Conectați / conexiune |
| Pair / Unpair | Asociați / Disociați |
| Account | Cont |
| limit / spend limit | limită / limită de cheltuieli |
| credits / credit pack | credite / pachet de credite |
| passkey | cheie de acces |
| Sign in / Sign out | Conectați-vă / Deconectați-vă |
| Inbox | Mesajele primite |
| Overview | Prezentare generală |
| Calendar | Calendar |
| Usage | Utilizare |
| plan (billing) | plan |
| Cancel / Continue | Anulați / Continuați |

### Bulgarian (bg)

Addressed as **вие**; buttons are nouns ("Одобряване", "Отказ", "Продължаване"). taf, Card, Coach, KIFF and Setup stay
as names in Latin letters, as Greek writes them: "taf" is masculine (plural "tafs") and Card feminine ("неговата
Card"), and neither takes the article. Every device word is masculine (телефон, таблет, компютър, Mac), so "този
{device}" always agrees. Apple's menu paths are the Bulgarian ones ("Настройки", "Системни настройки"); Windows' are
"Настройки". The plural forms are one and other.

| English | Bulgarian |
|---|---|
| Needs you | Чака ви |
| Approve / Approve once | Одобряване / Еднократно одобрение |
| Refuse | Отказ |
| held | задържана (заявка) |
| call (an action a taf asks for) | заявка |
| Halt / Halted | Спиране / Спряно |
| Stopped | Приключил |
| Pause / Paused | Пауза / На пауза |
| Resume | Възобновяване |
| Revoke | Оттегляне |
| Record | Дневник |
| Connect / connection | Свързване / връзка |
| Pair / Unpair | Сдвояване / Премахване на сдвояването |
| Account | Акаунт |
| limit / spend limit | лимит / лимит на разходите |
| credits / credit pack | кредити / пакет кредити |
| passkey | ключ за достъп |
| Sign in / Sign out | Вход / Изход |
| Inbox | Входящи |
| Overview | Преглед |
| Calendar | Календар |
| Usage | Използване |
| plan (billing) | план |
| Cancel / Continue | Отказ / Продължаване |

### Croatian (hr)

Addressed as **vi** (plural verb forms, as the legal pages write it); buttons are polite imperatives ("Odobrite", "Odbijte",
"Nastavite"). taf declines as the legal pages write it (taf, tafa, tafu, tafovi, tafova) and stays animate ("zaustaviti
tafa"); Card is masculine and declines too ("Card", "Carda", "Cardovi", "Cardova"); Coach, KIFF and Setup are names and
stay as they are. A value is a name and doesn't decline, so a line that would need a case words around it: "taf {taf}",
"za: {name}", "ovaj uređaj ({device})". A device word is masculine in every case (telefon, tablet, PC, Mac), so "ovaj
{device}" agrees. A door is "vrata", plural only. A call is "poziv", a request "zahtjev", a held call "zadržan poziv". Apple's
menu paths are the Croatian ones ("Postavke", "Postavke sustava", "Privatnost i sigurnost"). The plural forms are one, few
(2–4) and other.

| English | Croatian |
|---|---|
| Needs you | Čeka vas |
| Approve / Approve once | Odobrite / Odobrite jednom |
| Refuse | Odbijte |
| held | zadržan |
| Halt / Halted | Blokirajte / Blokirano |
| Stopped | Zaustavljeno |
| Pause / Paused | Pauzirajte / Pauzirano |
| Resume | Nastavite |
| Revoke | Opozovite |
| Record | Zapisnik |
| Connect / connection | Povežite / veza |
| Pair / Unpair | Uparite / Prekinite uparivanje |
| Account | Račun |
| limit / spend limit | ograničenje / ograničenje potrošnje |
| credits / credit pack | krediti / paket kredita |
| passkey | pristupni ključ |
| security key | sigurnosni ključ |
| Sign in / Sign out | Prijava / Odjava |
| Inbox | Pristigle |
| Overview | Pregled |
| Calendar | Kalendar |
| Usage | Potrošnja |
| brief | sažetak |
| receipt | potvrda |
| Cancel / Continue | Odustanite / Nastavite |

## Batch 3: Arabic (being written)

Arabic is the first right-to-left language the apps ship (the owner said yes, 21 Nov). Its catalog is in `all`, not `shipped`,
until its review and the right-to-left PR that carries it ships.

### Arabic (ar)

Modern Standard Arabic. Addressed to one reader, masculine as Arabic software writes it ("يمكنك", "اختر"); buttons are
verbal nouns ("الموافقة", "الرفض", "المتابعة"), as Apple's Arabic does. taf, Card, Coach, KIFF and Setup are names and stay in
Latin letters ("الـ taf", "الـ Card"), as Bulgarian and Greek write them. Numbers stay in Western digits (1, 2, 3); the
choice of Arabic-Indic digits is a product decision still to make. Quotes are « » for a line's own, and a name a taf
carries is isolated (`Words.isolated`). A door is "باب" (plural "أبواب"), a call "استدعاء", a request "طلب", a held call
"استدعاء معلّق", a knock "طرق".

**Plurals have six forms:** zero (0), one (1), two (2), few (3–10), many (11–99) and other (100 and up, and decimals).
Every count line in `ar/` writes all six. A line may leave the number out in `zero`, `one` and `two` ("يوم واحد",
"يومان"), as the words say it.

**Direction:**
- A menu path in a line that English writes with "→" writes "←" in Arabic. "‹" and "›" are mirrored by the text
  direction itself, so they are written as English writes them. `test_strings` fails an Arabic line with "→".
- The two dials (the Card knob and the door dial) turn the same way in Arabic. They are physical objects drawn with screen
  angles, not a leading or trailing edge, so a swipe or drag clockwise is clockwise in either direction. Nothing in their
  code mirrors.
- **A reference number** ("#5098") written in a line is wrapped in U+2066 … U+2069 (a left-to-right isolate). After an
  Arabic letter the digits become Arabic-script digits (the bidi rule W2), which leaves the "#" outside their run and shows
  "5098#". `test_strings` checks it.
- **Figures are Western digits (0–9), and dates are Gregorian** (legal-i18n, 22 Nov): the legal pages write "30 days" and
  "16" so, and their tests require every figure to match the English page. The iOS app formats with the locale
  `ar-u-ca-gregory-nu-latn` for Arabic (`IOS_LOCALE` in `generate.py`, `Words.locale(for:)`), so a time, a count and a date
  don't turn into Arabic-script digits or the Hijri calendar.
- **Captions** that the app sets in a monospaced face with letter-spacing (the Needs you "time left", the section eyebrows)
  are set in the plain face with no spacing in Arabic, as in Korean, Japanese and Chinese: spacing cuts Arabic's joined
  letters apart (`Eyebrow.ideographic`, `ScaledFont.design`).

| English | Arabic |
|---|---|
| Needs you | بانتظارك |
| Approve / Approve once | الموافقة / الموافقة مرة واحدة |
| Refuse | الرفض |
| held | معلّق |
| Halt / Halted | تجميد / مجمّد |
| Stopped | متوقف |
| Pause / Paused | إيقاف مؤقت / متوقف مؤقتًا |
| Resume | استئناف |
| Revoke | إلغاء |
| Record | السجل |
| Connect / connection | ربط / اتصال |
| Pair / Unpair | إقران / إلغاء الإقران |
| Account | الحساب |
| limit / spend limit | الحد / حد الإنفاق |
| credits / credit pack | الأرصدة / حزمة أرصدة |
| passkey | مفتاح المرور |
| security key | مفتاح الأمان |
| Sign in / Sign out | تسجيل الدخول / تسجيل الخروج |
| Inbox | الوارد |
| Overview | نظرة عامة |
| Calendar | التقويم |
| Setup (the agent's name / a screen title) | Setup in Latin letters / الإعداد (`setup.title`, `taf.section.setup`) |
| Usage | الاستخدام |
| brief | الملخص |
| receipt | الإيصال |
| Cancel / Continue | إلغاء / متابعة |

## Batch 3: Turkish, Indonesian, Vietnamese, Thai and Traditional Chinese

For the countries whose language the apps didn't have (TR, ID, VN, TH, TW). All five ship, and kiff.dev serves their legal
pages (kiff/cloud#80; Traditional Chinese's are under `/zh/`). `test_strings` holds each to its own table below until its
column joins the Terms table. They are first drafts: no native speaker has read them, and the Indonesian word for the
keyboard setting ("Papan Ketik") in the dictation paths is not confirmed from Apple's own pages. Arabic is its own piece
(tafs#1697), with the right-to-left rules.

### Turkish (tr)

Addressed as **siz** (the polite plural, as Turkish business software writes it); buttons are plain imperatives ("Onayla",
"Reddet", "Devam et"), and sentences address the reader with -(y)In forms ("Tekrar deneyin"). taf, Card, Coach, KIFF and
Setup are names and stay as they are. A value is a name and doesn't take Turkish's suffixes, so a line that would need
one words around it: "{taf} taf'ı", "şu Card: {owner}", "bu {device} cihazı". A door is "kapı", a call "çağrı", a request
"istek", a held call "tutulan çağrı", a receipt "makbuz", a brief "özet". Apple's menu paths are the Turkish ones ("Ayarlar",
"Sistem Ayarları", "Gizlilik ve Güvenlik"). The plural forms are one and other, and a number takes the singular noun
("5 görev").

| English | Turkish |
|---|---|
| Needs you | Sizi bekliyor |
| Approve / Approve once | Onayla / Bir kez onayla |
| Refuse | Reddet |
| held | tutuldu |
| Halt / Halted | Dondur / Donduruldu |
| Stopped | Durduruldu |
| Pause / Paused | Duraklat / Duraklatıldı |
| Resume | Sürdür |
| Revoke | İptal et |
| Record | Kayıt |
| Connect / connection | Bağla / bağlantı |
| Pair / Unpair | Eşleştir / Eşleştirmeyi kaldır |
| Account | Hesap |
| limit / spend limit | limit / harcama limiti |
| credits / credit pack | krediler / kredi paketi |
| passkey | geçiş anahtarı |
| security key | güvenlik anahtarı |
| Sign in / Sign out | Oturum aç / Oturumu kapat |
| Inbox | Gelen kutusu |
| Overview | Genel bakış |
| Calendar | Takvim |
| Usage | Kullanım |
| Cancel / Continue | Vazgeç / Devam et |

### Indonesian (id)

Addressed as **Anda**; buttons are plain imperatives ("Setujui", "Tolak", "Lanjutkan"). taf, Card, Coach, KIFF and Setup are
names and stay as they are. Indonesian doesn't decline or conjugate for a name, so "{taf}" stands where English has it.
A door is "pintu", a call "panggilan", a request "permintaan", a held call "panggilan yang ditahan", a receipt "tanda
terima", a brief "ringkasan". Apple's menu paths are the Indonesian ones ("Pengaturan", "Pengaturan Sistem", "Privasi &
Keamanan"). There is one plural form (other): a number takes the same noun ("5 tugas"). On Android the lines are in
`values-id` and again in `values-in`, Indonesian's old code: a library the app builds with ships `values-in`, and Android
took it over ours (`android.py`'s `ALSO`). `AppLocale` reads "in" as "id", as Android 11 to 13 name it.

| English | Indonesian |
|---|---|
| Needs you | Menunggu Anda |
| Approve / Approve once | Setujui / Setujui sekali |
| Refuse | Tolak |
| held | ditahan |
| Halt / Halted | Bekukan / Dibekukan |
| Stopped | Dihentikan |
| Pause / Paused | Jeda / Dijeda |
| Resume | Lanjutkan |
| Revoke | Cabut |
| Record | Catatan |
| Connect / connection | Hubungkan / koneksi |
| Pair / Unpair | Pasangkan / Lepas pasangan |
| Account | Akun |
| limit / spend limit | batas / batas pengeluaran |
| credits / credit pack | kredit / paket kredit |
| passkey | kunci sandi |
| security key | kunci keamanan |
| Sign in / Sign out | Masuk / Keluar |
| Inbox | Kotak Masuk |
| Overview | Ikhtisar |
| Calendar | Kalender |
| Usage | Penggunaan |
| Cancel / Continue | Batal / Lanjutkan |

### Vietnamese (vi)

Addressed as **bạn**; buttons are plain verbs ("Phê duyệt", "Từ chối", "Tiếp tục") and sentences are plain statements with no
sentence-final particles. taf, Card, Coach, KIFF and Setup are names and stay as they are. Vietnamese doesn't decline or
conjugate for a name, so "{taf}" stands where English has it, and a possessive is "của {name}" ("Card của {owner}"). A door is
"cửa", a call "lượt gọi", a request "yêu cầu", a held call "lượt gọi đang giữ", a receipt "biên nhận", a brief "bản tóm tắt",
the dial "núm xoay". Apple's menu paths are the Vietnamese ones ("Cài đặt", "Cài đặt hệ thống", "Quyền riêng tư & Bảo mật").
There is one plural form (other): a number takes the same noun ("5 nhiệm vụ").

| English | Vietnamese |
|---|---|
| Needs you | Đang chờ bạn |
| Approve / Approve once | Phê duyệt / Phê duyệt một lần |
| Refuse | Từ chối |
| held | đang giữ |
| Halt / Halted | Đóng băng / Đã đóng băng |
| Stopped | Đã dừng |
| Pause / Paused | Tạm dừng / Đã tạm dừng |
| Resume | Tiếp tục |
| Revoke | Thu hồi |
| Record | Sổ ghi |
| Connect / connection | Kết nối / kết nối |
| Pair / Unpair | Ghép đôi / Hủy ghép đôi |
| Account | Tài khoản |
| limit / spend limit | giới hạn / hạn mức chi tiêu |
| credits / credit pack | tín dụng / gói tín dụng |
| passkey | khóa truy cập |
| security key | khóa bảo mật |
| Sign in / Sign out | Đăng nhập / Đăng xuất |
| Inbox | Hộp thư |
| Overview | Tổng quan |
| Calendar | Lịch |
| Usage | Mức sử dụng |
| Cancel / Continue | Hủy / Tiếp tục |

### Thai (th)

Polite but neutral: **คุณ** for "you", and no sentence-final particles (ครับ/ค่ะ), so a line reads the same to every reader.
Buttons are plain verbs ("อนุมัติ", "ปฏิเสธ", "ดำเนินการต่อ"). taf, Card, Coach, KIFF and Setup are names and stay in Latin
letters. Thai has no plural and doesn't decline, so a number takes a classifier or the bare noun ("5 งาน", "3 ครั้ง"), and a
possessive is "ของ" ("Card ของ {owner}"). Words run together with no spaces, so a value that sits beside Thai text gets its
space from the line, and a line that joins a value to a Thai word writes them without one ("{device}เครื่องนี้"). A
`*_mac` sentence ends with a stop, as the test needs, though Thai ordinarily doesn't write one. A door is "ประตู", a call
"การเรียก", a request "คำขอ", a held call "การเรียกที่พักไว้", a receipt "ใบเสร็จ", a brief "สรุป", the dial "ปุ่มหมุน".
Apple's menu paths are the Thai ones ("การตั้งค่า", "การตั้งค่าระบบ", "ความเป็นส่วนตัวและความปลอดภัย"). There is one plural
form (other).

| English | Thai |
|---|---|
| Needs you | รอคุณอยู่ |
| Approve / Approve once | อนุมัติ / อนุมัติครั้งเดียว |
| Refuse | ปฏิเสธ |
| held | พักไว้ |
| Halt / Halted | ระงับ / ระงับแล้ว |
| Stopped | หยุดแล้ว |
| Pause / Paused | หยุดชั่วคราว / หยุดชั่วคราวแล้ว |
| Resume | ทำต่อ |
| Revoke | เพิกถอน |
| Record | บันทึก |
| Connect / connection | เชื่อมต่อ / การเชื่อมต่อ |
| Pair / Unpair | จับคู่ / ยกเลิกการจับคู่ |
| Account | บัญชี |
| limit / spend limit | ขีดจำกัด / ขีดจำกัดการใช้จ่าย |
| credits / credit pack | เครดิต / แพ็กเครดิต |
| passkey | พาสคีย์ |
| security key | กุญแจความปลอดภัย |
| Sign in / Sign out | เข้าสู่ระบบ / ออกจากระบบ |
| Inbox | กล่องข้อความ |
| Overview | ภาพรวม |
| Calendar | ปฏิทิน |
| Usage | การใช้งาน |
| Cancel / Continue | ยกเลิก / ดำเนินการต่อ |

### Traditional Chinese (zh-Hant)

Traditional characters, in the Taiwan register Apple's own Traditional Chinese uses ("設定", "系統設定", "隱私權與安全性"; on macOS 12
"系統偏好設定", "安全性與隱私權"). Addressed as **您**, the polite form. Buttons are plain verbs ("核准", "拒絕", "繼續"). taf, Card,
Coach, KIFF and Setup are names and stay in Latin letters. Chinese has no plural and doesn't decline, so a number takes a
measure word ("5 個 taf", "3 次呼叫") and a possessive is "的" ("{owner}的 Card"). Punctuation is full-width ("，。：？") except
inside placeholders and menu paths ("›"). A `*_mac` sentence ends with "。" and names macOS 12's path as the test needs.
A door is "門", a call "呼叫", a request "請求", a held call "已保留的呼叫", a receipt "收據", a brief "摘要", the dial "旋鈕".
There is one plural form (other). The code is `zh-Hant`: the plural table keys on `zh`, and the Android resource folder
for it is `values-b+zh+Hant`, and the same lines again in `values-zh-rTW`, `values-zh-rHK` and `values-zh-rMO`: a library
the app builds with ships `zh-rTW` and `zh-rHK`, and Android took those over ours (`android.py`'s `ALSO`).

| English | Traditional Chinese |
|---|---|
| Needs you | 等您處理 |
| Approve / Approve once | 核准 / 核准一次 |
| Refuse | 拒絕 |
| held | 已保留 |
| Halt / Halted | 凍結 / 已凍結 |
| Stopped | 已停止 |
| Pause / Paused | 暫停 / 已暫停 |
| Resume | 繼續 |
| Revoke | 撤銷 |
| Record | 紀錄 |
| Connect / connection | 連接 / 連線 |
| Pair / Unpair | 配對 / 取消配對 |
| Account | 帳號 |
| limit / spend limit | 上限 / 支出上限 |
| credits / credit pack | 點數 / 點數包 |
| passkey | 密碼金鑰 |
| security key | 安全金鑰 |
| Sign in / Sign out | 登入 / 登出 |
| Inbox | 收件匣 |
| Overview | 總覽 |
| Calendar | 行事曆 |
| Usage | 用量 |
| Cancel / Continue | 取消 / 繼續 |

## The AI disclosure

What a taf sends outside the company starts with its disclosure line, in the message's language
(`disclosureLines` in `runtime/src/jobs.ts`, mirrored by kiff/agents). It's in English, Spanish, French,
German, Portuguese and Italian today. **Dutch and Polish come with the language work:** "Geschreven door
een AI-agent voor <writes_for>." and "Napisane przez agenta AI dla <writes_for>.", each with the words
that tell a message's language, in tafs and kiff/agents together.

## Regional variants

A **regional variant** is a language's lines for one region where some read differently: `pt-BR` beside `pt`
(`app/strings/languages.json`'s `variants`, `"pt-BR": "pt"`). Its folder, `app/strings/pt-BR/`, holds **only the
lines that differ** from its base, one file per feature as every language's. `generate.py` (`load_lines`) reads the
variant as its base's lines with its own on top, so a key it doesn't change reads as the base's and it never falls
behind. A count takes its base's plural forms.

**A variant is declared, not shipped,** until it's in `shipped`. While it isn't, nothing generated changes. Once it is:
iOS's catalog gets a `pt-BR` localization and `Words.languages` lists it; Android gets `values-pt-rBR` and a `pt-BR` entry
in `locales_config.xml` (tested in `test_strings.py`). **Before shipping one, each app is checked where it maps a
language** to kiff.dev's pages and KIFF's (`KIFFClient`'s app language, `AppSignIn.pageLanguages`, the translated-pages
set in `Components.swift`, and Android's twins): a variant reads its base's pages, as `pt-BR` reads `/pt/`.

**pt-BR** (Wave 1, Brazil) ships: Brazilian forms over `pt`'s, "você" throughout, "celular", "tela", "arquivo", "senha",
"Entrar" for signing in, "conexão" for a connection, and the system's own names on each platform (iOS "Ajustes", macOS
"Ajustes do Sistema", Windows and Android "Configurações"). A line that states a legal rule (the terms, the age, a
consent) isn't in it: it reads as `pt`'s until the language reviewer writes it. iOS picks pt-BR for a phone set to
Brazilian Portuguese and keeps pt for Portugal's (`AppLanguage.deviceLanguage`); Android picks `values-pt-rBR` itself.
Both apps send `Accept-Language: pt-BR, pt;q=0.9, en;q=0.8` from a pt-BR phone: tafs cloud, planque and KIFF read `pt`
today (their words stay `pt`'s), and can tell Brazil apart once they hold pt-BR words of their own.
No "a + infinitive" ("A verificar…" is "Verificando…") and no enclitics where Brazil puts the pronoun first or says
"você" ("pede-lhe" is "pede que você"). Card is masculine ("o Card", "pelo seu Card"). Its glossary, where it
differs from Portuguese's (legal-i18n's read of tafs#1629):

| English | pt-BR |
|---|---|
| Needs you | Precisa de você |
| Record | Registro |
| Connect apps | Conectar apps |
| connection | conexão |
| Sign in again | Entrar novamente |
| Disconnect | Desconectar |
| Settings | Configurações |
| Plan & usage | Plano e uso |
| Working on it | Em andamento |
| Your step | Seu passo |
| Your brief | Seu resumo |
| Skip it | Pular |
| Approve once | Aprovar uma vez |
| Refuse | Recusar |
| Halt | Bloquear |

Also from that read, in running text: **app** is masculine ("um app", "os seus apps", "Apps conectados"); an app
signed in before its approval is **"Login feito"**, never "Conectado", which is a finished connection; a sign-out that
happened is **"Sessão encerrada"** ("Você saiu" for the person's own), and signed in to the account is **"conectado à sua
conta"**; **"aguardando"** for waiting, **"rodar"** for a taf's run, **"pedido"** for an order (a parcel is "encomenda"),
**"acompanhamento"** for a follow-up, **"assinatura"**, **"faturamento"**, **"Baixar"**, and the app's own Account
screen is **"Conta"**.

## How it's built

- **tafs cloud:** a catalog per language in `runtime/src/cloud/words/`, English the source (`en.ts`),
  each full language typed against it, and a test that fails on a key missing from any of them, an empty
  line, or a line left in English. It holds every line tafs cloud writes for people: a taf's work lines,
  the home list's line, the balance and credits words, refusal words, push titles and bodies, door
  alerts and a door's page, where a result went, a request's steps, a taf's Activity, the dial, the
  calendar, a trial's badge, the runs page, a steps card's line, Coach's tools as the phone connects
  them, and why Coach stopped driving the app. A run code tafs cloud knows (`ai_unavailable`) is
  worded from the catalog; one it doesn't know passes kiff/agents' words through. `error` fields stay
  English, for developers; the `words` beside them are the person's. A vendor's or provider's own words
  (why a reply wasn't sent, KIFF's tool titles) pass as they came.
- **The languages added since in tafs cloud, partial until written** (PM, 22 Nov): the full languages (`FULL_LANGUAGES`:
  the eight, pt-BR, ko, ja) have every key. Batch 2's ten and batch 3's five (`PARTIAL_LANGUAGES`) may be partial: each line
  written replaces English's, every other reads English, key by key. Three guards:
  - **The most-seen lines first** (`PRIORITY_PREFIXES`): Home's (`home_`), push titles (`push_`), Activity's details
    (`act_`) and the approval card's (`approval_`). A language with any other line has all of those (a test checks it).
  - **The target is 100%.** `words.test.ts` prints each language's coverage (`npx vitest run test/words.test.ts --silent=false`;
    vitest hides a passing test's output otherwise); the gap closes batch by batch, and no
    language stays partial for good.
  - **The tracker** marks a country's app screens as translated for the apps' own words only, with tafs cloud's lines
    noted apart until they reach 100% (for the "b or c" countries: EE, LV, LT, SK, SI).

  Before a line is written, a partial language is still the person's language for everything else tafs cloud does:
  Coach and the tafs are told it ("reads Turkish (tr)"), dates follow it, and the legal links open its pages (`pagePath`:
  Traditional Chinese's are under `/zh`). `languageFrom` reads Traditional Chinese by its script or region (`zh-Hant`,
  `zh-TW`, `zh-HK`, `zh-MO`, never Simplified) and Indonesian's old code `in` as `id`.
- **Brazilian Portuguese in tafs cloud:** `pt-BR.ts` is `pt`'s catalog with the lines that read differently in
  Brazil on top, and only those: "você" throughout ("Precisa de você", "Seu resumo"), "conexão" and "Conectar",
  "Entrar" and "login" for signing in, "celular", "tela", "registro", and the gerund for "está a fazer"
  ("Verificando…"). A key it doesn't change reads as `pt`'s, so it never falls behind. A line that states a legal
  rule (the AI consent, the terms and privacy line) isn't in it: it reads as `pt`'s until the language reviewer
  writes it (a test pins that). `languageFrom` keeps `pt-BR` when the app asks for it (both apps send
  `pt-BR, pt;q=0.9, en;q=0.8` from a Brazilian phone); every other region reads its base, `pt-PT` as `pt`. Counts
  follow Brazil's rule, where 0 is singular. Coach and the tafs are told "Brazilian Portuguese (pt-BR)". What's
  kept per base language only reads the base's: a recipe's words, and kiff.dev's Terms and Privacy (`/pt/`).
- **Which language, with a request:** the app's own resolved language, sent as `Accept-Language`
  (above). tafs cloud passes it on, resolved to one of the eight (a regional variant with its base after it,
  `pt-BR, pt;q=0.9`), on every call it makes to KIFF for that request, so KIFF's words in tafs cloud's answers match; an app that sends none leaves KIFF's
  own choice. **Without one** (a push alert, a door's alert, a delivery note), each phone's: the language
  its app sent when it signed up for alerts (`POST /v1/push`), kept with it. An alert is worded once per
  language and each phone gets its own.
- **Coach and every taf** are told the reply rule with each message, English included: a
  `[tafs language]` line before the owner's words, after tafs' own opening lines, so the thread
  still shows only the owner's words. **The rule:** answer in the language of the person's message;
  if that isn't clear (a short line, a number, a name), in the app's language; never in a data
  source's (a sheet, an email, a page, an account's locale: 18 Nov, an English message to a taf on a
  French Google account came back in French). A message a taf sends a customer keeps the customer's
  language. Coach also writes what the app shows (questions, cards, a version's note, a taf's
  summary) in the app's language. A line the person types that starts as one of tafs' own (`[tafs language]`, `[tafs time zone]`) is
  dropped from their message, so only tafs cloud's rule reaches the taf. A taf's message sent again under its `client_message_id` matches
  kiff/agents' digest with the line or without it (one sent before the line). For an account outside UTC, a
  `[tafs time zone]` line beside it says the account's zone and the time there now, and that every
  time Coach writes (a cron, an at, a time in a message) is in it, as the owner says it: kiff/agents'
  own date line is in UTC, and a cron converted to it ran an hour early.
- **A taf** follows the person writing in its chat. Its reports and alerts to its owner go in the
  account's language, as tafs cloud keeps it from the person's Coach chats: its instructions say so
  from its build, or its next Upgrade once the language changes. A message to a customer keeps the
  customer's language, as the disclosure does. English adds nothing to either.
- **Stays English on purpose:** the words a delivery note opens Coach with (`coach_words`), which
  tafs cloud reads to start Coach's delivery skill; Coach still answers in the person's language. A
  version's note, and the titles kept in the account's event log, are written once when they happen
  and stay as written. What tafs cloud keeps of a language: each phone's, with the phone, and the
  account's, from Coach chats, for a quarter.
- **Held to this glossary:** a test reads the Terms table above, as it is, and fails when a catalog line whose
  English has a term doesn't have that language's word for it (`runtime/test/words.test.ts`). A label (a
  capitalized term) matches as written, a noun in any case, the longest term first; the other grammatical
  forms a word takes are listed in the test, one by one. `docs/glossary.sha256` is the digest of the table's
  rows: KIFF keeps a copy of the glossary and checks it against the digest, so a change here fails there until
  its copy follows. Change the table and the digest together.
- **KIFF:** one catalog per language (`internal/words`), the same test.
- **The apps' own words have one source,** [app/strings](../app/strings/README.md): each line once, by a key
  named for what it says, in each language. A generator writes both apps' files from it (**Android:**
  `strings.xml` with a `values-xx` set per language; **iPhone and iPad:** a String Catalog), with a typed
  accessor per key, so a key that isn't there doesn't compile. Nobody edits a generated file by hand, and CI
  fails when one is out of date.
