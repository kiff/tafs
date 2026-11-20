# Onboarding

The first run after one sign-in, on iPhone, iPad and Mac (Android follows the same steps). It's built behind
`Onboarding.on` (`ios/Tafs/Model/Onboarding.swift`), **off**: while it's off, the welcome and the sign-in are as they
ship. It goes on only after an end-to-end test on real devices with the owner present.

## The steps

1. **The welcome, with one tick.** Today's welcome (board 1, "tafs", the lead and the three points), with one tick
   above the buttons: "I agree to KIFF's Terms of Service and the tafs Terms", each name a link to its page. Below it,
   privacy as two links, never ticked, and the age line. **Continue** takes the place of "Sign in with KIFF" and stays
   off until the box is ticked (`ConsentTick`). The box is its own control, so tapping a link opens its page and
   never ticks it. Trying the sample data needs no account, so it never waits on the tick. The AI consent stays its
   own screen (`AIConsentView`).
2. **One sign-in, in the system's sheet.** KIFF's `/oauth/authorize` with PKCE, in `ASWebAuthenticationSession`
   (Apple, Google or an email code), replacing the code-matching device flow. The contract is kiff/cloud's
   `api/internal/appsignin/README.md`; the tick's terms version goes with the token request, and KIFF records the
   consent in the same step that makes the session. The sheet is a private browser session (`VendorSignIn.kiffSheet()`): no earlier sign-in or cookie comes in, so every authorize goes through KIFF's form. A vendor's sheet still shares Safari's sign-in. iPad and Mac keep "Sign in with your phone".
3. **Three cards:** Your tafs, A Card is your permission, You stay in control. Each draws the app's own components
   with the sample's words, so they read in the person's language. The last card names each device's approval:
   Face ID or Touch ID on iPhone and iPad, Touch ID or the phone on the Mac (`onboarding.control_lead_mac`).
4. **The first Home:** with no taf, for anyone who may use Coach: board 2, tafs cloud's own empty line when it sends
   one (else the app's "No tafs yet" and what Coach does), Ask Coach, and How tafs works, which reopens the cards with
   the privacy and terms links (also in Account). Where tafs cloud lists recipes instead, Home is as it ships.

## Words

`onboarding.*` in `app/strings/<lang>/onboarding.json`, 19 lines in 8 languages, written by legal-i18n. The two
lines with links (`consent_tick`, `privacy_links`) take their link words as placeholders, the `sign_in.agree` shape,
and each link's words are the title of the page it opens in that language. `Legal.linked` puts each link wherever the
language puts it. KIFF's own pages are `Legal.kiffTerms` and `Legal.kiffPrivacy`; tafs's are `Legal.terms` and
`Legal.privacy`.

## Android

Behind `ONBOARDING_ON` (`android/app/src/main/kotlin/bot/tafs/app/model/Onboarding.kt`), **off**, as on iOS.

- **The welcome** (`OnboardingView`, step 1): the same tick, links and age line, with `ConsentTick` and **Continue**
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
  way a vendor's sign-in does (`BrowserVendorSignIn`, `appSignInTab`). So leaving for the mail app for an email code
  and coming back from the launcher returns to the tab. A tab closed with no link is left, with Open again and
  Cancel. The manifest takes `dev.kiff.tafs:` links (Android matches a link with no host by its scheme only), and
  `MainActivity` hands them to the waiting tab as they came. Only `AppSignIn.answer` decides whether one is ours. A
  code goes to `appToken`, then to today's `takeSession`: the same approval key, account and celebration as the
  device-code sign-in. A link with another state is `AppSignInRefused` `state_mismatch` and never reaches KIFF;
  KIFF's `access_denied` is its own refusal. The welcome's words for each refusal, and Continue calling it, come with
  the three cards and the first Home.
- **The three cards** (`OnboardingCards`, step 3): Your tafs, A Card is your permission, You stay in control, with
  Skip, Next and Get started (`OnboardingSteps`). Each picture is Android's own view over the sample's words: Home's
  `TafRow`s, the Card tab's `TafCardFace` ("Support replies · Zendesk", 12 of 40 replies) and a Needs you card. The
  pictures are hidden from TalkBack. The last card says the screen lock (`onboarding.control_lead_android`). They
  show full screen once per device after signing in (`CARDS_SEEN_KEY`), and Back is Skip.
- **The first Home** (`HomeView`, step 4): with no taf and no recipes listed, for anyone who may use Coach. It shows
  board 2, tafs cloud's empty line alone (the app's "No tafs yet" and `onboarding.home_lead` only when it sends
  none, `firstHomeLines`), **Ask Coach** and **How tafs works**.
- **How tafs works** (`HowTafsWorksSheet`), from the first Home and from Account's Legal: the three cards, each
  opening where it's tapped, and the four legal pages titled as their pages (`onboarding.row_*`, never the tick
  line's link words).
