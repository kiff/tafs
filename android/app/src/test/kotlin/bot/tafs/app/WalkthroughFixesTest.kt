package bot.tafs.app

import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.WorkflowStep
import bot.tafs.app.views.workflowLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A workflow step says its title once; and signing in from the sample leaves none of the sample's screens open. */
class WalkthroughFixesTest {
  @Test fun aStepWithNoWordsIsItsNumberAndTitleOnly() {
    assertEquals("2 · List your disputes", workflowLine(WorkflowStep(2, "List your disputes", "", app = "Stripe")))
    assertEquals("1 · Answer a dispute\nWith the order's tracking", workflowLine(WorkflowStep(1, "Answer a dispute", "With the order's tracking")))
  }

  @Test fun signingInFromTheSampleIsAnotherAccountButARefreshIsNot() {
    val sample = AppState(mode = Mode.sample)
    val signedIn = AppState(mode = Mode.kiff, kiffAccount = Account("t_01", "acme"))
    assertNotEquals(accountShown(sample), accountShown(signedIn))
    assertNotEquals(accountShown(signedIn), accountShown(signedIn.copy(kiffAccount = Account("t_02", "other"))))
    assertEquals(accountShown(signedIn), accountShown(signedIn.copy(kiffAccount = Account("t_01", "acme", timeZone = "Europe/Paris"))))
  }
}
