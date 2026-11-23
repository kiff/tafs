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

Arabic is the first right-to-left language the apps ship (the owner said yes, 21 Nov). Its right-to-left code shipped with
tafs#1697; the owner's rule of 22 Nov (native reads gate nothing) puts its catalog in `shipped`, with tafs#1704.

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
- **Android is ready for it, before it ships there:** `Res.figures` writes Arabic's figures in Western digits
  (`ar-u-nu-latn`) for counts, money, a line's numbers and ordinals. Dates are `java.time`'s, already 0–9 and Gregorian.
  The eyebrows keep the plain face, untracked (`ideographic` covers the Arabic blocks), and no line takes the monospaced
  face in Arabic (`JOINED_SCRIPTS`). Hebrew isn't in either: its letters don't join, and its digits are 0–9.

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
| Setup (a screen title; the agent's name stays Setup in Latin letters) | الإعداد |
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

## Batch 4: Serbian (Latin), Hebrew, Albanian, Georgian, Armenian, Azerbaijani and Bengali

For the countries whose language the apps didn't have (RS, IL, AL, GE, AM, AZ, BD), whose legal pages kiff.dev serves
(kiff/cloud#85). They ship one language at a time, each in its own commit. First drafts: no native speaker has read them.
Android's side is under "Batch 4 on Android" below.

### Serbian (sr-Latn)

Serbian in the Latin script, ekavian, as the legal pages write it; Cyrillic is not written yet. Addressed as **vi** (plural
verb forms); buttons are polite imperatives ("Odobrite", "Odbijte", "Nastavite"). Written from Croatian's lines
(`app/strings/hr/`) with Serbian's words and sound changes: "nalog" for an account, "zahtev", "obaveštenje", "sistem",
"podešavanja" for settings, "nedelja" for a week, "porudžbina" for an order. taf and Card decline as in Croatian (taf,
tafa, tafovi, tafova; Card, Carda, Cardovi, Cardova); Coach, KIFF and Setup are names and stay as they are. A value is a
name and doesn't decline, so a line that would need a case words around it. A door is "vrata", a call "poziv", a held
call "zadržan poziv". Apple's menu paths are the Serbian (Latin) ones ("Podešavanja", "Sistemska podešavanja", "Privatnost
i bezbednost"). The plural forms are one, few (2–4) and other.

| English | Serbian |
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
| Account | Nalog |
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

### Albanian (sq)

Addressed as **ju** (the polite plural); buttons are polite imperatives ("Miratoni", "Refuzoni", "Vazhdoni"). taf, Card, Coach,
KIFF and Setup are names and stay as they are; taf and Card take Albanian's endings with a hyphen as a loanword ("taf-i",
"Card-i", "Card-et", "tafs-et"), and a value is a name that takes none, so a line that would need one words around it:
"taf-i {taf}", "Card-i i: {owner}", "kjo pajisje ({device})". A door is "portë", a call "thirrje", a request "kërkesë", a
held call "thirrje e mbajtur", a receipt "dëftesë", a brief "përmbledhje". Apple's menu paths are the Albanian ones
("Cilësimet", "Cilësimet e Sistemit", "Privatësia dhe Siguria"). The plural forms are one and other; a number takes the
plural noun from 2 ("5 detyra").

| English | Albanian |
|---|---|
| Needs you | Ju pret |
| Approve / Approve once | Miratoni / Miratoni një herë |
| Refuse | Refuzoni |
| held | e mbajtur |
| Halt / Halted | Bllokoni / Bllokuar |
| Stopped | Ndalur |
| Pause / Paused | Pezulloni / Pezulluar |
| Resume | Vazhdoni |
| Revoke | Revokoni |
| Record | Regjistri |
| Connect / connection | Lidhni / lidhje |
| Pair / Unpair | Çiftoni / Hiqni çiftimin |
| Account | Llogaria |
| limit / spend limit | kufi / kufi shpenzimesh |
| credits / credit pack | kreditet / paketë kreditesh |
| passkey | çelës kalimi |
| security key | çelës sigurie |
| Sign in / Sign out | Hyrje / Dalje |
| Inbox | Kutia hyrëse |
| Overview | Përmbledhje |
| Calendar | Kalendari |
| Usage | Përdorimi |
| brief | përmbledhje |
| receipt | dëftesë |
| Cancel / Continue | Anuloni / Vazhdoni |

### Georgian (ka)

Addressed as **თქვენ** (the polite plural); buttons are polite imperatives ("დაამტკიცეთ", "უარყავით", "გააგრძელეთ"). taf, Card, Coach,
KIFF and Setup are names and stay as they are in Latin letters; Georgian's case endings join them with a hyphen ("taf-ს", "Card-ის",
"KIFF-ში"), and a value is a name that takes none, so a line that would need one words around it: "{name}-ს", "ამ მოწყობილობაზე
({device})". A door is "კარი", a call "ზარი", a request "მოთხოვნა", a held call "დაკავებული ზარი", a receipt "ქვითარი", a brief
"მიმოხილვა". Apple's menu paths are the Georgian ones ("პარამეტრები", "სისტემის პარამეტრები", "კონფიდენციალურობა და უსაფრთხოება").
Georgian has one plural form (one and other say the same): a number takes the singular noun ("5 ამოცანა").

| English | Georgian |
|---|---|
| Needs you | გელოდებათ |
| Approve / Approve once | დაამტკიცეთ / დაამტკიცეთ ერთხელ |
| Refuse | უარყავით |
| held | დაკავებული |
| Halt / Halted | გააჩერეთ / გაჩერებულია |
| Stopped | შეწყვეტილია |
| Pause / Paused | დააპაუზეთ / დაპაუზებულია |
| Resume | განაახლეთ |
| Revoke | გააუქმეთ |
| Record | ჩანაწერი |
| Connect / connection | დააკავშირეთ / კავშირი |
| Pair / Unpair | დააწყვილეთ / გააუქმეთ დაწყვილება |
| Account | ანგარიში |
| limit / spend limit | ლიმიტი / ხარჯვის ლიმიტი |
| credits / credit pack | კრედიტები / კრედიტების პაკეტი |
| passkey | პასკი |
| security key | უსაფრთხოების გასაღები |
| Sign in / Sign out | შესვლა / გასვლა |
| Inbox | შემოსულები |
| Overview | მიმოხილვა |
| Calendar | კალენდარი |
| Usage | გამოყენება |
| brief | მიმოხილვა |
| receipt | ქვითარი |
| Cancel / Continue | გაუქმება / გააგრძელეთ |

### Armenian (hy)

Eastern Armenian, addressed as **դուք** (the polite plural); buttons are polite imperatives ("Հաստատեք", "Մերժեք", "Շարունակել").
taf, Card, Coach, KIFF and Setup are names and stay as they are in Latin letters; Armenian's case endings join them with a
hyphen ("taf-ը", "Card-ի", "KIFF-ում"), and a value is a name that takes none, so a line that would need one words around
it: "{name}-ը", "այս սարքում ({device})". A door is "դուռ", a call "զանգ", a request "հարցում", a held call "պահված զանգ", a
receipt "անդորրագիր", a brief "ամփոփում". Apple's menu paths are the Armenian ones ("Կարգավորումներ", "Համակարգի կարգավորումներ",
"Գաղտնիություն և անվտանգություն"). Armenian's "one" covers 0 and 1; a number takes the singular noun ("5 առաջադրանք").

| English | Armenian |
|---|---|
| Needs you | Սպասում է ձեզ |
| Approve / Approve once | Հաստատեք / Հաստատեք մեկ անգամ |
| Refuse | Մերժեք |
| held | պահված |
| Halt / Halted | Կանգնեցրեք / Կանգնեցված է |
| Stopped | Դադարեցված է |
| Pause / Paused | Դադարեցրեք / Դադարեցված է |
| Resume | Վերսկսեք |
| Revoke | Չեղարկեք |
| Record | Գրանցամատյան |
| Connect / connection | Միացրեք / կապ |
| Pair / Unpair | Զուգակցեք / Չեղարկեք զուգակցումը |
| Account | Հաշիվ |
| limit / spend limit | սահմանաչափ / ծախսի սահմանաչափ |
| credits / credit pack | կրեդիտներ / կրեդիտների փաթեթ |
| passkey | մուտքի բանալի |
| security key | անվտանգության բանալի |
| Sign in / Sign out | Մուտք / Ելք |
| Inbox | Մուտքային |
| Overview | Ակնարկ |
| Calendar | Օրացույց |
| Usage | Օգտագործում |
| brief | ամփոփում |
| receipt | անդորրագիր |
| Cancel / Continue | Հրաժարվել / Շարունակել |

### Azerbaijani (az)

Latin script, addressed as **siz**; buttons are polite imperatives ("Təsdiq edin", "Rədd edin", "Davam edin").
taf, Card, Coach, KIFF and Setup stay as they are in Latin letters; Azerbaijani's suffixes join them with a hyphen
("taf-ın", "Card-ı", "Coach-a"), and a value is a name that takes none, so a line that would need one words around it:
"{taf} taf-ını", "{name} üçün". A door is "qapı", a call "çağırış", a request "sorğu", a held call "saxlanılan çağırış", a
receipt "qəbz", a brief "xülasə". Apple's menu paths are the Azerbaijani ones ("Parametrlər", "Sistem parametrləri",
"Məxfilik və təhlükəsizlik"). Azerbaijani's "one" covers 1 only, and a number takes the singular noun ("5 tapşırıq").

| English | Azerbaijani |
|---|---|
| Needs you | Sizi gözləyir |
| Approve / Approve once | Təsdiq edin / Bir dəfə təsdiq edin |
| Refuse | Rədd edin |
| held | saxlanılıb |
| Halt / Halted | Dondurun / Donduruldu |
| Stopped | Dayandırıldı |
| Pause / Paused | Fasilə verin / Fasilədədir |
| Resume | Davam etdirin |
| Revoke | Ləğv edin |
| Record | Qeydlər |
| Connect / connection | Qoşun / qoşulma |
| Pair / Unpair | Cütləşdirin / Cütləşməni ləğv edin |
| Account | Hesab |
| limit / spend limit | limit / xərc limiti |
| credits / credit pack | kreditlər / kredit paketi |
| passkey | giriş açarı |
| security key | təhlükəsizlik açarı |
| Sign in / Sign out | Giriş / Çıxış |
| Inbox | Gələnlər |
| Overview | İcmal |
| Calendar | Təqvim |
| Usage | İstifadə |
| brief | xülasə |
| receipt | qəbz |
| Cancel / Continue | Vaz keçin / Davam edin |

### Bengali (bn)

Standard Bengali, addressed as **আপনি**; buttons are polite imperatives ("অনুমোদন করুন", "প্রত্যাখ্যান করুন", "চালিয়ে যান").
taf, Card, Coach, KIFF and Setup are names and stay in Latin letters; Bengali's suffixes join them with a hyphen
("taf-এর", "Card-এ", "Coach-কে"), and a value is a name that takes the suffix the same way ("{taf}-এর", "{name}-এ"). A door is
"দরজা", a call "কল", a request "অনুরোধ", a held call "ধরে রাখা কল", a receipt "রসিদ", a brief "সারসংক্ষেপ". A sentence ends
with "।" (the danda). Apple's menu paths are the Bengali ones ("সেটিংস", "সিস্টেম সেটিংস", "গোপনীয়তা ও নিরাপত্তা"). Figures
that a line writes itself are Bengali digits (০–৯), as the locale's own numbers are; a reference number ("#5098") stays in
Latin digits, isolated as in the other languages. Bengali's "one" covers 0 and 1; a number takes the singular noun ("৫টি টাস্ক").

| English | Bengali |
|---|---|
| Needs you | আপনার অপেক্ষায় |
| Approve / Approve once | অনুমোদন করুন / একবার অনুমোদন করুন |
| Refuse | প্রত্যাখ্যান করুন |
| held | ধরে রাখা |
| Halt / Halted | থামান / থামানো হয়েছে |
| Stopped | বন্ধ করা হয়েছে |
| Pause / Paused | বিরতি দিন / বিরতিতে আছে |
| Resume | আবার চালু করুন |
| Revoke | প্রত্যাহার করুন |
| Record | রেকর্ড |
| Connect / connection | সংযুক্ত করুন / সংযোগ |
| Pair / Unpair | জোড়া লাগান / জোড়া ছাড়ান |
| Account | অ্যাকাউন্ট |
| limit / spend limit | সীমা / খরচের সীমা |
| credits / credit pack | ক্রেডিট / ক্রেডিট প্যাক |
| passkey | পাসকি |
| security key | সিকিউরিটি কী |
| Sign in / Sign out | সাইন ইন / সাইন আউট |
| Inbox | ইনবক্স |
| Overview | সংক্ষিপ্ত বিবরণ |
| Calendar | ক্যালেন্ডার |
| Usage | ব্যবহার |
| brief | সারসংক্ষেপ |
| receipt | রসিদ |
| Cancel / Continue | বাতিল করুন / চালিয়ে যান |

### Hebrew (he)

Modern Hebrew, written right to left. Addressed to one reader; Hebrew is gendered, so buttons and titles are verbal nouns
("אישור", "דחייה", "המשך", "ביטול"), as Apple's Hebrew writes them, and a sentence that must address the reader uses the
masculine as Hebrew software does ("אתה", "שלך") or an impersonal "יש ל…". taf, Card, Coach, KIFF and Setup stay in Latin
letters, and a Hebrew prefix joins one with a hyphen ("ה-taf", "ל-Coach", "ב-KIFF"); a value is a name that takes no
prefix, so a line that would need one words around it ("ההתקן הזה ({device})", "{name} שלו"). A door is "דלת", a call "קריאה", a
request "בקשה", a held call "קריאה מוחזקת", a receipt "קבלה", a brief "סיכום". Figures are Western digits (0–9), as the
locale writes them, and dates are Gregorian. Quotes are « » for a line's own, and a name a taf carries is isolated
(`Words.isolated`).

**Plurals have three forms:** one (1), two (2) and other (everything else; `many` left CLDR in 42, so an older phone that asks
for it reads `other`). A line may leave the number out in `one` and `two` ("יום אחד", "יומיים"), as the words say it.

**Direction** is Arabic's rule (see Arabic above): a menu path in a line that English writes with "→" writes "←" ("‹" and "›"
mirror with the text direction and are written as English writes them); the two dials turn the same way as in English; a
reference number ("#5098") is wrapped in U+2066 … U+2069. `test_strings` checks all three for Hebrew as for Arabic. No joined
script, so the monospaced face and the spacing of captions stay as they are.

| English | Hebrew |
|---|---|
| Needs you | זקוק לך |
| Approve / Approve once | אישור / אישור חד-פעמי |
| Refuse | דחייה |
| held | מוחזק |
| Halt / Halted | עצירה / נעצר |
| Stopped | הופסק |
| Pause / Paused | השהיה / מושהה |
| Resume | חידוש |
| Revoke | הפקעה |
| Record | תיעוד |
| Connect / connection | חיבור / חיבור |
| Pair / Unpair | צימוד / ביטול צימוד |
| Account | חשבון |
| limit / spend limit | מגבלה / מגבלת הוצאה |
| credits / credit pack | קרדיטים / חבילת קרדיטים |
| passkey | מפתח גישה |
| security key | מפתח אבטחה |
| Sign in / Sign out | כניסה / יציאה |
| Inbox | דואר נכנס |
| Overview | סקירה |
| Calendar | לוח שנה |
| Setup (a screen title; the agent's name stays Setup in Latin letters) | הגדרה |
| Usage | שימוש |
| brief | סיכום |
| receipt | קבלה |
| Cancel / Continue | ביטול / המשך |

## Batch 4 on Android: Serbian, Hebrew, Albanian, Georgian, Armenian, Azerbaijani and Bengali

Ready before their lines are written, so the strings PR only adds the words:

- **Codes.** Serbian ships as `sr-Latn`: plain `sr` means Cyrillic on Android, and the libraries ship `values-sr` in
  Cyrillic. A phone set to Serbian in Latin script reads it; one set to Serbian alone, which Android writes in Cyrillic,
  falls back to English until Cyrillic is written. Azerbaijani ships as plain `az`, since Latin is its default script and the
  libraries' `values-az` is Latin; shipped as `az-Latn`, its lines would go in `values-az` too (`ALSO`).
- **Hebrew's folder.** The libraries ship `values-iw`, Hebrew's old code, and no `values-he`, as with Indonesian, so its lines
  are written to both (`android.py`'s `ALSO`). `AppLocale` reads "iw" as "he". It's right to left: both apps already lay
  out from the end and set values apart in a line (#1696).
- **Plural forms** (`generate.py`'s `PLURALS`): Serbian one, few; Hebrew one, two (an older phone asking for "many" reads
  "other"); Albanian, Georgian, Armenian, Azerbaijani and Bengali one. Armenian and Bengali's "one" covers 0 and 1.

## Batch 5: Catalan, Macedonian, Mongolian, Nepali, Khmer and Lao

### Catalan (ca)

Standard Catalan (Barcelona usage), addressed as **tu**, the informal form every Catalan app uses ("Aprova", "Rebutja", "Continua").
Buttons are verbs in the imperative. taf, Card, Coach, KIFF and Setup (Configuració) are names: taf doesn't take a plural "s"
("els teus taf") and Card keeps its capital. Catalan has no English-style possessive, so "{owner}'s Card" is "el Card de {owner}"
(a vowel after "de" elides: "d'"). A door is "porta", a knock "trucada", a call "crida", a request "sol·licitud", a receipt "rebut",
the dial "selector". The punctuation mark in "sol·licitud" and "cancel·la" is the interpunct (·), never a full stop. Quotes are « ».
Plural forms: one, other.

| English | Catalan |
|---|---|
| Needs you | Et necessita |
| Approve / Approve once | Aprova / Aprova una vegada |
| Refuse | Rebutja |
| held | retingut |
| Halt / Halted | Bloqueja / Bloquejat |
| Stopped | Aturat |
| Pause / Paused | Posa en pausa / En pausa |
| Resume | Reprèn |
| Revoke | Revoca |
| Record | Registre |
| Connect / connection | Connecta / connexió |
| Pair / Unpair | Aparella / Desaparella |
| Account | Compte |
| limit / spend limit | límit / límit de despesa |
| credits / credit pack | crèdits / paquet de crèdits |
| passkey | clau d'accés |
| security key | clau de seguretat |
| Sign in / Sign out | Inicia la sessió / Tanca la sessió |
| Inbox | Safata d'entrada |
| Overview | Visió general |
| Calendar | Calendari |
| Usage | Ús |
| Cancel / Continue | Cancel·la / Continua |

### Macedonian (mk)

Standard Macedonian in Cyrillic, addressed as **ти**, the informal form. Buttons are verbs in the imperative ("Одобри", "Одбиј",
"Продолжи"). taf, Card, Coach, KIFF and Setup are names. Setup is "Конфигурација" in its own screen's title and taf, Card, Coach and
KIFF stay in Latin letters. A taf takes the plural "tafови" (a Cyrillic ending on the Latin name); Card doesn't change. The possessive
is "на": "Card на {owner}". A door is "врата" (a plural noun, so "Врата {door}" and "овие врата"), a knock "тропање", a call "барање", a
receipt "признаница", the dial "селектор". Months and weekdays are lower case in a sentence ("јануари", "пон"). Quotes are „ “.
macOS has no Macedonian, so a `*_mac` line names the English-named paths in Macedonian with macOS 12's names in brackets.
Plural forms: one, other.

| English | Macedonian |
|---|---|
| Needs you | Те чека |
| Approve / Approve once | Одобри / Одобри еднаш |
| Refuse | Одбиј |
| held | задржано |
| Halt / Halted | Запри / Запрен |
| Stopped | Стопиран |
| Pause / Paused | Паузирај / Паузиран |
| Resume | Продолжи |
| Revoke | Отповикај |
| Record | Запис |
| Connect / connection | Поврзи / врска |
| Pair / Unpair | Спари / Раздвои |
| Account | Сметка |
| credits | кредити |
| passkey | клуч за пристап |
| security key | безбедносен клуч |
| Sign in / Sign out | Најави се / Одјави се |
| Inbox | Приемно сандаче |
| Overview | Преглед |
| Calendar | Календар |
| Usage | Користење |
| Cancel / Continue | Откажи / Продолжи |

### Mongolian (mn)

Mongolian in Cyrillic, in the polite register (**та**). Buttons are the verbal noun, as Mongolian apps write them ("Зөвшөөрөх",
"Татгалзах", "Үргэлжлүүлэх"). taf, Card, Coach and KIFF stay in Latin letters; Mongolian doesn't mark a plural after a number, so a
count takes the bare noun ("3 taf", "2 дуудлага") and the plural of "taf" is the same word ("taf-ууд" only without a number).
A suffix joins a Latin name with a hyphen ("KIFF-ийн", "Card-ын"). Months are numbered ("11-р сар") and weekdays are two letters
("Да", "Мя"). A door is "хаалга", a knock "тогшилт", a call "дуудлага", a receipt "баримт", the dial "товчлуур". Quotes are “ ”.
macOS has no Mongolian, so a `*_mac` line gives the path in Mongolian with macOS 12's names in brackets.
There is one plural form in use (one and other read the same).

| English | Mongolian |
|---|---|
| Needs you | Таныг хүлээж байна |
| Approve / Approve once | Зөвшөөрөх / Нэг удаа зөвшөөрөх |
| Refuse | Татгалзах |
| held | түр барьсан |
| Halt / Halted | Зогсоох / Зогссон |
| Stopped | Зогссон |
| Pause / Paused | Түр зогсоох / Түр зогссон |
| Resume | Үргэлжлүүлэх |
| Revoke | Хүчингүй болгох |
| Record | Бүртгэл |
| Connect / connection | Холбох / холболт |
| Pair / Unpair | Хослуулах / Хослолыг салгах |
| Account | Бүртгэл |
| credits | кредит |
| passkey | нэвтрэх түлхүүр |
| security key | аюулгүй байдлын түлхүүр |
| Sign in / Sign out | Нэвтрэх / Гарах |
| Inbox | Ирсэн |
| Overview | Тойм |
| Calendar | Хуанли |
| Usage | Хэрэглээ |
| Cancel / Continue | Болих / Үргэлжлүүлэх |

### Nepali (ne)

Nepali in Devanagari, in the polite register (**तपाईं**). Buttons are the polite imperative ("स्वीकृत गर्नुहोस्", "अस्वीकार गर्नुहोस्",
"जारी राख्नुहोस्"). taf, Card, Coach and KIFF stay in Latin letters; Nepali doesn't mark a plural after a number, so a count takes
the bare noun ("३ taf", "२ कल"). A suffix follows a Latin name after a space ("taf को Card", "KIFF बाट"). Numbers in the app's
own text are Western digits where they come from the code and Devanagari digits only in the fixed sample lines. A door is "ढोका",
a knock "ढकढक", a call "कल", a receipt "रसिद", the dial "डायल". Quotes are “ ”. macOS has no Nepali, so a `*_mac` line gives the
path in Nepali with macOS 12's names in brackets. Plural forms: one, other (read the same).

| English | Nepali |
|---|---|
| Needs you | तपाईंको प्रतीक्षामा |
| Approve / Approve once | स्वीकृत गर्नुहोस् / एक पटक स्वीकृत गर्नुहोस् |
| Refuse | अस्वीकार गर्नुहोस् |
| held | रोकिएको |
| Halt / Halted | रोक्नुहोस् / रोकिएको |
| Stopped | रोकिएको |
| Pause / Paused | पज गर्नुहोस् / पज गरिएको |
| Resume | फेरि सुरु गर्नुहोस् |
| Revoke | रद्द गर्नुहोस् |
| Record | अभिलेख |
| Connect / connection | जडान गर्नुहोस् / जडान |
| Pair / Unpair | जोड्नुहोस् / जोडी हटाउनुहोस् |
| Account | खाता |
| credits | क्रेडिट |
| passkey | पासकी |
| security key | सुरक्षा कुञ्जी |
| Sign in / Sign out | साइन इन गर्नुहोस् / साइन आउट गर्नुहोस् |
| Inbox | इनबक्स |
| Overview | सारांश |
| Calendar | पात्रो |
| Usage | प्रयोग |
| Cancel / Continue | रद्द गर्नुहोस् / जारी राख्नुहोस् |

### Khmer (km)

Khmer script, in a plain polite register with no honorific particles in buttons (verbs stand alone: "អនុម័ត", "បដិសេធ"). taf, Card,
Coach and KIFF stay in Latin letters. Khmer has no plural marking, so a count takes the bare noun ("3 taf", "2 ការហៅ"). Words are not
separated by spaces: a space marks a phrase or clause. A door is "ទ្វារ", a knock "ការគោះ", a call "ការហៅ", a receipt "បង្កាន់ដៃ". Numbers
are Western digits. Quotes are “ ”. macOS has no Khmer, so a `*_mac` line gives the path in Khmer with macOS 12's names in brackets.
Plural forms: other only.

| English | Khmer |
|---|---|
| Needs you | ត្រូវការអ្នក |
| Approve / Approve once | អនុម័ត / អនុម័តម្តង |
| Refuse | បដិសេធ |
| held | ផ្អាកទុក |
| Halt / Halted | បញ្ឈប់ / បានបញ្ឈប់ |
| Stopped | បានឈប់ |
| Pause / Paused | ផ្អាក / បានផ្អាក |
| Resume | បន្ត |
| Revoke | ដកហូត |
| Record | កំណត់ត្រា |
| Connect / connection | ភ្ជាប់ / ការតភ្ជាប់ |
| Pair / Unpair | ផ្គូផ្គង / ឈប់ផ្គូផ្គង |
| Account | គណនី |
| credits | ក្រេឌីត |
| passkey | passkey |
| security key | សោសុវត្ថិភាព |
| Sign in / Sign out | ចូល / ចាកចេញ |
| Inbox | ប្រអប់សារចូល |
| Overview | ទិដ្ឋភាពទូទៅ |
| Calendar | ប្រតិទិន |
| Usage | ការប្រើប្រាស់ |
| Cancel / Continue | បោះបង់ / បន្ត |

### Lao (lo)

Lao script, in a plain polite register. taf, Card, Coach and KIFF stay in Latin letters. Lao has no plural marking, so a count takes the
bare noun ("3 taf", "2 ການໂທ"). Words are not separated by spaces: a space marks a phrase or clause. A door is "ປະຕູ", a knock "ການເຄາະ",
a call "ການໂທ", a receipt "ໃບຮັບເງິນ". Numbers are Western digits. Quotes are “ ”. macOS has no Lao, so a `*_mac` line gives the path in
Lao with macOS 12's names in brackets. Plural forms: other only.

| English | Lao |
|---|---|
| Needs you | ຕ້ອງການທ່ານ |
| Approve / Approve once | ອະນຸມັດ / ອະນຸມັດຄັ້ງດຽວ |
| Refuse | ປະຕິເສດ |
| held | ພັກໄວ້ |
| Halt / Halted | ຢຸດ / ຢຸດແລ້ວ |
| Stopped | ຖືກຢຸດ |
| Pause / Paused | ຢຸດຊົ່ວຄາວ / ຢຸດຊົ່ວຄາວແລ້ວ |
| Resume | ສືບຕໍ່ |
| Revoke | ຖອນ |
| Record | ບັນທຶກ |
| Connect / connection | ເຊື່ອມຕໍ່ / ການເຊື່ອມຕໍ່ |
| Pair / Unpair | ຈັບຄູ່ / ເຊົາຈັບຄູ່ |
| Account | ບັນຊີ |
| credits | ເຄຣດິດ |
| security key | ກຸນແຈຄວາມປອດໄພ |
| Sign in / Sign out | ເຂົ້າສູ່ລະບົບ / ອອກຈາກລະບົບ |
| Inbox | ກ່ອງຂໍ້ຄວາມ |
| Overview | ພາບລວມ |
| Calendar | ປະຕິທິນ |
| Usage | ການໃຊ້ງານ |
| Cancel / Continue | ຍົກເລີກ / ສືບຕໍ່ |

## Batch 6: Turkmen

### Turkmen (tk)

Turkmen in the Latin alphabet of 1993 as revised in 2021 (A B Ç D E Ä F G H I J Ž K L M N Ň O Ö P R S Ş T U Ü W Y Ý Z), addressed as **siz**,
the polite form, with buttons in the polite imperative ("Tassyklaň", "Ret ediň", "Dowam ediň"). taf, Card, Coach, KIFF, Setup and
planque are names and stay in Latin letters; they take Turkmen endings after a hyphen ("taf-yň", "Card-yny", "tafs-lar"). Turkmen has no
plural after a number ("3 taf", "2 çagyryş"), so a count uses the bare noun in both forms. Turkmen has no English-style possessive:
"{owner}'s Card" is "{owner} üçin Card", since a name can't take a vowel-harmony ending safely. A door is "gapy", a knock "kakyş", a call
"çagyryş", a receipt "kwitansiýa", the Record "Ýazgy", a run "işleme". Numbers are Western digits; quotes are “ ”. The weekdays are
Duş, Siş, Çar, Pen, Anna, Şen, Ýek and the months Ýanwar, Fewral, Mart, Aprel, Maý, Iýun, Iýul, Awgust, Sentýabr, Oktýabr, Noýabr,
Dekabr. Menu paths of the phone's own settings stay in English, as iOS, macOS and Android have no Turkmen. Plural forms: one, other.
First draft, from legal-i18n; a native read fixes it forward.

| English | Turkmen |
|---|---|
| Needs you | Size garaşýar |
| Approve / Approve once | Tassyklaň / Bir gezek tassyklaň |
| Refuse | Ret ediň |
| held | saklanan |
| Halt / Halted | Togtatmak / Togtadyldy |
| Stopped | Durdy |
| Pause / Paused | Bes ediň / Bes edildi |
| Resume | Dowam ediň |
| Revoke | Ýatyryň |
| Record | Ýazgy |
| Connect / connection | Birikdiriň / birikme |
| Pair / Unpair | Goşalaň / Goşalamany aýyryň |
| Account | Hasap |
| credits | kreditler |
| security key | howpsuzlyk açary |
| Sign in / Sign out | Giriň / Çykyň |
| Inbox | Inbox |
| Overview | Syn |
| Calendar | Senenama |
| Usage | Ulanyş |
| Cancel / Continue | Goýbolsun / Dowam ediň |

## Batch 7: Hindi and Urdu

### Hindi (hi)

Hindi in Devanagari, addressed as **आप**, the polite form, with buttons in the polite imperative ("मंज़ूर करें", "अस्वीकार करें",
"जारी रखें"). taf, Card, Coach, KIFF, Setup and planque are names and stay in Latin letters; a plural "s" is never added ("दो taf") and
Card is used as a masculine noun. A count takes the singular noun after the number ("3 taf", "2 कॉल"), so both plural forms are the same.
The English possessive "{owner}'s Card" is "{owner} का Card". A door is "द्वार", a knock "दस्तक", a call "कॉल", a receipt "रसीद", the
Record "रिकॉर्ड", a run "रन". Numbers are Western digits; quotes are “ ”; sentences end with the danda "।". The weekdays are
सोम, मंगल, बुध, गुरु, शुक्र, शनि, रवि and the months जनवरी, फ़रवरी, मार्च, अप्रैल, मई, जून, जुलाई, अगस्त, सितंबर, अक्टूबर, नवंबर,
दिसंबर. Menu paths of the phone's own settings that the phone shows in English stay in English. Plural forms: one, other. First draft,
from legal-i18n; a native read fixes it forward.

| English | Hindi |
|---|---|
| Needs you | आपका इंतज़ार |
| Approve / Approve once | मंज़ूर करें / एक बार मंज़ूर करें |
| Refuse | अस्वीकार करें |
| held | रोकी गई |
| Halt / Halted | रोकें / रोका गया |
| Stopped | रुका |
| Pause / Paused | थामें / थमा हुआ |
| Resume | फिर शुरू करें |
| Revoke | रद्द करें |
| Record | रिकॉर्ड |
| Connect / connection | जोड़ें / कनेक्शन |
| Pair / Unpair | जोड़ी बनाएँ / जोड़ी हटाएँ |
| Account | खाता |
| credits | क्रेडिट |
| security key | सुरक्षा कुंजी |
| Sign in / Sign out | साइन इन करें / साइन आउट करें |
| Inbox | Inbox |
| Overview | अवलोकन |
| Calendar | कैलेंडर |
| Usage | उपयोग |
| Cancel / Continue | छोड़ें / जारी रखें |

### Urdu (ur)

Urdu in Nastaliq-style Arabic script, written right to left, addressed as **آپ**, the polite form, with buttons in the polite imperative
("منظور کریں", "مسترد کریں", "جاری رکھیں"). taf, Card, Coach, KIFF, Setup, Halt and planque are names and stay in Latin letters; a plural
"s" is never added to taf in a count ("3 taf"), while "tafs" stays the product's name. Plural forms are written out where Urdu marks them
("1 کال", "2 کالیں"). The English possessive "{owner}'s Card" is
"{owner} کا Card". A door is "دروازہ", a knock "دستک", a call "کال", a receipt "رسید", the Record "ریکارڈ", a run "رن". Numbers are Western
digits; quotes are « »; sentences end with the Urdu full stop "۔". Menu paths of the phone's own settings are written as the phone shows them in Urdu; macOS and iOS names stay in English. Plural forms: one, other. First draft, from legal-i18n; a native read fixes it forward.

| English | Urdu |
|---|---|
| Needs you | آپ کی ضرورت |
| Approve / Approve once | منظور کریں / ایک بار منظور کریں |
| Refuse | مسترد کریں |
| Held | روکا گیا |
| Halt / Halted | Halt / روکا گیا |
| Stopped | رک گیا |
| Pause / Paused | روکیں / رکا ہوا |
| Resume | دوبارہ چلائیں |
| Revoke | منسوخ کریں |
| Record | ریکارڈ |
| Connect | جوڑیں |
| Pair / Unpair | جوڑیں / جوڑ ختم کریں |
| Account | اکاؤنٹ |
| Credits | کریڈٹس |
| Sign in / Sign out | سائن اِن کریں / سائن آؤٹ |
| Inbox | ان باکس |
| Overview | جائزہ |
| Calendar | کیلنڈر |
| Usage | استعمال |
| Cancel / Continue | چھوڑیں / جاری رکھیں |

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
- **A language tafs cloud adds before every line is written may be partial** (PM, 22 Nov): it goes in `PARTIAL_LANGUAGES`,
  each line written replaces English's, every other reads English, key by key, and at 100% it moves to `FULL_LANGUAGES`,
  where the type requires every key. Batch 2's ten and batch 3's five went that way: partial in tafs#1702, every line by
  tafs#1706, full since (a draft no native speaker has read yet). None is partial today; batch 4's languages would be next.
  Three guards while one is:
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
