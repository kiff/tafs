package bot.tafs.app.model

/**
 * The first run after one sign-in (docs/onboarding.md): the welcome with one tick for the terms, KIFF's sign-in in a Custom
 * Tab (`kiff/AppSignIn`), three cards on how tafs works, and the first Home. Off until the end-to-end test on real devices with
 * the owner: while it's off, the welcome and the sign-in are as they ship. On only in a K5 debug build (`-Pk5` or `TAFS_K5=1`,
 * for a test device or the emulator); a release build, every one for Play, has it off whatever is passed.
 */
val ONBOARDING_ON: Boolean = bot.tafs.app.BuildConfig.K5

/**
 * The welcome's one tick: the person agrees to KIFF's Terms and the tafs Terms before Continue. Privacy is linked beside it,
 * never ticked, and the AI consent is its own screen. Trying the sample needs no account, so it never waits on the tick.
 */
data class ConsentTick(val agreed: Boolean = false) {
  fun toggled(): ConsentTick = copy(agreed = !agreed)

  /** Continue opens the sign-in only once the terms are agreed to. */
  val canContinue: Boolean get() = agreed
}

/** The cards were seen on this device (Get started or Skip): they show once, after the first sign-in here. */
const val CARDS_SEEN_KEY = "tafs.onboarding-cards-seen"

/** Whether the cards follow the celebration after signing in: with the new onboarding on, once per device. */
fun showsOnboardingCards(seen: Boolean, on: Boolean = ONBOARDING_ON): Boolean = on && !seen

/**
 * Whether Home with no taf is the first Home (Ask Coach and How tafs works): with the new onboarding on, for someone who may use
 * Coach, whatever tafs cloud says there.
 */
fun showsFirstHome(mayUseCoach: Boolean, on: Boolean = ONBOARDING_ON): Boolean = on && mayUseCoach

/** The first Home's words: tafs cloud's own empty line when it sends one, alone; else the app's "No tafs yet" and what Coach does. */
fun firstHomeLines(cloud: String?): Pair<String, String?> =
  cloud?.takeIf { it.isNotBlank() }?.let { it to null } ?: (Words.Home.noTafs to Words.Onboarding.homeLead)

/**
 * The four cards, in order (docs/onboarding.md, step 3; first run v2, as the iPhone and the Mac): tafs, Coach, the Card with
 * how this phone approves, and what credits pay for.
 */
enum class OnboardingPage {
  Tafs, Coach, Card, Credits;

  val title: String get() = when (this) {
    Tafs -> Words.Onboarding.tafsTitle
    Coach -> Words.Onboarding.coachTitle
    Card -> Words.Onboarding.cardTitle
    Credits -> Words.Onboarding.creditsTitle
  }

  /** The Card's line ends with Android's own way to approve: the screen lock. */
  val lead: String get() = when (this) {
    Tafs -> Words.Onboarding.tafsLead
    Coach -> Words.Onboarding.homeLead
    Card -> Words.Onboarding.cardLead + " " + Words.Onboarding.controlLeadAndroid
    Credits -> Words.Billing.creditsBuy
  }

  val next: OnboardingPage? get() = entries.getOrNull(ordinal + 1)
}

/** Where the person is in the cards: Next goes on, the last card's Get started ends them, and Skip ends them from any card. */
data class OnboardingSteps(val page: OnboardingPage = OnboardingPage.Tafs, val finished: Boolean = false) {
  val isLast: Boolean get() = page.next == null

  fun advanced(): OnboardingSteps = page.next?.let { copy(page = it) } ?: copy(finished = true)

  fun skipped(): OnboardingSteps = copy(finished = true)
}

/**
 * The welcome's age line for the person's country (docs/onboarding.md): its own age where the law asks for more than
 * [MinimumAge.STANDARD] (the age table: India, Indonesia, Brazil…), else the line every country reads. The country is the
 * device's own region (the system's, not the app's language, which may be set apart): the person's statement, as on iOS.
 */
fun ageLine(region: String? = deviceRegion()): String {
  val age = MinimumAge.of(region)
  return if (age > MinimumAge.STANDARD) Words.Onboarding.ageN(age = age.toString()) else Words.Onboarding.age
}

/**
 * Today's sign-in (the new welcome's switch off): the country's own age only where it's above [MinimumAge.STANDARD] (India,
 * Indonesia, Brazil…), so a country that asks for more is told before signing in; anywhere else, nothing new is shown (as iOS).
 */
fun raisedAgeLine(region: String? = deviceRegion()): String? {
  val age = MinimumAge.of(region)
  return if (age > MinimumAge.STANDARD) Words.Onboarding.ageN(age = age.toString()) else null
}

/** The device's region as the system has it ("IN"), or null when none is set. */
fun deviceRegion(): String? =
  android.content.res.Resources.getSystem().configuration.locales.takeIf { !it.isEmpty }?.get(0)?.country?.takeIf { it.isNotEmpty() }
