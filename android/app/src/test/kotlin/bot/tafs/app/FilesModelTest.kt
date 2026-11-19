package bot.tafs.app

import bot.tafs.app.files.FileDetail
import bot.tafs.app.files.FileGroups
import bot.tafs.app.files.FileNames
import bot.tafs.app.files.FilePreview
import bot.tafs.app.files.FilesModel
import bot.tafs.app.files.FilesPage
import bot.tafs.app.files.FilesStore
import bot.tafs.app.files.FilesView
import bot.tafs.app.files.Goes
import bot.tafs.app.files.KeptFile
import bot.tafs.app.files.StubFilesStore
import bot.tafs.app.model.Words
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Files' store and model (docs/android-app.md, Files): the list read whole, its filters, the days left, the names and previews cleaned, and Delete asked first. */
class FilesModelTest {
  private val now = Instant.parse("2026-11-19T10:00:00Z")

  /** The stub, with every call to tafs cloud's routes written down. */
  private class Recording(private val inner: FilesStore = StubFilesStore(Instant.parse("2026-11-19T10:00:00Z"))) : FilesStore by inner {
    val calls = mutableListOf<String>()
    var deletes = true

    override suspend fun list(taf: String?, run: String?, chat: String?, cursor: String?): FilesPage =
      inner.list(taf, run, chat, cursor).also { calls += "list ${cursor ?: ""}".trim() }

    override suspend fun delete(id: String): Boolean = (deletes && inner.delete(id)).also { calls += "delete $id" }
  }

  private fun file(id: String, created: String, taf: String? = "taf_a", run: String? = "run_1", chat: String? = null, expires: String? = null) =
    KeptFile(id, "$id.csv", "text/csv", 100, Instant.parse(created), Instant.parse(expires ?: created).plusSeconds(if (expires == null) 30L * 86_400 else 0), taf, taf?.let { "Taf $it" }, run, chat)

  // MARK: The stub, to the contract's shape

  @Test fun theStubListsNewestFirstAndNarrowsByTafRunAndChat() = runTest {
    val store = StubFilesStore(now)
    val all = store.list().files
    assertEquals(all.sortedByDescending { it.created }, all)
    assertEquals(setOf("Late parcels", "Support replies", "Lead follow-up"), all.mapNotNull { it.tafName }.toSet())
    assertTrue(store.list(taf = "taf_parcels").files.all { it.tafId == "taf_parcels" })
    assertEquals(listOf("f_2", "f_3"), store.list(run = "run_p1").files.map { it.id })
    assertEquals(listOf("f_1"), store.list(chat = "chat_s1").files.map { it.id })
    // Kept 30 days from the run.
    for (f in all) assertEquals(30L * 86_400, f.expires.epochSecond - f.created.epochSecond)
  }

  @Test fun theModelReadsEveryPage() = runTest {
    val store = Recording(StubFilesStore(now, pageSize = 3))
    val model = FilesModel(store)
    model.refresh()
    assertEquals(4, model.state.value.files?.size)
    assertEquals(listOf("list", "list 3"), store.calls)
  }

  @Test fun aFailedReadKeepsWhatWasShownAndSaysWhy() = runTest {
    var fail = false
    val inner = StubFilesStore(now)
    val store = object : FilesStore by inner {
      override suspend fun list(taf: String?, run: String?, chat: String?, cursor: String?): FilesPage =
        if (fail) throw java.io.IOException("offline") else inner.list(taf, run, chat, cursor)
    }
    val model = FilesModel(store) { "Couldn't reach tafs cloud" }
    model.refresh()
    fail = true
    model.refresh()
    assertEquals(4, model.state.value.files?.size)
    assertEquals("Couldn't reach tafs cloud", model.state.value.error)
  }

  // MARK: The list's filters

  @Test fun theListNarrowsToATafAndGroupsByTafRunAndDay() {
    val files = listOf(
      file("a", "2026-11-19T08:00:00Z", taf = "taf_a", run = "run_1"),
      file("b", "2026-11-19T09:00:00Z", taf = "taf_b", run = "run_2"),
      file("c", "2026-11-18T23:30:00Z", taf = "taf_a", run = "run_1"),
      file("d", "2026-11-17T12:00:00Z", taf = "taf_a", run = "run_3"),
    )
    assertEquals(listOf("a", "c", "d"), FileGroups.forTaf(files, "taf_a").map { it.id })
    assertEquals(files, FileGroups.forTaf(files, null))
    assertEquals(listOf("taf_b" to listOf("b"), "taf_a" to listOf("a", "c", "d")), FileGroups.byTaf(files).map { it.key to it.files.map(KeptFile::id) })
    assertEquals(listOf("run_2" to listOf("b"), "run_1" to listOf("a", "c"), "run_3" to listOf("d")), FileGroups.byRun(files).map { it.key to it.files.map(KeptFile::id) })
    assertEquals(listOf("taf_b" to "Taf taf_b", "taf_a" to "Taf taf_a"), FileGroups.tafs(files))
    // By day in the account's zone: 23:30 UTC on the 18th is the 19th in Paris.
    val paris = ZoneId.of("Europe/Paris")
    assertEquals(
      listOf(LocalDate.of(2026, 11, 19) to listOf("b", "a", "c"), LocalDate.of(2026, 11, 17) to listOf("d")),
      FileGroups.byDay(files, paris).map { (day, its) -> day to its.map(KeptFile::id) },
    )
    assertEquals(files.maxOf { it.expires }, FileGroups.keptUntil(files))
    assertNull(FileGroups.keptUntil(emptyList()))
  }

  @Test fun aRunWithNoIdIsEachFileOnItsOwn() {
    val files = listOf(file("a", "2026-11-19T08:00:00Z", run = null), file("b", "2026-11-19T07:00:00Z", run = null))
    assertEquals(listOf("a", "b"), FileGroups.byRun(files).map { it.key })
  }

  // MARK: Days left, at the account's midnight

  @Test fun whenAFileGoesIsCountedInTheAccountsDays() {
    val paris = ZoneId.of("Europe/Paris")
    val today = LocalDate.of(2026, 11, 19)
    // 23:59 in Paris is still today; 00:00 is tomorrow.
    assertEquals(Goes.Today, Goes.of(Instant.parse("2026-11-19T22:59:59Z"), today, paris))
    assertEquals(Goes.Tomorrow, Goes.of(Instant.parse("2026-11-19T23:00:00Z"), today, paris))
    assertEquals(Goes.Tomorrow, Goes.of(Instant.parse("2026-11-20T22:59:59Z"), today, paris))
    assertEquals(Goes.InDays(2), Goes.of(Instant.parse("2026-11-20T23:00:00Z"), today, paris))
    assertEquals(Goes.InDays(30), Goes.of(Instant.parse("2026-12-19T09:00:00Z"), today, paris))
    // Past and not yet swept: today, never a negative count.
    assertEquals(Goes.Today, Goes.of(Instant.parse("2026-11-17T09:00:00Z"), today, paris))
    // The same instant in another zone is another day.
    assertEquals(Goes.Today, Goes.of(Instant.parse("2026-11-20T02:00:00Z"), today, ZoneId.of("America/New_York")))
  }

  @Test fun theDaysLeftAreSaidInFilesWords() {
    assertEquals("goes today", Words.Files.goesToday)
    assertEquals("goes tomorrow", Words.Files.goesTomorrow)
    assertEquals("goes in 2 days", Words.Files.goesInDays(2))
    assertEquals("goes in 30 days", Words.Files.goesInDays(30))
  }

  // MARK: Names and previews, cleaned

  @Test fun aNameIsShownWithNoPathAndNoControlOrDirectionCharacters() {
    assertEquals("Late parcels.csv", FileNames.name("reports/2026/Late parcels.csv", "f_1"))
    assertEquals("x.txt", FileNames.name("..\\..\\evil\\x.txt", "f_1"))
    // U+202E would show "invoice‮txt.exe" as "invoiceexe.txt".
    assertEquals("invoicetxt.exe", FileNames.name("invoice‮txt.exe", "f_1"))
    assertEquals("ab.md", FileNames.name("⁦a\u0000b\u0007.md⁩", "f_1"))
    assertEquals("ab", FileNames.name("a‎‏؜b‪‫‬‭", "f_1"))
    assertEquals("one line", FileNames.name("one\nline".replace("\n", " "), "f_1"))
    assertEquals("ab", FileNames.name("a\nb\r\t", "f_1"))
    // Nothing left: the file's id.
    assertEquals("f_1", FileNames.name("dir/‮  ", "f_1"))
    assertEquals("f_1", FileNames.name("folder/", "f_1"))
  }

  @Test fun aPreviewKeepsItsLinesAndTabsAndLosesEveryOtherControlOrDirectionCharacter() {
    assertEquals("Item\tDone\n#7781\t09:04\n", FileNames.preview("Item\tDone\r\n#7781\t09:04\r\n"))
    assertEquals("evil.txt\nnext", FileNames.preview("‮evil.txt\u0000\rnext\u0007"))
    assertEquals("ab", FileNames.preview("a  ﻿⁧b"))
    // Markdown and HTML stay as their own characters: it's drawn as text, never read as either.
    assertEquals("# Title\n<b>bold</b>", FileNames.preview("# Title\n<b>bold</b>"))
  }

  @Test fun aFilesTypeIsItsExtensionElseItsMediaType() {
    val base = file("a", "2026-11-19T08:00:00Z")
    assertEquals("CSV", FileNames.type(base))
    assertEquals("MD", FileNames.type(base.copy(name = "notes.md", mediaType = "text/markdown")))
    assertEquals("MARKDOWN", FileNames.type(base.copy(name = "notes", mediaType = "text/markdown; charset=utf-8")))
    assertEquals("PLAIN", FileNames.type(base.copy(name = "weird.verylongext", mediaType = "text/plain")))
    // A path or a direction character never reaches the tag.
    assertEquals("CSV", FileNames.type(base.copy(name = "a.txt/b.csv")))
  }

  // MARK: Delete now: asked first, gone for everyone

  @Test fun deleteIsAskedFirstAndCancelSendsNothing() = runTest {
    val store = Recording()
    val model = FilesModel(store)
    model.refresh()
    val f = model.state.value.files!!.first()
    // Not asked: nothing.
    assertFalse(model.confirmDelete("Deleted for everyone"))
    model.askDelete(f)
    assertEquals(f, model.state.value.asking)
    model.cancelDelete()
    assertFalse(model.confirmDelete("Deleted for everyone"))
    assertTrue("nothing was sent", store.calls.none { it.startsWith("delete") })
    assertEquals(4, model.state.value.files?.size)
  }

  @Test fun deleteConfirmedRemovesItForEveryone() = runTest {
    val store = Recording()
    val model = FilesModel(store)
    model.refresh()
    model.open("f_1")
    assertEquals("f_1", model.state.value.open?.file?.id)
    model.askDelete(model.state.value.open!!.file)
    assertTrue(model.confirmDelete("Deleted for everyone"))
    assertEquals(listOf("delete f_1"), store.calls.filter { it.startsWith("delete") })
    val s = model.state.value
    assertTrue(s.files!!.none { it.id == "f_1" })
    assertNull("its screen closes", s.open)
    assertNull(s.asking)
    assertEquals("Deleted for everyone", s.line)
    // Gone at tafs cloud: anyone reading again doesn't see it.
    assertNull(store.file("f_1"))
    assertTrue(FilesModel(store).also { it.refresh() }.state.value.files!!.none { it.id == "f_1" })
  }

  @Test fun aDeleteTafsCloudRefusesLeavesTheFile() = runTest {
    val store = Recording().apply { deletes = false }
    val model = FilesModel(store)
    model.refresh()
    model.askDelete(model.state.value.files!!.first { it.id == "f_2" })
    assertFalse(model.confirmDelete("Deleted for everyone"))
    assertEquals(4, model.state.value.files?.size)
    assertNull(model.state.value.line)
  }

  // MARK: A file

  @Test fun aFileOpensWithItsPreviewAndAGoneOneLeavesTheList() = runTest {
    val inner = StubFilesStore(now)
    val model = FilesModel(inner)
    model.refresh()
    model.open("f_3")
    val open = model.state.value.open!!
    assertTrue(open.preview is FilePreview.Text)
    assertTrue((open.preview as FilePreview.Text).text.startsWith("# Late parcels"))
    // Deleted by someone else meanwhile: off the list, nothing open.
    inner.delete("f_2")
    model.open("f_2")
    assertNull(model.state.value.open)
    assertTrue(model.state.value.files!!.none { it.id == "f_2" })
  }

  @Test fun theBytesComeFromTheShortLinkThroughThisPhone() = runTest {
    val model = FilesModel(StubFilesStore(now))
    assertEquals("Call with Nord Atelier", model.bytes("f_4")!!.decodeToString().lineSequence().first().substringBefore(','))
    assertNull(model.bytes("f_missing"))
  }

  @Test fun aFileOpenedFromAChatClosesBackToTheChat() = runTest {
    val model = FilesModel(StubFilesStore(now))
    model.refresh()
    model.open("f_1", alone = true)
    assertTrue(model.state.value.showing)
    model.closeFile()
    assertFalse(model.state.value.showing)
    // From the list: back to the list.
    model.show(taf = "taf_parcels", view = FilesView.ByRun)
    model.open("f_2")
    model.closeFile()
    assertTrue(model.state.value.showing)
    assertNull(model.state.value.open)
    assertEquals("taf_parcels", model.state.value.taf)
    assertEquals(FilesView.ByRun, model.state.value.view)
  }

  @Test fun aStoreThatAnswersNothingIsAnEmptyList() = runTest {
    val empty = object : FilesStore {
      override suspend fun list(taf: String?, run: String?, chat: String?, cursor: String?) = FilesPage(emptyList())
      override suspend fun file(id: String): FileDetail? = null
      override suspend fun download(id: String): String? = null
      override suspend fun delete(id: String) = false
      override suspend fun fetch(url: String): ByteArray? = null
    }
    val model = FilesModel(empty)
    assertNull("not read yet", model.state.value.files)
    model.refresh()
    assertEquals(emptyList<KeptFile>(), model.state.value.files)
  }
}
