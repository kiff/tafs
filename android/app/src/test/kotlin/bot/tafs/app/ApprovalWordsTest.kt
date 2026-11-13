package bot.tafs.app

import androidx.biometric.BiometricManager
import bot.tafs.app.app.Mode
import bot.tafs.app.model.ApprovalReadiness
import bot.tafs.app.model.ApprovalWords
import bot.tafs.app.views.gateReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Set up approvals on this device: each state the phone reports, and its own words. */
class ApprovalWordsTest {
  @Test fun eachOfThePhonesAnswersHasItsState() {
    assertEquals(ApprovalReadiness.Ready, ApprovalReadiness.of(BiometricManager.BIOMETRIC_SUCCESS))
    assertEquals(ApprovalReadiness.NoneEnrolled, ApprovalReadiness.of(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
    assertEquals(ApprovalReadiness.Unsupported, ApprovalReadiness.of(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
    assertEquals(ApprovalReadiness.Unsupported, ApprovalReadiness.of(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
    assertEquals(ApprovalReadiness.TemporarilyUnavailable, ApprovalReadiness.of(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
    assertEquals(ApprovalReadiness.TemporarilyUnavailable, ApprovalReadiness.of(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
    assertEquals(ApprovalReadiness.TemporarilyUnavailable, ApprovalReadiness.of(BiometricManager.BIOMETRIC_STATUS_UNKNOWN))
    assertEquals("an answer from a newer Android may work in a moment", ApprovalReadiness.TemporarilyUnavailable, ApprovalReadiness.of(9999))
  }

  @Test fun noTwoStatesShareWords() {
    val states = ApprovalReadiness.entries
    val cant = states.filter { it != ApprovalReadiness.Ready }
    assertEquals(cant.size, cant.map(ApprovalWords::cantApprove).toSet().size)
    cant.forEach { assertNotEquals("", ApprovalWords.cantApprove(it)) }
  }

  @Test fun theSampleNeverWaitsOnThePhone() {
    ApprovalReadiness.entries.forEach {
      assertEquals("the sample signs nothing", ApprovalReadiness.Ready, gateReadiness(Mode.sample, it))
      assertEquals("with KIFF, the phone's own answer", it, gateReadiness(Mode.kiff, it))
    }
  }

  @Test fun theWordsSayWhatToDo() {
    assertTrue(ApprovalWords.cantApprove(ApprovalReadiness.NoneEnrolled).contains("fingerprint"))
    assertTrue(ApprovalWords.cantApprove(ApprovalReadiness.TemporarilyUnavailable).contains("Try again"))
    assertTrue(ApprovalWords.cantApprove(ApprovalReadiness.Unsupported).contains("another"))
    assertEquals("", ApprovalWords.cantApprove(ApprovalReadiness.Ready))
  }
}
