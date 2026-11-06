package bot.tafs.app

import bot.tafs.app.drive.Box
import bot.tafs.app.drive.DriveDiagnostics
import bot.tafs.app.drive.Shot
import bot.tafs.app.drive.topmostAt
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/drive.md's contract, as the Android app keeps it: the coordinates of a scaled screenshot and a sheet, and, read from the
 * app's own source, every window guarded, every field checked, every confirming control and secret field marked.
 */
class DriveContractTest {
  private val sources = File("src/main/kotlin/bot/tafs/app")

  private fun files(): List<File> = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  @Test
  fun aPointOnAScaledScreenshotGoesToTheTopmostWindowUnderIt() {
    // A 1080×2400 phone: the screenshot is 495×1100, and a point in it is the screen's times 2400/1100.
    val shot = Shot(1080, 2400)
    assertEquals(495, shot.width)
    assertEquals(1100, shot.height)
    assertEquals(218 to 436, shot.toScreen(100, 200))
    assertEquals(Box(0, 550, 495, 1100), shot.toShot(Box(0, 1200, 1080, 2400)))
    // A sheet over the lower half: a touch there is the sheet's, less its offset; above it, the screen's.
    val windows = listOf("screen" to Box(0, 0, 1080, 2400), "sheet" to Box(0, 1200, 1080, 2400))
    val (x, y) = shot.toScreen(247, 825)
    assertEquals(Triple("sheet", 539, 600), topmostAt(windows, x, y))
    assertEquals(Triple("screen", 539, 600), topmostAt(windows, 539, 600))
    assertNull(topmostAt(windows, 2000, 10))
    // A screen that fits is sent as it is.
    assertEquals(1.0, Shot(800, 1000).factor, 0.0)
  }

  @Test
  fun aFailedRequestIsKeptByItsRouteOnly() {
    assertEquals("/v1/tafs/{id}/work", DriveDiagnostics.route("https://cloud.tafs.kiff.dev/v1/tafs/taf_01ab/work?x=secret#y"))
    assertEquals("/v1/coach/drive/{id}/results/{id}", DriveDiagnostics.route("/v1/coach/drive/d_1/results/r_9"))
  }

  @Test
  fun everySheetDialogAndMenuIsAGuardedWindow() {
    val raw = Regex("""(?<![\w.])(?:androidx\.compose\.material3\.|androidx\.compose\.ui\.window\.)?(ModalBottomSheet|AlertDialog|BasicAlertDialog|Dialog|DatePickerDialog|DropdownMenu|Popup)\(""")
    val found = files().filter { it.name != "DriveWindows.kt" }.flatMap { f ->
      f.readLines().mapIndexedNotNull { i, line -> if (raw.containsMatchIn(line) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")) "${f.name}:${i + 1}" else null }
    }
    assertTrue("Open these through DriveSheet, DriveAlert, DriveDialog, DriveDatePicker or DriveMenu (docs/drive.md): $found", found.isEmpty())
  }

  @Test
  fun everyTextFieldsChangeIsChecked() {
    val field = Regex("""(?<![\w.])(?:androidx\.compose\.material3\.|androidx\.compose\.foundation\.text\.)?(OutlinedTextField|BasicTextField|TextField)\(""")
    val unchecked = files().flatMap { f ->
      val s = f.readText()
      field.findAll(s).mapNotNull { m -> if ("driveTyped(" !in call(s, m.range.last + 1)) "${f.name}:${s.substring(0, m.range.first).count { it == '\n' } + 1}" else null }.toList()
    }
    assertTrue("A field's changes go through driveTyped (docs/drive.md, typing): $unchecked", unchecked.isEmpty())
  }

  @Test
  fun everyConfirmingControlCarriesTheMark() {
    // docs/drive.md's list: approve, disconnect, remove a taf, halt and resume, "Did this happen?", extra room, buying a pack,
    // dismissing a card that signs. What signs with the approval key sits in ApprovalGate, which carries the mark itself.
    val gate = File(sources, "views/ApprovalsSetupView.kt").readText()
    assertTrue("ApprovalGate carries the confirm mark", Regex("""fun ApprovalGate[\s\S]{0,400}confirms\(\)""").containsMatchIn(gate))
    val listed = listOf(
      "views/CoachViews.kt" to "{ actions.approve(c, grant.digest, draft?.version) }",
      "views/CoachViews.kt" to "actions.confirmKnob(taf.id)",
      "views/CoachViews.kt" to "{ actions.approveDrive(c) }",
      "views/CoachChats.kt" to "\"Give Coach its tools\"",
      "views/TaskRequestViews.kt" to "actions.approveRequest(product, r.id)",
      "views/UsageViews.kt" to "actions.buy(id)",
      "views/HaltViews.kt" to "\"Halt all now\" else \"Halt now\"",
      "views/HaltViews.kt" to "\"Halt again\"",
      "views/TafDetailView.kt" to "actions.revoke(taf.id)",
      "views/TafDetailView.kt" to "actions.remove(taf.id)",
      "views/TafDetailView.kt" to "actions.setPaused(taf.id, !taf.paused)",
      "views/SetupViews.kt" to "actions.togglePause(taf.id)",
      "views/SetupViews.kt" to "\"Remove for good\"",
      "views/ConnectAppsViews.kt" to "onDisconnect?.invoke(g.id)",
      "views/TafPresentationViews.kt" to "actions.connections.disconnect(app.id)",
      "views/ConnectionViews.kt" to "actions.disconnect(grant.id)",
      "views/NeedsYouView.kt" to "send(Answer.Refuse)",
      "views/NeedsYouView.kt" to "send(Answer.NextStep(step))",
      "views/VersionViews.kt" to "actions.installTrial(tafId)",
      "views/VersionViews.kt" to "actions.goBack(tafId)",
      "views/DoorViews.kt" to "onPair(sender, name)",
    )
    val unmarked = listed.filter { (file, anchor) ->
      val s = File(sources, file).readText()
      val at = s.indexOf(anchor)
      assertTrue("$file still has $anchor", at >= 0)
      "confirms()" !in enclosingButton(s, at)
    }
    assertTrue("These confirm, so they carry .confirms() (docs/drive.md): $unmarked", unmarked.isEmpty())
    // Controls that aren't buttons carry it on the modifier that takes the touch.
    val onModifiers = listOf(
      "views/DeleteAccountView.kt" to "heightIn(min = 48.dp).confirms()\n            .clickable(enabled = !busy) { scope.launch { if (delete()) onClose() } }",
      "views/UsageViews.kt" to "Modifier.confirms().clickable(enabled = state.buying == null) { scope.launch { actions.buy(id) } }",
      "views/DevicesView.kt" to "scope.launch { remove(device) } }, modifier = Modifier.confirms())",
    ).filter { (file, marked) -> marked !in File(sources, file).readText() }
    assertTrue("Delete account, a pack's price and signing out another device confirm: $onModifiers", onModifiers.isEmpty())
    // And everything ApprovalGate holds is inside it: the approvals, extra room, "Did this happen?", install, resume.
    val gated = files().sumOf { Regex("""ApprovalGate \{""").findAll(it.readText()).count() }
    assertTrue("the approval gate is in use", gated >= 10)
  }

  @Test
  fun aVendorsKeyIsNeverCaptured() {
    val s = File(sources, "views/ConnectAppsViews.kt").readText()
    assertTrue("the key field carries the secret mark", "driveTyped({ key = it }), Modifier.fillMaxWidth().secret()" in s)
  }

  /** The arguments of the call whose `(` ends just before [from]. */
  private fun call(s: String, from: Int): String {
    var depth = 1
    var i = from
    while (depth > 0 && i < s.length) {
      when (s[i]) { '(' -> depth++; ')' -> depth-- }
      i++
    }
    return s.substring(from, i)
  }

  /** The button call around [at]: from its name to its closing parenthesis. */
  private fun enclosingButton(s: String, at: Int): String {
    val start = Regex("""(OutlineButton|PrimaryButton|TextButton)\(""").findAll(s).map { it.range.last + 1 }
      .filter { it <= at }.lastOrNull { open -> open + call(s, open).length >= at } ?: return ""
    return call(s, start)
  }
}
