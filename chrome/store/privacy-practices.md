# Chrome Web Store: privacy practices

What to enter on the dashboard's **Privacy practices** tab for the tafs extension. Draft; nothing is submitted until
the owner registers the developer account. Each answer is matched to `docs/chrome.md` and to kiff.dev's tafs Privacy
page (the "Your browser (Chrome)" item): if a code change or that page changes, this changes with it.

**Privacy policy URL:** `https://kiff.dev/tafs/privacy`

## Single purpose

> tafs for Chrome lets the tafs a person runs in tafs cloud work in that person's own Chrome, only on the sites the
> person allows, with every risky step approved on the person's phone.

(One purpose: a browser for the person's own tafs. Coach in the side panel is the same chat as the tafs app, and the
saved sign-ins exist only so a taf can fill one on the exact site, after the phone approves that fill.)

## Permission justifications

Each permission in `chrome/manifest.json`, and why. The manifest asks for **no host permission at install**.

| Permission | Justification |
|---|---|
| `storage` | `chrome.storage.session` only, which Chrome keeps in memory: the pairing code while it is being typed, the extension's own status for the side panel, and the list of phone releases already used, so a step replayed after Chrome stops the service worker can't run twice. Nothing is written to disk with it. (The pairing, the keys, the sealed sign-ins and the KIFF device session token live in IndexedDB, which `storage` does not cover; unpairing wipes them.) |
| `alarms` | One alarm, once a minute: it wakes the service worker so the extension can reconnect to tafs cloud while it is paired, close the window of a run that nobody has sent a step to for a while, and close a connection that has been idle. |
| `scripting` | Reads the page of a run's own tab as text and form fields, with password, card and code fields removed, and does one act (a click, a fill, a choice) that tafs cloud asked for and the extension checked. Also stops the page's dialogs from blocking a run's own tab. Only on a site the person has allowed. |
| `sidePanel` | The side panel is the whole interface: pairing with the phone, what a taf is doing with Pause, Resume and Stop, the sites to allow, saved sign-ins, and Coach. |
| `tabs` | Opens the run's own window and tabs, reads the address and title of a run's tab to keep it on the sites the run lists, and, when the person clicks "Share this page", reads the title and address of the tab in front, once. Never any other tab. |
| `tabGroups` | Puts a run's tabs in one visible group named for the taf ("tafs · Refunds"), so the person can see which tabs a taf is using. |
| `webNavigation` | Sees a move to another site in a run's own tabs (a link, a redirect, a new window the page opens), so the extension can stop it unless the site is one of the run's sites. Nothing is recorded outside a run. |
| `declarativeNetRequest` | A fence for a run's own tabs: a request to a host that is not one of the run's sites is blocked there. It only blocks, only in those tabs, and widens no access. |
| Optional host permissions (`https://*/*`) | The extension does not hold access to any site at install. When a run needs a site, the side panel shows "Allow shop.example in Chrome" and Chrome asks the person, one site at a time. The extension reads and acts only on sites the person allowed, and never on a bank, wallet, password manager, payment, health or government site, a Chrome page or the Web Store, unless the person's phone allows that one site for one task. |

Not requested: `debugger`, `downloads`, `cookies`, `history`, `bookmarks`, `webRequest`, `nativeMessaging`,
`clipboardRead`, `activeTab` and any host permission at install.

## Remote code

**No.** All the code is in the package: no remote script, no `eval`, no `Function` from a string, and the extension
pages' policy is `script-src 'self'; object-src 'none'`. What tafs cloud sends is data, a step (open this site, click
this element), signed by tafs cloud, checked by the extension against the sites the run lists, and never run as code.

## Data usage

Answers for the category checkboxes. "Collected" means it leaves the person's computer.

| Category | Declare | What and why |
|---|---|---|
| Website content | **Yes** | The text and form fields of the pages a taf works on, on allowed sites, with password, card and one-time-code fields removed, secrets by their shape removed, and the page address's query and fragment dropped. It goes to tafs cloud (and the AI provider that runs the task) as part of the run. For each change a taf makes, KIFF keeps the page before and after, as text, for at most 30 days. The one page the person shares with Coach is the same, read once, on the person's click. |
| Personal communications | **Yes** | What the person types to Coach in the side panel, which is the same chat as in the tafs app. |
| User activity | **Yes** | Each step a taf does goes on the Record with its site and the name of the field, never what was typed into a secret field. |
| Web history | **Yes** (see the first judgment call) | The sites a taf opens in its own tabs, in the Record. Never the person's own browsing, and the extension has no `history` permission. |
| Authentication information | **Yes** | The extension signs in to KIFF as a view-only device of the account (it is paired in the same step, so the side panel can talk to Coach), and keeps that device session's token in IndexedDB on the computer. It sends it only to KIFF's own API, as the bearer of its own calls, and unpairing removes it. A saved sign-in is a different thing: sealed on the computer under a key that can't be exported, opened only for one fill on that exact site after the person's phone approves it, and **never sent** to tafs cloud or KIFF. Passwords, card and code fields are removed from every read. |
| Personally identifiable information | **No** | The extension asks for no name, email or address. The pairing sends the name the person gives this Chrome ("Work Mac") and its browser and system version (see the second judgment call). |
| Health, financial and payment, location | **No** | Health and payment sites are never worked on unless the person's phone allows one for one task, and card fields are removed. The extension never reads the location. |

### The three certifications

Tick all three; each is true.

1. The data is **not sold** to third parties, outside the approved use cases.
2. The data is **not used or transferred for purposes unrelated** to the single purpose.
3. The data is **not used or transferred to determine creditworthiness** or for lending.

(Dashboard wording changes: use the three Limited Use statements as the dashboard words them.)

## Judgment calls for the reviewers (mac12, agentgabo)

1. **Web history.** The extension has no `history` permission and never reads the person's own browsing, but the
   sites a taf opens in its own tabs are recorded. The conservative answer is Yes, and this draft says Yes. It is
   also what the tafs Privacy page already says about the Record.
2. **Personally identifiable information.** The name the person gives this Chrome is free text, and the pairing sends
   the browser and system version. Neither is a real name or an address, so this draft says No. If a reviewer reads
   "the Chrome's name is the person's data" the other way, tick it.
3. **Authentication information.** Yes, because of the extension's own KIFF device session token (a credential that
   identifies this Chrome to KIFF's API), found by mac12's read of `sw.js`. The saved sign-ins are the part that never
   leaves the computer: say that in the dashboard's explanation if it asks, so a reader doesn't take "Yes" to mean
   KIFF holds the person's passwords (`docs/chrome.md`, Sealed values).
4. **A page can show other people's details,** such as a taf's customers'. The tafs Privacy page says KIFF
   processes those as processor. The dashboard has no checkbox for that; the privacy policy URL carries it.
5. **Not the extension's to answer:** the store category, the support address, the screenshots and the promo
   images, and the developer account's contact email, trader status and country: the owner chooses those when
   registering.
