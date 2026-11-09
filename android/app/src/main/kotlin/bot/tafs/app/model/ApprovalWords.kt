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
  val TITLE get() = Words.Approval.title

  /** Why it matters, said once at the top. */
  val WHY get() = Words.Approval.why

  /** Before the first taf: Coach needs approvals to finish setting it up. */
  val FIRST_TAF get() = Words.Approval.firstTaf

  /** Carried over from the design note: a new fingerprint ends the key. */
  val NEW_FINGERPRINT get() = Words.Approval.newFingerprint

  fun state(r: ApprovalReadiness): String = when (r) {
    ApprovalReadiness.Ready -> Words.Approval.State.ready
    ApprovalReadiness.NoneEnrolled -> Words.Approval.State.noneEnrolled
    ApprovalReadiness.TemporarilyUnavailable -> Words.Approval.State.temporarilyUnavailable
    ApprovalReadiness.Unsupported -> Words.Approval.State.unsupported
  }

  /** Where an approval would be, when this phone can't approve: the state, in one line. */
  fun cantApprove(r: ApprovalReadiness): String = when (r) {
    ApprovalReadiness.Ready -> ""
    ApprovalReadiness.NoneEnrolled -> Words.Approval.CantApprove.noneEnrolled
    ApprovalReadiness.TemporarilyUnavailable -> Words.Approval.CantApprove.temporarilyUnavailable
    ApprovalReadiness.Unsupported -> Words.Approval.CantApprove.unsupported
  }

  /** After an answer that wasn't sent (the prompt closed, a finger not recognised, the key ended): the hold stays. */
  val KEPT get() = Words.Approval.kept

  val OPEN_SETTINGS get() = Words.Approval.openSettings
  val CHECK_AGAIN get() = Words.Approval.checkAgain
  val CONTINUE_WITHOUT get() = Words.Approval.continueWithout

  /** What continuing without approvals still allows. */
  val WITHOUT_ALLOWS get() = Words.Approval.withoutAllows
}
