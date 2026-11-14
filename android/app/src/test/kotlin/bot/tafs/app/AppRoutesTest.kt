package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.AppRoute
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every screen opened by its route (app/routes.json): each one for someone who may use it, and none for a path that names no
 * screen, a taf the app doesn't hold, or someone without what the route needs. A fake KIFF and tafs cloud stand in.
 */
class AppRoutesTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private val home =
    """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}"""
  private val manager = """"holds.answer","cards.manage","billing.manage","members.manage","account.delete""""

  private fun reads(capabilities: String = manager, manage: Boolean = true) {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","tenant_display_name":"Acme Pay","capabilities":[$capabilities]}""")
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":$manage}}}""")
  }

  private suspend fun model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore() }
  }

  /** The route's path with [taf] for its taf. */
  private fun path(route: AppRoute, taf: String = "refunds-1") = route.pattern.replace("{taf}", taf)

  /** What the screens are asked for: the route, and its taf by its id here. */
  private fun asked(route: AppRoute) = route to (if ("taf" in route.parameters) mapOf("taf" to "refunds-1") else emptyMap())

  /** The request the screens see, without the number that tells it from the last. */
  private fun AppModel.request() = state.value.requestedRoute?.let { it.route to it.values }

  @Test fun everyRouteOpensForSomeoneWhoManagesTheTafAndTheAccount() = runTest {
    reads()
    val m = model()
    for (route in AppRoute.entries) {
      m.routeTaken()
      m.open(path(route))
      assertEquals(route.pattern, asked(route), m.request())
    }
  }

  @Test fun aTafNamedByItsIdInTafsCloudOpensAsTheTafHere() = runTest {
    reads()
    val m = model()
    assertTrue(m.open("taf/taf_1/setup/versions"))
    assertEquals(asked(AppRoute.TafSetupVersions), m.request())
    m.routeTaken()
    assertTrue(m.open(AppRoute.TafCard, mapOf("taf" to "refunds-1")))
    assertEquals(asked(AppRoute.TafCard), m.request())
  }

  @Test fun theSameScreenAskedForAgainIsANewRequest() = runTest {
    reads()
    val m = model()
    assertTrue(m.open("taf/refunds-1/share"))
    val first = m.state.value.requestedRoute
    // Nothing took the first (its sheet wasn't drawn): asking again still reaches the screens, as a value the state hasn't held.
    assertTrue(m.open("taf/refunds-1/share"))
    val second = m.state.value.requestedRoute
    assertEquals(first?.let { it.route to it.values }, second?.let { it.route to it.values })
    assertNotEquals(first, second)
  }

  @Test fun aRequestForATafIsDroppedWhenItsPageLeavesAndNoOtherIs() = runTest {
    reads()
    val m = model()
    assertTrue(m.open("taf/refunds-1/setup/starts-when"))
    m.routeLeft("another-taf")
    assertEquals(asked(AppRoute.TafSetupStartsWhen), m.request())
    m.routeLeft("refunds-1")
    assertNull(m.state.value.requestedRoute)
    assertTrue(m.open("account/theme"))
    m.routeLeft("refunds-1")
    assertEquals(asked(AppRoute.AccountTheme), m.request())
  }

  @Test fun aPathThatNamesNoScreenOpensNothing() = runTest {
    reads()
    val m = model()
    val before = m.state.value
    for (path in listOf("", "nowhere", "taf", "taf/", "taf/refunds-1/nothing", "account/people/more", "/tafs", "Tafs")) {
      assertFalse(path, m.open(path))
      assertEquals(path, before, m.state.value)
    }
  }

  @Test fun aTafTheAppDoesntHoldOpensNothing() = runTest {
    reads()
    val m = model()
    val before = m.state.value
    assertFalse(m.open("taf/nobody"))
    assertFalse(m.open("taf/nobody/setup/versions"))
    assertFalse(m.open(AppRoute.TafCard))
    assertEquals(before, m.state.value)
    assertNull(m.state.value.requestedRoute)
  }

  @Test fun noRouteOpensForSomeoneWithoutWhatItNeeds() = runTest {
    reads(capabilities = """"holds.answer"""", manage = false)
    val m = model()
    val before = m.state.value
    for (route in AppRoute.entries.filter { it.needs != null }) {
      assertFalse(route.pattern, m.open(path(route)))
      assertEquals(route.pattern, before, m.state.value)
    }
    // What anyone may open still opens for them.
    assertTrue(m.open(path(AppRoute.TafSetup)))
  }

  @Test fun theSampleOpensItsOwnScreensAndNoneItLacks() = runTest {
    val m = testModel()
    val taf = m.state.value.tafs.first().id
    assertTrue(m.open("tafs"))
    assertTrue(m.open(path(AppRoute.TafSetup, taf)))
    assertEquals(AppRoute.TafSetup to mapOf("taf" to taf), m.request())
    m.routeTaken()
    val before = m.state.value
    // Nobody manages a sample taf, and the account's own screens are KIFF's.
    for (path in listOf(path(AppRoute.TafHalt, taf), path(AppRoute.TafShare, taf), "account/people", "account/devices", "account/delete", "calendar", "halt-all")) {
      assertFalse(path, m.open(path))
      assertEquals(path, before, m.state.value)
    }
  }

  @Test fun everyPatternMatchesItself() {
    for (route in AppRoute.entries) {
      val values = route.parameters.associateWith { "v_$it" }
      val path = values.entries.fold(route.pattern) { p, (k, v) -> p.replace("{$k}", v) }
      assertEquals(route.pattern, route to values, AppRoute.match(path))
    }
  }
}
