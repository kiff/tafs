# Changelog

User-visible changes and compatibility notes are recorded here. Dates and
version headings refer to releases, not merely to merged pull requests.

## Unreleased

- **iPhone and iPad: a held call says what it's on and why, in the taf's words.** Under KIFF's amount, action and Card rule, the card shows **The taf says**: the order as the owner knows it ("Order 1042") and the customer's reason, as KIFF keeps them with the hold. They're plain text, never markup or a link, and never take the place of KIFF's amount, rule or action. A KIFF without them shows the card as before (docs/ios-app.md).
- **iPhone and iPad: every ready-made job, at any time.** + New opens tafs cloud's gallery of jobs, with "Say it in your own words"; a short strip under the tafs list shows a few jobs the account doesn't run yet, in tafs cloud's order. Each job shows its apps with their logos, loaded only from tafs cloud, and each category has its colour once tafs cloud sends one. From a tafs cloud without the gallery, + New opens Coach as before (docs/ios-app.md).
- **Every job a taf can take, also once a taf runs (Android 1.4).** + New on Tafs opens every job by category, with "Say it in your own words" for Coach, and a short strip under the tafs list shows a few jobs the account doesn't run yet, as tafs cloud picks them. Each job shows its apps with their logos, loaded from tafs cloud only, and its category's colour where tafs cloud sends one; Connect apps shows logos too (docs/android-app.md).
- **Every ready-made job at any time, with app logos (tafs cloud).** `GET /v1/recipes/gallery` serves every job the catalog can connect, also once a taf is live, for **+ New** on Tafs, and the strip under the tafs list: up to three jobs the account doesn't run yet, connected apps first, only for someone who manages the tafs. Each job carries its apps with their logos. `GET /v1/logos/{key}` serves each logo as a small PNG that tafs cloud fetched, checked and drew itself, so the phones load nothing from a third party; Connect apps carries the same `logo` and no longer passes planque's `icon` URL. `GET /v1/recipes` keeps its meaning and gains `apps`, `connected` and `running`. Categories come from `recipes/categories.txt`. The apps follow (docs/api.md › Logos, docs/coach.md › The gallery).
- **Two ready-made jobs come off until a taf can do them (tafs cloud).** "Pay the right bills on time" (`pay-matching-bills`) and "A leaver's access ends the day they leave" (`access-for-joiners-and-leavers`) are no longer listed by `GET /v1/recipes`, in any language: the apps they connect can't do their Card's work today. Nine jobs remain. A taf already made from either keeps running, and Bill pay and Access stay starter jobs for the tafs that have one (docs/tafs.md, recipes/README.md).
- **Edge to edge without deprecated calls (Android 1.3.1).** The app no longer uses the window's bar colours or the short-edges cutout mode, which Google Play flags as deprecated for edge to edge from Android 15. It still draws behind the status and navigation bars, with their icons in the app's own Light or Dark from the first frame (docs/android-app.md).
- **iPhone and iPad: what Coach includes, under Solo.** As on Android 1.3. Plan & usage shows tafs cloud's sentence about Coach's help (`buy.coach`) under Solo, as kiff.dev says it. A tafs cloud without it shows no line (docs/ios-app.md).
- **iPhone and iPad: Coach's tools are asked after an install.** As on Android 1.3. Installing a version with its Card while Coach's tools are still to give asks the tools step on Coach's screen; the install's Face ID gives none of them (docs/ios-app.md, docs/coach.md).
- **Coach answers about cost in credits (usage-and-plans@2).** Coach describes today's plans in kiff.dev's and Plan & usage's words: credits per run, Solo at €12.50 with 500 credits a month, packs that never expire, Company per seat with a spend limit, no free trial, and "Coach is included: enough to set up about 4 new jobs a month. Past that, Coach's help uses your credits." Nothing about tasks or a free week, and never a figure it works out. Live after the next Eval and Upgrade.
- **iPhone and iPad: Coach's tools are asked for, and are tafs cloud's.** As on Android 1.2. A first Start ends on a step that says what Coach's tools let it do, in tafs cloud's words, with **Give** behind one Face ID and **Not now**. After Not now, one line above the conversation opens it again. The phone sends tafs cloud's batch of connections and Coach's Cards as they come, so an iPhone's Coach gets every tool Android's does. Before, the phone gave its own shorter list silently, inside another confirmation (docs/ios-app.md, docs/coach.md).
- **A taf's likely credits a month, said before the owner approves it (tafs cloud).** Before the result of a new scheduled taf, and before a dial version of one goes to the owner, Coach calls `taf_estimate` and says its one line as given, in the message right before the card: never a figure of its own. tafs cloud writes it from records only, in credits as KIFF counts them, best first, and says which it used: the taf's own counted runs over the last 30 days (credits per run, and runs a month once 7 days make a pace), a new version's trial runs (with the credits KIFF says each would have used), or the same job's runs in our Evals ("in our tests", from `estimates.json`, a table in credits generated outside this repo, empty until an Eval report is read). With none of them, it says it can't tell yet. It says what the plan covers before any euro figure ("On its own, it fits inside your plan's 500 a month."); on Company, what's past the plan's credits, at Company's published €0.01 a credit, against the account's own spend limit; on Solo, how many credits past the plan, with no euro figure. The last line depends on the plan: on Solo "It never goes past your credits: when they run out, it waits.", on Company "It never goes past your spend limit: there, it waits.", and none on an agreement; in all 8 languages. When KIFF's credits can't be read, the line falls to our tests or says it can't tell yet; when the tool can't answer in time, Coach says nothing about cost. Nothing waits on it.
  - **`GET /v1/tafs/{id}/estimate`** answers `{source, credits?, runs_a_month?, per_run?, words}` with the person's session.
  - **Coach's `taf_estimate`** reads the taf's runs from KIFF with its call's permit, `POST /v1/coach/credits` `{permit, agent}`; until KIFF serves that route, its line comes from our tests or says it can't tell yet.
  - **Plan & usage:** in this period's Credits panel, a taf's row gains `pace` ("At this pace, about 640 this month") once 7 days of its runs make one. The apps follow, drawing it under the row.
  - **The week-one note:** on the first read of a taf's conversation 7 to 14 days after it starts, by someone who manages it, one note in its chat says what its first week used and a month at that pace. Posted once; the read never waits on it more than a second and a half.
  - **The recipe:** `POST /v1/tafs` and Coach's result take `recipe`, kept on the taf for its estimate. kiff/agents' result card doesn't take the field yet.
  - Coach's `set-up-a-taf@5` and `the-dial@3` say when to call it; they reach Coach with the next Upgrade. Coach's new tool needs its Card, `coach-estimates`, which the phone asks for at its next read, as for any Card added later (docs/api.md, docs/coach.md, docs/accounts.md).
- **What Coach includes, under the Solo offer (Android 1.3).** Plan & usage shows tafs cloud's sentence about Coach's help (`buy.coach`) under Solo's terms, as kiff.dev says it. A tafs cloud without it shows no line.
- **Coach's tools are given in their own step only (Android 1.3).** A Start on a phone new to a Coach already there, and installing a version, no longer give the tools inside their own confirmation: where tools are still to give, the tools step is asked, in tafs cloud's words, as iOS does.
- **What Coach's help includes, under the Solo offer (tafs cloud).** The balance read's `buy` carries `coach`, in the person's language: "Coach is included: enough to set up about 4 new jobs a month. Past that, Coach's help uses your credits.", the same sentence as kiff.dev. The apps draw it under the Solo offer in their next batches (docs/accounts.md).
- **iPhone and iPad: Plan & usage on a new account says less.** As on Android 1.2. Solo sits under "Choose a plan", and "Add credits" shows only once Solo runs. The Credits panel is left out where tafs cloud says so (`credits_panel`: no plan and no run lately). The First month pack leads the packs only while it can be bought, and is never shown alone or greyed. Restore purchases stays, and a restore that finds nothing says "Nothing to restore." (docs/ios-app.md).
- **Coach's credits lines say what's included (tafs cloud).** Plan & usage reads "Coach, included" and "Coach, past what's included", as kiff.dev says it, in 8 languages; before, "within its fair use" (docs/accounts.md).
- **Plan & usage on a new account says less (Android 1.2).** Restore purchases shows only while a purchase is left to send again, and a restore that finds nothing says "Nothing to restore." The panel is titled "Choose a plan" while Solo is offered, and "Add credits" once it runs. The First month pack is never shown alone or greyed, and the Credits panel stays out where tafs cloud's `credits_panel` says so.
- **Coach starts with the first message (Android 1.2).** A job handed to Coach lands in Coach's box with no step before it, and the first Send starts Coach, then sends. A first Start asks for one fingerprint, the draft's, with nothing made before it. Coach's tools are then their own step, a sheet in tafs cloud's words with Not now, and after that one line above the conversation. With the keyboard open, the message box always shows, and its buttons keep their size. A Start that's closed or fails keeps the words in the box and says why.
- **iPhone and iPad: Home with no taf leads with the jobs a taf can take.** As on Android. An account with no taf sees tafs cloud's line, a chip per category, and each job led by the problem it ends, in the person's language. A tap opens the job's sheet: what it costs today, then its Card in plain zones (does on its own, asks first, never does), and one button that hands it to Coach with the words in the box, unsent. On iPad the jobs take the pane beside the sidebar. On a phone where Coach isn't started yet, the words show first and Send starts Coach behind one Face ID, then sends; a cancelled Start keeps the words, and a phone that can't approve yet gets the words that say why and no box (docs/ios-app.md).
- **Coach's tools step (tafs cloud).** `GET /v1/setup` sends the words of the step that asks for Coach's tools at the end of a first Start, in the person's language: four groups up front, each tool folded under them, with what it lets Coach do and why it needs a yes. Every limit comes from Coach's Cards (docs/coach.md).
- **Plan & usage on a new account says less (tafs cloud).** Before a plan, the balance read no longer offers the First month pack: it comes only once Solo runs, with the other packs. A new `credits_panel` flag is false with no plan and no run this period or the last, so the apps can leave the Credits panel out for someone who just signed in (docs/accounts.md).
- **The app's own language reaches every line (Android 1.1.2).** With tafs set to a language other than the phone's (Settings › Apps › tafs › Language), some of the app's own lines stayed in the phone's language, or in a language picked earlier: Home's "0 tafs working." read English in a German app. Each screen now reads the words in its own configuration's language, plurals included (docs/android-app.md).
- **A new account's Home leads with the jobs (Android 1.1.2).** Where Home lists the jobs a taf can take, it no longer shows "0 tafs working" and "Nothing needs you" above them.
- **Recipes keep one order (tafs cloud).** Each recipe has an `order` in its category, and `GET /v1/recipes` lists them by category, then that order, so refunds leads Money in the apps as it does on kiff.dev/tafs. Before, a category listed its recipes alphabetically by file (recipes/README.md).
- **Signing in always loads the account (Android 1.1.1).** After a sign-in, Home could stay on "Loading your tafs…" until a pull to refresh, because the sign-in screen left as soon as the phone was signed in and took the first refresh with it; a new phone could also miss registering its approval key or its alerts. A refresh, and everything a sign-in loads, now runs to the end whatever screen asked for it (docs/android-app.md).
- **Home with no taf shows the jobs a taf can take (Android).** Where tafs cloud says so (`home.shown`), Home lists the recipes under tafs cloud's line, each led by the problem it removes, with a chip per category. A tap opens its sheet: what it costs today, its Card in three zones (does on its own, asks you first, never does) and one button that hands it to Coach with its words, to edit and send. Otherwise Home shows tafs cloud's empty line. Every word is tafs cloud's. The store shows it as version 1.1 (docs/android-app.md).
- **Recipes lead with the problem, in the person's language (tafs cloud).** Each recipe now has a `headline` (the problem it ends, such as "No refund waits overnight.") and a `today` line (what that problem costs today, in words). `GET /v1/recipes` sends every word in all 8 languages: the recipes, their Card lists, the words for Coach, the categories and the suggestions words. It also says whether Home leads with them (`home: {shown, words}`) and sends the sheet's labels. Home leads with them only with no taf, suggestions on, for someone who manages the account's tafs, and with at least one recipe listed. Otherwise `home.words` is Home's empty line (docs/api.md, recipes/README.md).
- **Coach says a sign-in again in the owner's words (tafs cloud).** A Coach chat once showed "sign_in for same grant." after the owner closed a vendor's sign-in: shorthand from its `connect-an-app` skill. `connect-an-app@6` says it in full (a sign-in card for that same grant) and has Coach tell the owner in their words ("Sign in again to finish connecting it"). It reaches Coach with the next Eval and Upgrade (docs/coach.md).
- **Home's Needs you line, and a halted refusal, in the person's language (tafs cloud).** `GET /v1/home`'s `needs_you.words` ("Nothing needs you.", "4 need you.") was English on every phone; it's now in all 8 languages, the same words the apps use. A chat or Coach refused because it's halted said "This taf is halted" even when the whole account was: it now says the account is halted, Coach included, and how to resume it, or the taf's own line, in the person's language (docs/api.md).
- **A sign-in approved in the browser always lands (Android).** If Android recreated the sign-in screen, or restarted the app, while the person approved in the browser (creating an account takes a while), the app came back with a new code and waited on it, so the approval made in the browser was never picked up. The app now keeps the code until the sign-in ends, and goes on with it; "Try again" still starts over (docs/accounts.md).
- **An app's sheet follows its connection (Android).** A taf's app sheet kept saying "Connected" after the vendor signed it out, and again after a new sign-in, until the taf was opened again. The app now reads the taf's Setup again each time a sheet opens and whenever planque's state for one of its connections changes, so the sheet shows tafs cloud's words as they are now (docs/android-app.md).
- **The First month pack beside Solo (Android).** Plan & usage shows the First month pack, 7,000 credits once per account, right under Solo, at Play's price, where tafs cloud's balance offers it (`buy.first_month`). Before Solo starts, the row shows its price and tafs cloud's words for why it waits, with no button. Once Solo is recorded, its price is its button. It's never listed with the other packs, and it's gone once bought. iOS follows once the App Store has the product (docs/android-app.md).
- **Android: a vendor sign-in left in its browser tab lets go.** Closing the vendor's sign-in page without finishing left the card on "Opening the sign-in…" for up to 15 minutes, and every other approval in the app stayed disabled behind it. Now, when the app is back in front with no answer from the vendor, the step lets go after a moment and the sign-in is offered again (docs/android-app.md).
- **The First month pack shows beside Solo (tafs cloud).** An account can buy one First month pack of credits, once. The balance's `buy` gains `first_month`: before a plan it says "Add it once Solo starts." in the person's language, so the app shows the pack's row with the store's price and no button; once Solo runs, the row can be bought; once it's bought, it's gone. KIFF lists the pack and says when it's bought (docs/accounts.md).
- **A taf's report takes its numbers from a count, not from its own adding up (tafs cloud).** kiff/agents now counts a run's calls that change something, by tool: sent, waiting for the owner, refused, failed. The taf reads that count after each step, and its report's numbers are copied from it. Before, the taf was told to count its calls itself, and could still say "six" of five. The rule covers every kind the count shows, a call sent with no word on whether it took effect included. Tafs get the new rule at their next Upgrade; it needs kiff/agents' count, which ships first.
- **Run now that finds nothing new says so, not "The run didn't start" (Android).** A taf that checks first and finds nothing new answers with no session; Android takes that as a success and shows tafs cloud's words for it under Run now. Before, it read as a failure.
- **Halted shows sooner, and Tafs goes back to the list (Android).** The Halt screen no longer waits for the home list before it shows where the halt stands, and reads it again after half a second and a second before every 2 seconds, so "Halting…" lasts only as long as the halt does. Tapping Tafs on a taf's screen goes back to the list.
- **Links open in the app's language (Android).** Terms and Privacy policy open kiff.dev's page in the language the app shows (`/de/terms#tafs`), English keeping its own path. KIFF's held-call page and its device-approval page carry the app's language (`lang`), so they speak it whatever the browser's.
- **Every schedule a taf runs checks first (tafs cloud).** A taf on a schedule woke the AI at every firing, even with nothing to do, so a quiet 15-minute schedule used credits all day. Now each firing reads the taf's work list through one of its apps' read tools, without the AI, and the AI runs only when the read lists something new. A firing that found nothing is quiet, not a failure.
  - **New tafs:** Coach's result names the read (`check`: the tool, its arguments, where the list is, and a field that only grows) with `cron`, and one without it is refused in words Coach acts on. The first firing hands over the queue already there (`first: "all"`); when it's empty, no run starts and the taf is still set up (`session: null`, kiff/agents' `outcome` and a line saying what happened: nothing new, halted, switching versions, waiting for credits, too big for one run, or a check that couldn't read, with its reason, which Coach also hears to fix the check); Run now answers the same way. Coach's `set-up-a-taf@4` says how.
  - **The dial:** every schedule of a checked taf carries its check, and an edit of the times keeps it. A checked taf keeps one timetable, since two would hand the same work twice: a second one is refused with the nearest single timetable. The When ring says it, in all 8 languages: "Weekdays at 09:00 · checks for new work first". Coach's `taf_dial` takes `check`, to add one to a taf from before at its next dial change (`the-dial@2`); until then, those tafs work as they did. `app_check` stays (docs/api.md, docs/coach.md).
- **iPhone and iPad: Run now that finds nothing new is no failure.** A taf that checks its work first starts no run when there's nothing new. The app no longer reads that as "The run didn't start": it shows tafs cloud's words under Run now, in the person's language, until the next tap. As Android does (docs/ios-app.md).
- **iPhone and iPad: the pages the app opens read in its language.** The Terms and Privacy policy links open kiff.dev's page in the language the app's screens are in (`/de/terms#tafs` in German, and so on, English at `/terms#tafs`). KIFF's approval page (signing in, and approving again after Face ID changed) and a held call's page in KIFF open in that language too, whatever the browser's. As Android does (docs/ios-app.md).
- **A refund the owner said didn't happen, that went through after all, reaches the phones (tafs cloud).** When the owner answers "No" to "Did this happen?" about a refund and the payment processor then shows it, KIFF takes its room on the Card again and sends `refund_notice` of kind `refund_after_no`. The people who answer hear "A refund Refunds sent, which you said didn't happen, went through at the payment processor. Its room on the Card is taken again. Check the request wasn't refunded twice." in their own language, once, and tapping it opens the taf (docs/approvals.md).
- **A refund that failed, or a charge refunded twice, reaches the phones (tafs cloud).** KIFF's `refund_notice` names its kind, the taf's agent and the call. The people who answer hear it in their own language, "A refund Refunds sent failed at the payment processor. Its room on the Card came back." or "A charge Refunds refunded was also refunded outside KIFF. Check it before anything more is refunded.", and tapping it opens the taf. Once each; an agent no taf runs tells nobody (docs/approvals.md).
- **Coach has what it needs when a taf seems stuck or must stop (tafs cloud).** On an account with a taf of its own, every Coach conversation starts with the `cards-and-holds` and `halting` skills, whatever the account's state. An owner asks "it hasn't done anything since this morning" or "stop everything" in words that name no skill, and Coach was left to read the skill itself, which it didn't always do. A first taf, with nothing running yet, starts as before. A taf's scheduled report also counts its numbers from the calls in its record, so "six" is never said of five (docs/coach.md).
- **A Card that leaves one of the taf's tools uncovered isn't signed (tafs cloud).** KIFF refuses every call no Card covers, reads included, so a taf given a tool its Cards don't name could never use it, and its run would end waiting on the owner for a Card. On Approve, tafs cloud now checks every tool of the connection against the draft's Cards and the Cards in force for the taf: one left uncovered is answered to Coach as `failed: …`, naming the tool, and the card says why ("This Card doesn't cover …"). Setup's own reads aren't counted, and Cards in force that can't be read mean "read it again", never a signature. Coach's `draft-the-card@6` says the read that tells a money tool's currency goes on a Card like any other (docs/coach.md).
- **tafs cloud's words and the apps' words say the same things the same way (tafs cloud).**
  - **English:** a run cut off by its limit says "its limit of steps for one run", as the apps say "steps". An answer to a request for a pack or a higher limit reads "An admin raised the spend limit." (an account can have several admins). "Part-way" is spelled as the apps spell it.
  - **One word for one thing in each language:** a version's trial is "Experiência", "Proef" and "Próba" in Portuguese, Dutch and Polish, on its badge as in the credits lines. An app check is "revisión" in Spanish everywhere. A step is "passo" in Italian. Dutch "retrying" no longer reads as "retried", and Polish says "odrzucił" for declined in both lines.
  - **A halt's alerts:** "All your tafs halted" and "All your tafs resumed" agree in number in French, Spanish, Italian and Portuguese ("Tous vos tafs bloqués"), and the resume's line says "They work again" for all of them. Resuming a halted taf is the glossary's Resume in those four languages (Reprendre, Reanudar, Riprendi, Retomar), where it read "unblock". German says "Er arbeitet wieder" for a taf.
  - **The glossary has four more terms:** trial, paired senders, scheduled run and Coach mode (`docs/languages.md`), held by the same test. `docs/glossary.sha256` changes with them, so KIFF's copy follows.
- **Each taf has a health: healthy, at risk or failing (tafs cloud).**
  - **What it reads:** the taf's last runs and its schedule's last times, from kiff/agents, over 7 days. Runs that couldn't start, checks that couldn't read the app, work a run dropped, runs that failed or were cut short by a limit, and times skipped as late or busy.
  - **Where it shows:** `health: {level, lines}` on the list and on the taf's page, each line saying what happened and what to do, in all 8 languages. When the taf is otherwise idle, its line says the first reason where it said the next run, so both apps show it today with no app change.
  - **The alert:** one for each bad spell, when a taf's runs fail one after another. At risk sends nothing.
  - **A later partway stop** in the same run of failures alerts again, at most once every six hours for that schedule.
  - **The page's skipped list** also says a time skipped for a version switch, a check that couldn't read the app, a run whose work goes to the next run, and a run that stopped partway.
  - When the health can't be read, it's left out and the taf shows as before (docs/reliability.md, docs/approvals.md).
  - **Our own outage never reaches an owner's phone taf by taf.** A run that failed because the AI provider wasn't serving us counts for nothing toward a taf's health, and the alert for a schedule whose runs couldn't start stays quiet when its last run failed that way.
- **tafs speaks eight languages on iPhone and iPad (iOS).** A phone set to German, French, Spanish, Italian, Portuguese, Dutch or Polish, or with tafs set to one of them in Settings, sees every screen in it, and KIFF's and tafs cloud's words come in the same language: every request carries the screens' language as `Accept-Language`, then the phone's other languages tafs ships, then English. Before, tafs cloud's requests carried none. The calendar writes a date in the language's own order ("Do., 5. November 2026", "5 de noviembre de 2026"), and its month view names the month on its own ("Listopad 2026"). Halt all answers in Siri in the phone's language, and its phrases are heard in all eight. The sample's tafs read in the language too: their jobs, Card rules and proposed actions, as on Android, and its store writes prices the way the language does ("4,99 €"). The sign-in's line agreeing to KIFF's Terms and Privacy policy reads in the language, with both links where the language puts them.
- **The apps' words in German, French, Spanish, Italian, Portuguese, Dutch and Polish, not yet switched on.** Every line in `app/strings` now has all seven languages, in `docs/languages.md`'s voice and Terms, worded as tafs cloud's catalogs where an app line says the same thing. `languages.json` still ships English only, so nothing changes on screen and no generated file changes; each language is switched on in its own change, after a look on a device.
- **One line for each meaning, and the app's English agrees with tafs cloud's (iOS, Android).** 42 keys that said the same thing in the same sense are merged into the one that stays, so each language translates them once; lines that read alike but mean different things (a button and a state, an event and a state) stay apart. A connected system is "an app" everywhere, as the glossary's Connect apps says ("Connect an app", "Connected apps", "Nothing reaches your apps", "waiting for the app's answer"); "tools" stays for what Coach or a Card holds. Also on screen in English: "Open Plan & usage to see.", "All your tafs halted", "Waiting for you", "It works again as it did before the halt.", and the delete-account error no longer ends in two full stops.
- **Android: the device's own word, credits, the new stop lines and no English left in code (Android).** Lines that named "this phone" say "this tablet" on a large screen (`{device}`, as iOS names an iPad). The test purchase and pending purchase lines say credits, not tasks. A chat that stopped because its reply ran out of room, or because the AI declined to go on, says so (tafs cloud's `stopped`, read before `capped`). The owner's own answer line is known by a flag (`byYou`), not by matching its English, and KIFF's line for a call someone else approved reads "approved, done". `app/strings/android_literals.py` fails the strings tests on any English literal left in the Android app's Kotlin, as iOS's does; the few found ("Overview", the bar's Account and Coach, the dial's ring names, the plan's periods, the drive's Stop, the share prompt) now read through `Words`.

- **A name's possessive, a list, an ordinal and a role read in the line's own language (iOS, Android).** A line names a possessive as `{taf|possessive}`, and the generators make it by the language's rule: English and German have one ("Refunds' Card", "Coach's Card"), every other language writes the name plain and words the line around it. Lists of names are joined by the platform's list formatter in the line's language, with the verb following the count ("the Cards of Chargebacks and Refunds use it"), ordinals are the platform's, and each role has its own whole line rather than "a"/"an" put in front of it. Both apps pick the possessive and number form by the lines' language (`_language` on iOS, `words_language` on Android). On screen in English: a list of three or more takes the serial comma ("A, B, and C"), "Kept: Chargebacks' Card uses it" replaces "Chargebacks's", and "{count} waiting action(s) refused" has real singular and plural forms.
- **iPhone and iPad: the lines left in code move to `app/strings` too, and a test keeps it so (iOS).** The previous extraction skipped any line with a comparison on it, so a count made in code (`"\(n) thing\(n == 1 ? "" : "s")"`), a fallback name, both sides of a ternary and words in an assignment or an array stayed English: about 400 literals, now read through `Words` (125 new keys; the rest are Android's). What stays in code is kept by a rule or by name in `app/strings/ios_kept.json`, with the reason, and `app/strings/ios_literals.py` fails the strings tests on any English literal outside it. On screen in English: a few errors take the "X. {why}" form, a taf that didn't stop reads "It didn't stop: {why}", and "Not resumed: it wasn't confirmed." replaces "Resume wasn't confirmed on this device. Try again." Two fixes beside it: the one change on Android's screen, a Card's single call reads "1 call a day", not "1 calls a day" (`card.calls_window`'s `one` form; Android otherwise only gains unused lines); and the setup plan's step mark takes the status `done` again, not the word for it, which would have lost its check in another language.
- **iPhone and iPad: every line the app writes is in the shared `app/strings` (iOS).** The lines Android had no key for get keys of their own (about 360), and 73 move onto Android's where the two apps' words had only drifted. What changes on screen: errors read "X. {reason}", as Android's do, not "X: {reason}"; the questions card says Coach, not Setup; Privacy policy is in sentence case. A run that stopped because its reply ran out of room, or because the AI declined to go on, says so in the chat (tafs cloud's `stopped`). KIFF's line for a call someone else approved reads "approved, done", and "approved by you, done" only when you did.
- **iPhone and iPad read their words from the shared `app/strings` (iOS).** About 950 lines the app writes itself now read through `Words`, by the key Android uses for the same line; nothing changes on screen in English. The chat knows the owner's own decision line by a flag (`byYou`), not by matching "Refused by you", so a translated line or "Extra room by you" is shown as it is; Connect apps and the dial know their own errors the same way. A count always takes its number first, and picks its plural form in the app's language. The generator no longer breaks `Words.swift` on a line with a line break, nor shows "%%" in a line with no values. The notification extension gets the words and the catalog. The lines Android has no key for follow in the next iOS PR.
- **Connect apps' guide has one Disconnecting section.** The removed-tafs contract moves to `docs/api.md`, the screen detail to `docs/ios-app.md` and `docs/android-app.md`, and `docs/connect-apps.md` keeps plain lines on an app's connections, From removed tafs and Clear.
- **Android's words come from the shared `app/strings` (Android).** About 1,570 lines the app writes itself move to `app/strings/en.json` by shared key, read through generated accessors (`Words`). Nothing changes on screen: English is the only language shipped, and every unit test checks the same words. The other languages follow in their own change.
- **A chat that stopped because the AI provider isn't serving us says so (Android).** When tafs cloud's chat `end` names why (`code`, `words`; `ai_unavailable` today), the line under the chat is its words as they are, then what went through if anything did, still with Retry. Before tafs cloud sends them, nothing changes. iOS follows.
- **The sample's amounts are in euros (Android).** Same numbers, in €, and the README's jobs table with them.
- **Connect apps clears removed tafs' connections again, on tafs cloud's word (Android).** An app's connections from tafs tafs cloud names as removed (`removed_tafs`) show under "From removed tafs", each with the removed taf's name, between the tafs and Other connections. With the phone's signed grants read, tafs cloud says which a live taf's Card still uses: those are kept and named, and **Clear these connections** ends the rest with one confirmation and one fingerprint. A taf merely missing from the list is never treated as removed. Needs tafs cloud's `removed_tafs` (docs/connect-apps.md › Disconnecting); before it, nothing changes. iOS follows.
- **The sample no longer has the old new-taf form (Android).** Its + button and "New taf · 1 of 3 / What's the job?" form are gone, since a new taf starts in Coach once signed in and nobody signed in saw them. Under its list, the sample now says a new taf starts in Coach. iOS follows.
- **Plan & usage says when Google Play can't sell on this phone (Android).** Where the balance offers Solo or packs but Play lists none of them, the Buy panel says "Google Play can't sell on this phone, so nothing can be bought here." (or that Play couldn't be reached, to try again), rather than showing only Restore purchases. Play's own "billing unavailable" answer reads the same. iOS follows for packs StoreKit doesn't return.
- **A scheduled run that stopped partway says so (tafs cloud).** When an app check's run stops partway and kiff/agents drops what it was working on (`failing.outcome` is `failed_unfinished`), the alert reads "Its scheduled run stopped partway. What it was working on won't be tried again. Open it to see what it did.", in all 8 languages, and is sent at once, even when the same run of failures was already said as one that couldn't start. It no longer says the run "couldn't start". Older kiff/agents send no `outcome`, and the alert reads as before (docs/approvals.md).
- **Each language is reviewed against the glossary, and fixes go forward after release.** No language waits for a native speaker before it ships or is listed (docs/languages.md, docs/store-listing.md).
- **The apps' words get one shared source, and the iPhone app asks the servers for English until it ships its languages.**
  - **`app/strings`** holds each of the apps' own lines once, by a key named for what it says, in each language, with plural forms and named placeholders. A generator writes the iPhone and iPad String Catalog and typed accessors from it; Android's files follow from the same source. CI fails when a generated file is out of date. Two lines read from it so far, with nothing changed on screen.
  - **iPhone and iPad** sent Spanish or French as the app's language on such a phone, because Siri's phrases ship in those two, so KIFF's words came in that language beside an English screen. The app now asks for the languages its screens are written in: English, until the others ship.

- **A run cut off at its turn or cost cap no longer reads "Done" (tafs cloud).** Its request is `stopped`, and its line says which limit stopped it ("Stopped at 14:20: it reached its limit of turns for one run"), in all 8 languages, on the work page and the request page, and on the list where it shows the last run. A stop tafs cloud doesn't know reads "Stopped", never "Done". Both apps already know `stopped` (a cancelled run's state), so no app change (docs/reliability.md).
- **The account's roles are glossary terms.** admin, editor, approver and viewer are in `docs/languages.md`'s Terms table in all 8 languages, with `docs/glossary.sha256` updated for KIFF's check. Coach's example of a limit with extra room writes amounts as KIFF does ("€300 a day, plus €30 …", cards-and-holds@3).
- **tafs cloud's words are held to the glossary.** A test fails when a line in any of the eight catalogs uses another word for a glossary term than `docs/languages.md` gives, and `docs/glossary.sha256` lets KIFF's copy of the glossary fail loudly when it falls behind. Coach's example of a limit with extra room is in euros now (cards-and-holds@2). One Polish line follows the glossary ("Nie powiodło się" for Failed).
- **iPhone and iPad: the sample in euros, removed tafs' connections, and why a chat stopped.**
  - **The sample is in euros,** with the same numbers as Android and kiff.dev/tafs: Bills is €5,000 a bill and €25,000 a week, and accepting a loss is €100 a call and €500 a week.
  - **The sample no longer has the old new-taf form.** Its + button and "New taf" form are gone, since a new taf starts in Coach once signed in. Under its list, the sample says so.
  - **Connect apps shows "From removed tafs"**, the connections tafs cloud names as a removed taf's (`removed_tafs`), each with that taf's name. **Clear these connections** ends the ones no live taf's Card uses (`used_by`), after one confirmation and one Face ID, and says which it kept and for which taf. Without tafs cloud's list there is no Clear.
  - **A chat that stopped for a reason tafs cloud names says its words** (`ai_unavailable`: the AI provider isn't serving us, and no credits were used), then what went through, still with Retry.

- **tafs cloud speaks the person's language everywhere it writes, and Coach and the tafs are told it.** After the work lines, the rest of tafs cloud's lines come in the app's language: the balance and credits words, refusal words, a request's steps, where a result went, push alerts, door alerts and delivery notes, a door's page, a taf's Activity, the dial, the calendar, a trial's badge, the runs page and Coach's tool lines. tafs cloud passes the person's language on to KIFF on the calls it makes for them, so KIFF's words in its answers match. A phone keeps the language its app signed up for alerts in (`POST /v1/push`), so an alert sent with no request comes in it. Coach reads a `[tafs language]` line with each message from an app in another language and answers in it; the thread shows only the owner's words. A taf's reports to its owner go in the account's language, kept from Coach chats, from its build or its next Upgrade. Apps that send `en` see no change (docs/languages.md › How it's built).
- **tafs speaks eight languages, starting with its glossary and tafs cloud's work lines.** `docs/languages.md` fixes the eight languages (English, German, French, Spanish, Italian, Portuguese, Dutch, Polish), the voice in each, and every recurring term, for the apps, KIFF and tafs cloud to translate against; taf, Coach and Card stay as names. tafs cloud reads the app's `Accept-Language` and writes a taf's work lines, the home list's line, skipped firings and the `ai_unavailable` words in that language, else English. Apps that ship only English send `en`, so nothing changes on screen until an app ships a language. Also: the sends-outside note's claim is timed from when it's taken, with a margin before it's given back.
- **The AI disclosure is structural: a sending tool can't be left out (tafs cloud, gap 14).** A connection's profile says which of its tools send to people outside the company (`sends` with `resource.outside`). Each Approve of a connection or its Card notes them for the taf, and Coach's result for a new taf or an Improve is refused while its `disclose` leaves one out, naming each tool, so Coach fixes the draft in one go. An unreadable note refuses rather than letting it through, and two Approves of the same taf at once both keep their entries (the note's write holds a short claim). Tafs approved before this are checked as before until their next Approve. No app change (docs/coach.md).
- **A run kiff/agents stopped with a code says so in its own words (tafs cloud).** The chat stream's `end` carries `code` and `words` for a run kiff/agents stopped with a `code`, such as `ai_unavailable` when the AI provider isn't serving us. A taf's work line shows the same words, the run reads carry the `code`, and Coach's line says them whole. A failure with no code is unchanged. `ai_unavailable` isn't a credits code, so a chat keeps Not sent with Retry. The apps read `code` and `words` on `end` from their next batches (Android 39, then iOS); until then they show the failure as before (docs/chat.md).
- **Connect apps names removed tafs from the server (tafs cloud).** `GET /v1/connect/apps` carries `removed_tafs`: the KIFF agents KIFF archived, as removing a taf does, that are no taf of tafs cloud's any more, for someone who manages Cards. A grant whose agent is one of them is from a removed taf, so the apps can bring back clearing those connections on this alone, never on a taf missing from someone's list. With the phone's signed grants read, each also lists its live connections with `used_by`, the live tafs whose Cards still use one, which Clear keeps: one rule in tafs cloud instead of one per app. The apps follow in their next batches; until then nothing changes on screen (docs/connect-apps.md › Disconnecting).
- **Credits are the balance on Android: tasks, AI use, Go on and the trial are gone.** Plan & usage opens on tafs cloud's balance (`GET /v1/usage/balance`) in its words, with a bar, the warning, and Raise the limit, signed by the device key with the limit it raises from (`PUT /v1/me/usage/limit`). Settings' Plan card and Home's line are tafs cloud's `plan_line` and `home_line`. A chat or Run now refused for credits shows its words, with the chat's words back in the box and no Retry. Asking an admin is for a raise on Company and a pack on Solo, in the words both apps share. Packs are of credits, which never expire. A taf's Usage row opens its credits runs. The sample shows a balance and credits. It ships after tafs cloud's balance read: a tafs cloud without it shows no balance.
- **A door's message or an app's event waits while the tafs wait for credits (tafs cloud).** Refused for credits, it's kept for 48 hours, with nothing sent in the business's name, and runs once the tafs go on, oldest first across the account's tafs; one that comes meanwhile waits behind them. One still waiting after 48 hours doesn't run, nor one refused 3 times for anything else: its door's or event's stop on the taf's dial says it went unanswered for 7 days, and the people who answer the taf's holds are told once, in one push per taf. No app change (docs/accounts.md › Credits and plans).
- **Credits are the balance in Plan & usage (iOS).** Plan & usage opens on the balance in tafs cloud's words (`GET /v1/usage/balance`, docs/accounts.md › The balance read): its line, a bar, what comes next and the warning, with **Raise the limit** on Company and **Add credits** or Solo where tafs cloud offers them. The Credits panel follows. Settings' Plan card and Home's plan line show tafs cloud's `plan_line` and `home_line`.
  - **Raise the limit** is signed by the device key behind Face ID, as Go on was, with the limit it raises from (`from_cents`). Then the balance is read again; if that read fails, only Read it again is offered. A limit that changed meanwhile is read again, and the phone's ceiling says to contact KIFF.
  - **A chat, Coach or Run now refused for credits** shows tafs cloud's words, with no Retry, and the message stays in the composer.
  - **Asking an admin** is for a raise on Company and for a pack on Solo, in the request lines both apps share.
  - **A taf's Usage row** opens its credits this period, its runs, in place of its counted tasks.
  - **Gone:** the tasks, packs of tasks and counted tasks, AI use and Go on, and the plan's trial and its words. KIFF's usage read is read only for the plan, the store, billing and requests.
  - **The same as Android:** a failed read shows tafs cloud's words, a run's time is a short time, the last period is read again on each refresh, the sample shows the same balance and credits, and Plan & usage has the same order and request lines.
  - **Deploy order:** this build ships after the server side of credits as the balance. A tafs cloud from before shows no balance, and the Credits panel stays.
- **Connect apps lists an app's connections by taf (both apps).** One section per taf, those needing you first, then by name, each row the vendor's account or the app's name. Those whose taf the app can't name come last, under "Other connections", each with the same row and no action on them all at once: a taf missing from this person's list isn't proof it was removed. Clearing removed tafs' connections waits for tafs cloud to mark them. A sign-in that never came back reads Cancel there too, as on a taf's page.

- **Credits in Plan & usage (iOS), read only.** Above the tasks, which stay as they are, Plan & usage shows the credits the tafs used this period or the last: the period's line, each taf, Coach past its fair use, and what wasn't counted and why, all in tafs cloud's words (`GET /v1/usage/credits`, docs/accounts.md › Credits). A taf opens its runs, and a run opens its request, as on Android. Nothing is charged in credits yet. A tafs cloud without the read shows no panel, so there's no deploy order, and one that can't read KIFF says so in its words.
- **Credits in Plan & usage, read-only (Android).** Above the tasks, which stay as they are, a Credits panel shows tafs cloud's `GET /v1/usage/credits` in its words: the period, each taf's credits and runs, Coach past its fair use, what wasn't counted, and the note, with Last period and This period. A taf's row opens its runs, 50 at a time, each opening its request. A tafs cloud from before credits answers 404 and no panel shows, so there's no deploy order. iOS follows.

- **What's written while a taf or Coach works goes to its run once, and tafs cloud holds what it doesn't take (iOS, as Android does below).** iOS sends `hold: true` on `steer` (docs/chat.md) in a taf's chat and, new on iOS, in Coach's, which used to wait in the app.
  - The run reads it at its next step, or tafs cloud holds it for that chat. It shows dimmed with "Waits until it's done, then goes as your next message", as on Android, and goes as the person's next message once the taf is done.
  - More than tafs cloud holds (`held_full`) leaves the words in the composer, with tafs cloud's why as it says it. A message that didn't reach tafs cloud stays as not sent, with Retry.
  - The app's own queue is gone: it never sends what a run didn't read. What a stopped run never read shows once as not sent, with tafs cloud's why and Retry, which sends it as a new message. It's remembered as handled only when Retry, Edit or Delete takes it, so a restart before that shows it again.

- **Connect apps lists a connection under the app planque names for it (both apps).** planque now gives every grant its app (`entry`), a vendor's own sign-in included (kiff/planque#115 and #116's reviewed list of vendors' own servers). The apps match by it, or by the grant tafs cloud named for a row, and no longer work an app out from a connection's toolkit or server: a connection planque leaves unnamed, because its app is unclear, lists under none. On Android, an app's connections now scroll when there are more than fit: the rest were cut off below the screen.

- **The AI disclosure comes first, in the message's language, and "Built with tafs." is gone (tafs cloud; needs kiff/agents#363 deployed first).**
  - **Each taf's definition** carries its disclosure line in Spanish, French, German, Portuguese and Italian too (`disclose.lines`), naming who it writes for, and no `credit`. kiff/agents puts the line first in each text part of an outside message, in the message's language, said once. No message carries "Built with tafs.", on WhatsApp, SMS or email (the owner's call, 7 Nov).
  - **A taf is asked** to start each outside message with its line, word for word, in the language it writes in, and is given each one.
  - **A door's reply** starts with the taf's line, naming who it writes for, in the reply's language. Nothing is added after it.
  - **Coach's skill** is `draft-the-card@5` (on top of the company mailbox's `@4`): the line goes first, in the message's language, with nothing added. A live Coach moves to it on the Upgrade.
  - **Within 300 bytes in every language:** kiff/agents refuses a definition with a longer line. A new name the taf writes for is now at most 261 bytes (it was 272), so its line fits in German, the longest. A name stored before, or a company's name from KIFF, up to 272 bytes still works: a language whose line doesn't fit is left out, and English heads the taf's messages in it.
  - **Deploy order:** kiff/agents refuses a definition with a field it doesn't know, so `disclose.lines` needs kiff/agents#363 live first. Until the Upgrade re-saves a taf, its definition keeps its old `credit`, which kiff/agents#363 accepts and never sends.
- **A taf whose scheduled runs keep failing to start says so, once (tafs cloud).** kiff/agents now gives the items of a run that failed before any turn back to the next run, and keeps a schedule's `failing`. The sweep tells the phones that answer holds, Time Sensitive, opening the taf:
  - **a recurring schedule:** once for each run of failures, after two firings in a row or one six hours old with none working since. Not for one its owner turned off.
  - **a follow-up the taf set, or a one-time run:** at once, since its work won't run again by itself ("A follow-up it set for itself didn't run, and won't try again. Open it to see why."). Not while it's still on (its owner moved it during the run), and never said again a month on.
  - **Nothing for a halted taf or account,** which doesn't try again until it's resumed. It's said once it is.
- **Customer mail from the company's own mailbox (`draft-the-card@4`).** A taf that writes to customers sends, by default, through a mail connection the company already has, so mail comes from its address and replies land where its staff work. Coach suggests a dedicated mailbox (`support@`), never a person's own inbox, and asks only for what the taf does: the send tool alone when it only sends. A connection the account has comes first only when it is that dedicated mailbox: one that is a person's own inbox is never reused for customer mail. An inbox of the taf's own stays an option. The skill loads once the Upgrade names it.
- **The reference check** in CI also fails on a comment that names who decided rather than what was decided, and the two left in the tree now state the decision alone.

- **Delete on a message that wasn't sent (Android, as iOS).** A Not sent row has Delete beside Edit and Retry: the words leave for good, nothing is sent, and one of tafs cloud's `not_sent` never comes back. A Retry that doesn't send now leaves a `not_sent` owed, as on iOS, so a restart still shows it. A flaky test of recipes counted background reads as sends; it counts writes only.
- **What a stopped run never read shows once, as on iOS (Android).** Each of tafs cloud's `not_sent` messages becomes its own Not sent row once, with tafs cloud's why, Edit and Retry. Once Retry or Edit takes it, it's remembered by a hash, never its words, across launches, so it never comes back, and a second Retry finds nothing to send. Until then a restart shows it again. Before, Android drew it from each read of the thread until a newer run.
- **A message written while the taf works is sent once, and tafs cloud holds it (Android).** The app sends `hold: true` on a steer and never resends on a refusal. What waits shows as the person's own words, dimmed, with "Waits until it's done, then goes as your next message". What a stopped run never read shows as Not sent with tafs cloud's why, Edit and Retry. `409 held_full` shows tafs cloud's words and puts the text back in the box. An older tafs cloud's plain 409 still sends the message as a new one, so there's no deploy order. iOS follows in the next app batch.
- **tafs cloud holds a message written while the taf works, when its run doesn't take it (tafs cloud; apps opt in with `hold`).**
  - **The refusal:** a steer refused because the run had ended, or held as many messages as it takes, was a 409, and each app kept its own queue: iOS held the message, while Android sent it at once.
  - **With `hold: true`,** tafs cloud keeps the message for the chat it was written in, and answers 202:
    - it's sent at once as the person's next message in that chat when the chat's run is over (`sent`);
    - otherwise it waits (`held`), and a second message joins it;
    - it goes once the taf is done in that chat, and only there: at the end of an answer's stream, or on the next read of the thread or of that chat. One read sends it, and a failed send puts it back.
  - **Never cut:** at most 10 messages, 2,000 characters in all, wait in a chat. One more is refused whole (409 `held_full`), with words the app shows, and the app keeps it. When what a run never read and what was held make more than one message, whole messages go in order, and the rest goes next.
  - **What the run never read:** for a run written to with `hold`, what it hadn't read when it ended joins what waits in its chat, once, and goes as the next message.
  - **When the person stops the run,** what it never read, and what was written for it by then, isn't sent. The thread's `steering` shows them as `not_sent`, with why ("You stopped it before it read this."). A message written after the stop goes as the next message.
  - **Without `hold`,** nothing changes. Android and iOS move to it in their own batches (docs/chat.md).

- **Connect apps lists every connection to an app, and a connection's fix works and is where the owner looks (both apps; Android 36's phone round).**
  - **Every connection to an app:** an app's connections were only those made from its catalog entry, so the vendor's own sign-ins Coach started (which carry none) never showed under it. Stripe listed one leftover provider grant, not its 7 live sign-ins. A connection is now also the app's when it's a managed provider's whose toolkit is the app's slug, or, for any other, when a whole label of its server's host is (`mcp.stripe.com` is Stripe; `notstripe.com` and a provider's own host aren't).
  - **Sign in again reads KIFF's words first:** it refused before the fingerprint ("KIFF's words for this connection aren't read yet") wherever nothing had read them. It reads them now, as the approval card does, and says why when they can't be read.
  - **A provider's connection:** planque can't sign a connected managed-provider grant in again in place, so neither app offers it. The row says to disconnect it and connect it again, before any fingerprint.
  - **A taf's own app sheet** (Setup › an app) shows the connection's last failed call and its fix, Replace the key or Sign in again, with the confirm mark, as Connect apps does.

- **A connection says what's wrong with it and how to fix it, and a sign-in that never came back can be cancelled (both apps, gap 13).**
  - **In Connect apps,** each of an app's connections says its last failed call in KIFF's words: the newest call through its tools that wasn't done, its taf's own first. Beside it is **Replace the key** for a key-based connection, or **Sign in again** for a sign-in one, behind the fingerprint, next to Disconnect and the tafs that use it. Both carry the confirm mark, so Coach can point at them but never tap them. A record keeps the call's tool as KIFF names it, to tell which connection a call went through.
  - **Needs planque:** replacing a key, or signing in again, on a connection that's still connected needs planque to take it. Until then, planque answers why, and the app says so. A signed-out connection works today.
  - **A grant waiting on a sign-in that never came back** is listed on its taf ("Waiting for the sign-in at Google Calendar"), with **Cancel**, which ends it, so the owner can connect again.
- **Stop clears once Coach has handed over (both apps).** After a hand-over, the stream could stay open a while, and Stop with it. Once the thread shows the result card answered and Coach's words after it, with nothing new from the owner, Coach's run reads as done. tafs cloud's own `working` still says when a run is at work.
- **Retry sends a message's own files (Android).** A message with files that isn't sent keeps them, shown on it. Retry sends both, and Edit puts the words in the box and the files back in the tray. Retry waits, greyed, while another message is on its way, or, for a message with files, while the taf is at work, as Send does; the message stays Not sent until it goes. Files sent alone go back in the tray, as before. On iOS, such a message stays in the composer with its files, as before.

- **One fingerprint for Coach's install (Android).** Installing a version Coach asked for took two fingerprints: one for the Card, then one for Coach's held call. Now the Card's confirm names Coach's held call, and KIFF approves both under one signature (`hold` on `POST /v1/me/agent-drafts/{id}/confirm`, docs/needs-from-kiff.md item 7). Without a Card, Coach's approval is signed before the version goes live, so its fingerprint is the only prompt. A KIFF that ignores `hold` gets the call answered as before, so there's no deploy order. iOS already asked once.
- **A message that wasn't sent stays in Coach's thread, marked Not sent, with Retry and Edit (both apps).** On Android, a message that didn't reach tafs cloud left the thread, with only an error above the box; iOS kept it, with Retry, but no way to change it. On both apps it now stays where it was written, marked "Not sent · " and why, with Edit, which puts its words back in the box, and Retry, which sends it once.
- **A call that wasn't done says what the owner can do, and a taf's Ask Coach card (both apps, kiff/tafs#476, gaps 10 and 15).** An Activity row that wasn't done now shows tafs cloud's `next`. Each kind does this:
  - **`reconnect`:** "Connect it again" signs in again where the vendor signed out the connection whose tools include the call's tool, and otherwise opens Connect apps.
  - **`ask_coach`:** opens Coach on the taf with tafs cloud's first message in the box. Nothing is sent.
  - **`try_later`, or a kind the app doesn't know:** a line.

  Only someone who manages the taf gets a button. A taf's chat shows the `ask_coach` cards from its thread at their time, with Ask Coach, which writes the card's words into Coach's box on that taf. No other card of a taf's thread is drawn. Both work with or without tafs cloud's side: a row without `next`, or a thread without cards, reads as before.
- **A Card's total for each item apart, and KIFF's 7-day window, in the owner's words (kiff/tafs#476).** KIFF can now count calls for each item a call names (one reminder a week for each invoice) over `rolling_7d`. tafs cloud sends each such total as its own line in the Card's `each_item` ("1 call in any 7 days for each invoice id"), never in the Card's own count or money total. Both apps show these lines under the Card's limits, and a hold over one says which item ("…: this would be the 2nd for in_42"). Both apps know `rolling_7d` ("in any 7 days" / "in the last 7 days"). Android also read every KIFF window but a calendar day as "a day"; it now reads each as KIFF names it, as iOS did.
- **Coach hears every install.** Installing any version tells the owner's Coach, and Coach's chat shows "Version N is live." Before, only a version Coach made did: after v4 (Coach's), going back to v5 (an Upgrade's) said nothing, so Coach still read v4 as live. Coach's line says whether it made the version, and gives the version's own note when it didn't.
- **A steer carries Coach's lines.** Words written to a Coach run that is still at work, such as one paused on an approval, now carry Coach's unread lines. Before, those lines stayed under every later message until the run ended, and Coach never read them. A refused steer leaves them for the next message.
- **Docs:** the What I did card's steps stay 30 days with the drive's end in Coach's chat, not a day.
- **Narrow sign-ins (gap 5).** Coach connects an app found by search with the tools the taf will use (`request {entry, uses}`, `connect-an-app@5`). planque then asks the app's sign-in for only what those tools need, or tells the owner plainly that it asks for more. Order: kiff/planque#113, then kiff/agents#361, then this. The skill loads once the Upgrade names it.

- **Failures the owner can fix (kiff/tafs#476, gap 10).**
  - **Activity says why.** A refused call shows the words KIFF recorded for it, quoted as the app's: "The app refused it: "Agent Mail refused it (it answered 403): Forbidden."". Before, tafs cloud put the fixed line "The app refused it" in their place.
  - **And what to do.** A `not_done` row carries `next: {kind, words, tool, taf, message?}`: `reconnect` for a 401 or 403, `ask_coach` (with Coach's first message) for a 404 or a wrong action, and `try_later` for a 429 or a vendor down. The apps draw the buttons.
  - **Coach reads it too.** `taf_run` lists a run's `calls_not_done`, each with what came of it, the app's words and the next step, from kiff/agents' kept record lines. A new `taf_check` ("Check this taf") gives the latest failed call per tool across the taf's last runs, with no call to any app. It has its own Card, `coach-checks`.
  - Coach checks a connection with one read before calling it ready, so a refused key is caught at connect (`connect-an-app@4`).
- **One sign-in per vendor (gap 2).** A new taf reuses the account's sign-in to a vendor it already connects (`reuse`), and the owner approves its tools alone. That held for 3 of 3 recipes today.
- **Who a taf writes for (gaps 16, 18 and 20), and "Built with tafs".**
  - A result's `writes_for` (Coach asks once, offering the company's name) names the disclosure: "Written by an AI agent for Kiff Agents OÜ.", or "Written by an AI agent." when no one is named, never "for the company".
  - "Built with tafs." follows it in messages to people. A disclose entry with `credit: false`, such as Chargebacks' evidence, carries the disclosure alone.
  - With no name from Coach, a taf on a company account writes for the company's name as KIFF gives it, never a personal account's. The Upgrade gives that name to a taf from before that names no one.
  - A version built before this one says "for the company". Installing it, or switching back to it, names no one, so the Upgrade still gives the taf the company's name.
  - Coach sets a mail app's sender name and offers the company's own domain, with its DNS records checked (`draft-the-card@3`).
- **Settings go to Coach (gap 15).** A taf says a setting change is Coach's, and shows an `ask_coach` card with the owner's request. Its definition shows that card alone.
- Coach's `the-tafs@2` reads `taf_check` and `calls_not_done`.
- **Order:** kiff/agents#358 (`credit`, `show_types`, `writes_for`, reasons on record lines) deploys first, since kiff/agents decodes definitions and results strictly. Coach's new skill versions load once the Upgrade names them.

- **Coach's skills at the versions its live definition names (P0 after kiff/tafs#572).** tafs cloud named a new chat's skills, and a tool's add, at `lock.json`'s versions, which change with a deploy, while kiff/agents loads only the versions the account's live Coach definition names, which change with the Upgrade. After #572's deploy, every new setup chat was refused (`skill_not_named`) until the Upgrade. Now each skill is named at the version the live definition names, read from kiff/agents, and one it doesn't name yet is left out. A deploy can't run ahead of the Upgrade. The test fake refuses an unnamed skill as kiff/agents does.
- **The apps draw a drive's end where Coach's thread puts it, and place a card by its `waits` (kiff/tafs#568, the apps' side).** Both apps read the thread's `drive` items and show each "What I did" at the time the drive ended: after the drive's card, before what Coach writes next. Their own card after the list, with its Close, is gone. When a drive ends, the app reads Coach's thread again; on Android, once the stop call has landed. A card goes after Coach's words written with it, up to the owner's next message or its answer, only when it carries `waits`. Every other card stays at its own time. tafs cloud marks every card, old ones too, so the apps keep no list of card types of their own.
- **Setting up a taf from a recipe: fewer consents, and nothing promised the connection or the Card can't do** (kiff/tafs#476, from "Chase overdue invoices" on Android 35). Coach's skills connect-an-app@3, draft-the-card@2, name-the-tools@5 and set-up-a-taf@3:
  - **Only what a connection can do:** Coach checks the vendor has an operation before offering it, and says so, and why, when an answer can't be met.
  - **One approval for Setup's reads:** the accounts list and the operations' field list go in the first connection request. The connection's approval and sign-in cover them, with no question of their own and no second fingerprint.
  - **The account's mode, as reported:** once the accounts list says it, Coach says it then ("Your account there is in test mode"), not "I can't confirm whether…".
  - **Holds and limits as agreed:** a recipe's or the owner's hold stays a hold. A limit the Card can't count (one per item, or over a week) goes in the taf's instructions, and the rules say KIFF can't enforce it yet.
  - **The disclosure in a taf Coach sets up:** a tool that writes outside the company is named in the result's `disclose`, and kiff/agents ends each such call with "Written by an AI agent for the company.". tafs cloud checks each tool is one of the taf's, keeps it on the taf for each later build, and merges it over the job's own, so a starter job's taf gets it too. An improvement may add to it, never take any away: its disclosure is written to the live taf as soon as the version is made, before it's installed, which only ever adds a line (a tool the live version doesn't have just gets none).
  - **Small:** a questions card's text never counts its questions, and what a taf tells the owner goes to its chat, never to an address they didn't give.
  - **Order:** kiff/agents' show tool must take a result's `disclose` before accounts get these skills with the next Upgrade.

- **A refusal is that one call's, and a dispute's evidence always says an AI agent wrote it** (kiff/agents' Eval 42193895936).
  - **A refusal ends that item only:** a door asked for a refund of one charge, and the taf answered "The owner already refused a refund of this charge": the owner had refused a refund of another charge, which kiff_pending listed. The taf's rule now says a refused or waiting call is that one call, on the item its arguments name, and settles nothing about another item.
  - **The AI disclosure in a dispute's evidence:** Chargebacks sent evidence to the card network (a product description, a tracking number, a carrier) with no word that an AI agent wrote it. The job now has kiff/agents put "Written by an AI agent for the company." in its `update_dispute` evidence, at the end of its free text or product description, whatever the model wrote (kiff/agents#352), and the shared rule asks for that same line. This needs kiff/agents#352 deployed first: kiff/agents refuses a definition with a field it doesn't know. Accounts get it with the next Upgrade.

- **A drive's end is a message in Coach's chat, and the cards' waits come from kiff/agents (kiff/tafs#568, tafs cloud's side).**
  - **The drive's end, in the thread at its time:** when a drive ends, tafs cloud keeps its end with Coach's chat, once: the request, why it ended in the person's words, and each step from its record. The thread has it as a `drive` item at the time it ended, so both apps can draw it in place, with no "What I did" card of their own after the thread. The apps' side comes in its own change.
  - **A step is recorded once:** a step again under the same caption ("Looking for Versions", once in Setup and again in Card) is one line, which keeps every touch of it ("Setup, Card").
  - **Where Coach left the app:** after a drive, Coach says where it left the app ("I left it on Save Cancels' Versions"), never what is on screen now, which it no longer sees (Coach's skill drive-the-app@2).
  - **Which card waits:** the live line says the run waits for the person on a card kiff/agents marks `waits: "owner"` (kiff/agents#351), and keeps no list of its own. Its list had left out the drive card. A card from before kiff/agents marked them, with no `waits` at all, is read by its type, as kiff/agents' lists were then, and every card in the thread and the stream carries the mark, so the apps can drop their lists for old threads too.
- **Each streamed message logs where its wait went:** one line per message in tafs cloud's log, with each step before the run starts, the run's first event, the first words sent and the end, in milliseconds, and whether the function started cold. It holds numbers only, never a body, an id or a word.

- **An answer to a hold that comes before its run has paused is early, not failed.** When the owner answers a hold before the taf's run has stored its pause, kiff/agents asks for the alert again. tafs cloud passed that on as a 502 with an error in its log, about 70 a day from the Eval's fast answers. It now answers KIFF 409 with a warning, and KIFF delivers it again the same way. The phones are still told once, and these early deliveries no longer count toward giving the alert up.
- **On Android, Coach's words with a drive card come before it.** The drive card waits for the person like an approval, so Coach's line written with it ("I'm waiting for you to approve the drive.") goes above the card, as on iOS, not under "Approved".
- **A drive names what Coach touched by its title.** The caption and the "What I did" card said all of a tapped row's words ("Refunds · R Refunds Works from its chat Ready Refund a charge …"); they now say its description, or its first words that aren't a lone letter ("Refunds"). Coach's tree keeps the row's words whole. Android; iOS names a touch by its registered label already.
- **Coach can see the app on Android, and the person sees the drive.** On the first live drive (Android 34, an emulator against tafs cloud) Coach got no answer to either look, and the phone showed no border, caption or dot. The app's answer left out the screenshot's `media_type`, since its JSON leaves out a value equal to its default, so tafs cloud refused every answer that carried a screen (400). The overlay's windows were given the window's token instead of the activity's, so the system refused them, and the refusal was dropped without a word. Both are fixed, with a JVM test that an answer's image carries its `media_type` and a device test that the border and caption are on screen while a drive is on. An overlay window the system refuses is now in the drive's diagnostics. Also: the "What I did" card comes into view when a drive ends, rather than below the end of Coach's chat; and "Approve with …" names a fingerprint enrolled while the app was open, rather than the screen lock until the app restarted. iOS was unaffected.
- **A short line the run waits on stays in the thread after a card's answer.** tafs cloud leaves a run's progress lines out of the stored thread, and kept a line the run waited on only when a card, the owner's message or a resumed turn came before the run wrote again. A connection's approval answered on its card, with no turn marked resumed between, could drop a waiting line of 90 characters or less ("Falta que apruebes la conexión…") from the thread. The owner's answer to a card now counts as the wait too, as kiff/agents' grader reads it. The live stream needed no change: a started run's stream ends when the run waits, and its held text goes out then.
- **A taf says what a check found in a clause, not in a sentence about checking.** In kiff/agents' Eval a taf wrote "I read the charge first. It is €1,000 with nothing refunded yet." and "Antes lo revisé: el cobro era de €1.000 y no tenía reembolsos previos", as its chat rule's own example showed, and the judges read it as narrating its process. The rule now puts what a check found inside the sentence that says what was done, as one short clause at most, or leaves it out: "I refunded €3 on the €1,000 charge, which had nothing refunded before." A held call's sentence carries none of it, as the held call's own rule already said, and after a held call the owner approved or refused, the check made before asking gets no word. When the owner asked only for a check, with nothing to do, what was found is the answer. A held call's deadline is said in words in the chat ("today", "in ten minutes"), as the chat's own rule says, and as a time in the owner's zone only in a scheduled run's report: the two rules disagreed, and a taf wrote "until 06:59 UTC today". Accounts get it with the next Upgrade.
- **Coach drives the app, on the phone (kiff/tafs#544).**
  - **Starting:** Coach's drive card asks to drive the app for one request. Approve signs its Start with the approval key, behind Face ID or the fingerprint; Not now declines.
  - **While it drives:**
    - the whole app's border glows;
    - a caption above the tab bar says each step, with Stop;
    - a dot marks each touch a moment before it;
    - Coach's chat minimizes into the Coach button, which shows it's acting.
  - **What Coach can do:** look at the app's own windows (the screenshot, and the view tree when it asks), tap, long-press, swipe, scroll, type, go back, and read the app's recent errors and failed requests. Requests are by route and status only.
  - **What Coach can't do:**
    - it never confirms: a touch on Approve, Disconnect, Remove, Halt, Resume, an answer to "Did this happen?", extra room or a purchase is refused before it reaches the control;
    - while it drives, every approval asks for the fingerprint, since the approval window is suspended;
    - secret fields and open approvals are blanked in its screenshots;
    - the keyboard stays down.
  - **The drive ends at once** when the person:
    - touches the app (that touch isn't delivered);
    - goes back;
    - taps Stop or the Coach button;
    - leaves the app;
    - locks the phone;
    - signs out.
  - **At the end,** Coach's chat opens again with a "What I did" card.
  - **Both apps** follow `docs/drive.md`, with contract tests for the coordinates, the signing vector, every window guarded, every field checked and every mark.
- **A drive is its own chat's, exactly.** `app_look` and `app_act` now declare `agent_session`, which kiff/agents fills with the chat each call comes from, inside KIFF's permit; tafs cloud refuses a drive tool from any other chat. Before, it could only check that every Coach run of the person at work was in the drive's chat. A connection made before keeps that check. Needs kiff/agents' `agent_session` deployed first; the tools' schemas reach KIFF when each owner gives Coach its drive tools.
- **Coach asks test or live before any rules name a mode.** In a Spanish setup in kiff/agents' Eval, Coach wrote into the rules that every refund was pinned to test mode "como elegiste", then admitted it had never asked. The core rule now says to pin a mode on the calls only once the owner chose it, asking first, and `name-the-tools@4` asks the mode before Coach proposes tools that move money and before any rules or draft name one; until the owner answers, nothing names a mode, and "as you chose" is only ever their answer. Accounts get it with the next Upgrade.
- **Coach drives the app, tafs cloud's side (kiff/tafs#544).** Coach can operate the app on the owner's phone while they watch, one step at a time: it looks at a screenshot, then taps, types, swipes or scrolls inside the app, and never confirms anything. The person asks, or accepts Coach's suggestion, and approves that one request with the approval key (a drive card; `POST /v1/coach/drive`, refused for the device key or a window's tap). Coach's new tools, `app_look` and `app_act`, reach the phone through the drive stream (`GET /v1/coach/drive/{drive}/stream`) and come back with the screen as an image; KIFF decides each under two new Coach Cards. The drive ends, and never resumes, when the owner touches the app, leaves it, stops it, the phone locks, they sign out, the account is halted, Coach says it's done, or nothing happens for 15 minutes, and the person is told why in their own words ("You touched the app, so it's yours now"), the words the apps keep. Its starts, steps and end are in the account's events with tafs cloud's own words. Coach's new `drive-the-app` skill. The contract for the apps is `docs/drive.md`; the apps' side, the drive card in kiff/agents, and images in Coach's tool results follow. Existing accounts give Coach the two new tools and Cards once, as for any new Coach tool.
- **Sonnet 5.5's cache reads are $0.10 per million tokens**, as Anthropic lists them (0.05× input), not $0.20. A taf's costs and cost cap counted its cache reads at twice their price.
- **The tafs think a little more: Sonnet 5.5 at medium effort.** At low effort, a taf once wrote a call with a mistyped id in kiff/agents' Eval. Medium gives each write a little more care, still at Sonnet's price. Coach stays on Opus 5.5 at low effort. Accounts get it with the next Upgrade.
- **Coach on Opus 5.5 at low effort, the tafs on Sonnet 5.5 at low effort.** Coach keeps Opus 5.5, now at low effort; every taf moves to Sonnet 5.5 at low effort, at its own price ($2 / $10 per million tokens, cache reads $0.10) and never at fast output, which Sonnet 5.5 doesn't have. Measured on 5 Nov 2026 beside the shipped settings: Coach's first words came sooner and its checks held, and Sonnet did the chargebacks job in full at about half the cost. Accounts get it with the next Upgrade.
- **Coach's first line shows on the phone.** On Claude Opus 5.5 the short line Coach writes before it works ("Miro las últimas ejecuciones de Reembolsos…") comes back as a progress note, not as text, so no app drew it: the owner saw "Thinking…" until the message. Coach's and the tafs' definitions now ask for these notes and not the thinking summary (`thinking: "updates"`, with kiff/agents' `update` event), and tafs cloud puts each note on the live line as its words (one line, without its closing mark, since the app adds "…"), at once in place of "Thinking…". When the line comes as text instead, tafs cloud holds the turn's text while it is one short line, shows it on the live line the same way, and keeps it out of the answer and the stored thread when a tool call follows; an answer waits for at most its first line. iOS now draws the live line as Android does, and no longer draws the thinking summary. Needs kiff/agents with `thinking: "updates"` deployed first; the next Upgrade writes the definitions.
- **Disconnect asks first, and ends one taf's connection only.** In Connect apps, Disconnect now asks "Disconnect Stripe from Refunds?" (or "Disconnect Stripe?" once the taf was removed), names any other taf whose Card draws on its tools, which stops too ("Disconnect Stripe from Refunds? Chargebacks stops too."), says other connections to the app stay, and has Cancel; Face ID or the fingerprint then asks the same words. The footer says it: "Disconnecting one removes its tools from that taf only, and from any taf whose Card uses them. Other connections stay." The taf's own Card tab uses the same words. iOS and Android. With planque's change, a sign-in another taf still uses is never revoked by disconnecting one.
- **Coach says what it's doing before it works.** When a reply will use tools, Coach first writes one short line in the owner's language ("Miro las últimas ejecuciones de Reembolsos…"), then nothing more until its message. Its rules had said to write nothing between tool calls, so the owner's first words came only after every tool turn: about 22 s in a run measured on 5 Nov.
- **A message in Coach's live chat goes on in it, with nothing more read.** The apps name no session for the live chat, so tafs cloud took each such message for a new chat: it read KIFF's held calls, the halts and the account's connections again, and put the connections before the message. It now finds the person's live chat as kiff/agents does (their latest own chat, not put away, written in within 30 minutes) and sends the message there by name. Only a message that opens a new chat reads and carries them.
- **Removing a taf disconnects its connections.** Remove for good now also disconnects each planque connection the taf's agent asked for that holds a sign-in (its tools out of KIFF, its sign-in revoked at the app), signed under the same confirmation, and says so; one that won't disconnect is named. One that another taf's live Card still covers is kept, and the result names that taf ("Kept your Stripe connection: Chargebacks uses it."). Before, a removed taf's sign-ins stayed live and Coach offered them to the next taf as "already connected". iOS and Android.
- **Disconnect from Connect apps.** A connected app opens its connections (iOS: a screen of its own; Android: over the list), each with the taf it was set up for ("Set up for …", or "Its taf was removed"), any other taf whose Card covers it ("Also on …'s Card"), and Disconnect (Face ID or the fingerprint first). iOS and Android.
- **Several Cards read as several.** In a taf's wallet stack, each Card behind the front one is drawn a shade darker, so Cards of the taf's one colour no longer blend into one. Tapping a Card still brings it forward. iOS and Android.
- **A halted taf's chip opens Halt.** On the home list, the red "Halted" chip opens Halt, where the taf is resumed: the account's Halt when the whole account is halted, else the taf's. iOS and Android.
- **Android, landscape:** the navigation rail is thin (a fixed width, one-line labels, Coach compact), and the list beside the open taf folds away from a handle on its edge, so the taf takes the width, and comes back from the same handle; kept across rotation.
- **Coach says the test mode once, plainly.** A mode Coach pinned on the calls, as the owner chose, is what the calls do; Coach says it once, with the account's own mode as unknown ("Every call is pinned to test mode, as you chose. Whether the account itself is test or live, check it before going live."), and never repeats it (`name-the-tools@3`, the core rule).
- **A calendar of the tafs' scheduled work.** The calendar icon at the top right of the Overview (beside + on a phone's home) opens Day, Week and Month, Week first, with Today, previous and next, and a date to pick. On a tablet on its side, a month navigator sits at the upper left, the calendar beside it and the agenda under them; upright and on a phone, a week strip with the selected day's work under it. Only work still to come: the occurrences of live schedules, follow-ups the tafs set and runs' next tries, never past Activity. tafs cloud computes them for a range (`GET /v1/calendar`) in the account's zone, across a DST change, without paused tafs or tafs the person may not see; iOS and Android draw what comes. A tap opens the taf. Read-only.
- **Android: "Not sure, help me check".** A "Did this happen?" card shows KIFF's context for the call: which taf, when, the amount, what it acted on, why KIFF can't tell, and a link to the vendor's record. It offers KIFF's three answers in its words. **Not sure** is signed by the device key alone and opens Coach on that call to help check it; the owner still answers the card.
- **Coach helps check a "Did this happen?" (kiff/tafs#498, step 3).** The card's third answer, "Not sure, help me check", opens Coach on that one call (`POST /v1/setup/check-call {proposal}`) with its record as KIFF carries it, and the new `did-this-happen` skill has Coach read the vendor through the taf's connection and say which answer fits; the owner answers the card, signed, as before (`docs/coach.md`).
- **Coach offers the kind of connection the account already uses.** A new Coach conversation starts with the account's connections, which tafs cloud reads from planque with the person's session, for the tafs they may see, each with how it reaches its vendor (planque's `kind`). For a vendor the account already connects, it offers that same kind first, named with the taf that uses it ("your help desk's own sign-in, like Refunds"), and another kind only when the owner picks it (`connect-an-app@2`). A mode is said only as the connection gives it.
- **Coach offers the taf's chat first for where the work comes from.** When the owner named no app for it, handing the work to the taf in its chat is the first option, and an app is connected only when the owner picks it (`set-up-a-taf@2`).
- **Which account, and live or test.** A connection on a taf's Setup says which vendor account it works in and its mode, in the connection service's words ("Demo Shop · Live"); an unknown mode reads as treated as live. tafs cloud sends it as `account_words`; Android draws it on the connection's sheet, and iOS follows.
- **Android: Run now is back on Activity.** A signed-in taf's Activity shows when it runs next, with Run now and Pause, ahead of its work. A taf that works from its chat says so instead.
- **Android: where a job run's result went.** A job run's report in the thread shows tafs cloud's `delivered` line above the taf's words ("Sent to Google Sheets · Refunds 2026…").
- **Coach runs at the standard output.** With the lighter Coach, its turns run at the standard speed, as the tafs' do, not the fast one, which costs several times it; the next Upgrade writes it to every account's Coach with the skills.
- **tafs cloud, results to their destination (step 4).** A job run's report in the thread carries one line above the taf's words, `delivered`: where its result went ("Sent to Google Sheets · Refunds 2026 and handed to Invoices. Not sent to Slack · #ops yet: trying again.").
- **tafs cloud, a step Coach retries:** a call that failed and was made again with the same tool in the same run no longer reads as failed to the owner; the retry's outcome stands, on the live stream (the failure is held until the run ends, and dropped on the retry), in the chat's thread and on a request's steps. A failure Coach gave up on is still said, with its reason.
- **tafs cloud, dependable runs:** a connected app's event whose first delivery claimed its id and stopped before it answered the run's wait is answered by the next delivery after thirty seconds, instead of every retry reading "answered" for ten minutes while nothing answered.
- **iOS: recipes and Show suggestions.** A new Coach conversation opens to tafs cloud's recipes, by category; a tap puts a recipe's words in the message box and sends nothing. Account › Coach has the account's **Show suggestions** switch, kept in tafs cloud, with its words under it, for the people who manage the account's tafs, as on Android.
- **Android: Show suggestions.** Account › Coach has the account's **Show suggestions** switch, kept in tafs cloud, with its words under it, for the people who manage the account's tafs. A new Coach conversation shows recipes only when tafs cloud sends them.
- **Android: a chat's rows keep their place while an answer arrives.** Each message keeps one key from its first frame to its last: the owner's from the moment the stream names it, and the taf's answer lands in the row its live answer had, updated in place rather than replaced. The thread follows its end when the last row's height changes, not only as words arrive.
- **Coach, built on skills.** Coach's base is a core of about 2,000 tokens (who it is, how it talks, how a conversation works, the map of its skills, the rules that hold whatever is loaded, each pinned by a test) and ten skills cut from it word for word (`runtime/skills/coach`), stored in kiff/agents at versions that never change. tafs cloud decides which a conversation loads, never the model: by the entry point before the first turn, and by the tool when it is used, added to the session and read at the next turn, never in a tool's answer; `read_skill` stays as the fallback. A Coach thread shows its `usage` (turns, tokens in, cache reads, cost), so the change is measured where it's read (`docs/coach.md`, "Skills").
- **Android: tools added to a connection can be approved.** KIFF's words for a connection's profile are read again when its profile changes under the same connection, as adding tools does, instead of being kept from the first read; Approve no longer stops at "KIFF's words for this connection aren't read yet", and only the current profile's words are shown and signed.
- **Android: Coach's chat no longer jumps as an answer lands.** The live answer leaves in the same update that brings it into the thread, at the end of a run and where a run goes on after a hold, instead of showing twice for a moment and then shrinking back, which moved the thread back and forth.
- **Android: Send again by place.** A delivery to one of the taf's own places (an app's action, another taf) is sent again by its key, as tafs cloud retries it, instead of as a door reply, which answered that nothing was waiting. A delivery still not made offers **Ask Coach** with tafs cloud's words in the message box. The request sheet's **Try the reply now** stays the door reply's. iOS follows.
- **tafs cloud, a run waiting for an app's event, for the apps (the slow-tools design, step 5):** a request's waiting step carries `waits_on: "app"` when the wait is for a connected app's event, so the apps draw it as the app's and match on the field, not on words; and a taf whose run waits for such an event has `summary.status: "waiting_on_app"` on its work, with the connection's words as the line, instead of reading idle. Named apart from the home item's `state: "waiting"`, a call held for the owner.
- **Coach, where results go (results to destination, step 3).** Coach's rules get the flow: find the app's write (the connection's catalog, or a search and a connection asked for), fix the place's arguments from the owner's words by a pick by name (never an id asked for), say what each field takes (`fill`), another taf as a place, and the draft with `taf_dial`; a money tool is never a place, and tafs cloud refuses one the taf's Cards count in money, at the dial and when the version goes live. Opened from a delivery note, Coach reads why and proposes the fix as a version, never retrying itself (`docs/coach.md`).
- **tafs cloud, dependable runs:** a connected app's event that answered a run's wait is remembered by its id for ten minutes, so the same delivery again (the provider or planque sending it twice) answers the same and never starts a run for an outcome already handled.
- **Recipes in the app, decided by tafs cloud (kiff/tafs#476).** `GET /v1/recipes` sends the list only while the account has no live taf and its "Show suggestions" setting is on; otherwise an empty one with `shown: false` and the words why. The setting is the account's (`GET|PUT /v1/me/suggestions {show}`, on by default, a manager's to turn off for good), kept in tafs cloud. The apps draw only what comes.
- **tafs cloud, a taf from Coach does its own job.** A Coach result that names one of the five starter jobs (`job`) instead of the job drafted with the owner is refused, in words Coach reads, and no taf is made. Before, the taf took the starter job's kickoff and instructions under the owner's own summary, so its runs did that other job.
- **Android: a run waiting for an app's event.** A request waiting for a reply or another event of a connected app shows what it waits for in the connection's words (tafs cloud's `waiting_for`), and the step tafs cloud marks `waits_on: "app"` isn't drawn in the amber of something that needs you. On the home list such a taf reads as its work's line (`waiting_on_app`), and a state the app doesn't know reads as that line instead of "Card not active". iOS follows.
- **tafs cloud, a call whose outcome comes later (the slow-tools design, with kiff/planque#100 and kiff/agents#306):** a connected app's event that carries the handle of a call a taf's run sent (`key`) answers the run that waits on exactly that connection, event and key, as the call's result, instead of starting a run; one that comes before the run has paused is kept ten minutes and answered from the run's pause note. A taf's work line says what such a run waits for in the connection's words (`waiting_for`), and Coach reads it on the run. Coach drafts the event into the taf's wakes with a write whose outcome comes later, since the connection refuses the call otherwise (`not_listening`).
- **Android: one fingerprint, then taps (kiff/tafs#494).** Where tafs cloud's ask says a call takes an approval window, one fingerprint opens it at KIFF, and the approvals in it go as taps: signed by the device key with the window's id (`v2`), to KIFF and planque, which decide. A refusal signs again with the approval key, an ended window is opened again once, and the window closes after 30 seconds in the background or when the screen turns off. KIFF's words for the next approval show under Approve. A KIFF without windows leaves approving as it was. iOS follows.
- **tafs cloud, the approval window (kiff/tafs#494).** An approval card's `sign` ask says which key the call takes, `key: window` (a tap inside an approval window opened with the fingerprint: connecting an app, a draft KIFF says widens nothing) or `key: critical` (the fingerprint, every time; the default for a draft until KIFF's view says otherwise). The report stays the device key's, as the apps sign it; its check reads from KIFF whether it was signed inside a window and whether that window is open, and refuses a critical ask's report signed inside a window (`code: critical_key_required`) or a window that has ended (`code: window_required`), with the words the apps show.
- **Android, Coach:** an answered approval stays folded to its one line, and when it had a Card, a tap opens it again to that Card's lines, KIFF's draft at the version approved. Nothing in it can be answered again. iOS follows.
- **tafs cloud, results to their destination (step 2).** What became of each place of the taf's own is checked from the run's record by the sweep (which walks every taf with a place of its own) and when its page or a request is read (a manager's read acts, a viewer's starts nothing): an action called and taken is `sent`; one not made or refused is retried once by the taf itself, ten minutes later, as a task with the result; still not delivered, the people who manage the taf get one silent note with Send again (`POST …/deliveries/{key}/send`) and Coach's words to open it with, and nothing more while it stays broken. A result's "Hand-off" section becomes the receiving taf's run, once, one hop. The request's `deliveries` carry each (`docs/reliability.md`).
- **tafs cloud, results to their destination (step 1).** A result place at a connected app says what each field takes, in words (`fill`), so every run fills a row the same way; another taf can be a place (`{place: "taf", taf, name, fill}`): the result becomes that taf's work, under its own Card, one hop. Both go on the dial (`PUT …/knob`, the Results ring) and into the version's instructions (a taf place as a "Hand-off" section the taf ends its result with); Coach's `taf_results` takes the same shape and drafts a version for them. The hand-over itself and the delivery check come next.
- **tafs cloud, the approval window (kiff/tafs#494).** An approval card's `sign` ask says which key the call takes, `key: window` (a tap inside an approval window opened with the fingerprint: connecting an app, a draft KIFF says widens nothing) or `key: critical` (the fingerprint, every time; the default for a draft until KIFF's view says otherwise). The report's check reads which key signed and whether the window is open from KIFF, and refuses a critical ask signed by the device key (`code: critical_key_required`) or a window that has ended (`code: window_required`), with the words the apps show.
- **tafs cloud, a failed step says why (kiff/tafs#476, gap 9).** The stream's `step` event and the thread's steps carry `failed: true` and `why`: the first line of what the tool answered, in the owner's words, with ids and codes left out. The apps draw `why` under the step, and the " · failed" is the service's word, not theirs (`docs/chat.md`).
- **Android: a failed step says why.** The list of a run's steps shows a failed step in the failure colour, with tafs cloud's one-line reason under it when one is sent, and no longer adds " · failed" of its own.
- **Coach:** when its dial, install or results tool is refused because the owner hasn't given Coach that tool yet (an account from before it), Coach says so in one sentence and stops, instead of stopping silently (kiff/tafs#476, gap 5).
- **Sign in to a site (kiff/planque#86).** tafs cloud offers `site_sign_in` on a connected browser connection. On Android, the connection's sheet shows it, and a tap opens planque's live sign-in view in a web view, under planque's words, where the owner signs in to the site themselves. Nothing else is shown, and the view never leaves planque's address.
- **Android: any app on demand (kiff/planque#87).** Connect apps shows an app that isn't set up yet with tafs cloud's words in place of Connect, and nothing to tap. A taf's connection sheet says who keeps its sign-in, in tafs cloud's line.
- **Recipes:** the words to say to Coach name the kind of app ("my accounting app", "my email"), never a brand, since an app sold in one country isn't in another and the account may already have a different one; Coach finds the apps by those words and proposes the ones the account has first (kiff/tafs#476, gap 2).
- **tafs cloud, approvals:** a Card draft for an app set up on demand that would let one of its write or money tools run without asking isn't signed: Coach hears which tool, and drafts it again held. The default now holds in tafs cloud, not only in Coach's words (kiff/planque#87).
- **tafs cloud, Connect apps:** an app the connection service lists as needing setting up by an operator comes through as `state: "needs_setup"` with `words` for the apps to show in place of Connect, a taf's Setup connections say who holds the sign-in (`custody_words`) and whether the app was set up on the owner's pick (`on_demand`), and a catalog read that stopped with pages left as `partial`; the recipes route treats a partial read as unfiltered.
- **Coach (any app on demand, kiff/planque#87; kiff/tafs#476 gap 1):** when the owner names no app, Coach searches tools by words across every app the catalog can connect and proposes the apps before connecting; a long catalog is searched by words, never a reason to stop; an app whose sign-in was set up on the owner's pick gets its writes and money drafted to ask the owner each time until the owner widens it.
- **A Telegram door (Android):** Setup › Starts when › Add a door… › Telegram picks the taf's Telegram bot connection and adds its door, with nothing to sign. People are paired from their first message's knock, or by their Telegram user id. Every word of the step and of pairing, and the connection's provider, come from tafs cloud (`bot` on the offered app; `pair_hint`, `pair_from_knock` on the door), so the app keeps none of its own.
- **Android: recipes in Coach (kiff/tafs#476).** A new Coach conversation shows tafs cloud's recipes by category, each with its title and line. A tap puts its words in the message box for the person to edit and send; it never sends. The app's own three example prompts are gone. Without recipes (tafs cloud not reached), the conversation opens without them.
- **tafs cloud: Coach makes any job (kiff/tafs#476).** A taf's job is its own, drafted by Coach from the owner's words: what it does, what each run is asked to do, how it works, and when it runs or that it works from its chat (`POST /v1/tafs` takes `kickoff`, `instructions` and `cron` or `unscheduled`, with no `job`). The five starter jobs keep working for the tafs that have them, and are recipes now.
- **tafs cloud: recipes (kiff/tafs#476).** `GET /v1/recipes` serves the jobs a new Coach conversation opens to, each with the words to say to Coach, by category, filtered to what the account's catalog can connect. The source is `recipes/`, one file per recipe. The first batch is eleven, the five starter jobs among them; a tap fills Coach's message box, and the person edits before sending.
- **tafs cloud, iOS and Android: tafs cloud decides an approval's outcome (kiff/tafs#413).** On Approve, the apps send what the card showed; tafs cloud reads planque's grant and KIFF's draft and says what needs signing, if anything (`POST …/components/{component}/approval`), answering Coach itself with `already approved: …` or `failed: …` when nothing does. The phone signs only the message tafs cloud names, makes the one call as given, and reports what came of it, signed as a setup call (`POST …/approval/{ask}`); tafs cloud asks KIFF whether the phone signed it and reads what holds before Coach hears `approved`. Every word the cards show for an outcome is tafs cloud's. Older apps keep answering approval cards themselves.
- **A Telegram door (tafs cloud):** a taf can be reached through the owner's own Telegram bot, connected in planque, the same way as the Slack door. One door per bot (route `telegram:<grant>`), senders and keys are Telegram user ids, strangers knock, and the taf posts its own answer with the bot's send-message tool under its Card. tafs cloud holds no Telegram token.
- **iOS (v27):** Home's line and the Needs you badge use tafs cloud's `needs_you` count and words; when it sends none, the app counts held calls, currency requests and "Did this happen?" calls. An app's sheet shows `workflow_words` once, under Workflow.
- **Android (v27):** Home's line and the Needs you badge show tafs cloud's count, which also counts a knock at a door. An app's sheet with no saved workflow says "Ask Coach to set its workflow" once, under its own Workflow line. The UI tests no longer fail when the emulator's configuration settles after the first test starts.
- **v27, tafs cloud:** Home gets `needs_you: {count, words}`, the count of what Needs you lists (held calls, "Did this happen?", currency asks, tafs with a knock), so Home, the badge and the lock screen agree with it; left out when KIFF's "Did this happen?" list can't be read. With no saved workflow, an app in Setup sends `workflow_words` for its own Workflow line instead of a line on an action. A currency ask names the action without a currency it ends with ("…, in EUR").
- **v26, tafs cloud:** a schedule saved before the account's time zone changed now follows it by itself, hours as written, the first time someone who manages Cards opens the tafs (a viewer's read changes nothing and still says how to apply it). An app's sheet with no saved workflow says "Ask Coach to set its workflow" once, not on every action.
- **iOS, from the v25 phone check (v26):** a Resume not answered in time says "Face ID timed out. Try again." (or Touch ID). Account › Version reads the version first, then the build ("1.0 (26)"). An app's sheet skips an empty step line.
- **Android, from the v25 phone check (v26):** the Halt button says "Halting…" while a halt goes, never "Resuming…". A Resume not answered in time says "The fingerprint check timed out. Try again." Account › Version reads "1.0 (26)", the version first. Home's headline and the Needs you badge count "Did this happen?" items too, so Home never says nothing needs you while Needs you holds some.
- Currency-request panel failures leave Activity readable with a notice, and panel updates preserve history cursors. Dismissed requests clear from cached views. Resume stops a stalled biometric check after ten seconds and ignores late completion. On iOS, previously enabled alerts return for the same account or show an off notice, and Halt/Resume failures stay on screen.
- **Setup:** an action about money whose operation takes no amount field (contesting a claim on a payment, an invoice reminder) gets no made-up amount argument. Its Card limits it by calls a day.
- KIFF currency exceptions appear once in Needs you, Setup and Activity, with attempt history, a Coach request for the correct taf and signed dismissal. Activity collapses only typed refusals in the open episode; Record keeps every decision. They remain subject to the normal signed Card approval. Connection refusals use KIFF's typed provenance and words while preserving status, amounts and evidence. Time-zone synchronization reports partial failures in displayable words and rechecks a newer confirmed zone before reporting success.
- **Cloud and iOS, from the phone check:** recurring schedules can follow KIFF's confirmed account zone, and Card names that zone. Custom tafs use their own saved job and workflow, with no workflow invented from tool order. iOS keeps paging after a refresh, shows the build in Account and a schedule's current How often value, and opens Coach for the taf selected; iPad workflow rows align to the left.
- Setup's app actions use short step references and distinguish forbidden actions from calls that need approval every time. Workflow summaries collapse consecutive uses of the same app. A taf without a numbered live version says it runs its original definition. Activity and retained runs supply an end-of-history line.
- **Android, from the v24 phone check (v25):** a taf's Activity and its runs keep loading older pages to the end; reaching the end while the first page refreshed stopped them. Account has one way out, **Sign out** (one account per phone), and Version names the build ("25 (1.0)"). The status bar's icons follow the app's own Light/Dark choice from launch. A new time zone moves the tafs' schedules to it, every time on screen is read again in it, and the Card names the clock. Coach from a taf's page opens that taf's conversation, not another taf's. Signing in again to the same account brings this phone's alerts back on, or says they're off and offers to turn them on. A halt or resume that didn't go through says why on the Halt screen, and no screen says "planque". A Card asked to take a currency it has no limit for shows once, in Needs you, Setup and Activity, with Coach to add the limit and Dismiss, never Approve once. A Resume whose fingerprint check isn't answered within 10 seconds is cancelled and says so, and the button comes back.
- **CI:** a pull request waits less. iOS CI runs only for a change to `ios/` or its workflow. A draft runs only the fast checks (tafs cloud's tests, Android's build and unit tests); the iOS simulator and the Android emulator run once it's ready for review. The iPad's list tests run on a pull request only when it touches the iPad's layout, and always on main and release builds. iOS screenshots run beside the tests instead of after them: a key set (Tafs, a taf, Setup and Account, light and dark) on a pull request that changes a screen, the full set (light, dark, largest text, iPad, landscape) for a release build. A manual run picks `full`, `key` or `none`, and can skip the TestFlight upload.
- **Android, build:** the Android Gradle plugin goes to 9.1.1 and Gradle to 9.3.1, which AndroidX Core 1.19.1 and the Compose BOM 2026.09.00 need; both come in with it. No change to what the app does, and the versionCode stays. Google Play Billing goes to 9.1.0; the app's billing code is unchanged.
- From the v24 walkthrough: a workflow step says its title once (tafs cloud sends its words empty when they'd repeat it); on Android the back key closes an app's sheet and the full notes, and signing in from the sample opens the Tafs tab on the account's list.
- Phone-specific alert switches, saved Light/Dark/System appearance and searchable account time zones on Android and iOS. Account groups related actions and keeps sign out last.
- Cloud-paged Activity and recent runs; connected-tool rings with action limits, workflow steps and seven-day statistics; compact memory previews and full notes. Card shows changes to how the taf runs and full wallet-style back cards. Coach returns to the same taf and tab, and chats have a larger single-bubble composer. Android gains the animated door rim.

- **Android, the taf screen (as the owner approved):** the bar is Account · Tafs · Needs you · Record · Coach (a rail in landscape and on tablets), and Settings and Coach leave Home's header. A taf's tabs are Chat · Activity · Card · Setup, each with one job: Activity is what it did and what waits for you, Card only what's on its Card, and Setup how it runs (the job, Starts when, connected apps, Results go to, what it remembers, versions, Remove). Setup replaces the dial and the Doors tab. The Record filters by taf, and each day says what its money moved came to. Account › Alerts shows what alerts you and how. The app follows the phone's dark setting.
- **Android:** in Connect apps, "Which taf is this for?" is asked in a dialog over the list, where Connect was tapped, instead of at the top of the list, as on iPhone and iPad. A sign-in that comes back is said at once, with its next step (Coach chooses its actions), and a signed-in app's row reads "Signed in · choose its actions" and opens the taf's Coach.
- **Android, app events approved on the dial (v21):** an app's event that waits for the owner shows its filters, and one approval with the fingerprint subscribes exactly those at the connection, then tafs cloud records it for that version, as on iPhone and iPad. A subscription made but not recorded is recorded again without asking twice.
- **iPhone and iPad:** the dial remote stays above the tab bar while its contents scroll.
  Long ring and face labels fit with an ellipsis, with full words for VoiceOver. Pause
  follows the service's availability and shows its refusal words. Connect apps rows show
  the catalog title without an internal provider id. App-event stops can approve their
  filters with the device key; the dial shows approval only after the service records it.

- **Android, from the v20 walkthrough:** a long word on the dial ends in "…" within its ring and on the face, and TalkBack reads it whole. ⏻ is off for a taf that can't be paused, and a refused pause is said in tafs cloud's words. With the dial, the Doors tab's own Add a door panel is gone: a new door is Coach's. Connect apps shows each app by its title alone, and a late answer to an earlier search no longer replaces the newest one.
- **Android:** an app that takes an API key is connected from Connect apps, as on iPhone and iPad. Its approval sheet takes the key and gives it to planque with the same confirmation, instead of sending the owner to Coach.
- **iPhone and iPad:** Connect apps clears its list and approval when the account changes and ignores stale search replies. Removing a Slack door waits until the phone can stop its subscription.

### Removed

- The `TafsMac` target and `ios/Mac/`: the Apple app is built for iPhone and iPad only.

### Added

- **Docs:** `docs/taf-screen.md`, the app's screens for the person using them (the bar, a taf's four tabs, Setup › Starts when and Results go to, Account › Alerts, dark), replaces `docs/dial.md`. The README shows the v24 screens from CI on iPhone and iPad (light and dark, with Account's theme), and Android's from the UI tests.

- **Docs:** `docs/connect-apps.md`, Connect apps for the person running tafs; `docs/dial.md` covers app events' approval, ⏻ off for a taf that works from its chat, and labels cut with "…".

- **Docs:** `docs/dial.md`, the dial for the person using it; the README covers Android, the dial, Connect apps, Did this happen? and the Slack door, with the Android screenshots.

- **Android, the settled dial:** When · What · Where around the taf's Why, with "Ask Coach or find
  anything", Showing ▾ one connection's stops, and a remote: ◀ ▶ through the stops, − + the chosen
  stop's one size, ⏻ pause, the face as OK. Changes collect into "Make N changes live". No chips
  and no "+ Add": new things go through Coach.
- **Android, door states on the dial:** a door's When stop is lit when open, dashed when closed,
  grey while resting, amber with a dot while a knock waits, and lights up when a message arrives.
- **Android, the dial's words read upright:** a stop's word in the lower half of its ring turns
  half a turn more, so nothing on the dial reads upside down.
- **Android, the dial follows tafs cloud's rules:** what OK does on each stop, a door's look and
  the change waiting to go live ("DRAFT · Vn · NOT LIVE", "Make N changes live") come from the
  knob, and Add a door and an empty recipe's step ask Coach. A chosen stop turns under the pointer,
  a ring past six stops pages, OK with nothing chosen goes to the first stop, "‹ Why" goes back,
  ⏻ reads the dial again, and the footer is today's work alone. Coach's card says when its dial
  is live.

- **iPhone and iPad, the universal dial:** When, What and Where surround the taf's Why.
  A Showing menu filters any number of connections; Back/Next walk every stop, Lower/Raise
  use the service's fixed choices, and Pause uses the existing pause control. Changes build
  on the pending draft and become live through the biometric version switch. Coach's draft
  card groups the setup by connection. Today's count opens request steps and reply retry.

- **Android, Coach sets the dial:** Coach's dial card shows Wakes it, When and Results go in tafs
  cloud's words, made live with one confirmation, or Change back to Coach; the dial shows the same
  draft with Make it live.
- **Android, a request step by step:** today's work under the dial, or a request in Activity,
  opens what happened, step by step, with Try the reply now and Ask Coach.
- **Android, How this is counted:** under a Card's limits, a row opens KIFF's words for how each
  tool's calls are counted, as approved when it was connected.
- **Android, Did this happen?:** money calls KIFF can't prove show in Needs you in KIFF's words,
  answered No (the Card gets its room back) or Yes, it did, with one fingerprint.
- **Android, Connect apps:** Settings lists the apps tafs cloud offers, searchable, each saying
  whether it's connected; an app nobody listed goes to Coach. Connect asks which taf it's for,
  then opens the approval over the list, with KIFF's words, then the vendor's sign-in.
- **Android, KIFF's words for a connection:** a connection that carries its profile shows
  KIFF's words for it on the approval card, read from KIFF and checked against their digests,
  and the approval signs both. Approve waits until they're shown.
- **Android, the taf's dial:** Wakes it, When and Results go around the job, with a rim that
  counts today's work, in the taf's Doors tab: a door stop picks its door, the face opens or
  closes it or runs the taf now, and a held call can be approved once from the face.
  Turning a result place or If busy makes the taf's next version, shown in tafs
  cloud's words with Make it live.
- **iPhone and iPad, KIFF's words for a connection:** the same as Android: a connection that
  carries its profile shows KIFF's words on the approval card, read with the person's session
  and checked against both digests; the approval signs both, and waits until they're shown.
- **iPhone and iPad, Connect apps:** from Settings, the apps tafs cloud offers, searched as you
  type. Connecting shows its approval over the list, with KIFF's words, then the vendor's
  sign-in; a vendor reached by an API key takes its key there, with Face ID.
- **iPhone and iPad, Did this happen?:** in Needs you, a money call whose outcome KIFF can't
  prove asks you whether it happened. No gives its Card the room back.
- **iPhone and iPad, the taf's dial:** the same dial, drawn from the approved design and laid
  out from the knob API (not yet on a screen).
- **Setup, for any vendor:** its rules name roles, not one payments vendor's tools: the
  argument a call acts on, the read that says its unit, money in its own units, test or live when
  the vendor has both. A vendor with a REST API and no MCP server is connected by its key, which
  the owner gives on the phone, never in the chat. A plan step can name the call it waits on, and
  is done only once that call went through.

- **iPhone and iPad, an empty home for someone a taf is shared with:** "Tafs shared with you show
  here." Only someone who may use Coach reads "Connect a tool to set up your first taf".

- **iPhone and iPad, Signed in as:** Settings › Account › KIFF account labels the person's own
  address "Signed in as", so it never reads as the account's, as on Android.

- **iPhone and iPad, Go on past the AI use bound:** Settings › AI use shows tafs cloud's words as
  they are. While the tafs wait, the billing admin gets **Go on** (one Face ID), and AI use is read
  again after it; anyone else reads who can let them go on, and when it starts again by itself. If
  that read fails, it says the tafs went on and offers Read it again, never a second Go on.

- **Android, Go on:** while the tafs wait past this period's AI use, the billing admin sees **Go
  on** in Plan & usage's AI use. It asks once, then the phone's device key signs it for KIFF. If
  AI use can't be read after it, it says the tafs went on and offers Read it again, never a second
  Go on.
  Anyone else reads tafs cloud's words on who can, and when AI use starts again.
- **Android, a taf shared with you:** its Card reads as issued by the account, by name, never
  "You"; that stays only for a taf you manage. Settings labels your own address "Signed in as".
  An empty home list tells someone who can't use Coach "Tafs shared with you show here."
- **iPhone and iPad, an answered card stays under its turn:** once answered, a card stays after
  Coach's words that asked for it, and Coach's words after the answer come below it, as on Android.
  A "Try again" card no longer shows above the turn that offered it.

- **Android, Set up approvals on this device:** before sign-in, only when the phone can't approve
  yet, a screen says why approvals need a fingerprint and where this phone stands: none set up
  (Open settings goes straight to adding one), the sensor busy just now (Check again), or no
  sensor tafs can use (approve from another device), with Continue without approvals. Before the
  first taf, and at every approval, the same words stand in the button's place until the phone
  can approve; Refuse, Decline and Halt stay. It says up front that adding a fingerprint later
  stops approvals until they're approved again. An answer the fingerprint prompt didn't send says why and "It's kept in Needs you."
- **iPhone and iPad, Set up approvals on this device:** before sign-in, only when the device
  can't approve yet, a screen says where it stands: no Face ID or Touch ID set up (the path in
  Settings, in words, or a passcode first where there is none), turned off for tafs (Open
  Settings), locked after too many tries or unavailable just now (Check again), or none on this
  device (approve on another one), with Continue without approvals. Before the first taf Coach's start says approving is needed, and at
  every approval the same words stand in the button's place, or beside a disabled one, until the
  device can approve; Check again updates both at once. A cancelled or unrecognised approval says
  so, and that the request is kept. It says up front that adding a face or fingerprint later
  means approving this device again.
- **tafs cloud, going on past the AI use bound:** `GET /v1/usage/ai` says whether this person may
  go on (`can_go_on`, the billing admin) and when the AI use starts again by itself
  (`starts_again`). While the tafs wait, its words tell the billing admin to go on and anyone else
  that an admin of the account can, with the day it starts again. A chat refused for AI use says
  the same, pointing to Settings › AI use.
- **tafs cloud, a taf shared with you shows none of its doors:** someone who only uses a taf reads
  no doors, paired senders or knocks from `GET /v1/tafs/{id}/doors`, so other people's addresses
  stay with the people who manage the taf.
- **tafs cloud, the owner's own email is theirs:** a message from the owner's own sign-in email
  that passes the sender check runs as the owner, in their own live chat, so it shows there as
  their message (marked as by email) and an open chat reads it at the next change. Other paired
  senders are unchanged.

- **Android, Copy under an answer:** every finished answer from Coach or a taf has Copy under it,
  which copies the whole answer, and its text can be selected. Report this answer moves under
  More actions (⋯), so it's never the only thing a long press finds.

- **iPhone and iPad, as Android's v19:** Settings opens with a Plan card that opens **Plan &
  usage** (was Account usage), and Home shows one line about the plan only when it needs you,
  with Android's words and thresholds. An ended trial says "Your trial has ended." (with "Start
  Solo to keep your tafs running." where Solo is offered), with no past period end and no red
  meter. Packs are on sale only while a plan runs (`can_add_packs`); held ones say "Counts once
  Solo starts." Coach's New conversation keeps an empty conversation and shows its top. Sign out,
  Switch account and Sign in again ask first. "1 task", never "1 tasks". Signing out also removes
  the last export's files.
- **Android, the plan in sight:** Settings opens with a Plan card ("Trial · 5 days and 18 tasks
  left", "Trial ended · Start Solo", "Solo · 12 of 20 tasks left · Google Play") that opens what
  was Account usage, now **Plan & usage**. Home shows one line about the plan only when it needs
  you: a trial ended, not started or ending within 2 days, or tasks used up or down to a tenth.
- **Android, buying through Google Play:** Usage offers Solo (with its free trial) and the task
  packs at Play's prices, once KIFF names their Play products, to someone who may add tasks. KIFF
  records each purchase before Play is told it's done, and Restore purchases sends again what
  wasn't.
- **Export my chats, on iPhone and iPad:** Settings → Account → **Export my chats** prepares the
  person's own chats, trials and notes and offers `tafs-chats.json` and `tafs-chats.txt` in the
  system's share and save sheet. If it fails, it says so and offers Try again.
- **Exporting your own chats:** `GET /v1/me/chats/export` gives a person their own chats and trials with
  every taf, Coach's included, their attached files' names and their own notes, as JSON and as text.
  It's never another person's, and no one exports it for someone else.
- **Export my chats, on Android:** Settings → Account → **Export my chats** offers the same two
  files through the system's share sheet, says why and offers Try again if it fails, and deletes
  the files when the person signs out.
- **Reporting an answer:** `POST /v1/tafs/{id}/messages/{message}/report` takes a person's report of
  an answer Coach or a taf wrote (offensive, harmful, wrong or other, with a note). tafs cloud keeps it
  with the answer as kiff/agents holds it and emails KIFF, so a person reads it.
- **Report this answer, on iPhone and iPad:** an answer from Coach or a taf (a chat or a trial) has
  **Report this answer** in its menu. A sheet takes one of four reasons and an optional note, and
  thanks the person once it's sent, or says they reported it already.
- **Report this answer, on Android:** a long press on an answer from Coach or a taf (a chat, Coach's
  or a trial) shows **Report this answer**, with the same sheet, reasons and words as on iOS.
  TalkBack names the long press "Report this answer".
- **Coach, the hand-over:** a result's tools are their names in KIFF, never their titles, and
  Coach ends the hand-over with its closing message as text, after the plan update and with no
  tool call after it, also when its first result was refused.
- **Coach, writes that move no money:** answering a dispute or pausing a subscription is limited
  by its calls a day, never by an amount, and Coach never gives a tool an amount argument the
  vendor's operation doesn't take.
- **Coach hears an installed version:** when the owner installs a version Coach made, Coach's
  chat says "Version N is live." at once, and Coach ends that improvement's plan on its next
  turn instead of still asking for the install.
- **Coach, after an improvement:** once tafs cloud answers with the new version, Coach writes
  one closing sentence that the version is ready to try and then install, and never says it's
  still waiting. An approval carries no title of Coach's: its words are planque's and KIFF's.
- **A taf's job line after an improvement:** an improvement that changes what a taf does
  carries Coach's one-line `summary`, which becomes the taf's job line once that version goes
  live; going back to an earlier version brings its line back. The home list reads each
  taf's runs once.

- **A total's pending use, in KIFF's words:** tafs cloud's list passes on KIFF's
  `pending_text` for each Card total, how much of it is from calls the tool refused, which
  count until KIFF can confirm nothing moved. iOS shows it on a taf's Card and on a hold,
  in place of its own "nothing moved, but they used room".
- **iOS, as on Android:** an improvement's version is tafs cloud's, and the card only shows
  it; versions and a blocked switch read tafs cloud's words; an answered approval says what
  it was, and an answered card goes back to its own time; a card Coach shows again under an
  earlier id waits for its own answer; at launch Coach opens on its newest conversation
  alone; tafs cloud's words for a refusal show on every path; and an owner's message is
  keyed by tafs cloud's id, so each chat item shows once.

- **Tafs and Coach, calls under a Card:** a taf makes one call for one request, never smaller
  ones to stay under a limit, and asks the owner when it's refused. A money call that doesn't
  name its customer, such as a coupon, carries who it's for in a field the customer doesn't
  see, such as metadata, so the held call says it. Coach says the limits are the owner's, says for each action what asks first and what is
  refused, gives each card an id no earlier card in the conversation had, and hands over
  each taf with a one-line `summary` of what it does.
- **Android, dictation's language:** where the phone dictates in more than one of the owner's
  languages, a switch before the mic shows the one in use ("FR"), and each tap goes to the
  next, as on iOS. The choice is kept.
- **Android, versions:** who made a version, and what keeps a switch from going live, read in
  tafs cloud's words ("By Coach mode", "By you", "By someone on your account"), as on iOS.
  The app's own copies of both are gone.
- **iPhone and iPad, held calls:** a call Coach's own agent holds is titled **Coach**, with
  Coach's mark, on its card, its alert and the lock screen, never "A taf". Why a call is held
  reads KIFF's own words when sent; a count of calls without them reads "Each call asks you
  first" for a limit of 0, otherwise "20 calls a day: this would be the 21st", never the
  machine's reason.
- **iPhone and iPad, approve again without signing out:** after a face or fingerprint is added,
  **Approve again in your browser** lets the phone approve with its new key once the person
  approves it in KIFF, showing the same code on the phone and the page. The session, alerts and
  list stay. Sign in again remains the fallback.
- **Android, approve again in your browser:** a phone whose fingerprints changed approves again
  without signing out. **Approve again in your browser** proves its new approval key with one
  fingerprint, shows KIFF's code to compare, and waits for the browser, with Cancel; the session,
  its alerts and its list stay. **Sign in again instead** stays under it, and is what a KIFF
  without re-linking offers.
- **iPhone and iPad, approvals by Face ID or Touch ID only:** what grants authority (Approve,
  Extra room, a Card, a trial, Resume, sharing, connecting a tool) takes Face ID or Touch ID,
  never the passcode, and the approval key itself requires the faces or fingerprints enrolled
  when it was made. A new enrollment is found at refresh (a lockout never ends the key): the
  home screen and Needs you say this phone can't approve until a new sign-in, with **Sign in
  again**; Refuse stays. A prompt closed unrecognized says "Try again, or sign in again if your
  Face ID or fingerprints changed". **Compatibility:** the earlier approval key also took the
  passcode, so each iPhone and iPad signs in again once before it can approve.
- **iPhone and iPad, Cards:** a limit says what happens past it. On a Card that asks the owner
  past its limits: "up to $40 each; more asks you first", "$300 in total a day, then it asks
  you", "20 calls a day, then it asks you", and a count of 0 reads "Each call asks you first",
  never "0 calls a day". This applies on a taf's Card and on a draft's Cards, where KIFF's own
  `lines` are shown when it sends them. A Card that refuses past its limits reads as before.
- **Android, Cards:** a limit says what happens past it. On a Card that asks the owner past its
  limits: "up to $40 each; more asks you first", "$300 in total a day, then it asks you", "20 calls
  a day, then it asks you", and a count of 0 reads "Each call asks you first", never "0 calls a
  day". This applies on a taf's Card tab and on a draft's Cards, where KIFF's own `lines` are shown
  when it sends them. A Card that refuses past its limits reads as before. iPhone and iPad follow.
- **iPhone and iPad, sent messages:** a sent message is matched to the thread by tafs cloud's id
  for it, from its send or its stream's first event, never by its words, except a steer or an
  older tafs cloud; a message that answered a card says "Answered the card above".
- **Android, sent messages:** a sent message is matched to the thread by tafs cloud's id for it,
  never by its words, except a steer; and a message that answered a card says so. The retired
  `[type id] answer` form is no longer sent; older threads that hold it still read in words.
- **Android, approvals:** a phone whose approval key is gone (a fingerprint added since) says so at
  launch, on the home screen, in Needs you and on the approval cards, with **Sign in again**, in
  place of a button that would fail. A prompt closed without a recognized finger, while the key
  is still good, says "Try again, or sign in again if your fingerprints changed".

- **iPhone and iPad, this round:** a taf's page has a compact header (avatar, name, state) whose
  version, Share and Card line fold as the chat scrolls down; a door's knocks sit in the chat at
  their own times, and a paired sender's waiting message reads "Paired · Do it or Ignore this
  message". The app reads again what tafs cloud's live updates name, and shows each door's words.
  A taf whose connection its vendor signed out waits on the owner at the top of the home list,
  in planque's words, and its Card tab and Coach's card offer **Sign in again**; a sign-in reads
  as signed in only once planque says connected. The Activity tab shows the taf's work, with
  skipped scheduled runs and **Send again** for a door reply given up on. Dictation goes in at
  the cursor (iOS 18 and later).

- **Android, Coach:** **Improve <taf>** as a start in a new conversation and on a taf's Activity
  tab; what the owner wrote and didn't send stays, in Coach and each taf's chat; a card answer
  tafs cloud took never stays on "Sending your answer…"; and Coach's tools and Card go in one tap
  when the Card comes after the batch.

- **Android, a connection its vendor signed out:** its taf waits on the owner at the top of the
  home list, its row in planque's words ("Signed out by Stripe · sign in again"), and its Card
  tab offers **Sign in again**. planque's grants are read on every refresh and live update.

- **Android, a taf's work:** the Activity tab shows tafs cloud's view of the taf's work: its
  line, its recent requests in tafs cloud's words, skipped scheduled runs (dim, in their place by
  time), and **Send again** for a door reply given up on.

- **Android, a taf's page:** a compact header (back, name, state) whose Share and Card line
  fold as it scrolls; a chat opens at its newest message, with a door's knocks at their own
  times; and a paired sender's waiting message reads "Paired · Do it or Ignore this message".
  The app reads again what tafs cloud's live updates name, instead of polling an open chat,
  and shows each door's words. A vendor sign-in reads "Signed in" only once planque says the
  connection is connected, and says it's checking meanwhile. The home list's order holds while
  it's on screen, so a refresh never moves a taf under the owner's finger. A connection its
  vendor signed out (`needs_reconnect`) reads as planque says ("Signed out by Stripe · sign in
  again") and is signed in again from its card, instead of being refused.

- **Android, Coach's screen:** a compact header (back, Coach, a menu with its conversations),
  the plan in tafs cloud's one line that folds away as the thread scrolls, and one calm live
  line from tafs cloud's stream instead of the model's thinking and a list of steps. Alerts
  that tafs cloud didn't take at first are handed over again at the next launch.

- **tafs for Android** (`android/`, Android 11 and later), with what the iPhone app has
  ([docs/android-app.md](docs/android-app.md)):
  - tafs, Needs you and the Record, with KIFF's receipts checked on the phone;
  - a taf's page, with its runs, last report, memory, Revoke and Remove;
  - the live chat, with photos, files and dictation;
  - Coach, with its conversations, given its tools and Card exactly as tafs cloud drafts them.
    A taf Coach hands over is set up by tafs cloud, and its card shows tafs cloud's answer.
    Coach's cards are answered through tafs cloud's route and show the answer the thread
    holds; after an answer, Coach's next words show without leaving the conversation;
  - Doors, Versions and trials, Halt, Usage and asking an admin for tasks;
  - People and groups, Devices, the time zone and Delete account.

  Every approval is signed with an approval key that needs a fingerprint for each
  signature. What didn't go through is said in the app's own words, with what the owner can
  do, never a server's error text, and no tool's id is shown. Dictation works in a variant of
  the phone's language (English in France dictates in British English), and a phone without a
  microphone can install the app. A new fingerprint turns it off until the next sign-in. Signing out ends the
  session at KIFF before the phone shows signed out. Buying through Google Play is built
  but off. On a tablet, the list sits beside what's open, with Overview, and a taf's page
  has its second column in landscape.
- tafs cloud can alert Android phones through Firebase Cloud Messaging. `POST /v1/push` takes
  `platform: "android"` with the phone's Firebase token; an iPhone registers as before.
  Android alerts are data messages the phone builds its notification from, with no call
  parameters; a door's alert carries its taf (`taf_id`) and opens it. They are off until `FCM_PROJECT_ID` and `FCM_SECRET_ID` are set, and an Android
  phone's registration is refused (503) until then.

### Fixed

- **Android, Google Play unreachable:** when Play can't be read just before a purchase, it says
  "Couldn't reach Google Play. Try again.", not that Play doesn't sell it.

- **Android, an ended trial:** Usage says "Your trial has ended." (with "Start Solo to keep your
  tafs running." where Solo is offered), not that its tasks are used, and shows no past period
  end and no red meter.
- **Android, New conversation:** never greyed out in Coach's menu. On an empty conversation it
  keeps it; otherwise it opens a new one.

- **Android, Solo's free trial only while Play offers it:** Play's offers are read again each
  time Usage shows and after every purchase, never kept from an earlier read, so a trial already
  used reads "€10.99 a month", not "Free for 1 week".
- **Android, a purchase's outcome where you tapped:** under Start Solo, the pack or Restore
  purchases, read out by TalkBack, with Restore purchases right there when KIFF hasn't recorded
  it yet.
- **Android, packs need Solo:** before a trial starts or after it ends, Usage offers Solo alone;
  packs already held stay listed and say "Counts once Solo starts." (KIFF's `can_add_packs`).
- **Android, "1 task":** Usage says "1 task", "0 of 1 task" and "Tasks last 1 month", not "1 tasks".
- **Android, signing out asks first:** Sign out of KIFF, Sign in again and Switch account each
  confirm before the phone signs out, so a stray tap doesn't end the session.
- **Android, an answered card in Coach's chat:** it stays after the words that asked for it,
  and Coach's words after the answer come below it.

- **Android, Coach's last words on the open screen:** with Coach open, a run that paused on a
  result goes on once tafs cloud answers it, and its closing message shows without leaving
  Coach. The dots and Stop no longer stay after it's done.
- **Versions:** version 1 ("As it was set up") shows when the taf was set up, not the moment its
  second version was made.
- **Android, an approval is "approved" only once signed:** Approve on a connection already
  connected that brings no Card no longer tells Coach "approved" with nothing signed. Coach
  hears it's already connected and there's nothing new to approve. A Card KIFF already
  confirmed says "Already approved". An answered approval's line is one line, however long
  its words.
- **Android, installing a version:** Install this version opens the Card the version brings,
  then the fingerprint. The Card sheet no longer stays invisible over Versions, taking every
  tap. Back cancels the install.
- **Android, every refusal says why:** Run now, Send again and every other refusal tafs cloud
  explains show its words, not "It changed in the meantime".
- **Android, refused calls in a total:** how much of a Card's total is from calls the tool
  refused, in KIFF's words ("It counts until KIFF can confirm nothing moved"), on a held call
  and under the Card's limits, from live, active Cards only. The app's own line is gone.
- **A taf's job line and last run:** a taf Coach sets up shows what Coach said it does, not
  the nearest fixed job's line, and a run from its chat counts as its last run.
- **Android, Play's crash view:** the release bundle carries its native libraries' symbol
  tables, so a native crash reads by function name.
- **Android, Coach at launch:** Coach opens on its newest conversation alone, with the earlier
  ones under Previous conversations, never several conversations together in one thread.
- **Android, a refused message:** a message kiff/agents refused (the period's AI use spent, a
  taf halted) says why in tafs cloud's words, not "It changed in the meantime".
- **Android, an improvement's new version:** tafs cloud makes it in the hand-over, and the
  phone shows it ("See version n"), or why it wasn't made. The phone no longer makes it.
- **Android, Coach's answered cards:** an answered approval says in one line what it was
  ("Approved · Connect Stripe"), and an answered card goes back to its own time in the thread,
  while one waiting for the owner stays at the end.
- **Android, Coach's cards under a reused id:** a card in a new conversation, or a new card in
  the same one, under an id Coach used before, no longer shows "Answered." with no button, nor
  sits in another conversation's part of the thread.
- **Android, a chat while its taf or Coach works:** the thread no longer jumps about. The dots and
  the answer replacing them are one row that never shortens the thread's end, and an answer
  growing word by word is followed without restarting an animation on every word.
- **Versions, in tafs cloud's words:** each version says who made it (`by_words`), and a switch a
  run blocks says what's in the way (`words`), so the apps show tafs cloud's words, not their own.
- **Android, connecting a tool:** a sign-in that came back with its tools listed for Coach to choose
  from showed a red "didn't connect … planque has it as listed", though it was connected. Listed
  now counts as signed in, as on iOS.
- **Chats, words written while a card waited:** they showed twice in the thread, once as the
  card's answer and once as the run's message, at the same time. A phone's list could not hold
  both (Android closed on opening Coach). tafs cloud now gives one message, marked as the answer,
  with the id its send answered.
- **Android, held calls:** a call Coach's own agent holds is titled **Coach**, with Coach's mark,
  never "A taf". Why a call is held reads KIFF's own words when sent. A count of calls without
  them reads "Each call asks you first" for a limit of 0, otherwise "20 calls a day: this would
  be the 21st", never the machine's reason.
- **Android, Coach crash:** opening Coach no longer crashes when a chat holds the same message
  twice. An owner's message is told apart by tafs cloud's id for it, and a chat shows each item
  once.
- **Coach, a scheduled taf with no numbered version:** Coach said it had "no version installed
  yet" and that nothing was working, while the home said "Working · Next run 04:00". Its
  `tafs_state` now says such a taf runs the definition it was made with, and that a schedule
  that's on is working, with its next run, though nothing runs this minute.
- **Android, switching accounts:** after signing in to another account without closing the app,
  connecting tools and starting Coach work there. The phone used to sign planque's requests with
  the last account's device id, which planque refused, and the app said it was signed out.
- **Android, starting Coach:** when the tools can't be connected because planque couldn't check
  the phone, Coach says "Connecting your tools didn't work. Tap Start to try again", not that
  you're signed out, since the session still works.
- A taf's work steps say what it did, in words, both while it works and after: KIFF's title for
  a tool through KIFF ("List your charges"), and what Coach's and the agents' own tools do
  ("Showing you a card"). A step showed the tool's name before ("show", "list_charges_ijkp").
- Sign out of KIFF ends the session at KIFF first. When KIFF doesn't confirm, the app stays
  signed in and says so, instead of showing signed out while the session still works.
- When a change to groups or sharing fails, the app says why. The reason used to be cleared by
  the list being read again.
- A Card's total over all time, or over a rolling hour or 24 hours, no longer reads as "a day".
  The home list gives each total KIFF's own window and its words ("in total", "in any hour").
- A chat that opens with your answers to Setup's questions card is named by those answers,
  as the card shows them ("Test mode · Monday, Tuesday"), not by their raw JSON.
- Coming back to the app no longer shows "Not done: Couldn't reach KIFF" (the 930 test).
  A refresh the app makes by itself tries again on its own. While KIFF can't be reached, a
  quiet line under the account's name says so, with a link to KIFF's status page.
- A held call's card, in the chat, Needs you and the taf's page, says "Nothing is sent until
  you approve." while it waits, and so does its notification. The taf's message about it no
  longer repeats that, and a chat answer no longer ends with a summary of what wasn't done.
- Setup, for a taf whose work arrives somewhere it can't connect (an email inbox), tells you
  to pass each request in the taf's chat in your own words. It used to show an example with a
  charge id to type.
- A taf's answer when KIFF holds a call is one sentence: it waits for your approval and nothing
  was sent, the one limit that holds it, and until when. It used to add what was left on the
  payment and the other limits.
- A Card's amount with no known currency (#309) says so: "30 000 left today of 30 000 ·
  no currency", and "≤ 5 000 (no currency) each". It read like money, though the number
  is the vendor's own, often in cents.
- The iPad's tafs list (the 901 test): a tap anywhere on a taf, its icon and name
  included, opens it. Only the empty end of the row did.
- Coach (the 870 test): a step whose call came back refused or failed shows as failed in
  its plan, never done, and Coach says what went wrong. Before, a hand-off the tool refused
  showed as done. Its rules say a read tool's call count as its Card will have it, where
  they said "no limit" and the Card then counted the reads.
- Coach mode (the 870 test) works like a taf's chat. The card you answered goes to the top
  and stays, with Coach's work and answer under it. The work block sits under what started
  it and folds in place, each answer lists only its own steps, and folded steps read in
  Coach's words, not "Asking KIFF to run planque".
- A taf's page (the 870 test): Share uses the system's share mark, larger, beside who uses
  the taf. A Card that acts for real no longer says "Live", which read as live money beside
  a test-mode connection; a Card scoped to another environment still names it. Someone
  with no connections no longer sees an empty Connections box.
- The tafs list sets apart the tafs shared with you, under "Shared with you" (#306).
- Overview (the 870 test): each heading sits over its column, the Card reads as on the taf's
  page ("300 US$ left today of 300 US$", where it read "0 of 30000 today"), and who uses a
  taf shows only to whoever manages it.
- Refunds: the job's instructions say to look up the payment and read its result before
  asking for a refund (the 859 and 870 tests). The taf twice sent the look-up and the
  refund together, then said it had checked the payment; kiff/agents now refuses a
  refund sent beside a read, and this keeps the taf from spending a turn on it.
- Chat (#249): a run that fails after a call went through names what went through and
  how much moved, for example "Refunds stopped part-way, after this went through: create
  refund on ch_3001, US$5.", from the run's own record and KIFF's Record. Before, it only
  said it stopped part-way, so the owner had to check the Record to learn the money had
  moved.
- Chat: a call kiff/agents refused back unmade, because the taf sent an act beside a read,
  shows as "not made · read first", not as a failed step. Made again, it's one row
  with its tries. tafs cloud's `step` event may say `not_made`, and a message's `work`
  steps may carry `not_made`. A run's work is split where kiff/agents marks a turn
  `resumed`, the first after a pause, rather than guessed from the calls it waited on.
- Launch (the 870 test): the taf used last leads the list from the first frame, and the iPad
  opens on it. The list's order is settled once KIFF's record is read, and kept with the
  remembered list, so the app no longer opens on the first taf made and then jumps.
- Chat (the 859 test): when a run ends, the view stays on the pinned message, even as tafs
  cloud's copy takes the place of the one shown while it worked. If the message is gone, the
  chat goes to the latest rather than blank. A step the stream sends again keeps its status,
  so a finished step never shows as pending. On iPad, the app opens on the first taf in the
  list, selected, until the owner picks another, and a tap on the open taf counts as a pick.
- Chat (the 859 test): a run that paused on a held call and went on once the owner answered
  shows its work in parts. The answer written before the hold keeps what was done before it,
  and the time it took, not the time spent waiting for the owner. The work after the approval
  shows on its own, and the steps from before aren't listed again as new.
- Chat (the 845 test): a sent message goes to the top at once and stays there while the taf
  thinks, works and answers. The work block sits under it, with the answer streaming below,
  and folds in place to "Worked for" when the run ends; a run's work shows once, on its
  first answer. Coming back from another app shows the chat, not a blank screen. On iPad,
  the app opens on the taf used last. The sample taf now streams its answer like a real
  one, and a UI test pins all of this on a simulator.

### Added

- Deleting your account finishes even if the app closes partway: you confirm once, and tafs
  cloud deletes the account in tafs, its connections and KIFF, trying again on its own until
  it's done. It can't be undone.
- A card that waits for your answer (confirm, questions, approval, sign in) gets it as data:
  `POST /v1/tafs/{id}/components/{component}/answer`, and the thread shows each card's
  answer. Your choice reaches the taf as you made it, never as a line of chat text.
- Settings, **Devices**: the devices signed in as you on the account, when each was last
  used, and Sign out for any but this one, behind Face ID.
- Settings, **Notifications & haptics** (the 901 test): **Sound** off makes this device's
  alerts arrive quietly, in Notification Center and Needs you, with no sound or banner;
  **Haptics** off stops the app's taps. Each is for this device only. tafs cloud's
  `POST /v1/push` takes `sound`.
- Accounts (the 870 test, kiff/tafs#294): a person who uses a shared taf is offered only what
  their role allows. No Approve or Refuse without the right to answer held calls (a line says
  who answers instead), no Delete account without that right, and no Doors tab or share line
  on a taf they don't manage. "You" marks only their own door key, and the Card says the
  account issued it. The account's name shows next to Settings, and Settings has Switch account.
- Usage: after an account moves from Solo in the App Store to Team, which KIFF bills,
  the usage screen says Solo still renews with Apple on its date, with a Cancel Solo
  button that opens Apple's own subscriptions page. The usage read's `billing` may
  carry `apple_solo_until`.
- Chat: while a taf or Coach works, the chat shows the step in progress, one line
  of what it's thinking (once it has thought for a few seconds, changing at a pace
  you can read), and the steps done under it. A step tried again shows once, with
  its number of tries. Once the answer is in, the steps fold into "Worked for
  12 s", which opens them again, and that line stays with the message. Leaving the
  chat or the app mid-answer and coming back follows the work to its end. The
  answer appears at an even pace, with formatting still being written never shown
  as symbols. A message you sent can be sent again, edited first, or copied from
  its menu. tafs cloud's message stream gains `thinking` and `step` events and
  a `call_id` on `activity`; `GET /v1/tafs/{id}/runs/{run}/stream` picks your own
  run back up; conversation messages may carry `work`. The definitions of tafs and
  Coach set `thinking: "summarized"`, which needs kiff/agents with it.
- Doors: a taf can have an email address of its own. It has none until you turn
  it on in the taf's Doors tab, where a dial picks the door and pressing the taf
  opens or closes it. A paired sender's email works like a chat message, under the
  taf's Cards, and the answer goes back in the same thread; the chat marks both.
  Anyone else knocks: you're told who wrote and what they want, and answer Do it,
  Pair or Ignore. Your own email is paired from the start, and an email counts as
  a paired sender's only when it passes the sender check. Each door takes 20
  emails a day from you and paired senders and 10 from anyone else, then one line
  says how many more were dropped. 20,000 characters an email, text only. tafs
  cloud gains `/v1/tafs/{id}/doors` and the email door's webhook; the home list
  may carry `doors_offered` and each taf's `doors`, conversation messages `door`,
  and the conversation `knocks`.
- Security reporting, contribution and agent guidance, issue and pull-request
  templates, code owners, and dependency-update configuration.
- Halt: one tap stops a taf, or every taf with Halt all,
  until you resume it with Face ID. It shows Halted only once tafs' runtime, KIFF
  and planque all confirm, and Halting… until then. Nothing from before a halt
  runs again after a resume. tafs cloud gains `POST`, `GET` and `DELETE` on
  `/v1/tafs/{id}/halt` and `/v1/halt`.
- AI fair use (#193): chats with your tafs and Setup are included up to half
  your plan's monthly price in AI use each period. Past it, your tafs ask before
  going on, and again at each further full bound; AI use is never billed. tafs
  cloud sends the bound with each chat and gains `GET /v1/usage/ai`.
- Task packs: when your tafs' tasks run out, add a pack (Pico, Vonk, Rayo, Forte
  or Maxi) in the app. A pack's tasks last 12 months; your plan's tasks are used
  first each period, then packs, oldest first. Usage shows the tasks the period
  has, what's used and what's left, the packs you hold, and one line on who bills
  your plan, with Manage when Apple does.
- Solo in the app, through the App Store: a monthly subscription that starts
  with a free week, for an account with no plan billed elsewhere. Every offer
  shows its terms. Restore purchases brings back what this Apple account bought.
- Ask an admin: someone who can't add tasks asks for a pack, with a reason, and
  sees it waiting until an admin approves (buys the pack) or declines it with a
  note; they can withdraw it. tafs cloud tells the phones of people who may add
  tasks, and only theirs.

### Removed

- The monthly ceiling row on Usage: tasks past what the plan includes come from
  packs.

### Changed

- On iPhone and iPad, a card's answer goes to tafs cloud as data, not as a message in the chat,
  and the card shows the answer as it was recorded: your choice, Approved, Declined, or why it
  didn't go through. An answer that doesn't go stays on the card, so you can answer again.
- Dictation in a language whose own form the device doesn't have uses its best form: British,
  then American English for English; a language's own country's form for the others.
- On iPhone and iPad, a taf Coach hands over is set up by tafs cloud, not by the app. Its card
  shows tafs cloud's answer: set up, with a button to open it; why it wasn't, with Coach handing
  it over again; or "Setting up…" until the answer comes. The card no longer offers Try again.
- Deleting the account the earlier way, where the app itself asks tafs cloud, planque and KIFF
  in turn, still works for installed builds. It goes one release after the build that signs one
  intent for tafs cloud to carry.
- The app's earlier way of answering a card, a message `[<type> <id>] <answer>`, still
  works while that card waits, and is taken as the card's answer. It goes one release after
  the app build that uses the answer route.
- You see only what your role lets you do: halting, versions, revoking or removing a taf,
  its doors' controls and Coach are hidden unless you may use them, instead of failing
  once tapped (or after Touch ID).
- Coming back to the app, a chat stays where you left it instead of showing blank space.
- A chat whose task stopped at the taf's cost or step limit says so, and who can raise
  the limit, instead of staying on "…".
- No one reads another person's chats: People's work, the notice that a chat can be read,
  and Settings → Who read my work are gone. Your chats with a shared taf are yours alone.
- From the device test of build 793, on an iPad:
  - A held call's card waits in the chat for the taf's words that explain it, at
    most 45 seconds, instead of showing alone under your message first. Needs you
    and the alert still show it at once.
  - Opening a taf with a call waiting lands at the latest when the call is near the
    end, instead of on blank space with the Needs you pill and the arrow.
  - People & groups' button reads "+ Group" on the iPad too.
- The app no longer crashes on iPad while a chat or a sheet updates (build 793):
  SwiftUI on iPadOS 27 builds some views on a background render thread, and the
  app's runtime main-thread checks stopped it there.
- From the device test of build 789, on an iPad:
  - The app no longer crashes a few seconds after launch: the offer to turn
    on alerts shows over the screen instead of in a sheet.
  - The tafs list keeps its order while you look at it, and settles again when a taf
    comes or goes or the app comes back.
  - A held call's card comes after the taf's words that ask for it. When KIFF won't
    take your answer, the card says why in words instead of KIFF's code: for a call you
    asked for, someone else approves it and you can still refuse it; for other reasons
    the buttons that can't work go. An expired hold says so at once, Needs you reads
    KIFF again when it expires, and its Record row reads "no longer waiting · nothing
    sent" instead of "waiting for you".
  - A message you send goes to the top at once, and coming back from another app the
    chat returns to it, or to its latest, instead of blank space.
  - People & groups reads again while it's open, so someone who joins shows; its
    button says "Group".
  - Signing in shows the approval link, with a Copy button, to open in another browser.
- From the device retest of build 755, on an iPad and an iPhone:
  - The tafs list puts the taf waiting on you first, then the one used most
    recently.
  - Alerts are offered once, right after signing in, with a short screen that
    says why, so the first held call reaches you. Needs you still offers them.
  - A sent message moves to the top as soon as you send it, not once the answer
    has rendered. A chat opens at its latest message.
  - An answered hold sits where you answered it: after the taf's "waiting for
    your approval", before what it did next.
  - Stop takes effect at once in the app, and leaves no "Stopped" line.
  - A "Not sent" kept because you wrote too many messages too fast goes once a
    later message goes through.
  - The Card shows its own total and the extra room apart, as KIFF says them
    ("300 US$ a day · +30 US$ extra room until 00:00"), not one sum. tafs
    cloud's home list may carry KIFF's `extra_room`, `base_limit` and
    `room_window_end` on a rule and on a Card's total.
  - A hold's receipt reads "held for you by the Card" while it waits, not
    "allowed by the Card".
  - Usage on the Card updates as the taf works, not only once Usage is opened.
  - Needs you reads again when a hold's alert comes in before KIFF lists it.
  - Ending a trial goes back to the taf with a cross-fade, and "This taf is
    gone" no longer flashes. Starting a trial beside the sidebar selects it there.
  - Usage, Versions and Halt open wherever their panel is tapped.
  - Forget asks first, and says it can't be undone.
  - Do it on a knock says why it wasn't sent in its own sheet.
  - The ring of a taf with an open door runs along the avatar's edge.
  - Coach makes the new version a card hands over, even when the card has the
    same id as one from an earlier conversation. Before, it showed the earlier
    version and made none.
  - Coach says when extra room ends: when that total resets.
- Coach knows holds, tasks and packs, and halting (#218): why a call is held or
  refused and what the owner can do about it, what counts as a task and what
  happens when tasks run out, and what a halt stops. It no longer says a refused
  call waits for you. They're in its instructions: when they were skills it could
  read on demand, it read none of them in the Eval.
- Dictation uses any language the device dictates in (#208), the owner's first,
  and the switch while dictating goes through those. Before, it knew Spain's
  Spanish and US English only: a device with French dictation, or Spanish from
  Colombia, said Spanish wasn't available. With none on the device, it says how
  to turn dictation on.
- Dictating goes on through pauses (#208): after a pause the next words go after
  what was said, where before they replaced it, and the recognizer ending by
  itself no longer stops dictation. The mic stays in the message bar when the
  box has text, so you can carry on dictating after typing.
- While dictating, a bar under the message box shows your voice as it comes, so
  it's plain that the mic is on, with cancel (the box goes back as it was) and
  stop (what you said stays, unsent).
- The doors no longer show "Couldn't read the doors: cancelled" when their
  screen closes before the read ends.
- A taf that didn't start reaches Coach (#183): when tafs cloud refuses to start
  the taf Coach handed over, Coach hears it once and says plainly that no taf was
  created. When the name was taken, it hands it over again with the same Card under
  a new name; for any other reason, it asks you what to do first. It no longer
  claims a rename or a start that didn't happen, or drafts a second Card. A server
  error or an expired sign-in still offers Try again.
- Tables in a chat are drawn as tables (#211): a header, a line between rows,
  amounts aligned on the right, and a wide table scrolls sideways. VoiceOver reads
  each row with its column names. Before, a table showed as text with its pipes.
- Extra room on a held call (#211): a call over a total the Card keeps across
  calls, such as a day's, which approving once can't pass, now offers **Extra
  room** with Face ID. KIFF gives the Card just the room this call needs, until
  that total resets, and the call goes on; the Card is as it was after that.
  Changing the limit for good stays in Coach mode. The hold card and the Card
  also say how many calls the vendor refused today still counted toward it.
- Every time the app shows is in your account's time zone (#211): holds'
  deadlines, chats, the Record's days, Usage, versions and a taf's next run, as
  KIFF's Card days are. Without an account (sample mode), it's the device's. When
  the account's clock isn't the device's, a hold's deadline and the Record's times
  name the zone ("14:00 CET").
- Words show once while an answer streams (#214): when the chat reloads mid-answer
  (a taf read what you wrote meanwhile, or Setup's conversation after a step), the
  words it already recorded no longer show a second time as the live answer.
- Setup says each thing once and keeps its plan with you (#214): a question's card
  isn't repeated in its message, the plan comes once the job is known and never
  restates a message, a step waiting for you stays in progress instead of failed,
  and the plan moves first once you answer, approve or sign in.
- A taf's answer only grows as it streams (#206): its words stay as written at
  each tool call, and a call KIFF holds shows its card where the call was made,
  after the words before it, and stays there once answered, in your words
  ("Refused by you · …"). tafs cloud's message stream gains a `held` event with
  KIFF's ids for the call.
- A message you send stays at the top of the chat while the answer arrives
  below it, in a taf's chat and in Coach mode (#206): the chat no longer jumps
  to the end when the answer is short.

Earlier history is available in [merged pull requests](https://github.com/kiff/tafs/pulls?q=is%3Apr+is%3Amerged)
and [releases](https://github.com/kiff/tafs/releases). This file does not
retroactively assign release dates or claim that merged work was deployed.
