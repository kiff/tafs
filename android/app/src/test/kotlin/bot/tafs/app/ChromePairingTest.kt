package bot.tafs.app

import bot.tafs.app.kiff.ChromePairing
import bot.tafs.app.kiff.KiffClient
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing a Chrome (docs/chrome.md): the same inputs give the same proofs and signed bytes here as in the extension
 * (chrome/test/vectors.test.js) and on the iPhone (ChromePairingTests.swift), from one shared vector.
 */
class ChromePairingTest {
  private val vector: JsonObject = KiffClient.json.parseToJsonElement(File("../../vectors/chrome-pairing.json").readText()).jsonObject
  private fun v(key: String) = vector[key]!!.jsonPrimitive.content
  private fun bytes(key: String) = ChromePairing.base64UrlDecoded(v(key))!!

  @Test fun theSharedVectorGivesTheSameProofsAndSignedBytes() {
    val secret = v("secret")
    val chromeKey = bytes("chrome_key")
    val phoneKey = bytes("phone_key")
    assertTrue(ChromePairing.extensionProofHolds(secret, chromeKey, v("extension_proof")))
    assertEquals(v("chrome_key_sha256"), ChromePairing.chromeKeyHash(chromeKey))
    val c = ChromePairing.confirmation(secret, chromeKey, v("name"), phoneKey)
    assertEquals(v("phone_proof"), c.phoneProof)
    assertEquals(v("phone_key"), c.phoneKey)
    assertEquals(v("signed_bytes"), ChromePairing.base64Url(ChromePairing.signedBytes(v("pairing"), c)))
  }

  @Test fun anotherExtensionsProofOrAnotherSecretIsRefused() {
    val chromeKey = bytes("chrome_key")
    // Someone else's extension used the lookup: its proof is over its own key, or under a secret it guessed.
    assertFalse(ChromePairing.extensionProofHolds(v("secret"), chromeKey.copyOf().also { it[0] = (it[0] + 1).toByte() }, v("extension_proof")))
    assertFalse(ChromePairing.extensionProofHolds("29XMDQ", chromeKey, v("extension_proof")))
    // Not base64url, or padded: no proof at all.
    assertFalse(ChromePairing.extensionProofHolds(v("secret"), chromeKey, v("extension_proof") + "="))
    assertFalse(ChromePairing.extensionProofHolds(v("secret"), chromeKey, ""))
  }

  @Test fun aSecretIsSixCharactersFromTheCodesAlphabet() {
    repeat(200) {
      val s = ChromePairing.newSecret()
      assertEquals(ChromePairing.SECRET_LENGTH, s.length)
      assertTrue(s.all { it in ChromePairing.ALPHABET })
    }
    assertEquals("K7QF-29XMDP", ChromePairing.code("K7QF", "29XMDP"))
  }

  @Test fun theKeystoresDerSignatureIsSentAsRawRAndS() {
    val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val message = "tafs-chrome/pair/v1".toByteArray() + byteArrayOf(0) + "{}".toByteArray()
    repeat(20) {
      val der = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(message); sign() }
      val raw = ChromePairing.base64UrlDecoded(ChromePairing.rawSignature(der)!!)!!
      assertEquals(64, raw.size)
      // What WebCrypto checks: the same signature in IEEE P1363 form.
      assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(keys.public); update(message); verify(raw) })
    }
    assertNull(ChromePairing.rawSignature(ByteArray(0)))
    assertNull(ChromePairing.rawSignature(byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01)))
  }

  @Test fun aChromesNameIsTheOwnersTrimmedOrTheBrowsers() {
    assertEquals("Work Mac", ChromePairing.name("  Work Mac ", "Chrome on macOS"))
    assertEquals("Chrome on macOS", ChromePairing.name("   ", "Chrome on macOS"))
    assertEquals(ChromePairing.NAME_MAX, ChromePairing.name("x".repeat(60), "Chrome").length)
  }
}
