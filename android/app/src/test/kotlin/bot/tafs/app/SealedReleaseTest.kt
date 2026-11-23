package bot.tafs.app

import bot.tafs.app.kiff.ChromePairing
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SealedRelease
import bot.tafs.app.kiff.SealedRelease.Refusal
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's release of one sealed sign-in value for one fill (docs/chrome.md, "The phone's release card"): the bytes are the
 * extension's (vectors/chrome-release.json, made from `checkRelease`'s own `signingBytes`), the handles are its `parseHandle`'s,
 * and the phone signs only where its own checks hold. A software key stands in for the approval key; nothing is sent.
 */
class SealedReleaseTest {
  private fun repoJson(path: String): JsonObject = KiffClient.json.parseToJsonElement(File("../../$path").readText()).jsonObject
  private val v = repoJson("vectors/chrome-release.json")
  private fun s(k: String) = v[k]!!.jsonPrimitive.content

  private val phone: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  private val other: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  private val key = phone.public.encoded

  /** The approval key's DER signature, base64, as the Keystore signer answers. */
  private fun der(k: KeyPair, bytes: ByteArray): String =
    Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(k.private); update(bytes); sign() })

  private fun raw(k: KeyPair, bytes: ByteArray) = ChromePairing.rawSignature(Base64.getDecoder().decode(der(k, bytes)))!!

  private val now = v["exp"]!!.jsonPrimitive.long - 120_000
  private val grant = "g_7Q2M"
  private val sites = listOf("shop.example")

  private fun fill(
    origin: String = s("origin"),
    sealed: String = s("sealed"),
    chrome: String = s("chrome_id"),
    signedSites: List<String> = sites,
    sitesSig: String? = raw(phone, ChromePairing.sitesBytes(s("chrome_id"), grant, sites)),
    allowances: List<SealedRelease.Allowance> = emptyList(),
    readId: String = s("read_id"),
    expiresAt: Long = now + 180_000,
  ) = SealedRelease.Fill(
    chromeId = chrome, op = s("op"), sealed = sealed, origin = origin, fieldRef = s("field_ref"), readId = readId, grant = grant,
    sitesSigned = sitesSig?.let { SealedRelease.SitesSigned(signedSites, it) }, sensitiveAllowed = allowances, expiresAt = expiresAt,
  )

  private val paired = setOf(s("chrome_id"))

  @Test fun aReleaseSignsTheVectorsBytesAndTakesOnlyTheVectorsHandles() {
    val bytes = SealedRelease.bytes(s("chrome_id"), s("op"), s("sealed"), s("origin"), s("field_ref"), s("read_id"), s("nonce"), v["exp"]!!.jsonPrimitive.long)
    assertArrayEquals(ChromePairing.base64UrlDecoded(s("signed_bytes")), bytes)
    for ((h, want) in v["handles"]!!.jsonObject) {
      val part = SealedRelease.part(h)
      val expected = if (want is JsonNull) null else want.jsonPrimitive.content
      assertEquals(h, expected, part?.name?.lowercase())
    }
  }

  @Test fun theOwnersYesSignsOneReleaseForThatFillGoodForSixtySeconds() = runTest {
    var asked = 0
    val r = SealedRelease.release(fill(), paired, key, now, nonce = s("nonce")) { built -> asked++; der(phone, built()) }!!
    assertEquals(1, asked)
    assertEquals(now + 60_000, r.exp)
    assertEquals(s("chrome_id"), r.chromeId)
    assertEquals(s("op"), r.op)
    assertEquals(s("origin"), r.origin)
    // The extension's check: the eight fields, this phone's key, raw r‖s.
    val signed = SealedRelease.bytes(r.chromeId, r.op, s("sealed"), r.origin, s("field_ref"), s("read_id"), r.nonce, r.exp)
    assertTrue(ChromePairing.verifiesRaw(key, signed, r.sig))
    assertFalse("another phone's key", ChromePairing.verifiesRaw(other.public.encoded, signed, r.sig))
    // Each yes makes its own nonce: 16 random bytes, base64url, never the same twice.
    val a = SealedRelease.newNonce()
    assertEquals(22, a.length)
    assertFalse(a == SealedRelease.newNonce())
  }

  @Test fun thePhoneRefusesToSignWhereItsOwnChecksDontHoldWhateverPlanqueSays() = runTest {
    fun refused(f: SealedRelease.Fill, at: Long = now) = SealedRelease.refusal(f, paired, key, at)
    assertNull(refused(fill()))
    // The handle: anything but exactly sec_<id>:password or :username, a third segment included.
    assertEquals(Refusal.HANDLE, refused(fill(sealed = "sec_Ab3dE-f_9q:password:username")))
    assertEquals(Refusal.HANDLE, refused(fill(sealed = "sec_x:token")))
    // The Chrome must be one this phone is paired with.
    assertEquals(Refusal.CHROME, refused(fill(chrome = "chr_other")))
    // Late: the hold's end has passed.
    assertEquals(Refusal.EXPIRED, refused(fill(), at = now + 180_000))
    // The origin: https, a host, nothing more.
    for (o in listOf("http://shop.example", "https://shop.example/", "https://shop.example/login", "https://u@shop.example", "https://shop.example?x", "shop.example", "")) {
      assertEquals(o, Refusal.ORIGIN, refused(fill(origin = o)))
    }
    assertNull("a port and a subdomain are fine", refused(fill(origin = "https://login.shop.example:8443")))
    // Within the sites this phone signed, and that signature holds: planque can't widen the list or sign it.
    assertEquals(Refusal.NOT_SIGNED, refused(fill(origin = "https://evil.example")))
    assertEquals(Refusal.NOT_SIGNED, refused(fill(origin = "https://shop.example.evil")))
    assertEquals(Refusal.NOT_SIGNED, refused(fill(signedSites = sites + "evil.example", origin = "https://evil.example")))
    assertEquals(Refusal.NOT_SIGNED, refused(fill(sitesSig = raw(other, ChromePairing.sitesBytes(s("chrome_id"), grant, sites)))))
    assertEquals(Refusal.NOT_SIGNED, refused(fill(sitesSig = null)))
    // A sensitive site needs this phone's own allowance for that grant and site.
    val pay = listOf("paypal.com")
    val paySig = raw(phone, ChromePairing.sitesBytes(s("chrome_id"), grant, pay))
    val allowed = SealedRelease.Allowance("paypal.com", raw(phone, ChromePairing.sensitiveBytes(s("chrome_id"), grant, "paypal.com")))
    assertEquals(Refusal.SENSITIVE, refused(fill(origin = "https://www.paypal.com", signedSites = pay, sitesSig = paySig)))
    assertNull(refused(fill(origin = "https://www.paypal.com", signedSites = pay, sitesSig = paySig, allowances = listOf(allowed))))
    val forged = SealedRelease.Allowance("paypal.com", raw(other, ChromePairing.sensitiveBytes(s("chrome_id"), grant, "paypal.com")))
    assertEquals(Refusal.SENSITIVE, refused(fill(origin = "https://www.paypal.com", signedSites = pay, sitesSig = paySig, allowances = listOf(forged))))
    // A hold with nothing to name.
    assertEquals(Refusal.INCOMPLETE, refused(fill(readId = "")))
    // A refused fill makes no release, and nothing is signed.
    var asked = 0
    assertNull(SealedRelease.release(fill(chrome = "chr_other"), paired, key, now) { asked++; "" })
    assertEquals(0, asked)
  }

  @Test fun theCardIsOffUntilPlanqueHoldsASealedFill() {
    assertFalse(SealedRelease.ENABLED)
  }
}
