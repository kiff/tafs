package bot.tafs.app

import bot.tafs.app.files.FilePreview
import bot.tafs.app.files.TafsCloudFilesStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.TafsCloud
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Files over tafs cloud's routes (docs/android-app.md, Files), read from its own contract answers (contracts/files-*.json, written by
 * runtime/test/contract.ts): an answer whose shape changes fails here first, never on a phone.
 */
class TafsCloudFilesStoreTest {
  private fun answer(file: String): String =
    KiffClient.json.parseToJsonElement(File("../../contracts/$file").readText()).jsonObject["answer"]!!.toString()

  private val transport = FakeTransport()
  private val store = TafsCloudFilesStore(TafsCloud(transport, token = "at_1"))

  @Test fun theListIsTafsCloudsAnswerNewestFirstWithEachTafsName() = runTest {
    transport.answer("GET /v1/files", 200, answer("files-list.json"))
    val page = store.list()
    assertEquals(listOf("fil_00000000000000000000000000000002", "fil_00000000000000000000000000000001"), page.files.map { it.id })
    val week = page.files[1]
    assertEquals("week.csv", week.name)
    assertEquals("text/csv", week.mediaType)
    assertEquals(22L, week.size)
    assertEquals(Instant.parse("2026-11-19T08:00:00.000Z"), week.created)
    assertEquals(Instant.parse("2026-12-19T08:00:00.000Z"), week.expires)
    assertEquals("taf-0bde5000000000000000", week.tafId)
    assertEquals("Order rows", week.tafName)
    assertEquals("run-1", week.run)
    assertEquals("chat-1", week.chat)
    assertNull("a file from no chat", page.files[0].chat)
    assertNull("the last page", page.next)
  }

  @Test fun theListNarrowsAndPagesByTheLastIdRead() = runTest {
    transport.answer("GET /v1/files", 200, """{"files":[],"next":"fil_9"}""")
    val page = store.list(taf = "taf 1", run = "run-1", chat = "chat-1", cursor = "fil_8")
    assertEquals("fil_9", page.next)
    val url = transport.requestsTo("GET /v1/files").single().url
    assertTrue(url, url.endsWith("/v1/files?taf=taf%201&run=run-1&chat=chat-1&after=fil_8"))
    assertEquals("Bearer at_1", transport.requestsTo("GET /v1/files").single().headers["Authorization"])
  }

  @Test fun aFileComesWithItsPreviewAsPlainText() = runTest {
    transport.answer("GET /v1/files/fil_00000000000000000000000000000001", 200, answer("files-file.json"))
    val detail = store.file("fil_00000000000000000000000000000001")!!
    assertEquals("week.csv", detail.file.name)
    assertEquals(FilePreview.Text("name,amount\nAna,12.50\n"), detail.preview)
  }

  @Test fun aFileGoneIsNoneAndAFileWithNoPreviewHasNone() = runTest {
    // Nothing queued: a 404, a file deleted or past its 30 days.
    assertNull(store.file("fil_gone"))
    transport.answer("GET /v1/files/fil_1", 200, """{"id":"fil_1","name":"a.bin","media_type":"application/octet-stream","size":1,"created":"2026-11-19T08:00:00Z","expires":"2026-12-19T08:00:00Z"}""")
    assertEquals(FilePreview.None, store.file("fil_1")!!.preview)
  }

  @Test fun theBytesComeFromTheShortLinkReadByThisPhoneWithNoTokenOfThePersons() = runTest {
    transport.answer("GET /v1/files/fil_00000000000000000000000000000001/download", 200, answer("files-download.json"))
    transport.answer("GET /delivered/fil_00000000000000000000000000000001", 200, "name,amount\nAna,12.50\n")
    val url = store.download("fil_00000000000000000000000000000001")!!
    assertEquals("https://s3.test/delivered/fil_00000000000000000000000000000001?signed", url)
    assertEquals("name,amount\nAna,12.50\n", String(store.fetch(url)!!))
    val read = transport.requestsTo("GET /delivered/fil_00000000000000000000000000000001").single()
    assertNull("the link is signed for the one object: no bearer goes with it", read.headers["Authorization"])
  }

  @Test fun onlyAnHttpsLinkIsRead() = runTest {
    assertNull(store.fetch("http://s3.test/delivered/fil_1?signed"))
    assertNull(store.fetch("file:///data/x"))
    assertTrue(transport.requests.isEmpty())
  }

  @Test fun deleteIsTafsCloudsAnswerAndOneAlreadyGoneIsGone() = runTest {
    transport.answer("DELETE /v1/files/fil_1", 200, """{"deleted":true}""")
    assertTrue(store.delete("fil_1"))
    transport.answer("DELETE /v1/files/fil_2", 200, """{"deleted":false}""")
    assertFalse(store.delete("fil_2"))
    // Nothing queued for fil_3: a 404, already gone for everyone.
    assertTrue(store.delete("fil_3"))
  }

  @Test fun aFileWhoseTimesCantBeReadIsLeftOutNeverGuessed() = runTest {
    transport.answer("GET /v1/files", 200, """{"files":[
      {"id":"fil_ok","name":"a.txt","media_type":"text/plain","size":1,"created":"2026-11-19T08:00:00Z","expires":"2026-12-19T08:00:00Z"},
      {"id":"fil_bad","name":"b.txt","media_type":"text/plain","size":1,"created":"yesterday","expires":"2026-12-19T08:00:00Z"}]}""")
    assertEquals(listOf("fil_ok"), store.list().files.map { it.id })
  }
}
