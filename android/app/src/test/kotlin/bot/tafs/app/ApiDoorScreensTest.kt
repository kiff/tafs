package bot.tafs.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.TafDoors
import bot.tafs.app.kiff.Door
import bot.tafs.app.kiff.DoorCounts
import bot.tafs.app.kiff.DoorKey
import bot.tafs.app.kiff.DoorLastRequest
import bot.tafs.app.kiff.IssuedKey
import bot.tafs.app.model.Res
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.model.Words
import bot.tafs.app.views.API_KEY_CLIP
import bot.tafs.app.views.API_KEY_SHOWN
import bot.tafs.app.views.API_NEW_KEY
import bot.tafs.app.views.apiNewKeyName
import bot.tafs.app.views.apiExample
import bot.tafs.app.views.API_INSTRUCTIONS
import bot.tafs.app.views.ApiDoorActions
import bot.tafs.app.views.ApiDoorPanels
import bot.tafs.app.views.apiKeyLine
import bot.tafs.app.views.copyKeyForAMinute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** A door "From another application" (`api`) as its panel draws it: its day, its last request, its keys, and the key shown once. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w411dp-h2000dp")
class ApiDoorScreensTest {
  @get:Rule val compose = createComposeRule()

  private val taf = Taf(id = "refunds-1", name = "Returns", job = "", tools = emptyList(), colorIndex = 0, card = Card(emptyList(), "You"))
  private val key = DoorKey(id = "key_1", name = "ERP production", last4 = "iXrW", madeAt = "2026-11-19T08:00:00Z", lastUsedAt = "2026-11-19T09:42:15Z")
  private val door = Door(
    id = "door_ord7", app = "api", kind = "api", label = "Order system", open = true, proof = "proven", keys = listOf(key),
    today = 37, limit = 100, counts = DoorCounts(today = 37, waiting = 2), lastRequest = DoorLastRequest("done", "2026-11-19T09:42:15Z", "Done"),
  )

  private fun panel(door: Door, doors: TafDoors = TafDoors(doors = listOf(door)), actions: ApiDoorActions = ApiDoorActions()) =
    compose.setContent { TafsTheme { androidx.compose.foundation.layout.Column { ApiDoorPanels(taf, door, doors, actions) {} } } }

  @Test fun itsDayLastRequestAndKeysReadByNameAndLastFourNeverTheKey() {
    panel(door)
    compose.onNodeWithText(Words.Door.Api.ofLimit(count = 37, limit = Res.number(100))).assertIsDisplayed()
    compose.onNodeWithText("Done", substring = true).assertIsDisplayed()
    compose.onNodeWithText("ERP production").assertIsDisplayed()
    assertTrue(apiKeyLine(key).contains("iXrW"))
    compose.onNodeWithText(Words.Door.Api.boundary("Returns")).assertIsDisplayed()
    // A last request with no word from tafs cloud draws no line: the app never words a state itself.
    compose.onAllNodesWithText("done", substring = false).assertCountEquals(0)
  }

  // Material's AlertDialog with a text field never settles under Robolectric (even with none of tafs's code in it), so New key's
  // dialog is checked through its rule here, and on the emulator by hand.
  @Test fun aNewKeyIsAskedForByItsNameTrimmedElseTheDoorsAndNeverWhileBusy() {
    assertEquals("ERP staging", apiNewKeyName(" ERP staging ", door, busy = false))
    assertEquals("a blank name is the door's own, as on iPhone", "Order system", apiNewKeyName("   ", door, busy = false))
    assertEquals(null, apiNewKeyName("ERP staging", door, busy = true))
  }

  @Test fun revokeIsAskedOnceMoreThenSigned() {
    val revoked = mutableListOf<String>()
    panel(door, actions = ApiDoorActions(revoke = { _, _, key, reason -> revoked += "${key.id} | $reason" }))
    compose.onNodeWithText(Words.Taf.revoke).performClick()
    assertTrue("nothing goes before the person says so twice", revoked.isEmpty())
    compose.onNodeWithText(Words.Door.Api.revokeBody).assertIsDisplayed()
    compose.onAllNodesWithText(Words.Taf.revoke)[1].performClick()
    compose.waitForIdle()
    assertEquals(listOf("key_1 | ${Words.Door.Api.revokeReason("ERP production")}"), revoked)
  }

  @Test fun newKeyIsOfferedOnlyUnderTwoKeys() {
    panel(door)
    compose.onNodeWithTag(API_NEW_KEY).assertExists()
  }

  @Test fun newKeyIsNotOfferedAtTwoKeys() {
    panel(door.copy(keys = listOf(key, key.copy(id = "key_2", name = "ERP staging"))))
    compose.onAllNodesWithTag(API_NEW_KEY).assertCountEquals(0)
  }

  @Test fun theKeyIsShownOnceAndDoneForgetsIt() {
    var shown = 0
    val issued = IssuedKey("key_2", "tafs_api_secret_value", "ab12", "2026-11-19T21:00:00Z")
    panel(door, TafDoors(doors = listOf(door), issued = issued), ApiDoorActions(keyShown = { shown++ }, endpoint = { "https://cloud.example/v1/doors/api/${it.id}/requests" }))
    compose.onNodeWithTag(API_KEY_SHOWN).assertIsDisplayed()
    compose.onNodeWithText("tafs_api_secret_value").assertIsDisplayed()
    // Where to send work, and an example that reads the key from the caller's own secret, never this one.
    compose.onNodeWithText("https://cloud.example/v1/doors/api/door_ord7/requests").assertExists()
    compose.onNodeWithText("\$TAFS_API_KEY", substring = true).assertExists()
    assertFalse(apiExample("https://cloud.example/x").contains("tafs_api_"))
    compose.onNodeWithText(Words.Common.done).performClick()
    assertEquals(1, shown)
  }

  @Test fun apiInstructionsSayWhereAndHowButNeverTheKey() {
    panel(door, actions = ApiDoorActions(endpoint = { "https://cloud.example/v1/doors/api/${it.id}/requests" }))
    compose.onNodeWithTag(API_INSTRUCTIONS).performClick()
    compose.onNodeWithText("https://cloud.example/v1/doors/api/door_ord7/requests").assertExists()
    compose.onNodeWithText(Words.Door.Api.keyNotHere).assertExists()
    compose.onAllNodesWithText("tafs_api_", substring = true).assertCountEquals(0)
  }

  @Test fun anAddressThatIsAPathIsOnTafsCloudsOwnHost() {
    assertEquals("https://cloud.example/v1/doors/api/d/requests", bot.tafs.app.app.endpointOn("https://cloud.example/", "/v1/doors/api/d/requests"))
    assertEquals("https://elsewhere.example/r", bot.tafs.app.app.endpointOn("https://cloud.example", "https://elsewhere.example/r"))
  }

  @Test fun aCopiedKeyIsSensitiveAndGoneAfterAMinuteButNeverThePersonsNextCopy() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    copyKeyForAMinute(context, "tafs_api_secret_value", after = 10)
    // Not before its time.
    assertEquals("tafs_api_secret_value", clipboard.primaryClip?.getItemAt(0)?.text)
    assertEquals(API_KEY_CLIP, clipboard.primaryClipDescription?.label)
    assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
    ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
    assertFalse("the key is off the clipboard after its minute", clipboard.hasPrimaryClip() && clipboard.primaryClip?.getItemAt(0)?.text == "tafs_api_secret_value")
    // A copy the person made after it is never cleared.
    copyKeyForAMinute(context, "tafs_api_secret_value", after = 10)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("note", "mine"))
    ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
    assertEquals("mine", clipboard.primaryClip?.getItemAt(0)?.text)
  }
}
