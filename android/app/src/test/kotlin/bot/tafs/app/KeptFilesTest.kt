package bot.tafs.app

import bot.tafs.app.app.KEPT_FILES
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sign-out leaves nothing of the person's files on the phone: what they opened, their export, and the copy of a delivered file
 * Files' Share handed to another app (`kept-files`), so Files' "no copy kept" holds for the phone too (docs/data.md).
 */
class KeptFilesTest {
  @Test fun signOutClearsTheCopiesFilesSharedFromThePhone() = runTest {
    val cache = kotlin.io.path.createTempDirectory("cache").toFile()
    val opened = File(cache, "attachments").apply { mkdirs() }
    val shared = File(cache, "$KEPT_FILES/f_1").apply { mkdirs() }
    File(shared, "Tickets answered.csv").writeText("Item,Done\n#7781,09:04\n")
    File(opened, "a_1").apply { mkdirs() }.resolve("receipt.pdf").writeText("pdf")
    // Something else in the cache that isn't the person's files stays.
    val logos = File(cache, "logos").apply { mkdirs() }.resolve("stripe.png").apply { writeText("png") }

    val model = testModel(start = false, openedFiles = opened)
    model.signOut(tellKiff = false)

    assertFalse("a shared copy stays on the phone after sign-out", File(cache, KEPT_FILES).exists())
    assertFalse("an opened file stays on the phone after sign-out", opened.exists())
    assertTrue(logos.exists())
    cache.deleteRecursively()
  }
}
