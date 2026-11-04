# The app's screens: the bar, a taf's tabs and Setup

How the app is laid out for the person using it, on iPhone, iPad and Android. Every
line it shows about how a taf runs is tafs cloud's (`GET` and `PUT /v1/tafs/{id}/knob`,
`PUT …/results`, `…/follow-ups/{fid}`, `…/pause` and `…/resume`, in [api.md](api.md)).

## The bar

Five items, always in reach: **Account · Tafs · Needs you · Record · Coach**. On a
phone they run along the foot; on a tablet or in landscape they make a rail down the left,
with Coach at its bottom.

- **Account** holds the account's settings: alerts, time zone, people and groups,
  devices, signing out.
- **Tafs** lists your tafs.
- **Needs you** holds what waits for your answer, with its count.
- **Record** is everything your tafs did, asked or were refused.
- **Coach** is a filled button, not a tab: it opens Coach mode. It shows only to someone
  who may use Coach; for anyone else the bar has four items.

## Record

A row of chips filters it by taf: **All tafs**, then one chip per taf with its colour. It
combines with the filter by kind. Each entry starts with its taf ("Chargebacks · Refund
€120"), and each day's heading shows the money spent that day.

## A taf's four tabs

Each tab has one job.

- **Chat**: talk to the taf, or tell it to work differently. It works inside its Card
  either way.
- **Activity**: what it's waiting for from you (its Needs you items) at the top, then what
  it did, day by day, filtered **All / Done / Held / Refused**. An entry opens the request
  step by step. The call's receipt opens from its Activity entry. Pages and filters
  come from cloud; recent runs open their request steps.
- **Card**: the Card itself, its limits, how they're counted, what it has used,
  **Changes to how it runs**, **Halt** and **Revoke**.
- **Setup**: how it runs, in sections:
  - **The job**, with **Improve in Coach**;
  - **Starts when** and **Results go to** (below), each opened with **Open**;
  - **Connected tools**, a ring of apps around the taf's plain letter tile, with a
    dashed + for another app (see [connect-apps.md](connect-apps.md));
  - **Workflow**, folded by default;
  - **What it remembers**, a summary with **See all**, and **Remove for good**.

## Setup › Starts when

- **Its schedule**: when it runs ("Every 4 hours, 9:00–17:00") and the next run in the
  account's time zone. **−** and **+** move it to the next choice tafs cloud offers. Under
  it, **If it is still working when the next start comes**: **Skip it** or **Run after**.
  A taf that works from its chat has no schedule.
- **App events**: each event a connected app can wake it on, with its filter in words. One
  that needs you shows **Approve**: you approve its exact filters with Face ID, Touch ID or
  your fingerprint, and only then does the app start telling the taf. An approved one shows
  **Listening** and **Stop**.
- **Doors**: each [door](doors.md), open or closed, with **Edit**, then **Add a door…**.
- **Follow-ups it set itself**: each with its time and **Remove**.
- **Pause every start** at the bottom stops every schedule and follow-up, and the taf stays
  paused until **Resume every start**. If one couldn't be switched, it says so, and trying
  again finishes it.

A change to how it starts makes the taf's next version, shown as **not live** until you
make it live; where its Card changes, that asks for Face ID or your fingerprint. A change
never goes live by itself. Only someone who manages the taf can change Setup.

## Setup › Results go to

Where the taf's answers go. Each place is a switch. At least one of tafs' own places stays
on: the last of them can't be turned off, even while a place in a connected app is on.

- **From tafs**:
  - **Reply to the sender**: on the same thread, through the door it came in on;
  - **Alert our phones**: who was answered and through which door, never its words;
  - **Email me**: its answer to your email address.
- **In your connected apps**: each place by its name ("Notion · Support log"), then
  **Add a place from a connected app…**, which goes through Coach. The taf writes there
  itself, through KIFF, inside its Card.
- **Not sent**: a reply given up on after its tries shows **Send again**.

## Account › Alerts

Two examples at the top show what alerts look like on the lock screen: one with sound
("Chargebacks needs you · Dispute #4825 · €640 is over its limit") and one silent
("Refunds answered maria@… · By email"). Then:

- **Alerts on this phone** is a switch; Off unregisters only this phone. **Sound**
  controls a hold or a halt's sound.
- **What alerts you** is four switches while alerts are on, as tafs cloud sends it:
  - **A taf needs you** and **Every taf halted**: with sound when Sound is on;
  - **A taf answered someone**: silent, from the tafs whose Results go to includes
    Alert our phones;
  - **A reply wasn't sent**: silent.

## Dark

Every screen follows the saved Light, Dark or System choice; System is the default. In dark, the ground is a warm near-black, and
green, amber and red are brighter. Secondary text keeps at least 4.5:1 contrast in both.

## Account and taf details

Account groups account details, people and apps, Plan, Alerts, Theme, Time zone,
export and Legal; switching accounts and signing out are last. A bottom fade
shows when more content remains. Light, Dark and System apply immediately and
are saved on this device. Time zone offers a searchable cloud list, plus this
device's zone; changing it uses KIFF's signed setup call.

Alerts can be disabled for this phone without removing the account's alert
secret. The four alert-kind switches are phone-specific and checked by cloud
before delivery. The answered-kind link opens the taf's Results go to.

Activity loads server-filtered pages of 20, keeps pages per taf and view, and
refreshes the first page conditionally with its ETag. A changed snapshot restarts
paging. Its work previews three runs and pages recent runs on its secondary
screen. The upstream runs API currently exposes the latest 100 runs; older
history is not available through this view.

Connected tools shows a ring: a plain letter tile opens the job, app nodes show
cloud-provided names and action counts, and the dashed + opens Connect apps.
An active-step arc uses cloud state. Each app's draggable sheet shows actions,
Card limits, workflow steps and seven-day statistics; its statistics link to
filtered Activity. Managers can add actions through Coach or disconnect an app.
Workflow is folded by default, with the cloud's step count and app order.

What it remembers previews the cloud's note count and newest note, limited to
two lines. See all opens full notes and Forget. Changes to how it runs is on
Card, with the cloud's live-version and earlier-version summary. Cards behind
the front Card are full faces with their own corners and shadow.

Coach opens from the right and Back restores the same taf and tab. Its
new-conversation button uses the square-and-pencil icon; previous conversations
remain in the adjacent menu. Both chats use one rounded composer, text above
the attach, mic and send row, growing to six lines. Avatars remain plain letter
tiles; Android now draws the same animated door rim as iOS, using cloud state.

