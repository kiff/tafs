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
  step by step.
- **Card**: only what's on the Card: the Card itself, its limits, how they're counted, what
  it has used, **Halt** and **Revoke**.
- **Setup**: how it runs, in sections:
  - **The job**, with **Improve in Coach**;
  - **Starts when** and **Results go to** (below), each opened with **Open**;
  - **Connected apps**, each with its actions, **Choose actions** when none are chosen yet,
    and **Connect an app…** (see [connect-apps.md](connect-apps.md));
  - **What it remembers**, **Versions**, and **Remove for good**.

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

Where the taf's answers go. Each place is a switch, and at least one stays on: the last one
left can't be turned off.

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

- **Alerts on this phone**, and **Sound**, for a hold or a halt only;
- **What alerts you**, as tafs cloud sends it:
  - **A taf needs you** and **Every taf halted**: with sound, always;
  - **A taf answered someone**: silent, from the tafs whose Results go to includes
    Alert our phones;
  - **A reply wasn't sent**: silent.

## Dark

Every screen follows the device's appearance. In dark, the ground is a warm near-black, and
green, amber and red are brighter. Secondary text keeps at least 4.5:1 contrast in both.
