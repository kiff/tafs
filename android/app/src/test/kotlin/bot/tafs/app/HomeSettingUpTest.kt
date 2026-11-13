package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home lists tafs only (docs/tafs.md): a tafs cloud that sends `setting_up` draws those among the tafs, and its `others`, which
 * only repeats them for apps from before, isn't drawn. A tafs cloud from before `setting_up` still has its others drawn.
 */
class HomeSettingUpTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private val half = """{"name":"Invoicing day test","kiff_agent":"invoicing-x","color":2,"finish":{"taf":"setup_1","session":"s7","words":"Finish setting up"}}"""
  private val other = """{"name":"Invoicing day test","kiff_agent":"invoicing-x","color":2,"state":"idle","waiting":0,"card":{"ids":["c1"],"active":true}}"""

  private suspend fun model(home: String): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","time_zone":"UTC","capabilities":["members.manage","cards.manage"]}""")
    transport.answer("GET /v1/home", 200, home)
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  @Test fun settingUpIsDrawnAndItsRepeatInOthersIsnt() = runTest {
    val m = model("""{"tafs":[],"setting_up":[$half],"others":[$other],"removing":[]}""")
    assertEquals(listOf("Invoicing day test"), m.state.value.settingUp.map { it.name })
    assertTrue("others only repeats setting_up: not drawn", m.state.value.otherAgents.isEmpty())
  }

  @Test fun aTafsCloudFromBeforeSettingUpStillHasItsOthersDrawn() = runTest {
    val m = model("""{"tafs":[],"others":[$other],"removing":[]}""")
    assertTrue(m.state.value.settingUp.isEmpty())
    assertEquals(listOf("invoicing-x"), m.state.value.otherAgents.map { it.id })
  }
}
