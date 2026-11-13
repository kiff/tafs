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

  @Test fun aPersonalAccountShowsItsEmailOnceAndNoRawId() {
    // Named after its owner's email (the owner's iPad, 13 Nov: shown twice, then "user-5d7d…").
    val personal = Account("t_01", "user-5d7d", displayName = "anna@example.test", email = "Anna@Example.test")
    org.junit.Assert.assertTrue(personal.personal)
    org.junit.Assert.assertNull("the account's name already says it", personal.signedInAs)
    // A named account: who's signed in is news, and its id is its own.
    val named = Account("t_02", "acme", displayName = "Acme", email = "anna@example.test")
    org.junit.Assert.assertFalse(named.personal)
    assertEquals("anna@example.test", named.signedInAs)
    org.junit.Assert.assertFalse("no email: not personal", Account("t_03", "acme").personal)
    val settings = java.io.File("src/main/kotlin/bot/tafs/app/views/SettingsView.kt").readText()
    org.junit.Assert.assertTrue(settings.contains("if (!account.personal) Value(Words.Settings.accountId, account.slug)"))
    val people = java.io.File("src/main/kotlin/bot/tafs/app/views/PeopleViews.kt").readText()
    org.junit.Assert.assertTrue(people.contains("if (account.personal) \"\" else"))
  }
}
