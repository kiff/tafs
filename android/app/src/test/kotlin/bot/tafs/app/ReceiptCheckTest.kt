package bot.tafs.app

import bot.tafs.app.kiff.Hex
import bot.tafs.app.kiff.RawJson
import bot.tafs.app.kiff.ReceiptCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's check of a KIFF receipt (kiff/tafs#167), against the same receipt iOS's tests use: signed the way KIFF signs
 * and written by the receipts endpoint's encoder, with a test key. Its chain has what a re-encoding would change: escaped
 * <, > and &, a map's sorted keys, and Go's 1e+21.
 */
class ReceiptCheckTest {
  private val signed = """{"schema_version":"0.1","trace_id":"01KTRACE","tenant_id":"t_1","entity_id":"pi_3Q_test","entity_type":"Payment","action_name":"stripe_create_refund","outcome":"allowed","issued_at":"2026-10-21T00:41:03.12Z","causal_chain":[{"sequence":1,"kind":"decision","actor_id":"agt_refunds","occurred_at":"2026-10-21T00:41:03.12Z","message":"held: over \u003c€100\u003e \u0026 more","data_hash":"aa"},{"sequence":2,"kind":"settled","occurred_at":"2026-10-21T00:41:04.12Z","data_hash":"bb","data":{"amount":15000,"answer":"approved_once","big":1e+21,"tool":{"status":"done"}}}],"attestation":{"algorithm":"secp256k1+keccak256","signature":"79917d81e09b24ef196e5b71c72ea1c5cc8951e628b63922072ef59832d0a43e4a0b198bead4ddad1312fe7f1e9b6d41b5f617fe08c5aed59abcd9e5b225a2ce01","wallet_address":"0x2c7536e3605d9c16a7a3d7b1898e529396a65c23","anchor_tx_hash":"","anchored_at":"2026-10-21T00:41:03.12Z"},"risk_score":null,"compliance_map":null,"insurer_envelope":null}"""
  private val testSigner = "0x2c7536e3605d9c16a7a3d7b1898e529396a65c23"

  private fun check(text: String, signer: String = testSigner) = ReceiptCheck.check(text.toByteArray(), signer)

  @Test fun keccakMatchesTheStandardVectors() {
    assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", Hex.encode(ReceiptCheck.keccak256(ByteArray(0))))
    assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", Hex.encode(ReceiptCheck.keccak256("abc".toByteArray())))
    // Either side of a block (136 bytes), against Go's sha3.NewLegacyKeccak256.
    assertEquals("34367dc248bbd832f4e3e69dfaac2f92638bd0bbd18f2912ba4ef454919cf446", Hex.encode(ReceiptCheck.keccak256(ByteArray(135) { 0x61 })))
    assertEquals("a6c4d403279fe3e0af03729caada8374b5ca54d8065329a3ebcaeb4b60aa386e", Hex.encode(ReceiptCheck.keccak256(ByteArray(136) { 0x61 })))
    assertEquals("5b7e0e47a96f32a88b4f14ca177982790807c40e1a105742ba0fc1babe1ef826", Hex.encode(ReceiptCheck.keccak256(ByteArray(300) { 0x61 })))
  }

  @Test fun aReceiptKiffSignedChecksOut() {
    assertEquals(ReceiptCheck.Verified(testSigner), check(signed))
  }

  @Test fun theChainIsHashedAsWrittenNotReencoded() {
    val chain = String(RawJson.valueOf("causal_chain", signed.toByteArray())!!)
    assertTrue(chain.startsWith("""[{"sequence":1,"kind":"decision""""))
    // As Go wrote it, escaped: a decoder and re-encoder would write "<€100> & more".
    assertTrue(chain.contains("""\u003c€100\u003e \u0026 more"""))
    assertTrue(chain.endsWith(""""big":1e+21,"tool":{"status":"done"}}}]"""))
    assertNull(RawJson.valueOf("missing", signed.toByteArray()))
  }

  @Test fun aChangedByteInTheChainFails() {
    val changed = signed.replace(""""data_hash":"bb"""", """"data_hash":"bc"""")
    assertNotEquals(signed, changed)
    assertEquals(ReceiptCheck.Failed("The signature doesn't match these steps."), check(changed))
    val amount = signed.replace(""""amount":15000""", """"amount":95000""")
    assertEquals(ReceiptCheck.Failed("The signature doesn't match these steps."), check(amount))
  }

  @Test fun aReceiptSignedByAnotherKeyIsNotKiffs() {
    // The test key's receipt is sound, but the app pins KIFF's signer.
    assertEquals(ReceiptCheck.Failed("It isn't signed by KIFF."), ReceiptCheck.check(signed.toByteArray()))
  }

  @Test fun notSignedYetIsNotAFailure() {
    val pending = signed.replace(Regex(""""attestation":\{[^}]*\}"""), """"attestation":null""")
    assertEquals(ReceiptCheck.NotSignedYet, ReceiptCheck.check(pending.toByteArray()))
  }

  @Test fun aMalformedSignatureFailsWithoutStoppingTheApp() {
    assertNull(ReceiptCheck.recoverAddress(ByteArray(32) { 1 }, ByteArray(65)))
    assertNull(ReceiptCheck.recoverAddress(ByteArray(32) { 1 }, ByteArray(65) { 0xFF.toByte() }))
    val short = signed.replace(Regex(""""signature":"[0-9a-f]+""""), """"signature":"00"""")
    assertEquals(ReceiptCheck.Failed("The signature is malformed."), check(short))
  }

  // MARK: The sheet's steps and the line against them

  private fun entry(
    onValue: String? = null,
    amountValue: String? = null,
    amountUnit: String? = null,
    answer: String? = null,
  ) = bot.tafs.app.model.RecordEntry(
    tafId = "refunds-1", kind = bot.tafs.app.model.RecordEntry.Kind.approved, title = "Refund a payment", detail = "",
    at = java.time.Instant.parse("2026-10-21T00:41:03Z"), onValue = onValue, amountValue = amountValue, amountUnit = amountUnit,
    answer = answer?.let { bot.tafs.app.model.RecordAnswer(decision = it) },
  )

  @Test fun theSheetShowsTheStepsKiffSignedInWords() {
    val steps = bot.tafs.app.kiff.ReceiptSteps.of(signed.toByteArray())
    assertEquals(listOf("held: over <€100> & more", "settled"), steps.map { it.words })
    assertEquals(java.time.Instant.parse("2026-10-21T00:41:04.12Z"), steps[1].at)
    assertEquals("bb", steps[1].hash)
  }

  private val chain = """{"causal_chain":[{"sequence":1,"kind":"decision","data_hash":"0123456789abcdef","data":{"on_hash":"sha256:${
    java.security.MessageDigest.getInstance("SHA-256").digest("pi_3Q".toByteArray()).joinToString("") { "%02x".format(it) }
  }","amount":{"value":"150.00","unit":"EUR"}}},{"sequence":2,"kind":"hold_settled","data":{"answer":"approve_once"}}]}"""

  @Test fun aLineThatMatchesWhatKiffSignedHasNoMismatch() {
    val steps = bot.tafs.app.kiff.ReceiptSteps.of(chain.toByteArray())
    assertEquals("0123…cdef", steps[0].hash)
    assertNull(bot.tafs.app.kiff.ReceiptSteps.mismatch(entry(onValue = "pi_3Q", amountValue = "150.00", amountUnit = "EUR", answer = "approved"), steps))
  }

  @Test fun aLineThatNamesAnotherPaymentAmountOrAnswerDoesntCheckOut() {
    val steps = bot.tafs.app.kiff.ReceiptSteps.of(chain.toByteArray())
    assertEquals("The payment on this line doesn't match KIFF's receipt.", bot.tafs.app.kiff.ReceiptSteps.mismatch(entry(onValue = "pi_OTHER"), steps))
    assertEquals("The amount on this line doesn't match KIFF's receipt.", bot.tafs.app.kiff.ReceiptSteps.mismatch(entry(amountValue = "950.00", amountUnit = "EUR"), steps))
    assertEquals("The amount on this line doesn't match KIFF's receipt.", bot.tafs.app.kiff.ReceiptSteps.mismatch(entry(amountValue = "150.00", amountUnit = "USD"), steps))
    assertEquals("The answer on this line doesn't match KIFF's receipt.", bot.tafs.app.kiff.ReceiptSteps.mismatch(entry(answer = "refused"), steps))
  }

  // MARK: What a line acted on

  @Test fun aLineIsNamedOnlyWithAValueWhoseHashIsKiffs() {
    val hash = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest("pi_3Q".toByteArray()).joinToString("") { "%02x".format(it) }
    val line = entry().copy(entityId = "inv_1", onHash = hash)
    val named = bot.tafs.app.kiff.KiffMapping.naming(listOf(line), mapOf("inv_1" to "pi_3Q")).single()
    assertEquals("pi_3Q", named.onValue)
    // tafs cloud naming another payment for that call: the line stays unnamed.
    assertNull(bot.tafs.app.kiff.KiffMapping.naming(listOf(line), mapOf("inv_1" to "pi_OTHER")).single().onValue)
  }

  // MARK: The sheet's words

  @Test fun theSheetSaysWhoDecidedInWords() {
    val words = bot.tafs.app.model.ReceiptWords
    val done = entry().copy(kind = bot.tafs.app.model.RecordEntry.Kind.done, amountText = "€150.00")
    assertEquals("Done. €150.00 moved.", words.outcome(done))
    assertEquals("allowed by the Card", words.decision(done))
    assertEquals("KIFF, against the Card", words.decidedBy(done, signedIn = true))
    val refused = entry(answer = "refused").copy(kind = bot.tafs.app.model.RecordEntry.Kind.refused)
    assertEquals("held → refused by ${refused.answerer}", words.decision(refused))
  }
}
