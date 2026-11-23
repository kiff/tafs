# The Chrome panel: watching a taf work (design)

**Status: design, not built.** tafs in Chrome should feel like an assistant working in the browser with a person beside
it. This page is the design the code follows. The extension's contract stays [chrome.md](chrome.md); KIFF's passkey part
is designed on its side, and linked here when it lands.

## Why Chrome

To give a taf a hand on the web without our own hosted browser. The taf works in the owner's own Chrome, in its own tabs,
only on the sites its connection lists.

## What the panel is

The panel is mainly for **watching a taf work**, with the person beside it. Coach is no longer its main chat.

- **The taf in this Chrome.**
  - The panel shows the tafs whose connections run in this Chrome. planque gives the list in the relay's `welcome`, and
    again when one is approved, ends or moves (a planque change).
  - With one taf, it opens on it. With several, a picker sits at the top, with the one at work first.
  - **The list is for display only, never authority.** What a step may do still comes only from the signed step and the
    connection's phone-signed sites. The extension never takes a site from the list as allowed.
- **Its work, live.** While it runs, the panel shows each step as it happens: opened, read, typed, clicked, held. The
  run's tab group ("tafs · {taf}") sits beside the person's tabs, and **Show** brings the run's tab to the front. Pause and
  Stop stay.
- **Its chat.** The person talks to the taf below its work. It's the same taf chat as the phone's
  (`GET /v1/tafs/{id}/conversation`, `POST …/messages`), read on events, then on `pollDelay`: 4 s while it works or for a
  minute after a send, 30 s otherwise, and nothing while the panel is hidden.
- **Coach is a button:** "Set up a new taf for this site" opens Coach, with the site in front named.

## A step handed to the person

A taf sometimes needs the person to do one thing in the page: fill in a card number, solve a check, click Send. It hands
it over and carries on after.

- **planque sends it as a step,** `op: "hand"`, signed like every step, with the taf's words ("Fill in the card number,
  then tap Done"). It may also name the field or button, by the `ref` of the page's last read. Its sites are the
  connection's, so a hand-over can't take the person to a page the taf couldn't open.
- **The panel shows "Your turn"** with the taf's words. **Show me** brings the run's tab to the front and outlines the
  field or button, and the toolbar badge says the taf waits.
- **The extension never acts for the person here.** It types nothing, clicks nothing and fills nothing. A sealed value is
  never offered.
- **Done reads the page back to the taf,** masked as every read is. **Not now** answers the step as declined. A
  hand-over that waits past its step's time (the step's `exp`) ends with the run's usual words, and the taf decides what
  next.
- A hand-over never approves anything held. A held step is still a card (below).

## Approving in the panel

Cards that wait for the owner (a held step, Coach's), drawn in KIFF's own words, are **approved in the panel with the
computer's Touch ID, Face ID or Windows Hello. The phone isn't needed.**

- **The route:** an extension's own origin can't use a kiff.dev passkey. So **Approve** opens **KIFF's own approval
  page** (kiff.dev, as a sign-in's approval page) in a small window, for that one held call.
  - The page asks for the computer's kiff.dev passkey with user verification required, over that call's exact message
    ([computer-passkeys.md](computer-passkeys.md), "What a passkey approval carries").
  - KIFF checks the assertion and approves. The window closes, and the panel reads the card again.
- **Refuse stays unsigned,** in the panel, as on the phone: refusing only takes power away.
- **Which passkey:** the computer's own kiff.dev passkey, never a synced one (backup eligible or backed up is refused, as
  today). A computer without one gets the phone route.

### Never a password alone

The rule: a biometric (Touch ID, Face ID, a Windows Hello face or fingerprint) or a device-bound unlock, never a
computer's password alone.

**What the browser can and can't tell.**
- A WebAuthn assertion says only that the person was verified (`UV`), not how. The same flag comes from Touch ID, Windows
  Hello's PIN and a Mac password fallback.
- So the rule is kept by **which computers are offered the panel route**, not by reading the assertion:
  - **A Mac with Touch ID:** offered. Chrome's macOS passkeys use Touch ID. **Open question:** Chrome can fall back to the
    Mac's password after failed Touch ID tries, and the page can't see that.
  - **A Mac without Touch ID:** phone route only. There, Chrome's passkey would ask for the password.
  - **Windows with Windows Hello:** offered. Hello's PIN is bound to that computer's TPM, not a password. **Open
    question:** whether the owner counts Hello's PIN as allowed.
  - **Linux, and anything else:** phone route only.
- **How the panel tells which computer it's on:** the KIFF device record of the computer's passkey says what made it.
  The tafs desktop app knows whether Touch ID or Windows Hello biometrics are enrolled, and can mark it at pairing.
  Without that mark, it's the phone route.

## Open questions

1. **A product decision:** a Touch ID Mac whose Chrome falls back to the password after failed tries, and Windows Hello's PIN: allowed,
   or phone route?
2. **KIFF:** the approval page's route for one held call; how a computer's passkey is registered for a Chrome
   with no tafs desktop app; and where the "biometric" mark lives on the device record.
3. **The hand-over step:** planque's `op: "hand"` and its result, and the tool the taf asks it with.

## At 10,000 users

- **The panel:** reads only on events, with a slow fallback. Its list of tafs is pushed by planque, never polled.
- **A hand-over:** one step, like any other.
- **An approval:** one page load and one assertion, as a sign-in's approval.

Nothing new runs on idle.
