package bot.tafs.app.model

/**
 * The first run after one sign-in (docs/onboarding.md): the welcome with one tick for the terms, KIFF's sign-in in a Custom
 * Tab (`kiff/AppSignIn`), three cards on how tafs works, and the first Home. Off until the end-to-end test on real devices with
 * the owner: while it's off, the welcome and the sign-in are as they ship.
 */
const val ONBOARDING_ON = false

/**
 * The welcome's one tick: the person agrees to KIFF's Terms and the tafs Terms before Continue. Privacy is linked beside it,
 * never ticked, and the AI consent is its own screen. Trying the sample needs no account, so it never waits on the tick.
 */
data class ConsentTick(val agreed: Boolean = false) {
  fun toggled(): ConsentTick = copy(agreed = !agreed)

  /** Continue opens the sign-in only once the terms are agreed to. */
  val canContinue: Boolean get() = agreed
}
