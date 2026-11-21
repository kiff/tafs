package bot.tafs.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.CardTotal
import bot.tafs.app.model.Money
import bot.tafs.app.model.Res
import bot.tafs.app.model.TafCard
import bot.tafs.app.model.Window
import bot.tafs.app.model.Words
import bot.tafs.app.views.CARD_REVOKED
import bot.tafs.app.views.TafCardFace
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A revoked Card's face (the emulator's Revoke, 21 Nov): it says Revoked, with no "No end date" beneath and no "left today" on
 * its face, which read as if it still ran. A Card in use keeps both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RevokedCardFaceTest {
  @get:Rule val compose = createComposeRule()

  private val card = TafCard(
    "card_1", "Refunds",
    rules = listOf(CardRule(action = "create_refund", label = "Refund a payment", perAction = Money(15000, "EUR", 2))),
    total = CardTotal(Money(200000, "EUR", 2), Money(4000, "EUR", 2), Window.day),
  )

  private fun show(revoked: Boolean) {
    RuntimeEnvironment.setQualifiers("en-w411dp-h900dp-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
    compose.setContent { Column(Modifier.width(320.dp)) { TafCardFace(card, "You", androidx.compose.ui.graphics.Color.Blue, revoked = revoked) } }
  }

  @Test fun aRevokedCardSaysRevokedWithNoEndDateAndNothingLeft() {
    show(revoked = true)
    compose.onNodeWithTag(CARD_REVOKED, useUnmergedTree = true).assertExists()
    compose.onAllNodesWithText(Words.Card.noEndDate, useUnmergedTree = true).assertCountEquals(0)
    compose.onAllNodesWithText(card.hero!!.words, useUnmergedTree = true).assertCountEquals(0)
  }

  @Test fun aCardInUseKeepsItsEndAndWhatsLeft() {
    show(revoked = false)
    compose.onAllNodesWithText(Words.Card.noEndDate, useUnmergedTree = true).assertCountEquals(1)
    compose.onAllNodesWithText(card.hero!!.words, useUnmergedTree = true).assertCountEquals(1)
  }
}
