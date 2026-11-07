# Accounts, people and plans

tafs keeps no accounts of its own. A person signs in with KIFF, and KIFF says who they
are, on which account, with which rights. People, groups, usage and billing are KIFF's;
the app shows what KIFF says, and each change is the person's own act, behind Face ID.

Code: in the app, `SignInView` (`SettingsView`, `AccountView`), `PeopleViews`,
`ShareViews` (`ShareTafSheet`, `OverviewView`), `UsageView`, `TasksViews`,
`DeleteAccountView`, `Services/Purchases.swift`, and the Signing in, People, Usage,
Tasks and plans and Deleting the account sections of `AppModel`; `KIFFClient`. In tafs
cloud, `person`, `needTaf`, `managesTaf`, `deleteAccount`, `usageBalance`, `usageCredits` and
`tafCredits` in `handler.ts`, and `runtime/src/cloud/credits.ts`.

## Signing in

Sign in with KIFF's device flow (`AppModel.startSignIn`, `finishSignIn`):

1. The phone asks KIFF for a code (`POST /v1/auth/device/start`) and sends its signing
   key with it.
2. It shows the code. The person approves it in KIFF on the web, signed in as
   themselves; the browser shows the key's fingerprint.
3. The phone polls (`POST /v1/auth/device/token`) until the code is approved or expires.
   It keeps polling through a dropped connection or a KIFF error, since iOS closes the
   app's sockets while the person is in the browser, and asks again at once when the app
   comes back.
4. The session is kept in the Keychain. It names the account and holds the phone's key,
   so KIFF accepts the phone's answers only with that key's signature. The approval key
   is registered next ([approvals.md](approvals.md#how-an-answer-is-signed)).

Each way out asks first: Sign out and Sign in again open a confirmation,
so a stray tap never ends the session (Android `ConfirmSignOut`, iOS `SignOutWay`). Signing out (`POST /v1/auth/device/logout`) or switching account clears everything of the
earlier session on the phone, including files opened from a chat. The session ends at KIFF
first: when KIFF doesn't confirm, the phone stays signed in and says so ("Not signed out at
KIFF yet…"), so a device shown as signed out never holds a session KIFF still accepts.
Its alerts are unregistered just before the logout (the call needs the session) and
registered again at once when KIFF doesn't end it, so a device still signed in keeps them. A
session KIFF already ended (a 401, or the account deleted) is cleared on the phone at once. Work still running for
the old session is dropped when it finishes (`accountGeneration`).

The phone remembers that alerts were enabled for this account. Signing back in restores
its token without another unlock when that consent is saved, or when cloud confirms this
phone is already enrolled for the same person/account. A different account does not inherit
that consent. If alerts cannot be restored, the sign-in shows **Alerts are off on this
phone**, with Turn on and Not now; a previous account's declined offer does not suppress it.

**The session ends at KIFF first.** Sign out and Sign in again ask KIFF to
end the session before anything changes on the phone, so a phone that shows signed out
never holds a session KIFF still accepts. If KIFF doesn't confirm, the phone stays signed
in and says "Not signed out at KIFF yet. Try again, or sign this phone out from another
device's Devices." It keeps trying by itself: after 2, 10, 30 and 60 seconds, then every 2
minutes, until KIFF ends the session (Android: `AppModel.signOutAtKiff`). The phone's alerts are
unregistered with tafs cloud just before KIFF is asked, since that needs the live session,
and registered again at once if KIFF didn't end it: a phone still signed in keeps its alerts.

**Devices**, in Settings (`DevicesView`): the devices signed in as this person on the
account (`GET /v1/me/devices`), each with when it was last used, and "This device" on
this one. **Sign out** on another device, behind Face ID, removes it
(`DELETE /v1/me/devices/{id}`): it no longer approves, and its sign-ins on the account
end at once. This device signs out with **Sign out of KIFF**.

## Roles and what a person may do

KIFF gives each person a role on the account, and a set of capabilities that the app
and tafs cloud check:

| Capability | What it allows in tafs |
|---|---|
| `cards.manage` | Start, change, pause and remove tafs; give a Card extra room |
| `holds.answer` | Answer held calls, get alerts for them, continue a paused run |
| `members.manage` | Make and change groups; use Coach, by default |
| `billing.manage` | Add tasks, and answer members' requests for them |
| `account.delete` | Delete the account |

What a person may do with each taf is KIFF's answer too (`GET /v1/me/access`): **use**
it, **oversee** it, or **manage** it. The app lists only the tafs a person may use or
oversee, and shows controls only where they manage the taf. An editor manages only the
tafs they made, which the role alone can't say, so tafs cloud asks KIFF for that taf
(`needTaf`). Only someone who manages a taf sees its doors' conversations and knocks.

The app offers only what the person's capabilities allow (`AppModel.allows`): no answer
buttons on a held call without `holds.answer` ([approvals.md](approvals.md#answering)),
and no **Delete account** without `account.delete`. An account from before roles, which
KIFF sends with no capabilities, keeps everything.

**Which account:** the account's name is always in sight: beside Settings on an iPad and
beside the mascot on an iPhone (`AppModel.accountLabel`). Someone on more than one
account signs out from Account, then signs in to the account they want. There is one
account on this phone at a time; sessions for several accounts are not kept side by side.
Account's Version shows the build and version, such as **25 (1.0)**.

Chats are each person's own. Nobody else on the account reads them, not an admin and not
the account's owner.

## People, groups and sharing

**People and groups**, in Settings (`PeopleGroupsView`): the account's groups, each with
its people and the tafs it uses. A group's manager sees the group's work on those tafs;
its members use them. Making a group, renaming it, adding or removing someone, and
making them its manager are each signed behind Face ID.

**Share** on a taf's page (`ShareTafSheet`), for whoever manages it, is two steps: who
uses it (a group, or one person), then what that means for them, then one Face ID for
all of it.

**Overview** (`OverviewView`) is today across the tafs the person uses or oversees: what
needs them, and a line per taf with what it does now, its Card as its page says it, and its
last result. Who uses the taf shows only to whoever manages it.

## Credits and plans

**Credits are the account's balance.** Each run uses the AI it used, in whole credits, and the
plan says how many credits the account has. There are no tasks, no AI bound with its Go on, and
no free trial: an account pays from its first day, and before a plan starts nothing that uses credits
runs. Plans and billing are KIFF's. tafs cloud writes every line the apps show about them
(`runtime/src/cloud/credits.ts`), and the apps draw the words as they are.

### Credits

**What a run uses:** the AI it used, in whole credits, rounded up once per run. KIFF works
them out from what kiff/agents records for the run, so every screen gives the same number. A run
counts once, when it ends, in the period it ends in, so a run that waits on an approval counts
when it ends. A run that used no AI (refused before it began) isn't listed.

**What isn't counted:**
- Coach and setting tafs up, within Coach's fair use (`coach`). Past it, Coach's runs count, and
  show as their own row;
- a trial of a new version, which sends nothing (`trial`);
- a run that failed on our side: it couldn't start, or kiff/agents or the model failed it
  (`failed`). A run carried on after it stopped on our side counts once: what the stopped
  attempt used isn't counted.

A call KIFF refused, or one held and then refused, doesn't change its run's credits: the run's
AI was used.

**The reads** (tafs cloud), every line in tafs cloud's words. The period's days are its first and
last as KIFF bounds it (UTC): the start's date, and the day before the end's date. The apps draw
the words as they are and derive nothing from the numbers but their order. `GET /v1/usage/credits`
is the period:

```json
{
  "period": { "start": "2026-11-01T00:00:00Z", "end": "2026-12-01T00:00:00Z", "current": true },
  "credits": 1240,
  "runs": 61,
  "words": "This period: 1,240 credits used · ends on 30 Nov",
  "tafs": [
    { "taf": "taf-3f9a1c0b7d2e4a6f8b91", "credits": 820, "runs": 41, "words": "Refunds · 820 credits · 41 runs" },
    { "taf": "taf-0b7c2e9d4a1f6e3b8c50", "credits": 374, "runs": 18, "words": "Chargebacks · 374 credits · 18 runs" }
  ],
  "coach": { "credits": 46, "runs": 2, "words": "Coach, past its fair use · 46 credits · 2 runs" },
  "not_counted": {
    "runs": 12,
    "words": "Not counted: 12 runs",
    "lines": [
      { "why": "coach", "runs": 7, "words": "Coach, within its fair use · 7 runs" },
      { "why": "trial", "runs": 3, "words": "Trials of a new version · 3 runs" },
      { "why": "failed", "runs": 2, "words": "Failed on our side · 2 runs" }
    ]
  },
  "note": "Each run counts once, when it ends, in whole credits."
}
```

The period before reads "Last period: 980 credits used · 1 Oct to 31 Oct", and one with nothing
used "This period: no credits used yet · ends on 30 Nov". `GET /v1/usage/credits/tafs/{id}` is one
taf's runs, newest first:

```json
{
  "period": { "start": "2026-11-01T00:00:00Z", "end": "2026-12-01T00:00:00Z", "current": true },
  "taf": "taf-3f9a1c0b7d2e4a6f8b91",
  "credits": 820,
  "runs": 41,
  "words": "Refunds · 820 credits · 41 runs this period",
  "items": [
    { "run": "4f0c2a9e…", "at": "2026-11-07T08:00:41Z", "credits": 6, "counted": true, "why": null,
      "words": "Its schedule · 6 credits", "opens": true },
    { "run": "b71d03c5…", "at": "2026-11-06T17:12:09Z", "credits": 15, "counted": true, "why": null,
      "words": "From its chat · 15 credits", "opens": true },
    { "run": "0a9e77d1…", "at": "2026-11-05T10:31:55Z", "credits": 0, "counted": false, "why": "trial",
      "words": "A trial of a new version · not counted", "opens": false }
  ],
  "next": "MjAyNi0xMS0wNVQxMDozMTo1NVp8MGE5ZTc3ZDE"
}
```

`taf` is the taf's id, as `GET /v1/tafs` gives it (`taf-` and 20 hex digits, which is its id on
kiff/agents too), never the name of its agent in KIFF. `run` is the run's id on kiff/agents, the
one its request opens by. Where a run came from is said as the request view says it: "Its
schedule", "From its chat", "By email", "Run now".

**The Credits panel** in Plan & usage, below the balance: the period's `words`, each taf's row,
Coach's row when there is one, then `not_counted` and `note`, with **Last period** and **This
period** to switch. The period's numbers are the account's, but a taf the person doesn't use or
oversee isn't named: it goes on the one row with `taf: null`, "Other tafs" (or "Removed tafs" when
that's all it holds), which doesn't open. A taf's list holds the person's own runs, and everyone's
on a taf they manage. A taf's row opens its runs (the second read), 50 at a time with **More
runs**, and a run's row opens its request while `opens` is true; otherwise it doesn't open. A 404
from a tafs cloud from before credits shows no panel, and a 503 shows tafs cloud's `words`. On
Android, `CreditsPanel` and `CreditsRunsSheet` (`readCredits`, `openTafCredits`, `moreTafCredits`
and `openCreditsRun` in `AppModel`); on iPhone and iPad, `CreditsViews.swift`
([ios-app.md](ios-app.md)).

**Settled since the first version:**
- **Runs are the unit** of a taf's row and its list, since a run is what credits count.
- **Every run's row carries its `run`**, a chat's included, and opens its request (what it did,
  step by step, with its Record entries) while kiff/agents keeps it.
- **`previous` is the only other period.** A list of periods can come with billing.

### The balance

**The plans,** each KIFF's:
- **Solo**, one person, billed by Apple or Google: the period's included credits, then the packs
  of credits it holds, which never expire. When both are used, the tafs wait for the next period,
  or for a pack.
- **Company**, seats billed by KIFF (KIFF's `team`): each seat's included credits, pooled for the
  period, then credits beyond them on the period's invoice, up to the account's **spend limit**.
  At the limit, the tafs wait until someone who manages billing raises it.
- **An agreement**, a plan arranged with KIFF (KIFF's `company`), and **internal**: no limit.
- **No plan:** nothing that uses credits runs. A person can sign in, look around, and ask Coach
  what tafs do. Coach sets nothing up, and no taf runs, until a plan starts.

**While the tafs wait:**
- **No new run starts:** a chat, Run now, a schedule's firing, a follow-up a taf set for itself,
  an app's event, a door's message, or Coach past its fair use. A chat and Run now answer with
  words that say why. For the rest, the taf's screen says what waited.
- **A run already going finishes.** What it uses past the balance counts as any run's does. On
  Company that's beyond the included credits, so runs going when the limit is reached can pass
  it, and no new one starts. On Solo it isn't charged.
- **A schedule's firing starts no run** (`skipped_credits`). What its check found waits for the
  next firing that runs, as after a run that failed before its first turn. It isn't a failure, so
  the schedule's `failing` doesn't move, and no "keeps failing" alert is sent.
- **Coach within its fair use doesn't wait** while a plan runs. Past it, Coach waits as the tafs do.

**Warnings,** each once a period, by push to the people who manage billing (`billing.manage`) and
in Plan & usage: at 80% and at 100%. On Solo that's of the period's included credits and packs, on
Company of the spend limit.

**Raising the spend limit** (Company): KIFF's `PUT /v1/me/usage/limit` with `{"raise": true}`, by
someone who manages billing. It's signed by the phone's device key (`KIFF-Device-Signature`), behind
Face ID or a fingerprint, as Go on was, and a replayed signature is refused, so one tap adds one
step. Each raise adds one step to the limit, which stays for the periods after, and answers the new
limit (`{"limit_cents": …}`). The tafs go on at once. Anyone else is told that an admin of the
account can raise it, by role, never by email.

**Asking an admin.** A member who can't raise the limit or buy a pack asks an admin, with a reason,
through KIFF's requests (`POST /v1/me/pack-requests`, which keeps its name). On Company a request
asks for a raise (`kind: "raise"`), and on Solo for a pack (`kind: "pack"`). The admins are alerted.
A raise answers every open request for one, and a pack bought for a request answers it. An admin can
decline one with a note, signed, as before, and the member can take theirs back.

### The balance read

`GET /v1/usage/balance` (tafs cloud) is the balance now, in tafs cloud's words, for Plan & usage,
Settings' Plan card and Home. The figures are samples:

```json
{
  "balance": {
    "state": "warning",
    "why": null,
    "meter_pct": 80,
    "words": "Company · 4 seats · €64 of the €80 spend limit used",
    "detail": "All 1,600 included credits are used. Beyond them, credits go on this period's invoice.",
    "warning": "80% of the spend limit is used. At the limit, your tafs wait until an admin raises it.",
    "raise": { "words": "Raise the limit to €120" },
    "buy": null
  },
  "plan_line": "Company · 4 seats · €64 of the €80 limit used",
  "plan_tone": "warning",
  "home_line": "80% of the spend limit is used"
}
```

- `state` is `ok`, `warning` (from 80%) or `waiting`. While it's `waiting`, `why` is
  `credits_used_up` (Solo: the period's credits and the packs), `limit_reached` (Company: the spend
  limit) or `no_plan`. Otherwise it's `null`.
- `meter_pct` is how much is used, 0 to 100, for a bar. On Solo, it's of the period's included
  credits and packs. On Company, it's of the included credits until they're used, then of the spend
  limit. It's `null` where nothing bounds it: an agreement, internal, or no plan.
- `words` is the balance's line, and `detail` its second line, or `null`. On Solo, `detail` is the
  packs ("Then 900 in packs, which never expire"). On Company, it's what comes after the included
  credits ("Then up to the spend limit, €80, on this period's invoice").
- `warning` is `null` below 80%. From 80%, and while waiting, it says what happens next and who can
  change it.
- `raise` is `{words}` only for someone who may raise the spend limit (Company, `billing.manage`),
  else `null`.
- `buy` is `null`, or what the app may offer this person (`billing.manage`) in the store, as
  `{solo, packs}`: `solo` on an account with no plan billed, `packs` while Solo runs.
- `plan_line` is Settings' Plan card: "Solo · 260 credits left", "Company · 4 seats · 1,240 of 1,600
  included credits used", "Company · waiting: the spend limit is reached", "No plan · choose one to
  start". `plan_tone` is `ok`, `warning` or `waiting`, for its colour.
- `home_line` is Home's one line about the plan, or `null` when the plan doesn't need the person:
  it's set from 80%, while waiting, and with no plan.
- It's the same on every refresh, whichever period the Credits panel shows. A 503 with `words` says
  KIFF can't be read, as the credits reads do.

**Waiting, in words** (samples):
- Solo: "Your tafs are waiting: this period's credits and your packs are used. A pack lets them go
  on, or they go on when the period starts again on 1 Dec."
- Company, to someone who may raise it: "Your tafs are waiting: the spend limit is reached. Raise it
  to let them go on, or they go on when the period starts again on 1 Dec."
- Company, to anyone else: "Your tafs are waiting: the spend limit is reached. An admin of this
  account can raise it."
- No plan: "Choose a plan to start your tafs."

**A run that waits:** a chat, or Run now, answers 409 with `code` (`credits_used_up`,
`limit_reached` or `no_plan`) and `words`, as above, and the app shows the words.

### In the apps

- **Plan & usage** opens on the balance: `words`, `detail`, a bar at `meter_pct` (none when it's
  `null`), the `warning`, then **Raise the limit** where `raise` is set, and Solo or packs where
  `buy` says. The Credits panel follows.
- **Raise the limit** asks once, then the device key signs KIFF's request behind Face ID or a
  fingerprint, as Go on did. While the phone can't approve, its state stands in the button's place
  ([approvals.md](approvals.md#set-up-approvals-on-this-device)). After it, the balance is read
  again, with the button busy until then. If that read fails, the screen says "Your tafs can go on.
  This period's credits couldn't be read since." and offers **Read it again**, which signs nothing.
  No other raise is offered until the balance has been read.
- **Settings' Plan card** shows `plan_line` in `plan_tone`'s colour, and opens Plan & usage.
  **Home** shows `home_line` when it's set. Both are read with each refresh.
- **A chat or Run now refused** for credits shows its `words`, with no button.
- **Asking an admin** stays where it was (`AskAdminView` on iPhone and iPad, `TaskRequests` on
  Android): for a raise on Company, and for a pack on Solo. An admin answers a request for a raise
  with the raise, and one for a pack by buying it for that request. Decline stays signed (on
  Android, `tafs.decline-request.v1|{tenant}|{request}`).
- **Gone:** the tasks parts (the tasks, packs of tasks, and the tasks the tafs counted), AI use and
  Go on, and the trial's screens and words.
- **The same on both apps:**
  1. A failed refresh shows tafs cloud's `words`, never silence.
  2. A run's time is a short time, as Android shows it.
  3. The last period is read again on each refresh, never kept for good.
  4. Sample mode shows credits on both apps: it's the demo.

**Buying in the app** (`Purchases`), through the App Store and Google Play only:
- **Solo**, a monthly subscription, offered where `buy.solo` is set. Its screen shows the store's
  price and every term: the period, that it renews until cancelled, how to cancel, and the Terms
  and Privacy Policy.
- **Packs of credits**, which never expire, offered where `buy.packs` is set.
- The products and their store ids come from KIFF's usage read (`store`), as now, with each pack's
  `credits`. Each purchase goes to KIFF for this account (Apple's signed transaction, or Google's
  purchase token) before the store is told it's done. One KIFF didn't record before the app closed
  is sent at the next launch. **Restore** brings back the store account's purchases.
- **Manage** opens the store's own subscriptions page. The app never links to buying outside the
  store. An account moved from Solo to Company is billed by KIFF. The app says when the Solo bought
  in a store still renews, and offers to cancel it there.

### Under the hood

- **KIFF keeps the balance:** each plan's included credits per seat, the packs in credits and what
  each run draws from them, the period's credits from each run's row, and the spend limit with its
  raises. Its credits read (`GET /v1/me/usage/credits`) gains the balance's figures, which tafs
  cloud puts in words. It alerts at the warnings, as it alerts for a held call.
- **KIFF answers before every run:** `GET /v1/me/credits/allowance`, with the agent's own key (a
  taf's, or Setup's for Coach), answers `{"allowed": true}`, or `{"allowed": false, "why": …}` with
  `credits_used_up`, `limit_reached` or `no_plan`. Setup's key gets Coach's rules.
- **kiff/agents asks before every new run,** whatever started it, and refuses one that waits with
  the reason as its `code` (409).
  - It keeps an allowed answer for up to a minute per account, so a chat doesn't wait on KIFF each
    time. A refusal isn't kept, so a raise or a pack lets the tafs go on at once.
  - When KIFF doesn't answer, the run doesn't start, as with any KIFF outage: a firing is tried
    again, and a chat answers 502.
- **tafs cloud reads the balance** with the person's session, and writes the words.
- **Gone:**
  - tafs cloud's `GET /v1/usage/ai`, and the AI bound it sent with each chat;
  - kiff/agents' bound and its `ai_fair_use` refusal;
  - KIFF's tasks (the refusal of a call past them, the tasks read, and packs of tasks), Go on
    (`PUT /v1/me/usage/ai`) and the trial.

## The account's time zone

Every time the app shows is in the account's time zone (`AccountTime`), and a Card's day
ends at the account's midnight. When the device's zone differs, the app offers it once:
"Use Europe/Paris for times in tafs?". Yes sets it in KIFF with a signed call
(`PUT /v1/me/time-zone`, one Face ID). Not now is remembered for that zone and account.

After KIFF confirms a chosen zone, the phone asks tafs cloud to apply that confirmed zone
to recurring schedules (`POST /v1/account/time-zone`), then refreshes times and drops its
cached Activity/run pages. Absolute one-off times and follow-ups keep their instants.
If scheduling fails, Account says the zone changed but schedules still need retry;
repeating the change finishes only schedules still in another zone. Card names the
account's zone. Tafs cloud never signs the person's KIFF change or accepts a zone from
the caller in the synchronization request.

## Deleting the account

**Delete account** is shown only to someone whose role has `account.delete`. It is in
Settings → the account, set apart from everyday actions
(`DeleteAccountView`, App Store Review Guideline 5.1.1(v)). It says what goes, then,
behind Face ID:

1. tafs cloud erases what it and kiff/agents keep for the account
   (`POST /v1/account/delete`): every taf as removing it does, Coach included, then
   kiff/agents' account, then its own records.
2. The phone erases the account in planque, then in KIFF (`POST /v1/me/delete`), each
   signed, KIFF last.
3. The app signs out.

A step that fails stops the rest and says which; asking again goes on from there, since
every step is safe to repeat.

**From one signed intent.** The phone signs `tafs.delete-account.v2` once, with its approval
key (the tenant, the device, when it was made, when it expires, at most 10 minutes later, and a
nonce), and sends it to `POST /v1/account/delete` `{intent, signature}`. KIFF checks the
signature itself and accepts the deletion first, with the person's session, deleting nothing yet;
from KIFF's accept on, the account can't be used. planque then accepts on KIFF's word, in its
finish step. tafs cloud then erases its own, then planque's, then KIFF's, and
keeps the intent until all three are done: what fails is tried again every 5 minutes (backing
off to 6 hours) with no phone. There is no undo. [data.md](data.md#deleting-the-account-185) lists what
each step deletes.
