# Accounts, people and plans

tafs keeps no accounts of its own. A person signs in with KIFF, and KIFF says who they
are, on which account, with which rights. People, groups, usage and billing are KIFF's;
the app shows what KIFF says, and each change is the person's own act, behind Face ID.

Code: in the app, `SignInView` (`SettingsView`, `AccountView`), `PeopleViews`,
`ShareViews` (`ShareTafSheet`, `OverviewView`), `UsageView`, `TasksViews`,
`DeleteAccountView`, `Services/Purchases.swift`, and the Signing in, People, Usage,
Tasks and plans and Deleting the account sections of `AppModel`; `KIFFClient`. In tafs
cloud, `person`, `needTaf`, `managesTaf`, `deleteAccount` and `aiUsage` in `handler.ts`,
and `runtime/src/cloud/aiuse.ts`.

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

Each way out asks first: Sign out, Sign in again and Switch account open a confirmation,
so a stray tap never ends the session (Android `ConfirmSignOut`, iOS `SignOutWay`). Signing out (`POST /v1/auth/device/logout`) or switching account clears everything of the
earlier session on the phone, including files opened from a chat. The session ends at KIFF
first: when KIFF doesn't confirm, the phone stays signed in and says so ("Not signed out at
KIFF yet…"), so a device shown as signed out never holds a session KIFF still accepts.
Its alerts are unregistered just before the logout (the call needs the session) and
registered again at once when KIFF doesn't end it, so a device still signed in keeps them. A
session KIFF already ended (a 401, or the account deleted) is cleared on the phone at once. Work still running for
the old session is dropped when it finishes (`accountGeneration`).

**The session ends at KIFF first.** Sign out, Switch account and Sign in again ask KIFF to
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
account uses **Switch account…** in Settings, which goes through KIFF's sign-in, where
each account is listed.

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

## Usage, tasks and plans

**Plan & usage** in Settings (`UsageView`) is the account's period, from KIFF
(`GET /v1/me/usage`):

- the **tasks** the period has (what the plan includes, and packs) and what's left;
- who bills the plan: "Billed by Apple", "Billed by KIFF", or "Managed by your
  company's agreement";
- adding tasks, or asking an admin for them;
- the tasks the tafs counted (`GET /v1/me/usage/tasks`), each opening its Record entry;
- what wasn't counted, and why: refused by KIFF, held then refused, halted, failed at the
  tool, not valid, retries, reads, trials of a new version, Coach and Setup.

A trial that has ended (its `trial.ends_at` past, a lapsed Solo included) says
"Your trial has ended.", never that its tasks are used: KIFF gives it no tasks on purpose. Where
Solo is offered, "Start Solo to keep your tafs running." follows it. A trial that hasn't started
or has ended runs no period, so Usage shows no period end and no meter (`UsageWords.notice`,
`trialEnded`, `noPeriod`; iOS `UsageView`). Packs are on sale only while a plan runs
(`can_add_packs`, falling back to `can_add_tasks`); packs held meanwhile say "Counts once Solo
starts."

The plan shows outside it too. **Settings** opens with a Plan card, one line from
the same read (`UsageWords.planLine`): "Trial · 5 days and 18 tasks left", "Trial · not started",
"Trial ended · Start Solo" (amber), "Solo · 12 of 20 tasks left · Google Play" (red when none are
left), "Company · 472 tasks this period". It opens the screen, called **Plan & usage**. **Home**
shows one line about the plan only when it needs the owner (`UsageWords.homeLine`): a trial ended,
not started or ending within 2 days, tasks used up, or a tenth or less of the period's left.
Otherwise Home stays about the tafs. The plan is read with each refresh (`refreshPlan`), so
both are current. iOS has the same words and thresholds (`UsageView.planLine`, `homeLine`,
`planTone`, `PlanRow`, `PlanLine`; `refreshPlan`).

Each taf's own part is a row on its Card tab (`TafUsageView`).

**Buying in the app** (`Purchases`), through the App Store only:

- **Solo**, a monthly subscription, offered only to someone who may add tasks on an
  account with no plan billed (the trial, or one that ended). Its screen shows the
  App Store's price and every term: the period, that it renews until cancelled, how to
  cancel, and the Terms and Privacy Policy.
- **Packs** of tasks (Pico, Vonk, Rayo, Forte, Maxi), each lasting 12 months.
- Each purchase goes to KIFF for this account (`POST /v1/me/billing/apple/transactions`,
  Apple's signed transaction) before the App Store is told it's done. One KIFF didn't
  record before the app closed is sent at the next launch. **Restore** brings back the
  Apple account's purchases.
- **Manage** opens Apple's own subscriptions page. The app never links to buying outside
  the App Store. An account moved from Solo to Team is billed by KIFF; the app says when
  the Solo bought in the App Store still renews, and offers Cancel Solo through Apple.

**Asking for tasks.** A member who can't add tasks asks an admin for a pack, with a
reason (`AskAdminView`, `POST /v1/me/pack-requests`). The admins are alerted; one buys
it, which answers the request, or declines it with a note, behind Face ID. The member can
take their request back.

On Android the same is on Usage (`TaskRequests`, `UsageWords.waiting`, `lastAnswer`,
`openRequests`). Decline is signed, behind one confirmation
(`tafs.decline-request.v1|{tenant}|{request}`). Approve buys the pack through Google
Play for that request, so it shows only where the app offers buying; until then, an
admin on Android declines, or approves on an iPhone.

### AI use

What chats with the tafs and Coach cost the model is bounded per period at half the
plan's monthly price (`runtime/src/cloud/aiuse.ts`). kiff/agents counts the cost and
refuses a new chat past the bound; KIFF keeps the plan, its seats, the period, and how
many times the owner said **go on**, which raises the bound (signed on the phone). AI use
is never billed: the bound only asks first. `GET /v1/usage/ai` gives the share used and
where the tafs ask next.

## The account's time zone

Every time the app shows is in the account's time zone (`AccountTime`), and a Card's day
ends at the account's midnight. When the device's zone differs, the app offers it once:
"Use Europe/Paris for times in tafs?". Yes sets it in KIFF with a signed call
(`PUT /v1/me/time-zone`, one Face ID). Not now is remembered for that zone and account.

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
