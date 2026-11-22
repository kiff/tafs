package bot.tafs.app

import bot.tafs.app.kiff.ChromePairing
import bot.tafs.app.kiff.GrantBrowser
import bot.tafs.app.kiff.PlanqueError
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.PairQr
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
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

  private fun repoJson(path: String): JsonObject = KiffClient.json.parseToJsonElement(File("../../$path").readText()).jsonObject

  /**
   * Approving a browser grant: the bytes for its sites and for one sensitive site are the extension's
   * (vectors/chrome-grant.json), so what the phone signs is what `checkSites` and `checkAllowances` verify.
   */
  @Test fun aGrantsSitesAndASensitiveAllowanceSignTheExtensionsBytes() {
    val g = repoJson("vectors/chrome-grant.json")
    fun s(k: String) = g[k]!!.jsonPrimitive.content
    val sites = g["sites"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals(s("sites_signed_bytes"), ChromePairing.base64Url(ChromePairing.sitesBytes(s("chrome_id"), s("grant"), sites)))
    assertEquals(s("sensitive_signed_bytes"), ChromePairing.base64Url(ChromePairing.sensitiveBytes(s("chrome_id"), s("grant"), s("site"))))
    assertFalse(
      "another site list is other bytes",
      ChromePairing.sitesBytes(s("chrome_id"), s("grant"), sites + "evil.example").contentEquals(ChromePairing.sitesBytes(s("chrome_id"), s("grant"), sites)),
    )
  }

  /**
   * `chrome_sites_sig`: approving a grant for the owner's own Chrome signs exactly the vector's bytes with the approval key, and
   * sends it as raw r‖s that WebCrypto (the extension's `checkSites`) verifies.
   */
  @Test fun theSitesSignatureSignsTheVectorsBytesAsRawRAndS() = kotlinx.coroutines.test.runTest {
    val g = repoJson("vectors/chrome-grant.json")
    fun s(k: String) = g[k]!!.jsonPrimitive.content
    val sites = g["sites"]!!.jsonArray.map { it.jsonPrimitive.content }
    val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    var signed: ByteArray? = null
    val sig = ChromePairing.sitesSignature(GrantBrowser(GrantBrowser.EXTENSION, s("chrome_id"), sites), s("grant")) { built ->
      val bytes = built().also { signed = it }
      java.util.Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(bytes); sign() })
    }!!
    assertEquals(s("sites_signed_bytes"), ChromePairing.base64Url(signed!!))
    val raw = ChromePairing.base64UrlDecoded(sig)!!
    assertEquals(64, raw.size)
    assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(keys.public); update(signed); verify(raw) })
  }

  /** Nothing is signed over an open list: a grant for the owner's Chrome that names no Chrome or no sites is refused; others sign nothing. */
  @Test fun theSitesSignatureIsRefusedWithoutAChromeOrSitesAndNoneForOtherGrants() = kotlinx.coroutines.test.runTest {
    var asked = 0
    val sign: suspend (() -> ByteArray) -> String = { asked++; "MEUCIQ" }
    for (open in listOf(
      GrantBrowser(GrantBrowser.EXTENSION, null, listOf("shop.example")),
      GrantBrowser(GrantBrowser.EXTENSION, "", listOf("shop.example")),
      GrantBrowser(GrantBrowser.EXTENSION, "chr_01HZX5", null),
      GrantBrowser(GrantBrowser.EXTENSION, "chr_01HZX5", emptyList()),
    )) {
      try {
        ChromePairing.sitesSignature(open, "g_7Q2M", sign)
        org.junit.Assert.fail("refused: $open")
      } catch (_: PlanqueError.DigestMismatch) {
      }
    }
    assertNull(ChromePairing.sitesSignature(null, "g_7Q2M", sign))
    assertNull(ChromePairing.sitesSignature(GrantBrowser("hosted", "chr_01HZX5", listOf("shop.example")), "g_7Q2M", sign))
    assertEquals("nothing was signed", 0, asked)
  }

  /** The sensitive list is the extension's (vectors/chrome-sensitive.json), and reads each sample host as it does. */
  @Test fun theSensitiveListIsTheExtensions() {
    val v = repoJson("vectors/chrome-sensitive.json")
    assertEquals(v["sites"]!!.jsonArray.map { it.jsonPrimitive.content }, ChromePairing.SENSITIVE_SITES)
    assertEquals(v["labels"]!!.jsonPrimitive.content, ChromePairing.SENSITIVE_LABELS)
    for ((host, want) in v["hosts"]!!.jsonObject) {
      assertEquals(host, want.jsonPrimitive.boolean, ChromePairing.isSensitive(host))
    }
  }

  /** A host is read as the extension's `hostOf` reads it, so a trailing dot or an ideographic one changes nothing. */
  @Test fun aSensitiveHostIsReadAsTheExtensionReadsIt() {
    assertTrue(ChromePairing.isSensitive("PayPal.com."))
    assertTrue(ChromePairing.isSensitive("www。paypal。com"))
    assertTrue(ChromePairing.isSensitive("impots．gouv．fr"))
    assertFalse(ChromePairing.isSensitive("notpaypal.com."))
  }

  @Test fun theProofCoversTheExtensionsKiffOfferAsTheVectorSays() {
    val secret = v("secret")
    val chromeKey = bytes("chrome_key")
    val offer = v("kiff_offer")
    assertTrue(ChromePairing.extensionProofHolds(secret, chromeKey, v("extension_proof_with_kiff_offer"), offer))
    // planque can swap neither key: another KIFF offer, or none, and the proof no longer holds.
    assertFalse(ChromePairing.extensionProofHolds(secret, chromeKey, v("extension_proof_with_kiff_offer")))
    assertFalse(ChromePairing.extensionProofHolds(secret, chromeKey, v("extension_proof_with_kiff_offer"), offer.replace("tafs for Chrome", "tafs for Chrom")))
    assertFalse(ChromePairing.extensionProofHolds(secret, chromeKey, v("extension_proof"), offer))
  }

  @Test fun aRePairingsCarriedConnectionsSignTheExtensionsBytes() {
    val carried = vector["carried"]!!.jsonArray.map { e ->
      val o = e.jsonObject
      ChromePairing.Carried(o["grant"]!!.jsonPrimitive.content, o["sites"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
    val c = ChromePairing.confirmation(v("secret"), bytes("chrome_key"), v("name"), bytes("phone_key")).copy(carried = carried)
    assertEquals(v("signed_bytes_with_carried"), ChromePairing.base64Url(ChromePairing.signedBytes(v("pairing"), c)))
    // A first pairing carries nothing, and its bytes are as they always were.
    assertEquals(v("signed_bytes"), ChromePairing.base64Url(ChromePairing.signedBytes(v("pairing"), c.copy(carried = emptyList()))))
    // Another order is other bytes: the extension checks the list as the phone wrote it.
    assertFalse(ChromePairing.signedBytes(v("pairing"), c.copy(carried = carried.reversed())).contentEquals(ChromePairing.signedBytes(v("pairing"), c)))

  @Test fun theOneTouchProofIsTheExtensionsAsTheVectorSays() {
    val secret = v("secret")
    val chromeKey = bytes("chrome_key")
    val offer = v("kiff_offer")
    val proof = v("one_touch_proof_with_kiff_offer")
    assertTrue(ChromePairing.oneTouchProofHolds(secret, chromeKey, proof, offer))
    // Its own label: the pairing's proof isn't it, and it covers the KIFF offer and the key as the pairing's does.
    assertFalse(ChromePairing.oneTouchProofHolds(secret, chromeKey, v("extension_proof_with_kiff_offer"), offer))
    assertFalse(ChromePairing.oneTouchProofHolds(secret, chromeKey, proof))
    assertFalse(ChromePairing.oneTouchProofHolds(secret, bytes("phone_key"), proof, offer))
    assertFalse(ChromePairing.oneTouchProofHolds("another secret", chromeKey, proof, offer))
  }

  @Test fun theExtensionsKiffDeviceIsReadOnlyAsAChromeExtensionInSoftware() {
    val offer = v("kiff_offer")
    val device = PairQr.readChromeExtension(offer)!!
    assertEquals("chrome", device.platform)
    assertEquals("extension", device.kind)
    assertEquals("software", device.keyStore)
    assertEquals("tafs for Chrome", device.name)
    assertNull("never a computer's QR", PairQr.read(offer))
    // Anything that could approve, or isn't a Chrome extension, isn't paired.
    assertNull(PairQr.readChromeExtension(offer.replace("\"key_store\":\"software\",", "")))
    assertNull(PairQr.readChromeExtension(offer.replace("\"kind\":\"extension\"", "\"kind\":\"enclave\"")))
    assertNull(PairQr.readChromeExtension(offer.replace("\"platform\":\"chrome\"", "\"platform\":\"macos\"")))
    assertNull(PairQr.readChromeExtension(offer.replace("\"v\":\"kiff.pair.v1\"", "\"v\":\"kiff.pair.v2\"")))
    assertNull(PairQr.readChromeExtension(offer.replace("{", "{\"approval_pub\":\"AAAA\",")))
    assertNull(PairQr.readChromeExtension(offer.replace("{", "{\"device_kind\":\"android_phone\",")))
    assertNull(PairQr.readChromeExtension("not json"))
  }

  @Test fun anotherExtensionsProofOrAnotherSecretIsRefused() {
    val chromeKey = bytes("chrome_key")
    // Someone else's extension used the lookup: its proof is over its own key, or under a secret it guessed.
    assertFalse(ChromePairing.extensionProofHolds(v("secret"), chromeKey.copyOf().also { it[0] = (it[0] + 1).toByte() }, v("extension_proof")))
    assertFalse(ChromePairing.extensionProofHolds(v("secret").dropLast(1) + "4", chromeKey, v("extension_proof")))
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
    assertEquals("K7QF-29XM-DPQR-STVW-XYZ2-3", ChromePairing.code("K7QF", "29XMDPQRSTVWXYZ23"))
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
