# Chrome Web Store: privacy practices

What to enter on the dashboard's **Privacy practices** tab for the tafs extension. Draft; nothing is submitted until
the owner registers the developer account. Each answer is matched to `docs/chrome.md` and to kiff.dev's tafs Privacy
page (the "Your browser (Chrome)" item): if a code change or that page changes, this changes with it.

**Privacy policy URL:** `https://kiff.dev/tafs/privacy`

## Single purpose

> tafs for Chrome lets the tafs a person runs in tafs cloud work in that person's own Chrome, only on the sites the
> person allows, with every risky step approved on the person's phone.

(One purpose: a browser for the person's own tafs. The side panel shows the taf working in this Chrome and its own chat, the
same as in the tafs app, with Coach in its own view. Coach, and from 0.2.14 a taf on one of its own phone-signed sites, reads the page in front when the person sends it a message, only once they have allowed that in the extension with a consent of its own. It keeps no passwords: at a sign-in page, the taf asks the person to sign in themselves, in their own tab.)

## Permission justifications

Each permission in `chrome/manifest.json`, and why. The manifest asks for **one host permission at install**: every https site, as Claude in Chrome does.

| Permission | Justification |
|---|---|
| `storage` | `chrome.storage.session` only, which Chrome keeps in memory: the pairing code while it is being typed, the extension's own status for the side panel, the relay's step nonces, the person's answers to a "Your turn" until KIFF's connection service says it has them, and which of a finished run's tabs the extension may still close. Nothing is written to disk with it. (The pairing, the keys and the KIFF device session token live in IndexedDB, which `storage` does not cover; unpairing wipes them.) |
| `alarms` | One alarm, once a minute: it wakes the service worker so the extension can reconnect to KIFF's connection service while it is paired, close the window of a run that nobody has sent a step to for a while, and close a connection that has been idle. |
| `scripting` | Reads the page of a run's own tab as text and form fields (a run's own tab includes, from 0.2.12, the tab the person is on when they start that taf from the side panel, if it's on one of the connection's sites: it joins the run's tab group, is fenced and read the same way, and stays open when the run stops; from 0.2.14 it joins only when the run's first page is that tab's own address, read in place, any other address the run opens goes to a tab of the run's own, and the run never sends the person's tab to another address itself; a step it takes there (a click, a submit, back) can still move that tab to another page, only within the connection's sites), with password, card and code fields removed, and does one act (a click, a fill, a choice) that KIFF asked for and the extension checked. Also stops the page's dialogs from blocking a run's own tab, and, when a taf hands one step to the person ("Your turn"), outlines the field or button it names for a few seconds; it types, clicks and fills nothing then. All of that only on a site the person's connection lists, which they approved on their phone. Separately, only once the person has allowed it in the side panel (the page consent: off until given, withdrawable there, with "Send without the page" always available), when the person sends a message to Coach, or from 0.2.14 to a taf on one of that taf's phone-signed sites (checked in the extension and again by tafs cloud), it reads the page in the tab in front once, as its visible text only (no form fields or typed values, nothing inside editable regions; card numbers cut to their last four digits, keys and labelled secrets masked, and one-time codes named as one in English, French, Spanish or German masked), to send with that message; never on a bank, payment, mail or health site, a site the person switched off for Coach, a Chrome page or the Web Store, and never another tab. While a run is live, it also draws a small bar of its own in the tab in front of each window (any https page), so the person can pause, stop or go to the taf's tab from wherever they are: the bar reads nothing from that page and sends only which of its buttons was pressed. |
| `sidePanel` | The side panel is the whole interface: pairing with the phone, the taf working in this Chrome with its own chat (words, and files the person picks with Chrome's file picker) and Pause, Resume and Stop, the "Your turn" card when a taf asks the person to do one step in the page (Show me, Done, Not now), and Coach in its own view, with the person's conversations with Coach (each can be archived, and put back). |
| `tabs` | Opens the run's own window and tabs, reads the address and title of a run's tab to keep it on the sites the run lists. Never any other tab. |
| `tabGroups` | Puts a run's tabs in one visible group named for the taf ("tafs · Refunds is working", then "… is done" once the run ends), so the person can see which tabs a taf is using. |
| `webNavigation` | Sees a move to another site in a run's own tabs (a link, a redirect, a new window the page opens), so the extension can stop it unless the site is one of the run's sites. Nothing is recorded outside a run. |
| `declarativeNetRequest` | A fence for a run's own tabs: a request to a host that is not one of the run's sites is blocked there. It only blocks, only in those tabs, and widens no access. |
| `notifications` | Chrome asks this of an extension that receives Web Push. It is used for one thing: a silent wake-up message from KIFF's connection service when a task needs this Chrome and the extension is asleep, so the extension can connect (no notification is ever shown, and the message carries no data). For that, the extension gives KIFF's connection service its push subscription: the address Chrome's push service gives this extension, its public key, and the shared secret used to encrypt what's sent to it. It sends nothing else and shows nothing. |
| Host permissions (`https://*/*`) | Lets the extension work on the sites a person's connection lists, without Chrome asking again for each one. **It acts only on the sites each connection lists, which the person approves on their phone** (the phone signs the list, and the extension checks that signature before any step), and a network fence on each run's own tab blocks every other host. It never works on a bank, wallet, password manager, payment, health or government site, a Chrome page or the Web Store, unless the person's phone allows that one site for one task. It reads nothing in the background, and nothing outside a run's own tab except the page in front when the person sends Coach a message (see `scripting`). The only thing it puts on any other page is the floating bar described under `scripting`, which reads nothing. |

Attaching a file needs no permission: Chrome's file picker gives the extension only the files the person picks.

Not requested: `debugger`, `downloads`, `cookies`, `history`, `bookmarks`, `webRequest`, `nativeMessaging`,
`clipboardRead` and `activeTab`.

## Remote code

**No.** All the code is in the package: no remote script, no `eval`, no `Function` from a string, and the extension
pages' policy is `script-src 'self'; object-src 'none'`. What KIFF's connection service sends is data, a step (open this site, click
this element), signed by it, checked by the extension against the sites the run lists, and never run as code.

## Data usage

Answers for the category checkboxes. "Collected" means it leaves the person's computer.

| Category | Declare | What and why |
|---|---|---|
| Website content | **Yes** | The text and form fields of the pages a taf works on, on allowed sites, with password, card and one-time-code fields removed, secrets by their shape removed, and the page address's query and fragment dropped. It goes to KIFF's connection service (planque) and the AI provider that runs the task, as part of the run. For each change a taf makes, KIFF keeps the page before and after, as text, for at most 30 days. Once the owner's Privacy page says so and planque's switch is on (the build ships with it off), each step that acts also keeps one picture of the page, taken just before the act with password, card and code fields, and text that looks like a card number or a key covered, never on a sensitive site or a checkout; KIFF keeps it encrypted for at most 30 days and shows it only to the owner in the tafs app. After a "Your turn" the page is read back to the taf with no field values and no text inside editable regions, so what the person typed is not sent (a page that repeats it in its own plain text is read like any page, with secrets masked by pattern); Not now sends no page. Only after the person allows it in the side panel (the page consent: a screen saying what goes, when and to whom, then Allow; off until then, withdrawable with "Stop reading pages", and "Send without the page" always there), when they send a message to Coach, or from 0.2.14 to a taf on one of that taf's phone-signed sites (checked in the extension and again by tafs cloud; off those sites nothing is read and the panel says why), the page in front goes with it: its address without query or fragment, its title and its visible text only (no form fields or typed values, nothing inside editable regions; card numbers cut to their last four digits, keys and labelled secrets masked, one-time codes named as one in English, French, Spanish or German masked), at most 24 KB; without the page consent the tab is never touched, and tafs cloud drops a page sent without it; never from a bank, payment, mail or health site, a site the person switched off for Coach, a Chrome page or the Web Store. It goes through tafs cloud to the AI provider that answers, for that message only. tafs keeps the page only while that answer is being made (or while a paused answer waits) and then drops it; the chat keeps the site's name, shown under the message as "Reading: ‹site›", for 30 days, and Coach's or the taf's answer, which may quote the page, is kept with the chat for 30 days like any answer. For a taf, the AI service keeps the page text on the run only until the run ends, and in the turn the page goes with, the taf only reads, never acts. Logs carry only the site's name. |
| Personal communications | **Yes** | What the person types to a taf in the side panel, and the answer to a "Your turn" (Done or Not now, with no typed values), which is the same chat as in the tafs app, and to Coach, behind a button, which is the same chat as Coach in the app. From 0.2.10, the files the person picks with the **+** in the side panel's composer (Chrome's own file picker, nothing else: no page, tab or screen capture): at most 5 a message, photos, PDF, text, CSV, Markdown, Word or Excel, within the apps' limits, each image redrawn as a JPEG first so only its pixels go (no location or other metadata). They go to tafs cloud and Claude, like attachments in the apps: declared to tafs cloud, uploaded by the extension straight to KIFF's storage, kept for the person alone with that chat, and read by the AI with that message. The extension keeps no copy and never opens one again. |
| User activity | **Yes** | Each step a taf does goes on the Record with its site and the name of the field, never what was typed into a secret field. |
| Web history | **Yes** (see the first judgment call) | The sites a taf opens in its own tabs, or works in after the person started it from the side panel in the tab they were on, in the Record. Never the person's own browsing, and the extension has no `history` permission. |
| Authentication information | **Yes** | The extension signs in to KIFF as a view-only device of the account (it is paired in the same step, so the side panel can show the person's tafs and talk to them and to Coach), and keeps that device session's token in IndexedDB on the computer. It sends it only to KIFF's own services, as the bearer of its own calls: KIFF's API (`api.kiff.dev`, to claim and end the session) and tafs cloud (`cloud.tafs.kiff.dev`, for the tafs, their chats and Coach). Unpairing removes it. The extension keeps **no passwords**: at a sign-in page, the taf asks the person to sign in themselves, in their own tab. Passwords, card and code fields are removed from every read. |
| Personally identifiable information | **No** | The extension asks for no name, email or address. The pairing sends the name the person gives this Chrome ("Work Mac") and its browser and system version (see the second judgment call). |
| Health, financial and payment, location | **No** | Health and payment sites are never worked on unless the person's phone allows one for one task, and card fields are removed. The extension never reads the location. |

### The three certifications

Tick all three; each is true.

1. The data is **not sold** to third parties, outside the approved use cases.
2. The data is **not used or transferred for purposes unrelated** to the single purpose.
3. The data is **not used or transferred to determine creditworthiness** or for lending.

(Dashboard wording changes: use the three Limited Use statements as the dashboard words them.)

## Judgment calls for the reviewers

1. **Web history.** The extension has no `history` permission and never reads the person's own browsing, but the
   sites a taf opens in its own tabs (or works in, from the tab the person started it in) are recorded. The conservative answer is Yes, and this draft says Yes. It is
   also what the tafs Privacy page already says about the Record.
2. **Personally identifiable information.** The name the person gives this Chrome is free text, and the pairing sends
   the browser and system version. Neither is a real name or an address, so this draft says No. If a reviewer reads
   "the Chrome's name is the person's data" the other way, tick it.
3. **Authentication information.** Yes, because of the extension's own KIFF device session token (a credential that
   identifies this Chrome to KIFF's API), found in `sw.js`. The extension keeps no passwords at all: say that in the dashboard's explanation if it asks,
   so a reader doesn't take "Yes" to mean KIFF holds the person's passwords.
4. **A page can show other people's details,** such as a taf's customers'. The tafs Privacy page says KIFF
   processes those as processor. The dashboard has no checkbox for that; the privacy policy URL carries it.
5. **Not the extension's to answer:** the store category, the support address, the screenshots and the promo
   images, and the developer account's contact email, trader status and country: the owner chooses those when
   registering.
6. **The page in front, sent with a message to Coach or (from 0.2.14) to a taf, needs affirmative consent** (the store's user data policy: prominent disclosure, then an in-product action). From 0.2.7 it is off until the person opens "Let Coach read the page…", reads what goes, when and to whom, and presses **Allow**. Not now, "Send without the page" and "Stop reading pages" keep Coach working without the page. From 0.2.14 the same consent covers a message to a taf, only on that taf's own phone-signed sites; the screen says so ("Let Coach and your tafs read the page…"), and its version is 2, so everyone who allowed version 1 is asked again. If a reviewer asks, point to that screen (`page_consent_*` in `chrome/_locales`) and to `docs/chrome.md`.
