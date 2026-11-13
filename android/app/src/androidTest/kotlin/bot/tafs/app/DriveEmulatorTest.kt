package bot.tafs.app

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.drive.Action
import bot.tafs.app.drive.AndroidDriveHost
import bot.tafs.app.drive.DriveInfo
import bot.tafs.app.drive.DriveInput
import bot.tafs.app.drive.DriveMenu
import bot.tafs.app.drive.DriveOverlay
import bot.tafs.app.drive.DriveState
import bot.tafs.app.drive.DriveWindow
import bot.tafs.app.drive.DriveWords
import bot.tafs.app.drive.Target
import bot.tafs.app.drive.confirms
import bot.tafs.app.drive.driveDismissal
import bot.tafs.app.drive.driveTyped
import bot.tafs.app.drive.secret
import bot.tafs.app.drive.approvalTerms
import androidx.compose.foundation.background
import bot.tafs.app.kiff.Account
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.views.CoachActions
import bot.tafs.app.views.CoachScreen
import bot.tafs.app.views.ConnectionActions
import bot.tafs.app.views.DoorActions
import bot.tafs.app.views.TafActions
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Coach drives the app on Android (docs/drive.md), on a device: the person's own input pauses a drive and is delivered, on the
 * screen, a dialog, a sheet's scrim and outside a menu; their third tap on a control, their typing and their back end it as
 * `owner_took_over`; a pause stops Coach's step; Coach's tap reaches a sheet's button but never a confirming one; the
 * screenshot holds a dialog over the screen with a secret field blanked; Coach types into a focused field; and Coach can't approve
 * its own drive. The person's input goes through the system's real input path (the instrumentation injects it), Coach's through the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
class DriveEmulatorTest {
  @get:Rule(order = 0) val awake = AwakeDevice()
  @get:Rule(order = 1) val rule = createStableComposeRule()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val ends = mutableListOf<String>()
  private var pauses = 0
  private var taps = 0
  private var runNow = 0
  private var approved = 0
  private var sheetTaps = 0
  private var sheetOpen by mutableStateOf(false)
  private var dialogOpen by mutableStateOf(false)
  private var menuOpen by mutableStateOf(false)
  private var text by mutableStateOf("")
  private var key by mutableStateOf("sk_test_secret")
  private lateinit var host: AndroidDriveHost

  @Before fun drive() {
    ends.clear()
    pauses = 0
    taps = 0
    DriveInput.personsInput = { why -> ends += why }
    // As the controller does: the touch pauses, and the third tap on a control since then takes over.
    DriveInput.personsTouch = { pauses++ }
    DriveInput.personsTap = { onControl -> if (onControl && ++taps >= 3) ends += "owner_took_over" }
    DriveInput.coachStep = { false }
  }

  @After fun stop() {
    instrumentation.runOnMainSync {
      DriveInput.turn(false)
      DriveInput.unguard(rule.activity.window)
    }
    DriveInput.personsInput = {}
    DriveInput.personsTouch = {}
    DriveInput.personsTap = {}
    DriveInput.isControlAt = { _, _ -> false }
    DriveInput.isPaused = { false }
  }

  private fun show() {
    rule.setShown {
      Column {
        Text("Home")
        Button({ runNow++ }, Modifier.fillMaxWidth().height(96.dp)) { Text("Run now") }
        Button({ approved++ }, Modifier.confirms()) { Text("Approve") }
        TextField(text, driveTyped<String> { text = it }, label = { Text("Note") })
        TextField(key, driveTyped<String> { key = it }, Modifier.secret(), label = { Text("Key") })
        Text("Refund up to 50 a day", Modifier.fillMaxWidth().height(48.dp).background(Color.White).approvalTerms("Stripe"), color = Color.Black)
        if (sheetOpen) ModalBottomSheet(driveDismissal { sheetOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
          DriveWindow()
          Column { Text("Sheet title"); Button({ sheetTaps++ }) { Text("Sheet button") } }
        }
        DriveMenu(menuOpen, { menuOpen = false }) { DropdownMenuItem(text = { Text("Menu item") }, onClick = {}) }
        if (dialogOpen) AlertDialog(
          onDismissRequest = driveDismissal { dialogOpen = false },
          containerColor = Color(0xFF00C853),
          title = { DriveWindow(); Text("Halt every taf?") },
          confirmButton = { TextButton({}, Modifier.confirms()) { Text("Halt") } },
          dismissButton = { TextButton({ dialogOpen = false }) { Text("Cancel") } },
        )
      }
    }
    hostIt()
  }

  private fun hostIt() {
    rule.waitForIdle()
    instrumentation.runOnMainSync {
      DriveInput.guard(rule.activity.window)
      host = AndroidDriveHost(rule.activity, DriveOverlay(rule.activity, 0xFFFFB300.toInt(), stop = {}), { null })
      DriveInput.isControlAt = host::controlAt
    }
  }

  private fun driving() = instrumentation.runOnMainSync { DriveInput.turn(true) }

  /** The person's tap, through the system's input path, at a point on screen. */
  private fun personTaps(x: Float, y: Float) {
    val t = SystemClock.uptimeMillis()
    instrumentation.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
    instrumentation.sendPointerSync(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
    rule.waitForIdle()
  }

  private fun settle() { rule.waitForIdle(); Thread.sleep(700); rule.waitForIdle() }

  /** The tree Coach would read, by words: each node's id and its bounds in the screenshot's pixels. */
  private fun tree(): Map<String, Pair<String, IntArray>> = runBlocking {
    val look = host.look(tree = true, diagnostics = false)
    (look.tree as JsonArray).mapNotNull { e ->
      val o = e.jsonObject
      val words = o["words"]?.jsonPrimitive?.content ?: return@mapNotNull null
      val b = o.getValue("bounds").jsonObject
      words to (o.getValue("id").jsonPrimitive.content to intArrayOf(b.getValue("x").jsonPrimitive.int, b.getValue("y").jsonPrimitive.int, b.getValue("w").jsonPrimitive.int, b.getValue("h").jsonPrimitive.int))
    }.toMap()
  }

  /** A node's middle on screen, from the tree's screenshot pixels. */
  private fun screenMiddle(words: String): Pair<Float, Float> {
    val (_, b) = tree().getValue(words)
    val decor = rule.activity.window.decorView
    val factor = maxOf(1.0, maxOf(decor.width, decor.height).toDouble() / 1_100)
    return ((b[0] + b[2] / 2) * factor).toFloat() to ((b[1] + b[3] / 2) * factor).toFloat()
  }

  // 1. The person's input pauses the drive, and is theirs: it's delivered.

  @Test fun thePersonsTapOnTheScreenPausesTheDriveAndIsDelivered() {
    show(); driving()
    val (x, y) = screenMiddle("Run now")
    personTaps(x, y)
    assertTrue("paused", pauses > 0)
    assertEquals("the person's tap reached Run now", 1, runNow)
    assertTrue("one tap ends nothing", ends.isEmpty())
  }

  @Test fun thePersonsTapOnADialogPausesTheDriveAndIsDelivered() {
    show(); dialogOpen = true; settle(); driving()
    val (x, y) = screenMiddle("Cancel")
    personTaps(x, y)
    settle()
    assertTrue(pauses > 0)
    assertFalse("Cancel ran", dialogOpen)
    assertTrue(ends.isEmpty())
  }

  @Test fun thePersonsTapOnASheetsScrimPausesTheDriveAndClosesIt() {
    show(); sheetOpen = true; settle(); driving()
    // Above the sheet, on its scrim.
    personTaps(rule.activity.window.decorView.width / 2f, 200f)
    settle()
    assertTrue(pauses > 0)
    assertFalse("the scrim tap closed the sheet", sheetOpen)
    assertTrue(ends.isEmpty())
  }

  @Test fun thePersonsTapOutsideAMenuPausesTheDriveEvenDuringCoachsStep() {
    show()
    val (_, y) = screenMiddle("Run now")
    val x = rule.activity.window.decorView.width * 0.9f
    menuOpen = true; settle(); driving()
    // One of Coach's steps is under way (its dot, its settle, its look): the person's touch is still theirs.
    DriveInput.coachStep = { true }
    personTaps(x, y)
    settle()
    assertTrue(pauses > 0)
    assertFalse("the menu folded", menuOpen)
    assertTrue(ends.isEmpty())
  }

  @Test fun aThirdTapOnAControlTakesOverButEmptySpaceCountsForNothing() {
    show(); driving()
    // Empty space, low on the screen, below everything shown.
    val decor = rule.activity.window.decorView
    repeat(3) { personTaps(decor.width / 2f, decor.height * 0.8f) }
    assertTrue("a touch on empty space counts for nothing", ends.isEmpty())
    val (x, y) = screenMiddle("Run now")
    repeat(3) { personTaps(x, y) }
    assertEquals(listOf("owner_took_over"), ends.distinct())
    assertEquals("every tap of theirs was delivered", 3, runNow)
  }

  @Test fun thePersonsTypingIsKeptAndTakesOver() {
    show(); driving()
    val (x, y) = screenMiddle("Note")
    personTaps(x, y)
    assertTrue("tapping into a field only pauses", ends.isEmpty())
    instrumentation.sendStringSync("hi")
    rule.waitForIdle()
    assertEquals(listOf("owner_took_over"), ends.distinct())
    assertEquals("hi", text)
  }

  @Test fun aPauseStopsCoachsStepBeforeItsTouch() = runBlocking {
    show(); driving()
    DriveInput.isPaused = { true }
    val button = tree().getValue("Run now").first
    val tapped = host.act(Action.Tap(Target.Element(button), "Tapping Run now"))
    rule.waitForIdle()
    assertFalse(tapped.done)
    assertEquals(DriveWords.PAUSED, tapped.line)
    assertEquals("nothing was dispatched", 0, runNow)
  }

  // The person sees the drive: the border and the caption are windows on screen while it's on, and the end's words after.

  @Test fun theBorderAndTheCaptionAreOnScreenWhileADriveIsOn() {
    show()
    val drive = DriveInfo("d1", task = "Run Chargebacks")
    fun overlays(): List<android.view.View> {
      var found = emptyList<android.view.View>()
      instrumentation.runOnMainSync { found = WindowInspector.getGlobalWindowViews().filter { DriveOverlay.isOverlay(it) && it.isAttachedToWindow } }
      return found
    }
    try {
      instrumentation.runOnMainSync { host.show(DriveState.On(drive)) }
      rule.waitForIdle()
      instrumentation.waitForIdleSync()
      val on = overlays()
      assertEquals("the border and the caption, each a window: $on", 2, on.size)
      val caption = on.filterIsInstance<android.widget.LinearLayout>().single()
      assertEquals("Coach is driving: Run Chargebacks", (caption.getChildAt(0) as android.widget.TextView).text.toString())
      // The screenshot leaves them out: what Coach sees is the app, not its own marks.
      assertTrue(tree().keys.none { it.contains("Coach is driving") })

      instrumentation.runOnMainSync { host.show(DriveState.Over(drive, "owner_took_over", DriveWords.ended("owner_took_over"))) }
      instrumentation.waitForIdleSync()
      val over = overlays()
      assertEquals("only the caption, with why it ended: $over", 1, over.size)
      assertEquals(DriveWords.ended("owner_took_over"), ((over.single() as android.widget.LinearLayout).getChildAt(0) as android.widget.TextView).text.toString())
    } finally {
      instrumentation.runOnMainSync { host.show(DriveState.Off) }
      instrumentation.waitForIdleSync()
    }
    assertTrue("gone once the drive is off", overlays().isEmpty())
  }

  // What a touch is called, in the caption and "What I did": a row by its title, not all its words.

  @Test fun aTappedRowIsNamedByItsTitleNotItsWholeText() = runBlocking {
    var opened = 0
    rule.setShown {
      Row(Modifier.fillMaxWidth().clickable { opened++ }.padding(16.dp)) {
        Text("R")
        Column(Modifier.weight(1f)) { Text("Refunds"); Text("Works from its chat") }
        Text("Ready")
      }
    }
    hostIt(); driving()
    // Coach's tree keeps the row's words whole.
    val row = tree().entries.single { it.key.startsWith("R Refunds") }
    assertEquals("R Refunds Works from its chat Ready", row.key)
    val tapped = host.act(Action.Tap(Target.Element(row.value.first), "Opening Refunds"))
    rule.waitForIdle()
    assertTrue(tapped.line, tapped.done)
    assertEquals(1, opened)
    assertEquals("Refunds", tapped.touched?.words)
    assertEquals(DriveWords.tapped("Refunds"), tapped.line)
  }

  // 2. The person's back is a clear takeover.

  @Test fun thePersonsBackTakesOver() {
    show(); driving()
    // The test's own screen has nowhere to go back to: its back is recorded instead of leaving.
    var wentBack = false
    DriveInput.personsBack = { wentBack = true }
    try {
      instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
      rule.waitForIdle()
      assertEquals(listOf("owner_took_over"), ends.distinct())
      assertTrue("the back was the person's: it went back", wentBack || rule.activity.isFinishing)
    } finally {
      DriveInput.personsBack = {}
    }
  }

  @Test fun pausedTheCaptionReadsPausedWithContinueAndStop() {
    show()
    val drive = DriveInfo("d1", task = "Run Chargebacks")
    try {
      instrumentation.runOnMainSync { host.show(DriveState.On(drive, paused = true)) }
      instrumentation.waitForIdleSync()
      var caption: android.widget.LinearLayout? = null
      instrumentation.runOnMainSync {
        caption = WindowInspector.getGlobalWindowViews().filter { DriveOverlay.isOverlay(it) && it.isAttachedToWindow }
          .filterIsInstance<android.widget.LinearLayout>().singleOrNull()
      }
      val bar = caption!!
      assertEquals(DriveWords.PAUSED, (bar.getChildAt(0) as android.widget.TextView).text.toString())
      assertEquals(android.view.View.VISIBLE, bar.getChildAt(1).visibility)
      assertEquals(android.view.View.VISIBLE, bar.getChildAt(2).visibility)
    } finally {
      instrumentation.runOnMainSync { host.show(DriveState.Off) }
      instrumentation.waitForIdleSync()
    }
  }

  // 3. Coach's tap reaches a sheet's button; a confirming control is refused before anything is dispatched.

  @Test fun coachsTapReachesASheetsButtonAndNeverAConfirmingOne() = runBlocking {
    show(); sheetOpen = true; settle(); driving()
    val button = tree().getValue("Sheet button").first
    val tapped = host.act(Action.Tap(Target.Element(button), "Tapping Sheet button"))
    rule.waitForIdle()
    assertTrue(tapped.line, tapped.done)
    assertEquals(1, sheetTaps)
    assertTrue("Coach's own tap isn't the person's", ends.isEmpty())

    sheetOpen = false; settle()
    val approve = tree().getValue("Approve").first
    val refused = host.act(Action.Tap(Target.Element(approve), "Tapping Approve"))
    rule.waitForIdle()
    assertFalse(refused.done)
    assertEquals(DriveWords.CONFIRM, refused.line)
    assertEquals("the confirming control never ran", 0, approved)
    assertTrue(ends.isEmpty())
  }

  // 4. The screenshot: a dialog over the screen, and a secret field blanked.

  @Test fun theScreenshotHoldsADialogOverTheScreenAndBlanksASecretField() = runBlocking {
    show(); driving()
    val secret = tree().getValue("Secret field").second
    dialogOpen = true; settle()
    val title = tree().getValue("Halt every taf?").second
    val look = host.look(tree = false, diagnostics = false)
    val bytes = Base64.getDecoder().decode(look.image!!.data)
    assertTrue("a JPEG within 200,000 bytes", bytes.size <= 200_000 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
    val shot = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    assertTrue("scaled to at most 1,100 pixels", maxOf(shot.width, shot.height) <= 1_100)
    // The dialog's own green, just under its title: the dialog window is in the screenshot.
    val g = shot.getPixel(title[0] + 4, title[1] + title[3] + 4)
    assertTrue("the dialog's green at its place: ${Integer.toHexString(g)}", android.graphics.Color.green(g) > 150 && android.graphics.Color.red(g) < 80)
    // The secret field's middle is the blank fill, never its words.
    val s = shot.getPixel(secret[0] + secret[2] / 2, secret[1] + secret[3] / 2)
    assertTrue("the secret field blanked: ${Integer.toHexString(s)}", listOf(android.graphics.Color.red(s), android.graphics.Color.green(s), android.graphics.Color.blue(s)).all { it in 0x20..0x50 })
  }

  @Test fun anOpenApprovalsTermsAreBlankedAndNamedOnlyByTheirTitle() = runBlocking {
    show(); driving()
    val seen = tree()
    // In the tree: only that an approval is open, and for what. Never its terms.
    val terms = seen.getValue("An approval is open: Stripe").second
    assertTrue("its terms aren't in the tree", seen.keys.none { it.contains("Refund up to 50") })
    val look = host.look(tree = false, diagnostics = false)
    val bytes = Base64.getDecoder().decode(look.image!!.data)
    val shot = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    // The tree's bounds are the screenshot's, as for the secret field: the terms' middle is the blank fill, not their white ground.
    val p = shot.getPixel(terms[0] + terms[2] / 2, terms[1] + terms[3] / 2)
    assertTrue("the terms blanked: ${Integer.toHexString(p)}", listOf(android.graphics.Color.red(p), android.graphics.Color.green(p), android.graphics.Color.blue(p)).all { it in 0x20..0x50 })
  }

  // 5. Coach types into a focused field. The keyboard staying down is the app's screen's (MainActivity's; DriveContractTest reads it).

  @Test fun coachTypesIntoAFocusedField() = runBlocking {
    show(); driving()
    val field = tree().getValue("Note").first
    host.act(Action.Tap(Target.Element(field), "Tapping Note"))
    val typed = host.act(Action.Type("Refund 1042", "Typing the note"))
    rule.waitForIdle()
    assertTrue(typed.line, typed.done)
    assertEquals("Refund 1042", text)
    assertTrue("Coach's typing isn't the person's", ends.isEmpty())
  }

  @Test fun coachNeverTypesIntoASecretField() = runBlocking {
    show(); driving()
    val field = tree().getValue("Secret field").first
    host.act(Action.Tap(Target.Element(field), "Tapping the key"))
    val typed = host.act(Action.Type("sk_live_other", "Typing the key"))
    rule.waitForIdle()
    assertFalse(typed.done)
    assertEquals(DriveWords.SECRET_FIELD, typed.line)
    assertEquals("nothing typed", "sk_test_secret", key)
    assertTrue(ends.isEmpty())
  }

  // 6. A drive starts only on the person's approval: Coach's tap on the drive card's Approve is refused; the person's own reaches it.

  @Test fun coachCantApproveADriveButThePersonCan() = runBlocking {
    var approvals = 0
    val card = KiffClient.json.decodeFromString<SetupComponent>("""{"type":"drive","id":"d1","text":"Run Chargebacks"}""")
    val state = AppState(
      mode = Mode.kiff, ready = true, kiffAccount = Account("t_01", "acme", capabilities = listOf("members.manage")), coachChecked = true, coachCloudId = "setup_1", coachAgent = "setup-acme", coachChat = "s1",
      coachComponents = listOf(ShownComponent(Instant.parse("2026-11-06T00:30:00Z"), "s1", "r1", card)),
    )
    val actions = TafActions(
      answer = { _, _ -> }, togglePause = {}, revoke = {}, loadMemory = {}, forget = { _, _ -> }, loadChat = {},
      sendChat = { _, _, _, _ -> }, issuer = "KIFF",
      doors = DoorActions({}, { _, _ -> null }, { _, _, _ -> }, { _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}),
      coach = CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}, approveDrive = { approvals++ }),
      connections = ConnectionActions({}, { false }),
    )
    rule.setShown {
      TafsTheme { CompositionLocalProvider(LocalBiometry provides "your fingerprint") { CoachScreen(state, actions, loadCoach = {}, onBack = {}) } }
    }
    hostIt(); driving()
    val approve = tree().getValue("Approve with your fingerprint").first
    val refused = host.act(Action.Tap(Target.Element(approve), "Tapping Approve"))
    rule.waitForIdle()
    assertFalse(refused.done)
    assertEquals(DriveWords.CONFIRM, refused.line)
    assertEquals("Coach's tap never approved", 0, approvals)

    instrumentation.runOnMainSync { DriveInput.turn(false) }
    rule.onNodeWithText("Approve with your fingerprint").performClick()
    assertEquals("the person's own tap does", 1, approvals)
  }
}
