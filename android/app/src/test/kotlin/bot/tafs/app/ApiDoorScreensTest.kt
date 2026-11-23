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
import bot.tafs.app.views.draftField
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
    // Only ever on tafs cloud's own host, over HTTPS, whatever the address says (as iOS, Windows and Linux).
    for ((address, shown) in listOf(
      "https://cloud.example/v1/x" to "https://cloud.example/v1/x",
      "https://CLOUD.example/v1/x" to "https://CLOUD.example/v1/x",
      "v1/x" to "https://cloud.example/v1/x",
      "https://elsewhere.example/r" to "https://cloud.example/r",
      "http://cloud.example/v1/x" to "https://cloud.example/v1/x",
      "https://cloud.example:8443/v1/x" to "https://cloud.example/v1/x",
      "https://me@cloud.example/v1/x" to "https://cloud.example/v1/x",
      "https://cloud.example.evil.example/v1/x" to "https://cloud.example/v1/x",
      "//evil.example/v1/x" to "https://cloud.example/v1/x",
      "foo:x@evil.example/v1/x" to "https://cloud.example",
      "mailto:a@evil.example" to "https://cloud.example",
      "@evil.example/x" to "https://cloud.example",
      "\\\\evil.example\\x" to "https://cloud.example",
      "https://evil.example@cloud.example.evil.example/" to "https://cloud.example/",
    )) {
      val e = bot.tafs.app.app.endpointOn("https://cloud.example/", address)
      assertEquals(address, shown, e)
      val u = java.net.URI(e)
      assertEquals(address, "https", u.scheme)
      assertEquals(address, "cloud.example", u.host.lowercase())
      assertEquals(address, -1, u.port)
      assertEquals(address, null, u.rawUserInfo)
    }
  }

  /**
   * The example is pasted into a shell, so the endpoint keeps only a plain path (letters, digits and `/ _ . ~ -`), with no
   * query or fragment, else it's tafs cloud's own address alone; and the example quotes it, so the shell takes it as it is.
   */
  @Test fun anEndpointKeepsOnlyAPlainPathAndTheExampleQuotesIt() {
    for (address in listOf(
      "/v1/doors/api/d\$(id)/requests", "/v1/doors/api/d;id", "/v1/doors/api/d&id", "/v1/doors/api/d'id", "/v1/doors/api/d`id`",
      "/v1/doors/api/d|id", "/v1/doors/api/d%24(id)", "/v1/doors/api/d!x", "/v1/doors/api/d*", "/v1/doors/api/d+x,y=z",
      "https://cloud.example/v1/doors/api/d\$(id)", "https://cloud.example/v1/doors/api/d;id",
      "https://cloud.example/v1/doors/api/d?x=\$(id)", "https://cloud.example/v1/doors/api/d#\$(id)",
      "https://cloud.example/v1/doors/api/d%27",
    )) assertEquals(address, "https://cloud.example", bot.tafs.app.app.endpointOn("https://cloud.example/", address))
    // tafs cloud's own door paths are plain, and kept.
    assertEquals("https://cloud.example/v1/doors/api/door_ord7/requests",
      bot.tafs.app.app.endpointOn("https://cloud.example/", "/v1/doors/api/door_ord7/requests"))
    assertEquals("https://cloud.example/v1/doors/api/door-ord.7~x/requests",
      bot.tafs.app.app.endpointOn("https://cloud.example/", "https://cloud.example/v1/doors/api/door-ord.7~x/requests"))
    val example = apiExample("https://cloud.example/v1/doors/api/door_ord7/requests")
    assertTrue(example.startsWith("curl -X POST 'https://cloud.example/v1/doors/api/door_ord7/requests' "))
  }

  private val drafted = bot.tafs.app.kiff.DoorDraft.read("""{"draft_id":"draft-1","body":{"kind":"api","draft":{"name":"Order system",
    "job":"Check the return and refund it if the Card allows","fields":[{"name":"order","type":"string","max":40}],
    "outcomes":["refunded","refused"],"text":true}}}""")!!
  private val cloudTaf = taf.copy(cloudId = "taf_1")

  private fun draftCard(manage: Boolean, actions: bot.tafs.app.views.CoachActions) {
    val state = bot.tafs.app.app.AppState(mode = bot.tafs.app.app.Mode.kiff, tafs = listOf(cloudTaf),
      access = mapOf(cloudTaf.id to bot.tafs.app.kiff.Access(use = true, manage = manage)))
    val card = bot.tafs.app.kiff.SetupComponent(type = "door", id = "c1", tafId = "taf_1", draftId = "draft-1")
    compose.setContent { TafsTheme { androidx.compose.foundation.layout.Column { bot.tafs.app.views.DoorDraftCard(card, state, actions) } } }
  }

  private fun coachActions(approve: suspend (String, bot.tafs.app.kiff.DoorDraft, String) -> bot.tafs.app.kiff.AddedApiDoor? = { _, _, _ -> null }) =
    bot.tafs.app.views.CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {},
      doorDraft = { t, d -> if (t == "taf_1" && d == "draft-1") drafted else null }, approveDoorDraft = approve,
      endpoint = { "https://cloud.example/v1/doors/api/${it.id}/requests" })

  /** The card draws tafs cloud's draft, never Coach's words: its name, what the taf does, what it sends and gets back, the risk. */
  @Test fun coachsDoorDraftIsDrawnAndItsApprovalShowsTheFirstKeyOnce() {
    val signed = mutableListOf<String>()
    draftCard(manage = true, coachActions { t, d, reason ->
      signed += "$t ${d.id} | $reason"
      bot.tafs.app.kiff.AddedApiDoor(door, bot.tafs.app.kiff.IssuedKey("key_1", "tafs_api_first", "irst", "2026-11-19T22:00:00Z"))
    })
    compose.onNodeWithText("Order system").assertExists()
    compose.onNodeWithText(Words.Door.Api.draftWill(taf = "Returns", job = "Check the return and refund it if the Card allows")).assertExists()
    compose.onNodeWithText("order · string").assertExists()
    compose.onNodeWithText(Words.Door.Api.draftWordsRisk(taf = "Returns", app = "Order system")).assertExists()
    compose.onNodeWithTag(bot.tafs.app.views.DOOR_DRAFT_APPROVE).performClick()
    compose.waitForIdle()
    assertEquals(listOf("taf_1 draft-1 | ${Words.Door.Api.faceidBody(app = "Order system", taf = "Returns")}"), signed)
    compose.onNodeWithTag(API_KEY_SHOWN).assertExists()
    compose.onNodeWithText("tafs_api_first").assertExists()
  }

  @Test fun onlyTheTafsManagerSeesApproveOnCoachsDoorDraft() {
    draftCard(manage = false, coachActions())
    compose.onNodeWithText("Order system").assertExists()
    compose.onAllNodesWithTag(bot.tafs.app.views.DOOR_DRAFT_APPROVE).assertCountEquals(0)
  }

  @Test fun aDoorDraftFieldReadsAsADeveloperReadsIt() {
    assertEquals("reason · damaged | late", draftField(bot.tafs.app.kiff.DoorDraft.Field("reason", "enum", listOf("damaged", "late"))))
    assertEquals("order · string", draftField(bot.tafs.app.kiff.DoorDraft.Field("order", "string")))
  }

  /**
   * The Chrome pairing code's Copy (the owner's ask, 22 Nov): copied as a key is, under its own label, marked sensitive and off
   * the clipboard after its time, never the person's next copy.
   */
  @Test fun aCopiedChromePairingCodeIsSensitiveAndGoneAfterItsMinute() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    val code = "DK8M-QYKG-4MEK-4QRV-FC9X-6"
    copyKeyForAMinute(context, code, after = 10, label = bot.tafs.app.views.CHROME_CODE_CLIP)
    assertEquals(code, clipboard.primaryClip?.getItemAt(0)?.text)
    assertEquals(bot.tafs.app.views.CHROME_CODE_CLIP, clipboard.primaryClipDescription?.label)
    assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
    ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
    assertFalse("the code is off the clipboard after its time", clipboard.hasPrimaryClip() && clipboard.primaryClip?.getItemAt(0)?.text == code)
    copyKeyForAMinute(context, code, after = 10, label = bot.tafs.app.views.CHROME_CODE_CLIP)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("note", "mine"))
    ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
    assertEquals("mine", clipboard.primaryClip?.getItemAt(0)?.text)
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
