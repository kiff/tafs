package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.FilePrep
import bot.tafs.app.model.FileProblem
import bot.tafs.app.model.PendingFile
import bot.tafs.app.model.PreparedFile
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Photos and files on a message (docs/attachments.md): what may go, the tray, sending them, and opening one again. */
class AttachmentsTest {
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val opened: File = Files.createTempDirectory("opened").toFile()
  private val declare = "POST /v1/tafs/taf_1/attachments"
  private val send = "POST /v1/tafs/taf_1/messages"
  private val att1 = "att_" + "1".repeat(32)
  private val att2 = "att_" + "2".repeat(32)

  private fun reads(thread: String = """{"messages":[]}""") {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":["cards.manage","holds.answer"]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/tafs/taf_1/conversation", 200, thread)
  }

  /** tafs cloud takes a file: its ref, and where its bytes go. */
  private fun declared(id: String, name: String = "receipt.pdf", type: String = "application/pdf", size: Int = 3) =
    transport.answer(
      declare, 200,
      """{"attachment":{"id":"$id","kind":"pdf","media_type":"$type","name":"$name","size":$size},
          "upload":{"url":"https://storage.example/up/$id?sig=s","method":"PUT","headers":{"Content-Type":"$type","x-amz-checksum-sha256":"c"}}}""",
    )

  private suspend fun TestScope.model(): AppModel {
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    return testModel(transport = transport, secrets = secrets, clock = { t0 }, start = false, scope = backgroundScope, openedFiles = opened)
      .also { it.restore() }
  }

  private val pdf = PreparedFile("receipt.pdf", "application/pdf", "pdf".toByteArray())

  private fun body(key: String, i: Int = 0) = KiffClient.json.parseToJsonElement(String(transport.requestsTo(key)[i].body!!)).jsonObject

  // MARK: What may go

  @Test fun eachTypeHasItsLimitAndTheRestCantBeAttached() {
    assertEquals("application/pdf", FilePrep.mediaType("application/pdf", "a.pdf"))
    assertEquals("text/csv", FilePrep.mediaType("text/comma-separated-values", "a.csv"))
    assertEquals("text/markdown", FilePrep.mediaType(null, "notes.md"))
    assertEquals(FilePrep.WORD, FilePrep.mediaType("application/octet-stream", "letter.docx"))
    assertEquals(FilePrep.EXCEL, FilePrep.mediaType(null, "sheet.xlsx"))
    assertEquals("image/heic", FilePrep.mediaType("image/heic", "IMG_1.HEIC"))
    assertNull(FilePrep.mediaType("application/zip", "a.zip"))
    try {
      FilePrep.file("a.zip", ByteArray(1), FilePrep.mediaType("application/zip", "a.zip"))
      fail("a zip was taken")
    } catch (e: FileProblem) {
      assertEquals("a.zip can't be attached. Photos, PDFs, text, CSV, Markdown, Word and Excel files can.", e.message)
    }
  }

  @Test fun aFileOverItsLimitIsRefusedSayingWhich() {
    try {
      FilePrep.check("notes.txt", 3L shl 20, "text/plain")
      fail("a text file over 2 MB was taken")
    } catch (e: FileProblem) {
      assertEquals("notes.txt is 3.0 MB; the most is 2.0 MB.", e.message)
    }
    FilePrep.check("scan.pdf", 25L shl 20, "application/pdf")
    assertEquals(25L shl 20, FilePrep.LARGEST)
  }

  @Test fun aPhotoIsDrawnNoLargerThanTheModelReadsAndNamedAsAJpeg() {
    assertEquals(2576 to 1932, FilePrep.scaledSize(4032, 3024, FilePrep.LONG_EDGE))
    assertEquals(1932 to 2576, FilePrep.scaledSize(3024, 4032, FilePrep.LONG_EDGE))
    assertEquals(800 to 600, FilePrep.scaledSize(800, 600, FilePrep.LONG_EDGE))
    assertEquals("IMG_0042.jpg", FilePrep.photoName("IMG_0042.HEIC"))
    assertEquals("Photo.jpg", FilePrep.photoName(""))
  }

  @Test fun aFilesHashIsItsSha256InLowercaseHexAndItsSizeReadsInWords() {
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FilePrep.sha256("abc".toByteArray()))
    assertEquals("1 KB", FilePrep.sizeWords(3))
    assertEquals("240 KB", FilePrep.sizeWords(240 * 1024))
    assertEquals("1.5 MB", FilePrep.sizeWords(3L shl 19))
  }

  // MARK: The tray

  @Test fun aFileIsDeclaredThenUploadedStraightToStorageWithoutTheSession() = runTest {
    reads(); declared(att1)
    transport.answer("PUT /up/$att1", 200)
    val m = model()
    m.attach(pdf, "refunds-1")
    val sent = body(declare)
    assertEquals("application/pdf", sent.getValue("media_type").jsonPrimitive.content)
    assertEquals("receipt.pdf", sent.getValue("name").jsonPrimitive.content)
    assertEquals("3", sent.getValue("size").jsonPrimitive.content)
    assertEquals(FilePrep.sha256("pdf".toByteArray()), sent.getValue("sha256").jsonPrimitive.content)
    val put = transport.requestsTo("PUT /up/$att1").single()
    assertEquals("https://storage.example/up/$att1?sig=s", put.url)
    assertEquals("pdf", String(put.body!!))
    assertEquals("c", put.headers["x-amz-checksum-sha256"])
    assertNull("no KIFF session goes to storage", put.headers["Authorization"])
    assertEquals(att1, m.state.value.files("refunds-1").single().ref?.id)
  }

  @Test fun aFileTafsCloudRefusesSaysWhyInTheTrayAndNeverGoesUp() = runTest {
    reads()
    transport.answer(declare, 400, """{"error":"that type of file can't be attached"}""")
    val m = model()
    m.attach(pdf, "refunds-1")
    assertEquals(PendingFile.State.Failed(bot.tafs.app.model.FilePrep.NOT_TAKEN), m.state.value.files("refunds-1").single().state)
    assertTrue(transport.requests.none { it.method == "PUT" })
  }

  @Test fun anUploadThatFailsSaysToAttachItAgain() = runTest {
    reads(); declared(att1)
    transport.answer("PUT /up/$att1", 403, "<Error/>")
    val m = model()
    m.attach(pdf, "refunds-1")
    assertEquals(PendingFile.State.Failed("The file didn't upload. Try attaching it again."), m.state.value.files("refunds-1").single().state)
  }

  @Test fun aMessageCarriesAtMostFiveFiles() = runTest {
    reads(); declared(att1)
    transport.answer("PUT /up/$att1", 200)
    val m = model()
    repeat(6) { m.attach(pdf, "refunds-1") }
    assertEquals(5, m.state.value.files("refunds-1").size)
    assertEquals(5, transport.requestsTo(declare).size)
    assertEquals("A message carries at most 5 files.", m.state.value.fileNotices["refunds-1"])
  }

  @Test fun aFileTakenOutOfTheTrayDoesntGo() = runTest {
    reads(); declared(att1)
    transport.answer("PUT /up/$att1", 200)
    val m = model()
    m.attach(pdf, "refunds-1")
    m.removeFile(m.state.value.files("refunds-1").single().id, "refunds-1")
    assertTrue(m.state.value.files("refunds-1").isEmpty())
  }

  @Test fun theSampleAndATrialsChatTakeNoFiles() = runTest {
    val sample = testModel()
    assertFalse(sample.state.value.canAttach(sample.state.value.tafs.first().id))
    reads()
    val m = model()
    assertTrue(m.state.value.canAttach("refunds-1"))
    assertFalse(m.state.value.canAttach(bot.tafs.app.app.trialChatId("refunds-1")))
  }

  // MARK: Sending

  @Test fun sendTakesTheReadyFilesWithTheWordsAndLeavesTheFailedInTheTray() = runTest {
    reads(); declared(att1)
    transport.answer("PUT /up/$att1", 200)
    transport.answer(send, 200, """{"session":"s1","new_session":true}""")
    val m = model()
    m.attach(pdf, "refunds-1")
    transport.answer(declare, 400, """{"error":"too big"}""")
    m.attach(pdf, "refunds-1")
    assertTrue(m.deliverChat("Refund this one", "refunds-1", withFiles = true))
    val sent = body(send)
    assertEquals("Refund this one", sent.getValue("text").jsonPrimitive.content)
    assertEquals(listOf(att1), sent.getValue("attachments").jsonArray.map { it.jsonPrimitive.content })
    // The failed file stays, saying why; the sent one shows on the message at once.
    assertTrue(m.state.value.files("refunds-1").single().state is PendingFile.State.Failed)
    val shown = m.state.value.chats["refunds-1"].orEmpty().last { it.from == ChatMessage.From.owner }
    assertEquals(listOf(att1), shown.attachments.map { it.id })
  }

  @Test fun aMessageMayBeFilesAlone() = runTest {
    reads(); declared(att1)
    transport.answer("PUT /up/$att1", 200)
    transport.answer(send, 200, """{"session":"s1","new_session":true}""")
    val m = model()
    m.attach(pdf, "refunds-1")
    assertTrue(m.deliverChat("", "refunds-1", withFiles = true))
    assertEquals("", body(send).getValue("text").jsonPrimitive.content)
    assertTrue(m.state.value.files("refunds-1").isEmpty())
  }

  @Test fun sendWaitsWhileAFileIsStillGoingUp() = runTest {
    reads(); declared(att1)
    transport.answer("PUT /up/$att1", 200)
    transport.answer(send, 200, """{"session":"s1","new_session":true}""")
    // Its upload hasn't answered yet.
    transport.hold("PUT /up/$att1")
    val m = model()
    val going = launch { m.attach(pdf, "refunds-1") }
    testScheduler.runCurrent()
    assertEquals(PendingFile.State.Uploading, m.state.value.files("refunds-1").single().state)
    assertTrue(m.state.value.holdsSend("refunds-1"))
    assertFalse(m.deliverChat("Here it is", "refunds-1", withFiles = true))
    assertTrue(transport.requestsTo(send).isEmpty())
    // Up: now it goes.
    transport.release("PUT /up/$att1")
    going.join()
    assertFalse(m.state.value.holdsSend("refunds-1"))
    assertTrue(m.deliverChat("Here it is", "refunds-1", withFiles = true))
  }

  @Test fun filesNeverGoIntoARunAtWork() = runTest {
    reads(thread = """{"messages":[],"working":{"session":"s1","run":"run_1"}}""")
    transport.answer("GET /v1/tafs/taf_1/runs/run_1/stream", 404, """{"error":"not_found"}""")
    declared(att1)
    transport.answer("PUT /up/$att1", 200)
    val m = model()
    m.loadChat("refunds-1")
    m.attach(pdf, "refunds-1")
    assertTrue(m.state.value.holdsSend("refunds-1"))
    assertFalse(m.deliverChat("And this", "refunds-1", withFiles = true))
    assertTrue(transport.requestsTo("POST /v1/tafs/taf_1/steer").isEmpty())
    assertEquals(1, m.state.value.files("refunds-1").size)
  }

  @Test fun aSendThatFailsPutsItsFilesBackInTheTray() = runTest {
    reads(); declared(att1)
    transport.answer("PUT /up/$att1", 200)
    transport.answer(send, 503, """{"error":"unavailable"}""")
    val m = model()
    m.attach(pdf, "refunds-1")
    assertFalse(m.deliverChat("Refund this one", "refunds-1", withFiles = true))
    assertEquals(att1, m.state.value.files("refunds-1").single().ref?.id)
  }

  @Test fun sendAgainSendsTheWordsOnly() = runTest {
    reads(); declared(att1)
    transport.answer("PUT /up/$att1", 200)
    transport.answer(send, 200, """{"session":"s1","new_session":false}""")
    val m = model()
    m.attach(pdf, "refunds-1")
    assertTrue(m.deliverChat("Again", "refunds-1"))
    assertFalse(body(send).containsKey("attachments"))
    assertEquals(1, m.state.value.files("refunds-1").size)
  }

  // MARK: In the thread

  @Test fun aMessagesFilesAreReadFromTheThreadAndOneTheAppCantReadIsLeftOut() = runTest {
    reads(
      thread = """{"messages":[{"at":"2026-10-28T08:00:00Z","from":"owner","text":"","session":"s1",
        "attachments":[{"id":"$att1","kind":"image","media_type":"image/jpeg","name":"Photo 1.jpg","size":240000},{"id":7}]}]}""",
    )
    val m = model()
    m.loadChat("refunds-1")
    val files = m.state.value.chats["refunds-1"].orEmpty().single().attachments
    assertEquals(listOf("Photo 1.jpg"), files.map { it.name })
    assertEquals(240000L, files.single().size)
  }

  @Test fun aSentFileOpensFromItsOwnDownloadAndIsGoneAfterSignOut() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/attachments/$att2", 200, """{"url":"https://storage.example/down/$att2?sig=s"}""")
    transport.answer("GET /down/$att2", 200, "the receipt")
    val m = model()
    val ref = bot.tafs.app.model.AttachmentRef(att2, "pdf", "application/pdf", "../receipt.pdf", 11)
    val copy = m.openFile(ref, "refunds-1")!!
    assertEquals("the receipt", copy.readText())
    // Under its own name, inside the app's folder only.
    assertEquals("receipt.pdf", copy.name)
    assertTrue(copy.canonicalPath.startsWith(opened.canonicalPath))
    m.signOut(tellKiff = false)
    assertFalse(copy.exists())
  }

  @Test fun aFileThatCantBeOpenedSaysSo() = runTest {
    reads()
    transport.answer("GET /v1/tafs/taf_1/attachments/$att2", 404, """{"error":"not found"}""")
    val m = model()
    assertNull(m.openFile(bot.tafs.app.model.AttachmentRef(att2, "pdf", "application/pdf", "receipt.pdf", 11), "refunds-1"))
    assertEquals("receipt.pdf couldn't be opened. This phone and the server didn't understand each other. Nothing to do on your side; we're on it.", m.state.value.fileNotices["refunds-1"])
  }
}
