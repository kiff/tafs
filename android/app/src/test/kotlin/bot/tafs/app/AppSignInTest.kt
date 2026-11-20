package bot.tafs.app

import bot.tafs.app.kiff.AppSignIn
import bot.tafs.app.kiff.AppSignIn.Answer
import bot.tafs.app.kiff.ONBOARDING_ON
import java.net.URI
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row 39's sign-in, the app's part: PKCE S256, the state, and only our own redirect (docs/android-app.md "Onboarding"). */
class AppSignInTest {
  private val attempt = AppSignIn.attempt()

  @Test fun theSwitchIsOffUntilTheEndToEndTest() = assertFalse(ONBOARDING_ON)

  /** S256, against a value made outside the app: Python's `base64.urlsafe_b64encode(hashlib.sha256(v).digest()).rstrip(b"=")`. */
  @Test fun theChallengeIsS256() =
    assertEquals("6gePYgMUvzld4Pjo__yHuD6pT5P_2W6MeDfAhWWhqrQ", AppSignIn.challengeOf("dBjftJeZ4CVP-mJ92K9oEhSeVAAHvcY0e7NhsGWG7Zc"))

  @Test fun eachAttemptHasItsOwnVerifierAndState() {
    val other = AppSignIn.attempt(SecureRandom())
    assertEquals(43, attempt.verifier.length)
    assertTrue(attempt.verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
    assertTrue(attempt.state.matches(Regex("[A-Za-z0-9_-]{43}")))
    assertNotEquals(attempt.verifier, other.verifier)
    assertNotEquals(attempt.state, other.state)
    assertNotEquals(attempt.verifier, attempt.state)
  }

  @Test fun theAuthorizeLinkAsksWithTheChallengeNeverTheVerifier() {
    val url = AppSignIn.authorizeUrl("https://api.kiff.dev/", attempt, "fr-FR", " ana@example.com ")
    val uri = URI(url)
    assertEquals("https://api.kiff.dev/oauth/authorize", url.substringBefore('?'))
    val q = uri.rawQuery.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    assertEquals("code", q["response_type"])
    assertEquals("tafs-app", q["client_id"])
    assertEquals("dev.kiff.tafs:/oauth", q["redirect_uri"])
    assertEquals(attempt.challenge, q["code_challenge"])
    assertEquals("S256", q["code_challenge_method"])
    assertEquals(attempt.state, q["state"])
    assertEquals("fr", q["ui_locales"])
    assertEquals("ana@example.com", q["login_hint"])
    assertFalse(url.contains(attempt.verifier))
  }

  @Test fun aLanguageKiffDoesntSpeakIsLeftOut() {
    val url = AppSignIn.authorizeUrl("https://api.kiff.dev", attempt, "ja")
    assertFalse(url.contains("ui_locales"))
    assertFalse(url.contains("login_hint"))
  }

  @Test fun ourRedirectWithOurStateGivesTheCode() =
    assertEquals(Answer.Code("c_1"), AppSignIn.answer("dev.kiff.tafs:/oauth?code=c_1&state=${attempt.state}", attempt))

  @Test fun kiffsRefusalIsItsOwn() {
    assertEquals(Answer.Refused("access_denied"), AppSignIn.answer("dev.kiff.tafs:/oauth?error=access_denied&state=${attempt.state}", attempt))
    assertEquals(Answer.Refused("unsupported_client"), AppSignIn.answer("dev.kiff.tafs:/oauth?error=unsupported_client&state=${attempt.state}", attempt))
    assertEquals(Answer.Refused("invalid_request"), AppSignIn.answer("dev.kiff.tafs:/oauth?state=${attempt.state}", attempt))
  }

  @Test fun anotherStateIsNeverUsed() {
    val other = AppSignIn.attempt()
    assertEquals(Answer.WrongState, AppSignIn.answer("dev.kiff.tafs:/oauth?code=c_1&state=${other.state}", attempt))
    assertEquals(Answer.WrongState, AppSignIn.answer("dev.kiff.tafs:/oauth?code=c_1", attempt))
    // Even a refusal says nothing about this attempt without its state.
    assertEquals(Answer.WrongState, AppSignIn.answer("dev.kiff.tafs:/oauth?error=access_denied", attempt))
    // A state given twice is neither.
    assertEquals(Answer.WrongState, AppSignIn.answer("dev.kiff.tafs:/oauth?code=c_1&state=${attempt.state}&state=x", attempt))
  }

  @Test fun aCodeGivenTwiceIsNone() =
    assertEquals(Answer.Refused("invalid_request"), AppSignIn.answer("dev.kiff.tafs:/oauth?code=a&code=b&state=${attempt.state}", attempt))

  @Test fun onlyOurRedirectExactly() {
    val s = attempt.state
    for (link in listOf(
      "tafs://oauth?code=c_1&state=$s",
      "dev.kiff.tafs://oauth?code=c_1&state=$s",
      "dev.kiff.tafs:/oauth/x?code=c_1&state=$s",
      "dev.kiff.tafs:/planque?code=c_1&state=$s",
      "https://api.kiff.dev/oauth?code=c_1&state=$s",
      "dev.kiff.tafs:/oauth#code=c_1&state=$s",
      "not a link",
    )) assertEquals(link, Answer.NotOurs, AppSignIn.answer(link, attempt))
  }
}
