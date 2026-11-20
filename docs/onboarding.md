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
   consent in the same step that makes the session. iPad and Mac keep "Sign in with your phone".
3. **Three cards:** Your tafs, A Card is your permission, You stay in control. Each draws the app's own components
   with the sample's words, so they read in the person's language. The last card names each device's approval:
   Face ID or Touch ID on iPhone and iPad, Touch ID or the phone on the Mac (`onboarding.control_lead_mac`).
4. **The first Home:** board 2, Ask Coach, and How tafs works, which reopens the cards with the privacy and terms
   links (Account, or the Mac's sidebar).

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
  their code (`invalid_grant`, `terms_outdated`). The Custom Tab, its intent filter and the token step's place in
  the sign-in come next, with the three cards and the first Home.
