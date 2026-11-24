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
  (`GET /v1/tafs/{id}/conversation`, `POST …/messages`), read on each event: the panel shown, a send, a step seen on the
  relay. Then it's read every 4 s while it works or for a minute after a send. Idle, it backs off from 60 s to 5 minutes, each
  wait jittered, and nothing is read while the panel is hidden.
- **Coach is a button:** "Set up a new taf for this site" opens Coach on **a new conversation**, every time (`startFresh`), in
  the panel's own view: a new taf's setup never goes under the conversation open before. The earlier ones stay under
  Previous conversations.

## A step handed to the person

A taf sometimes needs the person to do one thing in the page: fill in a card number, solve a check, click Send. It hands
it over and carries on after.

- **planque sends it as a step,** `op: "hand"`, signed like every step, with the taf's words ("Fill in the card number,
  then tap Done"). It may also name the field or button, by a `ref` that must be in the run's last read. Anything else
  refuses the step. Its sites are the connection's, so a hand-over on a page off them is refused, as any step is.
- **The taf's words are the taf's,** never KIFF's or the extension's: plain text, at most 280 characters, shown under "The
  taf says", never as a line the extension or KIFF would write.
- **The panel shows "Your turn"** with those words. **Show me** brings the run's tab to the front and only outlines the field
  or button. When it can't (the page changed, the field isn't on screen, or the taf named none), the card says so. The toolbar badge says the taf waits.
- **The extension never acts for the person here.** It types nothing, clicks nothing and fills nothing, and it
  keeps no sign-in: at a sign-in page, signing in is the person's own hand-over.
- **A hand-over isn't an ordinary step.** planque waits for a step's signed result inside one call, far shorter than a
  person needs to find a card and type it. So the hand-over is answered at once as **handed**, and the run waits. The
  person's **Done** or **Not now** comes back later as its own signed result. planque keeps that result until the taf asks
  for it, as it keeps a late result today. The taf decides what to do if none comes.
- **Done reads the page back to the taf, without what the person typed.** The read after a hand-over leaves out the
  **values** of every field the person filled: any input, select or editable, not only those a pattern would mask. The taf
  learns only that each is filled. A card number or a one-time code never reaches the taf, KIFF or planque, even masked.
  **Not now** answers the hand-over as declined.
- A hand-over never approves anything held. A held step is still a card (below).

## Approving in the panel

Cards that wait for the owner (a held step, Coach's), drawn in KIFF's own words, are **approved in the panel with the
computer's Touch ID, Face ID or Windows Hello. The phone isn't needed.**

**This route is off by default.** It turns on only once the two gates below are answered: the test on real hardware, and the
product decision. Until then the panel works fully without it. Watching, the hand-over and the chat are all there, and its
cards say to approve on the phone.

- **The route:** an extension's own origin can't use a kiff.dev passkey. So **Approve** opens **KIFF's own approval
  page** (kiff.dev, as a sign-in's approval page) in a small popup window, for that one held call.
  - **The window shows only what KIFF built.** Its URL carries only the held call's id. The page draws KIFF's own words for
    it, and the extension passes no text. It's a popup window with its address bar, so the person sees `kiff.dev`.
  - The page asks for the computer's kiff.dev passkey with user verification required, over that call's exact message
    ([computer-passkeys.md](computer-passkeys.md), "What a passkey approval carries").
  - KIFF checks the assertion and approves. The window closes, and the panel reads the card again.
- **Refuse stays unsigned,** in the panel, as on the phone: refusing only takes power away.
- **Which passkey:** the computer's own kiff.dev passkey, never a synced one (backup eligible or backed up is refused, as
  today). A computer without one gets the phone route.
- **Gate 1, a test on real hardware, before any code turns the route on.** Chrome's passkeys on a Mac are mostly saved to
  Google Password Manager or iCloud Keychain, which sync, so their backup-eligible flag is set and today's rule would refuse
  them. Windows Hello's and a Secure Enclave key usually aren't synced. The test:
  - on a Mac with Touch ID and on Windows with Hello, register a kiff.dev passkey in Chrome;
  - read its backup-eligible and backup-state flags.

  If the Mac's are synced, the Mac route needs a decision. Either KIFF accepts a synced passkey for this route alone, or the
  Mac approves with the tafs Mac app's own approval key, or a Mac uses the phone.

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

1. **Gate 2, a product decision:** a Touch ID Mac whose Chrome falls back to the password after failed tries, and Windows Hello's PIN: allowed,
   or phone route?
2. **KIFF:** the approval page's route for one held call; how a computer's passkey is registered for a Chrome
   with no tafs desktop app; and where the "biometric" mark lives on the device record.
3. **planque:** the hand-over's own result, kept until read. Its step and result shapes, and how long it's kept, are planque's
   to design, along with the tool the taf asks it with.
4. **Gate 1's test:** a Mac with Touch ID and a Windows PC with Hello, each registering a kiff.dev passkey in Chrome.

## At 10,000 users

- **The panel:** reads on events. Idle, it falls back to one read every 60 s, backing off to every 5 minutes, jittered:
  under 1 read a minute per open idle panel, and none while hidden. Its list of tafs is pushed by planque, never polled.
- **A hand-over:** one step, like any other.
- **An approval:** one page load and one assertion, as a sign-in's approval.

Nothing new runs on idle.
