package bot.tafs.app

import bot.tafs.app.kiff.CardAnswer
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.model.SetupShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An approval card's outcome comes from tafs cloud's stored answer, which is in no language, never from its words in the owner's.
 * A refused approval once read "Approved · signed in to Stripe" for an owner in French: the words "Refusé" weren't the English
 * "Declined", and anything else read as approved. Nothing reads as approved by default now.
 */
class ApprovalAnswerTest {
  private val approval = SetupComponent(type = "approval", id = "ap-stripe")
  private val signIn = SetupComponent(type = "sign_in", id = "si-stripe")

  /** tafs cloud's words for an approval's answer in each shipped language (runtime/src/cloud/words/<lang>.ts). */
  private val declined = listOf("Declined", "Abgelehnt", "Rechazado", "Refusé", "Rifiutato", "Geweigerd", "Odrzucone", "Recusado")
  private val failed = listOf(
    "Approve · it didn't go through: no answer", "Genehmigen · hat nicht geklappt: keine Antwort", "Aprobar · no se completó: sin respuesta",
    "Approuver · cela n'a pas abouti : pas de réponse", "Approva · non è andata a buon fine: nessuna risposta",
    "Goedkeuren · het is niet gelukt: geen antwoord", "Zatwierdź · nie powiodło się: brak odpowiedzi", "Aprovar · não foi concluído: sem resposta",
  )
  private val approvedElsewhere = listOf("Genehmigt", "Aprobado", "Approuvé", "Approvato", "Goedgekeurd", "Zatwierdzone", "Aprovado")

  private fun outcome(c: SetupComponent, words: String, stored: String? = null) =
    SetupShape.recorded(CardAnswer(words = words, answer = stored), c)!!.text

  private fun readsApproved(text: String) = text.startsWith("approved") || text.startsWith("already approved")

  @Test fun aStoredDeclineOrFailureNeverReadsApprovedWhateverTheWords() {
    for (w in declined + failed + approvedElsewhere + "Approved") {
      assertEquals(w, "declined", outcome(approval, w, "declined"))
      assertEquals(w, "failed: no answer", outcome(approval, w, "failed: no answer"))
    }
  }

  @Test fun theStoredAnswerSaysApprovedOrConnectedAndOnlyThenItsDone() {
    assertEquals("approved", outcome(approval, "Refusé", "approved"))
    // A connect step that worked, its Card next: tafs cloud settles it as connected, so the card is done, never failed.
    assertEquals("approved", outcome(approval, "Stripe est connecté. Ensuite, la Card.", "connected"))
    assertFalse(readsApproved(outcome(approval, "Approved", "something new")))
  }

  @Test fun withoutTheStoredAnswerOnlyTafsCloudsExactEnglishReadsApproved() {
    assertEquals("approved", outcome(approval, "Approved"))
    assertEquals("already approved", outcome(approval, "Already approved"))
    assertEquals("declined", outcome(approval, "Declined"))
    assertEquals("failed: no answer", outcome(approval, "Approve · it didn't go through: no answer"))
    // Every other language's declined, failed, even approved: not approved, in tafs cloud's own words.
    for (w in declined.drop(1) + failed.drop(1) + approvedElsewhere) {
      val text = outcome(approval, w)
      assertFalse("$w read as $text", readsApproved(text))
      assertTrue("$w is said as it came", text.endsWith(w))
    }
  }

  @Test fun aSignInIsSignedInOnlyWhenItSaysSo() {
    assertEquals("signed in", outcome(signIn, "Connecté", "signed in"))
    assertEquals("not now", outcome(signIn, "Signed in", "not now"))
    assertEquals("failed: expired", outcome(signIn, "x", "failed: expired"))
    assertEquals("signed in", outcome(signIn, "Signed in"))
    assertEquals("not now", outcome(signIn, "Connecté"))
  }
}
