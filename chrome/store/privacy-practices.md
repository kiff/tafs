# Chrome Web Store: privacy practices

What to enter on the dashboard's **Privacy practices** tab for the tafs extension. Draft; nothing is submitted until
the owner registers the developer account. Each answer is matched to `docs/chrome.md` and to kiff.dev's tafs Privacy
page (the "Your browser (Chrome)" item): if a code change or that page changes, this changes with it.

**Privacy policy URL:** `https://kiff.dev/tafs/privacy`

## Single purpose

> tafs for Chrome lets the tafs a person runs in tafs cloud work in that person's own Chrome, only on the sites the
> person allows, with every risky step approved on the person's phone.

(One purpose: a browser for the person's own tafs. The side panel shows the taf working in this Chrome and its own chat, the
same as in the tafs app, with Coach behind a button. It keeps no passwords: at a sign-in page, the taf asks the person to sign in themselves, in their own tab.)

## Permission justifications

Each permission in `chrome/manifest.json`, and why. The manifest asks for **one host permission at install**: every https site, as Claude in Chrome does.

| Permission | Justification |
|---|---|
| `storage` | `chrome.storage.session` only, which Chrome keeps in memory: the pairing code while it is being typed, the extension's own status for the side panel, the relay's step nonces, the person's answers to a "Your turn" until KIFF's connection service says it has them, and which of a finished run's tabs the extension may still close. Nothing is written to disk with it. (The pairing, the keys and the KIFF device session token live in IndexedDB, which `storage` does not cover; unpairing wipes them.) |
| `alarms` | One alarm, once a minute: it wakes the service worker so the extension can reconnect to KIFF's connection service while it is paired, close the window of a run that nobody has sent a step to for a while, and close a connection that has been idle. |
| `scripting` | Reads the page of a run's own tab as text and form fields, with password, card and code fields removed, and does one act (a click, a fill, a choice) that KIFF asked for and the extension checked. Also stops the page's dialogs from blocking a run's own tab, and, when a taf hands one step to the person ("Your turn"), outlines the field or button it names for a few seconds; it types, clicks and fills nothing then. Only on a site the person's connection lists, which they approved on their phone. |
| `sidePanel` | The side panel is the whole interface: pairing with the phone, the taf working in this Chrome with its own chat and Pause, Resume and Stop, the "Your turn" card when a taf asks the person to do one step in the page (Show me, Done, Not now), and Coach behind a button. |
| `tabs` | Opens the run's own window and tabs, reads the address and title of a run's tab to keep it on the sites the run lists. Never any other tab. |
| `tabGroups` | Puts a run's tabs in one visible group named for the taf ("tafs · Refunds"), so the person can see which tabs a taf is using. |
| `webNavigation` | Sees a move to another site in a run's own tabs (a link, a redirect, a new window the page opens), so the extension can stop it unless the site is one of the run's sites. Nothing is recorded outside a run. |
| `declarativeNetRequest` | A fence for a run's own tabs: a request to a host that is not one of the run's sites is blocked there. It only blocks, only in those tabs, and widens no access. |
| `notifications` | Chrome asks this of an extension that receives Web Push. It is used for one thing: a silent wake-up message from KIFF's connection service when a task needs this Chrome and the extension is asleep, so the extension can connect (no notification is ever shown, and the message carries no data). For that, the extension gives KIFF's connection service its push subscription: the address Chrome's push service gives this extension, its public key, and the shared secret used to encrypt what's sent to it. It sends nothing else and shows nothing. |
| Host permissions (`https://*/*`) | Lets the extension work on the sites a person's connection lists, without Chrome asking again for each one. **It acts only on the sites each connection lists, which the person approves on their phone** (the phone signs the list, and the extension checks that signature before any step), and a network fence on each run's own tab blocks every other host. It never works on a bank, wallet, password manager, payment, health or government site, a Chrome page or the Web Store, unless the person's phone allows that one site for one task. It reads nothing in the background and nothing outside a run's own tab. |

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
| Website content | **Yes** | The text and form fields of the pages a taf works on, on allowed sites, with password, card and one-time-code fields removed, secrets by their shape removed, and the page address's query and fragment dropped. It goes to KIFF's connection service (planque) and the AI provider that runs the task, as part of the run. For each change a taf makes, KIFF keeps the page before and after, as text, for at most 30 days. Once the owner's Privacy page says so and planque's switch is on (the build ships with it off), each step that acts also keeps one picture of the page, taken just before the act with password, card and code fields, and text that looks like a card number or a key covered, never on a sensitive site or a checkout; KIFF keeps it encrypted for at most 30 days and shows it only to the owner in the tafs app. After a "Your turn" the page is read back to the taf with no field values and no text inside editable regions, so what the person typed is not sent (a page that repeats it in its own plain text is read like any page, with secrets masked by pattern); Not now sends no page. When Coach works on a page, it reads it the same way, as a run's own read on the connection's sites, and that goes to tafs cloud as part of the chat. |
| Personal communications | **Yes** | What the person types to a taf in the side panel, and the answer to a "Your turn" (Done or Not now, with no typed values), which is the same chat as in the tafs app, and to Coach, behind a button, which is the same chat as Coach in the app. |
| User activity | **Yes** | Each step a taf does goes on the Record with its site and the name of the field, never what was typed into a secret field. |
| Web history | **Yes** (see the first judgment call) | The sites a taf opens in its own tabs, in the Record. Never the person's own browsing, and the extension has no `history` permission. |
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
   sites a taf opens in its own tabs are recorded. The conservative answer is Yes, and this draft says Yes. It is
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
