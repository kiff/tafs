# Onboarding

The first run after one sign-in, on iPhone, iPad and Mac (Android follows the same steps). It's built behind
`Onboarding.on` (`ios/Tafs/Model/Onboarding.swift`), **on in every build**, after the end-to-end test on real devices with
the owner (K5).

**K5, the end-to-end pass, is a simulator walk and a device pass:**
- **A simulator run:** `.github/workflows/ios-k5.yml`, manual. `K5UITests` walks the age line in India, Brazil and
  Colombia (18), and in the US and France (16), plus one more country from the `region` input. Then the tick, Continue
  and KIFF's sign-in page, with a screenshot kept at each step and at 5, 20 and 40 s after Continue (the `k5` artifact).
  KIFF's sheet is private (ephemeral), so iOS shows no "Wants to Use kiff.dev to Sign In" ask for it; one that comes is
  taken. **The page passes only when it draws the four ways the owner keeps on** within 30 s: Apple, Google, GitHub, and
  email, whose password the provider's form asks for on its next step (the device pass takes it). It fails on the provider's own way to sign in with a code sent by email (that strategy stays off); KIFF's own "Email me a code" link, whose id is `email-code-link`, is not counted. A page
  with its title and nothing to sign in with fails, as KIFF's did on 21 Nov.
- **A device pass** on a TestFlight build, with the owner in person at the sign-in step.

While KIFF has in-app sign-in off (`unsupported_client` on `/v1/auth/app/…`), Continue says so in KIFF's words, or
the app's "Signing in inside the app isn't on yet." (`KIFFError.signInNotOn`), as when KIFF's authorize page sends it
back (`AppSignIn.Failure.refused("unsupported_client")`). The sign-up's email code and GitHub sign-in are left to the device pass.

## The steps

1. **The welcome, with one tick.** Today's welcome (board 1, "tafs", the lead and the three points), with one tick
   above the buttons: "I agree to KIFF's Terms of Service and the tafs Terms", each name a link to its page. Below it,
   privacy as two links, never ticked, and the age line: the person's country's own age where the law asks more
   than 16 (`Onboarding.ageLine`, from the device's region and `MinimumAge.swift`, generated from
   `app/strings/ages.json`). Before the switch is on, today's sign-in screen says a raised age too
   (`Onboarding.raisedAgeLine` under the agreement: India, Indonesia, Brazil, Colombia; nothing new anywhere else). **Continue** takes the place of "Sign in with KIFF" and stays
   off until the box is ticked (`ConsentTick`). The box is its own control, so tapping a link opens its page and
   never ticks it. Trying the sample data needs no account, so it never waits on the tick. The AI consent stays its
   own screen (`AIConsentView`).
2. **One sign-in, in the system's sheet.** KIFF's `/oauth/authorize` with PKCE, in `ASWebAuthenticationSession`
   (Apple or Google; email-code sign-in is off), replacing the code-matching device flow. The contract is kiff/cloud's
   `api/internal/appsignin/README.md`; the tick's terms version goes with the token request, and KIFF records the
   consent in the same step that makes the session. The sheet is a private browser session (`VendorSignIn.kiffSheet()`): no earlier sign-in or cookie comes in, so every authorize goes through KIFF's form. A vendor's sheet still shares Safari's sign-in. iPad and Mac keep "Sign in with your phone": its pairing claim carries the same terms version, so KIFF records the consent for the device that showed the tick however the person signs in, and `terms_outdated` asks for the tick again there too.
3. **Four cards** (first-run v2, owner-approved 27 Nov 2026): Your tafs work for you, Coach sets it up with you, A
   Card is your permission, Credits pay for the work. Each draws the app's own components with the sample's words
   (Home's taf rows; Coach's first exchange; a Card, then a held call; Plan & usage's credits and meter), so they read
   in the person's language and theme. The Card card ends with each device's approval as its own paragraph: Face ID
   or Touch ID on iPhone and iPad, Touch ID or the phone on the Mac (`onboarding.control_lead_mac`). Skip on every
   card, Next on the first three and Get started on the last; a swipe left or right moves between cards (never past
   either end), and with reduced motion they don't slide.
4. **The first Home** (the iPhone's Home, and the iPad's and Mac's wide Overview, which is where a wide layout opens: `FirstHome`, one block for both): with no taf, for anyone who may use Coach: board 2, tafs cloud's own empty line when it sends
   one (else the app's "No tafs yet" and what Coach does), Ask Coach, and How tafs works, which reopens the cards with
   the privacy and terms links (also in Account). Where tafs cloud lists recipes instead, Home is as it ships.
   Above either, **A first taf to try** (`FirstTafCard`): the job tafs cloud names in `home.first_taf`
   (`Recipes.firstToTry`; it picks the first proved recipe written in the person's language), its title and line in
   tafs cloud's words, and **Set it up with Coach**, which opens Coach with its `say` in the box to edit and send. With
   no field, a word missing, or the list not shown, there's no card: the app never picks a job of its own.

## Words

`onboarding.*` in `app/strings/<lang>/onboarding.json`, 19 lines in 8 languages. The two
lines with links (`consent_tick`, `privacy_links`) take their link words as placeholders, the `sign_in.agree` shape,
and each link's words are the title of the page it opens in that language. `Legal.linked` puts each link wherever the
language puts it. KIFF's own pages are `Legal.kiffTerms` and `Legal.kiffPrivacy`; tafs's are `Legal.terms` and
`Legal.privacy`.

## Android

Behind `ONBOARDING_ON` (`android/app/src/main/kotlin/bot/tafs/app/model/Onboarding.kt`), **on in every build**, as on iOS.

**Signing in inside the app not on** (KIFF's `unsupported_client`, from its API or its authorize page): Continue says so, in
KIFF's `words` when it sends them, else "Signing in inside the app isn't on yet." (`sign_in.in_app_not_on`), never "didn't work"
(`ContinueOutcome.NotOn`, as iOS's `KIFFError.signInNotOn`).

**K5** (`BuildConfig.K5`, as iOS's `Onboarding.on`): `ONBOARDING_ON` is on in **every** build, debug and release, since the
owner's yes on 22 Nov, so every build for Play has the onboarding and the sign-in inside tafs. CI checks it on every run ("A
release build has K5"; `K5SwitchTest`). `android.yml` run by hand with `k5` still leaves a debug APK as a run artifact for 7
days, built without Firebase's settings (alerts off) since the repository is public, for a pass on a test device; a `k5` run
never starts `release` or `wear-release`.

- **The welcome** (`OnboardingView`, step 1): the same tick, links and age line (the person's country's age where the
  law asks more than 16: `ageLine` from the device's region and `MinimumAge.kt`, generated from `app/strings/ages.json`),
  and, before the switch is on, today's sign-in sheet says a raised age too (`raisedAgeLine`: India, Brazil, Indonesia, Colombia; nothing
  new anywhere else), so those countries needn't wait for the switch, with `ConsentTick` and **Continue**
  off until the box is ticked. The box is Material's checkbox, so TalkBack reads it as the whole tick line; the links
  in the line are their own targets. `linked` puts each link where the language puts it. `Legal.kiffTerms` and
  `Legal.kiffPrivacy` are KIFF's pages in the app's language (`/de/terms`), English for any other.
- **The sign-in's app side** (`kiff/AppSignIn.kt`, step 2): a 43-character verifier and its S256 challenge, a fresh
  `state`, and the authorize link (`client_id=tafs-app`, `redirect_uri=dev.kiff.tafs:/oauth`, `ui_locales` when
  KIFF speaks the app's language, `login_hint` when given). A link back is taken only when it's
  `dev.kiff.tafs:/oauth` exactly, with this attempt's `state`. Another state, or one given twice, is never used, not
  even as a refusal. `KiffClient.appToken` trades the code at `POST /v1/auth/app/token` with its verifier, the terms
  version and the device exactly as `/v1/auth/device/start` sends it, for today's `DeviceToken`. KIFF's refusals keep
  their code as KIFF's `error` (`invalid_grant`, `terms_outdated`); `appTerms` reads the version in force.
- **The tab** (`AppModel.signInInApp`): KIFF's page opens in a Custom Tab from tafs's own screen, in its task, the
  way a vendor's sign-in does (`BrowserVendorSignIn`, `appSignInTab`). It's a **private browsing session** where the browser offers one
  (`isEphemeralBrowsingSupported`, opened in that browser), as iOS's private sheet: no cookie or earlier sign-in
  comes in, so every authorize goes through KIFF's form. A vendor's tab stays shared. A browser without private tabs
  shares its cookies, and KIFF's server is the guard there (kiff/cloud#23). So leaving the tab for another app
  and coming back from the launcher returns to the tab. A tab closed with no link is left, with Open again and
  Cancel. The manifest takes `dev.kiff.tafs:` links (Android matches a link with no host by its scheme only), and
  `MainActivity` hands them to the waiting tab as they came. Only `AppSignIn.answer` decides whether one is ours. A
  code goes to `appToken`, then to today's `takeSession`: the same approval key, account and celebration as the
  device-code sign-in. A link with another state is `AppSignInRefused` `state_mismatch` and never reaches KIFF;
  KIFF's `access_denied` is its own refusal.
- **Continue** (`OnboardingView`): it calls `signInInApp` with the terms version read when the tick showed
  (`appTermsVersion`), and waits while it runs. Then the sign-in sheet's own celebration (`InAppSignedIn`). How it
  ends when it doesn't sign in (`continueOutcome`):
  - **KIFF's page refused** (`access_denied`) says nothing, and the welcome is as it was;
  - **back in tafs from the tab with no answer** (the tab closed, or tafs brought back in front: `AppModel.appSignInLeft`,
    1.5 s after) ends the wait (`endAppSignIn`) and says "Signing in didn't work.", with Continue free again. Before, Continue
    stayed greyed with nothing said for the tab's 15 minutes; K5 found it when KIFF's page ended on "This sign-in has
    expired" and was closed. iOS's sheet ends as it closes, so it never waited;
  - **terms that changed** since the tick (409 `terms_outdated`) untick the box and say so (`onboarding.terms_changed`).
    KIFF refuses the terms before it spends the code, so the code is kept in memory for 50 of its 60 seconds, and the
    new tick sends it again with the new version without opening KIFF's page twice. Past that, or after any other
    refusal, nothing is kept;
  - **anything else** says signing in didn't work, with the tick kept, so Continue tries again. A link back with
    another state ends the attempt: a tab hands back one link, so there's no code left to wait for.
- **A tablet** also offers **Sign in with your phone** under Continue, waiting on the same tick, as iPad does: the
  sign-in sheet's phone half only (`SignInSheet(phoneOnly = true)`), with no KIFF code. Every pairing claim
  carries the terms version the tick agreed to (`terms_version` on `/v1/pair/claim`, kiff/cloud#24), and KIFF
  records it for this tablet with the session. It's read when the phone way is tapped if the welcome couldn't, and
  with none, nothing opens and the welcome says signing in didn't work, so consent is never skipped. Terms that
  changed (409 `terms_outdated`, before anything is claimed) close the sheet and untick the box, as Continue does. The
  welcome counts KIFF's changed-terms answers (saved with the screen), so a rotation while the sheet is open neither loses
  one nor unticks the box again after it was ticked anew.
  However the sheet closes, the ticked version goes with it. Off, the claim is today's.
- **The four cards** (`OnboardingCards`, step 3; first run v2, as the iPhone and the Mac): Your tafs, Coach sets it up with
  you, A Card is your permission, Credits pay for the work, with Skip, Next and Get started (`OnboardingSteps`). Each picture
  is Android's own view over the sample's words:
  - Your tafs: Home's `TafRow`s;
  - Coach: the person's line (`sample.coach.ask`) in the chat's own bubble, then Coach's answer, connect first, with the plan
    line;
  - the Card: the Card tab's `TafCardFace` ("Support replies · Zendesk", 12 of 40 replies), and under it a call it holds as
    Needs you draws it: nothing is sent, and **Approve once** naming the fingerprint (`Biometry.approvingWith`, never the
    screen lock);
  - Credits: Plan & usage's Solo line and meter, and Coach's own work counted apart.

  Their lines: `onboarding.tafs_lead`; `onboarding.home_lead`; `onboarding.card_lead` then
  `onboarding.control_lead_android` ("You approve with your fingerprint or face"), each its own paragraph (`OnboardingPage.lines`: Japanese
  and Chinese put no space after a full stop, so the app never joins two sentences); `billing.credits_buy`. The pictures are hidden from
  TalkBack. They show full screen once per device after signing in (`CARDS_SEEN_KEY`), and Back is Skip.
- **The first Home** (`HomeView`, step 4): with no taf and no recipes listed, for anyone who may use Coach. It shows
  board 2, tafs cloud's empty line alone (the app's "No tafs yet" and `onboarding.home_lead` only when it sends
  none, `firstHomeLines`), **Ask Coach** and **How tafs works**.
- **A first taf to try** (`FirstTafCard`, first run v2): while tafs cloud serves `home.first_taf` (its first proved job in
  the person's language, only while Home lists the jobs, for someone who manages the tafs), Home shows "A first taf to try",
  its title and line as served, and **Set it up with Coach**, which opens a new Coach conversation with its `say` in the
  message box to edit and send (`AppModel.handFirstTafToCoach`). It's read whole or not at all (`RecipesHome.firstTafToTry`:
  title, line and `say` all there, trimmed), so a partial one, or none, draws no card. As on the iPhone and the Mac.
- **How tafs works** (`HowTafsWorksSheet`), from the first Home and from Account's Legal: the four cards, each
  opening where it's tapped, and the four legal pages titled as their pages (`onboarding.row_*`, never the tick
  line's link words). Then, as on iPhone and iPad, two short sections (`help.json`): **Chrome** only where planque
  offers Chromes (`AppState.chromes` not null), and **Sign in with an emailed code** only where KIFF can send one
  (`codeSignIn.available`); the sheet reads both as it opens, so opened from Home first it still knows them. The screens are named with the app's own titles, so the app says
  nothing about what the person can't use (`OnboardingCardsTest.howTafsWorksSaysChromeAndTheCodeOnlyWhereTheyApply`).
