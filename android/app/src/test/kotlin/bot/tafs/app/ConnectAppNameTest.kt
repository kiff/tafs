package bot.tafs.app

import bot.tafs.app.kiff.ConnectApps
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.displayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Connect apps rows read as the app's name, tafs cloud's, with what the entry is under it (docs/api.md, Connect apps). */
class ConnectAppNameTest {
  private fun page(json: String) = KiffClient.json.decodeFromString(ConnectApps.serializer(), json)

  // Discover read "Qonto invoicing: reads, and invoice drafts from quotes" as an app's name.
  @Test fun aRowReadsAsTheAppsNameWithItsSubtitleUnderIt() {
    val qonto = page("""{"apps":[{"entry":"qonto@2","vendor":"Qonto","title":"Qonto invoicing: reads, and invoice drafts from quotes","name":"Qonto","subtitle":"Invoicing: reads, and invoice drafts from quotes"}]}""").apps.single()
    assertEquals("Qonto", qonto.displayName)
    assertEquals("Invoicing: reads, and invoice drafts from quotes", qonto.subtitle)
    val gmail = page("""{"apps":[{"entry":"connector:composio:gmail","vendor":"gmail","title":"Gmail","name":"Gmail"}]}""").apps.single()
    assertEquals("Gmail", gmail.displayName)
    assertNull(gmail.subtitle)
  }

  @Test fun aTafsCloudBeforeNameLeavesTheTitle() {
    val old = page("""{"apps":[{"entry":"stripe@1","vendor":"Stripe","title":"Stripe"}]}""").apps.single()
    assertEquals("Stripe", old.displayName)
    assertEquals("Stripe", old.copy(name = " ").displayName)
  }
}
