package bot.tafs.app

import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.CoachWords
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** A card answered again replaces its own failed line in Coach's thread: a failure the retry put right never shows. */
class CoachRetriedAnswerTest {
  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private fun owner(i: Long, text: String) = ChatMessage(t0.plusSeconds(i), ChatMessage.From.owner, text, "s1")
  private fun taf(i: Long, text: String) = ChatMessage(t0.plusSeconds(i), ChatMessage.From.taf, text, "s1")

  @Test fun aRetriedApprovalShowsOnlyItsLastAnswer() {
    val thread = listOf(
      owner(0, "Connect Google Sheets and Gmail"),
      owner(1, "[approval a1] failed: the connection came back signed_in with no sign-in link"),
      taf(2, "Let's try Sheets again."),
      owner(3, "[approval a1] approved"),
      owner(4, "[approval a2] approved"),
    )
    assertEquals(
      listOf("Connect Google Sheets and Gmail", "Let's try Sheets again.", "[approval a1] approved", "[approval a2] approved"),
      CoachWords.latestAnswers(thread).map { it.text },
    )
  }

  @Test fun aFailureNotRetriedStaysAndOnlyFailuresAreReplaced() {
    val thread = listOf(
      owner(0, "[approval a1] approved"),
      owner(1, "[approval a1] declined"),
      owner(2, "[sign_in s1] failed: closed"),
    )
    // Coach can use an id again for a new card: an earlier answer that wasn't a failure stays.
    assertEquals(thread.map { it.text }, CoachWords.latestAnswers(thread).map { it.text })
  }
}
