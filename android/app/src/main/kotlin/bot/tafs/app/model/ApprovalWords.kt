package bot.tafs.app.model

import androidx.biometric.BiometricManager

/**
 * Whether this device can approve: its approval key unlocks only with a biometric the phone counts as strong
 * (docs/approvals.md). Each state the phone reports gets its own words; never "not set up" for all of them.
 */
enum class ApprovalReadiness {
  /** A strong biometric is enrolled: approvals work. */
  Ready,

  /** The phone can, but no fingerprint is enrolled: set one up. */
  NoneEnrolled,

  /** The sensor can't be used just now (locked after tries, busy, an update pending): try again. */
  TemporarilyUnavailable,

  /** This phone has no strong biometric at all: approvals need another device. */
  Unsupported;

  companion object {
    /** From `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`'s result. */
    fun of(code: Int): ApprovalReadiness = when (code) {
      BiometricManager.BIOMETRIC_SUCCESS -> Ready
      BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> NoneEnrolled
      BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> Unsupported
      // HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, STATUS_UNKNOWN, and anything newer: it may work in a moment.
      else -> TemporarilyUnavailable
    }
  }
}

/** "Set up approvals on this device": the same title on Android, iPhone and iPad, naming no one biometric. */
object ApprovalWords {
  const val TITLE = "Set up approvals on this device"

  /** Why it matters, said once at the top. */
  const val WHY = "Every approval you give in tafs, a Card, a connection or a payment over a limit, is signed on this phone with a key only your fingerprint unlocks. Your PIN or pattern can't approve."

  /** Before the first taf: Coach needs approvals to finish setting it up. */
  const val FIRST_TAF = "Setting up a taf needs approvals: Coach asks you to approve its tools and the taf's Card."

  /** Carried over from the design note: a new fingerprint ends the key. */
  const val NEW_FINGERPRINT = "If you add another fingerprint later, approving on this phone stops until you approve it again."

  fun state(r: ApprovalReadiness): String = when (r) {
    ApprovalReadiness.Ready -> "Approvals are set up on this phone."
    ApprovalReadiness.NoneEnrolled -> "No fingerprint is set up on this phone. Add one in its settings, then come back."
    ApprovalReadiness.TemporarilyUnavailable -> "The fingerprint sensor can't be used just now. Wait a moment, or unlock the phone once, then check again."
    ApprovalReadiness.Unsupported -> "This phone has no fingerprint sensor tafs can use for approvals. Approve from another phone or a supported device."
  }

  /** Where an approval would be, when this phone can't approve: the state, in one line. */
  fun cantApprove(r: ApprovalReadiness): String = when (r) {
    ApprovalReadiness.Ready -> ""
    ApprovalReadiness.NoneEnrolled -> "Approving needs a fingerprint on this phone."
    ApprovalReadiness.TemporarilyUnavailable -> "The fingerprint sensor can't be used just now. Try again in a moment."
    ApprovalReadiness.Unsupported -> "This phone can't approve. Approve from another supported device."
  }

  /** After an answer that wasn't sent (the prompt closed, a finger not recognised, the key ended): the hold stays. */
  const val KEPT = "It's kept in Needs you."

  const val OPEN_SETTINGS = "Open settings"
  const val CHECK_AGAIN = "Check again"
  const val CONTINUE_WITHOUT = "Continue without approvals"

  /** What continuing without approvals still allows. */
  const val WITHOUT_ALLOWS = "Without approvals you can still sign in, review, refuse and halt."
}
