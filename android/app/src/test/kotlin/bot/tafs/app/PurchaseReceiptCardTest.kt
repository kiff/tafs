package bot.tafs.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.ActivityCall
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.KiffMapping
import bot.tafs.app.kiff.PurchaseReceipt
import bot.tafs.app.model.Res
import bot.tafs.app.model.Words
import bot.tafs.app.views.LocalPurchaseReceipt
import bot.tafs.app.views.ReceiptSheet
import java.io.File
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A purchase's line in the Record (docs/purchases.md): KIFF names its case, and its sheet draws KIFF's signed receipt in KIFF's
 * words, with the phone's check under it. A receipt whose key this app doesn't hold is never shown as checked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h900dp-port")
class PurchaseReceiptCardTest {
  @get:Rule val compose = createComposeRule()

  private val value = KiffClient.json.parseToJsonElement(File("../../contracts/purchase.json").readText()).jsonObject
    .getValue("receipt").jsonObject.getValue("value").jsonObject

  private val entry = KiffMapping.record(
    listOf(
      KiffClient.json.decodeFromString<ActivityCall>(
        """{"proposal_id":"p1","action_name":"issue_card","title":"Buy","actor_id":"a-1","outcome":"allowed","created_at":"2026-11-28T09:16:52Z",
          "case_id":"${value.getValue("case_id").toString().trim('"')}"}""",
      ),
    ),
    setOf("a-1"),
  ).single()

  @Test fun aPurchasesLineNamesItsCase() {
    assertEquals(value.getValue("case_id").toString().trim('"'), entry.purchaseCase)
  }

  private fun draw(check: PurchaseReceipt.Check) {
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    val r = PurchaseReceipt.read(value)!!
    compose.setContent {
      TafsTheme {
        CompositionLocalProvider(LocalPurchaseReceipt provides { _ -> r to check }) {
          ReceiptSheet(entry, AppState(mode = Mode.kiff)) {}
        }
      }
    }
  }

  @Test fun itDrawsKiffsWordsAndSaysCheckedOnlyWhenItIs() {
    draw(PurchaseReceipt.Check.Verified)
    compose.onNodeWithText("Bought").assertExists()
    compose.onNodeWithText("Order PL-2026-118204 confirmed by the shop.").assertExists()
    compose.onNodeWithText(Words.Receipt.checkedOnDevice(bot.tafs.app.model.ThisDevice.word)).assertExists()
  }

  @Test fun aKeyThisAppDoesntHoldIsNeverShownAsChecked() {
    draw(PurchaseReceipt.Check.UnknownKey)
    compose.onNodeWithText("Bought").assertExists()
    compose.onNodeWithText(Words.Receipt.Check.unknownSignature).assertExists()
    compose.onAllNodesWithText(Words.Receipt.checkedOnDevice(bot.tafs.app.model.ThisDevice.word)).assertCountEquals(0)
  }

  @Test fun aChangedReceiptSaysItDoesntCheckOut() {
    draw(PurchaseReceipt.Check.Changed)
    compose.onNodeWithText(Words.Receipt.doesntCheckOut).assertExists()
    compose.onAllNodesWithText(Words.Receipt.checkedOnDevice(bot.tafs.app.model.ThisDevice.word)).assertCountEquals(0)
  }
}
