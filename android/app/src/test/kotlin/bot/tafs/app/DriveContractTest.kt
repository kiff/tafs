package bot.tafs.app

import bot.tafs.app.model.Words
import bot.tafs.app.drive.Box
import bot.tafs.app.drive.DriveDiagnostics
import bot.tafs.app.drive.Shot
import bot.tafs.app.drive.topmostAt
import java.io.File
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

  /** A file's text with Unix line endings, so a checkout with CRLF reads the same. */
  private fun File.text(): String = readText().replace("\r\n", "\n")

  private fun files(): List<File> = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  /**
   * Each `Words` accessor (`Words.Taf.Remove.button`) as the English it says, from the shared `app/strings/en/`, one file per
   * feature, a count by its `other` form: the scans read the words a control shows, wherever they're kept. No words read is a
   * failure, never an empty scan that passes.
   */
  private val english: Map<String, String> by lazy {
    val files = File("../../app/strings/en").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
    val en = files.flatMap { kotlinx.serialization.json.Json.parseToJsonElement(it.readText()).jsonObject.entries }
    check(en.size > 1_000) { "app/strings/en/ wasn't read: ${en.size} keys from ${files.size} files" }
    fun camel(part: String, upper: Boolean) = part.split("_").let { w -> (if (upper) w[0].replaceFirstChar(Char::uppercase) else w[0]) + w.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) } }
    en.associate { (key, value) ->
      val parts = key.split(".")
      val accessor = "Words." + parts.dropLast(1).joinToString(".") { camel(it, true) } + "." + camel(parts.last(), false)
      val text = (value as? kotlinx.serialization.json.JsonObject)?.get("other") ?: value
      accessor to text.jsonPrimitive.content
    }
  }

  private val accessor = Regex("""Words(?:\.[A-Z]\w*)+\.[a-z]\w*""")

  /** [line] with each `Words` accessor written out as its English, quoted, so a control's words read as they show. */
  private fun readable(line: String): String = accessor.replace(line) { m -> english[m.value]?.let { "\"" + it.replace("\"", "'") + "\"" } ?: m.value }

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
      val s = f.text()
      field.findAll(s).mapNotNull { m -> if ("driveTyped(" !in call(s, m.range.last + 1)) "${f.name}:${s.substring(0, m.range.first).count { it == '\n' } + 1}" else null }.toList()
    }
    assertTrue("A field's changes go through driveTyped (docs/drive.md, typing): $unchecked", unchecked.isEmpty())
  }

  @Test
  fun everyConfirmingControlCarriesTheMark() {
    // docs/drive.md's list: approve, disconnect, remove a taf, halt and resume, "Did this happen?", extra room, buying a pack,
    // dismissing a card that signs. What signs with the approval key sits in ApprovalGate, which carries the mark itself.
    val gate = File(sources, "views/ApprovalsSetupView.kt").text()
    assertTrue("ApprovalGate carries the confirm mark", Regex("""fun ApprovalGate[\s\S]{0,400}confirms\(\)""").containsMatchIn(gate))
    val listed = listOf(
      "views/CoachViews.kt" to "actions.openKeySheet(c, draft?.version) else actions.approve(c, grant.digest, draft?.version)",
      "views/TafChatView.kt" to "resuming = true; resume()",
      "views/CoachViews.kt" to "actions.confirmKnob(taf.id)",
      "views/CoachViews.kt" to "{ actions.approveDrive(c) }",
      "views/CoachChats.kt" to "Words.Coach.Tools.give",
      "views/TaskRequestViews.kt" to "actions.approveRequest(product, r.id)",
      "views/UsageViews.kt" to "actions.buy(id)",
      "views/HaltViews.kt" to "Words.Halt.allNow else Words.Halt.now",
      "views/HaltViews.kt" to "Words.Halt.again",
      "views/TafDetailView.kt" to "actions.revoke(taf.id)",
      "views/TafDetailView.kt" to "actions.remove(asked)",
      "views/TafDetailView.kt" to "actions.setPaused(taf.id, !taf.paused)",
      "views/SetupViews.kt" to "actions.togglePause(taf.id)",
      "views/SetupViews.kt" to "Words.Setup.removeForGood",
      "views/ConnectAppsViews.kt" to "onDisconnect?.invoke(g.id)",
      "views/TafPresentationViews.kt" to "actions.connections.disconnect(app.id)",
      "views/TafPresentationViews.kt" to "actions.connections.replaceKey(grant)",
      "views/ConnectionViews.kt" to "actions.disconnect(grant.id)",
      "views/NeedsYouView.kt" to "send(Answer.Refuse)",
      "views/NeedsYouView.kt" to "send(Answer.NextStep(step))",
      "views/VersionViews.kt" to "actions.installTrial(tafId)",
      "views/VersionViews.kt" to "actions.goBack(tafId)",
      "views/DoorViews.kt" to "onPair(sender, name)",
    )
    val unmarked = listed.filter { (file, anchor) ->
      val s = File(sources, file).text()
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
      "views/ConnectAppsViews.kt" to "Modifier.heightIn(min = 44.dp).confirms().clickable(enabled = state.connecting == null) { act(g) }",
    ).filter { (file, marked) -> marked !in File(sources, file).text() }
    assertTrue("Delete account, a pack's price, signing out another device and a connection's fix confirm: $onModifiers", onModifiers.isEmpty())
    // And everything ApprovalGate holds is inside it: the approvals, extra room, "Did this happen?", install, resume.
    val gated = files().sumOf { Regex("""ApprovalGate \{""").findAll(it.text()).count() }
    assertTrue("the approval gate is in use", gated >= 10)
  }

  /**
   * A control a person touches whose words take something away or grant it (Delete, Remove, Disconnect, Halt, Revoke, Sign out,
   * Buy, Resume, Approve, Install, Refuse, Pair, Unpair, Forget, Go back) carries the confirm mark, or sits behind ApprovalGate. So a
   * new one can't be forgotten. Words that only name a screen or a title are listed here, each with why.
   */
  @Test
  fun everyControlWhoseWordsConfirmCarriesTheMark() {
    val label = Regex(""""[^"]*\b(Delete|Remove|Disconnect|Halt|Revoke|Sign out|Buy|Resume|Approve|Install|Refuse|Pair|Unpair|Forget|Go back)\b[^"]*"""")
    val touch = Regex("""TextButton|DropdownMenuItem|OutlineButton|PrimaryButton|\bButton\(|clickable""")
    val notConfirming = setOf(
      "Modifier.clickable(onClickLabel = \"Open Halt\"", // the halt chip opens the halt screen, whose buttons carry the mark
      "contentDescription = \"Remove {name}\"(file.name)", // takes a file off a message not sent yet
      "Text(\"Delete account\", style = scaled(28", // the screen's title
      "Text(if (tafId == null) \"Halt all\" else \"Halt\", style = scaled(", // the halt screen's title, and the row that opens it
      "title = { Text(", // a dialog's title, never touched
    )
    // Words kept as constants count too: `ConnectAppsWords.DISCONNECT` is "Disconnect" wherever it's used, whether it's written
    // there or reads a `Words` line.
    val constant = Regex("""(?:const val (\w+)\s*=\s*("[^"]*")|val (\w+)(?::\s*String)?\s*get\(\)\s*=\s*(Words[\w.]+))""")
    val confirmingConstants = files().flatMap { f ->
      val obj = Regex("""object (\w+)""").find(f.text())?.groupValues?.get(1)
      constant.findAll(f.text()).mapNotNull { m ->
        val (name, words) = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] to m.groupValues[2] else m.groupValues[3] to readable(m.groupValues[4])
        if (label.containsMatchIn(words)) obj?.let { "$it.$name" } else null
      }.toList()
    }.toSet()
    fun confirming(line: String) = label.containsMatchIn(line) || confirmingConstants.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(line) }
    val unmarked = files().filter { "/drive/" !in it.path }.flatMap { f ->
      val lines = f.readLines().map(::readable)
      lines.indices.filter { i ->
        val line = lines[i]
        confirming(line) && notConfirming.none { it in line } &&
          touch.containsMatchIn(lines.subList(maxOf(0, i - 4), minOf(lines.size, i + 5)).joinToString("\n")) &&
          "confirms()" !in lines.subList(maxOf(0, i - 4), minOf(lines.size, i + 5)).joinToString("\n") &&
          "ApprovalGate" !in lines.subList(maxOf(0, i - 8), minOf(lines.size, i + 2)).joinToString("\n")
      }.map { "${f.name}:${it + 1}" }
    }
    assertTrue("These controls' words confirm, so they carry .confirms() (docs/drive.md): $unmarked", unmarked.isEmpty())
  }

  @Test
  fun aVendorsKeyIsNeverCaptured() {
    val s = File(sources, "views/ConnectAppsViews.kt").text()
    assertTrue("the key field carries the secret mark", Regex("""driveTyped\(\{ typed ->[\s\S]{0,400}?\}\),\s*Modifier\.fillMaxWidth\(\)\.secret\(\)""").containsMatchIn(s))
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
