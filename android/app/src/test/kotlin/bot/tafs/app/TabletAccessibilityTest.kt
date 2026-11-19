package bot.tafs.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.app.design.LocalBiometry
import bot.tafs.app.design.TafsTheme
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.ShownComponent
import bot.tafs.app.model.CardRule
import bot.tafs.app.model.Money
import bot.tafs.app.model.Res
import bot.tafs.app.model.TafCard
import bot.tafs.app.model.Words
import bot.tafs.app.views.CoachActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tablet's screens with TalkBack and the largest text (docs/android-app.md, Tablets): every control says what it is and what
 * it does, Coach's panel works without a drag, and nothing is cut off at twice the text size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class TabletAccessibilityTest {
  @get:Rule val compose = createComposeRule()

  private val inSidebar = hasAnyAncestor(hasTestTag("sidebar"))

  private fun words() {
    RuntimeEnvironment.setQualifiers("en-w1366dp-h1024dp-land-xhdpi")
    Res.install(bot.tafs.app.design.AndroidWords(ApplicationProvider.getApplicationContext()))
  }

  /** Android's font size, as a person sets it: [scale] times the text, nothing else. */
  @Composable
  private fun AtTextSize(scale: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) { TafsTheme { content() } }
  }

  private fun showApp(scale: Float = 1f) {
    words()
    val model = testModel(clock = { java.time.Instant.now() }).also { it.showForScreenshots() }
    compose.setContent {
      AtTextSize(scale) {
        CompositionLocalProvider(LocalBiometry provides "your fingerprint") {
          bot.tafs.app.views.ProvideApprovalReadiness(model.state.value.mode, bot.tafs.app.model.ApprovalReadiness.Ready, recheck = {}) {
            SignedInView(model, onSignIn = {})
          }
        }
      }
    }
    compose.waitForIdle()
  }

  private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { all(it) }

  /** What TalkBack would read for each control without a role or a name, on the screen as it is now. */
  private fun unnamed(screen: String): List<String> {
    compose.waitForIdle()
    return all(compose.onRoot().fetchSemanticsNode())
      // A text field is announced as one by Android itself.
      .filter { it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsActions.SetText) }
      .mapNotNull { n ->
        val role = n.config.getOrNull(SemanticsProperties.Role)
        val name = listOfNotNull(
          n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
          n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
        ).joinToString(" ").ifBlank { null }
        if (role == null || name == null) "$screen: role=$role name=$name" else null
      }
  }

  private fun open(text: String) {
    compose.onAllNodes(hasText(text) and inSidebar and hasClickAction())[0].performClick()
    compose.waitForIdle()
  }

  /** At twice the text size, every control on the tablet's screens has a role and a name: the sidebar, the Overview, Record, a
   *  taf's four tabs, Account beside the sidebar and Coach's panel. */
  @Test fun everyControlOnTheTabletsScreensHasARoleAndAName() {
    showApp(scale = 2f)
    val found = mutableListOf<String>()
    found += unnamed("overview")
    open(Words.Record.title); found += unnamed("record")
    open("Support replies"); found += unnamed("taf")
    listOf("Activity", "Card", "Setup").forEach { tab ->
      compose.onAllNodes(hasText(tab) and hasClickAction() and !inSidebar)[0].performClick(); found += unnamed("taf $tab")
    }
    open(Words.Settings.title); found += unnamed("account")
    compose.onNode(hasContentDescription(Words.Coach.coachMode) and inSidebar).performClick(); found += unnamed("coach")
    assertTrue(found.distinct().joinToString("\n"), found.isEmpty())
  }

  /** A taf's tabs read as tabs, the one shown selected ("Card, tab, selected"). */
  @Test fun aTafsTabsAreTabsWithTheOneShownSelected() {
    showApp()
    open("Support replies")
    fun tab(name: String) = compose.onNode(hasText(name) and hasClickAction() and !inSidebar)
    compose.onNode(hasText("Card") and hasClickAction() and !inSidebar).performClick()
    compose.waitForIdle()
    tab("Card").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    tab("Activity").assertIsNotSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
  }

  /** The theme is one of three: each a radio button, the chosen one selected. */
  @Test fun theThemeIsOneOfThreeWithTheChosenOneSelected() {
    showApp()
    open(Words.Settings.title)
    val choices = listOf(Words.Settings.Theme.light, Words.Settings.Theme.dark, Words.Settings.Theme.system)
      .map { compose.onNode(hasText(it) and hasClickAction()).fetchSemanticsNode() }
    choices.forEach { assertEquals(Role.RadioButton, it.config.getOrNull(SemanticsProperties.Role)) }
    assertEquals(1, choices.count { it.config.getOrNull(SemanticsProperties.Selected) == true })
  }

  /** Coach's questions say whether an answer is picked: a box where several may be, one of a few otherwise. */
  @Test fun coachsAnswersSayWhetherTheyArePicked() {
    words()
    val card = KiffClient.json.decodeFromString(SetupComponent.serializer(), """{"type":"questions","id":"q","title":"A few questions","questions":[
      {"id":"mode","text":"Which mode?","options":[{"value":"test","label":"Test mode"},{"value":"live","label":"Live"}]},
      {"id":"which","text":"Which refunds?","multi":true,"options":[{"value":"small","label":"Small"},{"value":"big","label":"Big"}]}]}""")
    compose.setContent {
      TafsTheme {
        bot.tafs.app.views.CoachCard(
          ShownComponent(java.time.Instant.EPOCH, "s", "r", card, null, "k"), AppState(mode = Mode.kiff, ready = true),
          CoachActions(answer = { _, _, _ -> }, approve = { _, _, _ -> }, cancelSignIn = {}),
        )
      }
    }
    compose.waitForIdle()
    val test = compose.onNode(hasText("Test mode") and hasClickAction())
    test.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsNotSelected()
    test.performClick(); compose.waitForIdle()
    test.assertIsSelected()
    val small = compose.onNode(hasText("Small") and hasClickAction())
    small.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertIsNotSelected()
    small.performClick(); compose.waitForIdle()
    small.assertIsSelected()
  }

  /** Coach's panel without a drag: its edge is a button, and TalkBack's tap switches it to the whole screen and back. */
  @Test fun coachsPanelEdgeIsAButtonThatWidensItWithoutADrag() {
    words()
    compose.setContent {
      TafsTheme {
        bot.tafs.app.views.CoachPanel(open = true, onClose = {}) { full, _ -> Text(if (full) "whole screen" else "a third", Modifier.testTag("width")) }
      }
    }
    compose.waitForIdle()
    val edge = compose.onNode(hasContentDescription(Words.Coach.coachModeWidth))
    edge.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    compose.onNode(hasText("a third")).assertIsDisplayed()
    edge.performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
    compose.onNode(hasText("whole screen")).assertIsDisplayed()
    edge.performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
    compose.onNode(hasText("a third")).assertIsDisplayed()
  }

  /** A sent message's menu: on a long press, and TalkBack's long press, with no tap offered that would do nothing. */
  @Test fun aSentMessagesMenuOpensOnALongPressWithNoEmptyTap() {
    showApp()
    open("Support replies")
    val bubble = compose.onNode(hasText("How did this morning go?"))
    val node = bubble.fetchSemanticsNode()
    assertFalse(node.config.contains(SemanticsActions.OnClick))
    bubble.performSemanticsAction(SemanticsActions.OnLongClick); compose.waitForIdle()
    compose.onNode(hasText(Words.Chat.sendAgain)).assertIsDisplayed()
  }

  /** A Card as the sample's Refunds has it: its money left today, a limit for each refund, and its tools. */
  private val card = TafCard(
    "card_1", "Refunds",
    rules = listOf(
      CardRule(action = "create_refund", label = "Refund a payment", perAction = Money(15000, "EUR", 2), perWindow = Money(200000, "EUR", 2)),
      CardRule(action = "create_credit", label = "Credit an account", perAction = Money(15000, "EUR", 2)),
      CardRule(action = "list_charges", label = "Read charges"),
    ),
    total = bot.tafs.app.model.CardTotal(Money(200000, "EUR", 2), Money(4000, "EUR", 2), bot.tafs.app.model.Window.day),
  )

  /** A Card bar's small Card carries the Card's mark, never an empty coloured block, and its name reads on up to two lines. */
  @Test fun aCardBarsSmallCardHasItsMark() {
    words()
    val long = card.copy(title = "Lister vos litiges Stripe (mode test) pour la boutique de Lyon")
    compose.setContent { Column(Modifier.width(320.dp)) { bot.tafs.app.views.CardBar(long, 3, androidx.compose.ui.graphics.Color.Blue) {} } }
    compose.waitForIdle()
    compose.onNode(hasTestTag(bot.tafs.app.views.CARD_BAR_MARK), useUnmergedTree = true).assertExists()
    compose.onNode(hasText(long.title, substring = true), useUnmergedTree = true).assertExists()
  }

  /** The usual face, the face at twice the text size and a stack at twice: each Card's face, and the stack's foot below its front Card's. */
  private fun cards(): Triple<androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect, Float> {
    words()
    compose.setContent {
      Column {
        AtTextSize(1f) { Column(Modifier.width(300.dp).testTag("usual")) { bot.tafs.app.views.TafCardFace(card, "You", androidx.compose.ui.graphics.Color.Blue) } }
        AtTextSize(2f) { Column(Modifier.width(300.dp).testTag("large")) { bot.tafs.app.views.TafCardFace(card, "You", androidx.compose.ui.graphics.Color.Blue) } }
        AtTextSize(2f) {
          Column(Modifier.width(300.dp)) {
            bot.tafs.app.views.CardStack(listOf(card, card.copy(id = "card_2", title = "Payouts")), "You", androidx.compose.ui.graphics.Color.Blue, "card_1", {}, modifier = Modifier.testTag("stack"))
          }
        }
      }
    }
    compose.waitForIdle()
    fun face(tag: String) = compose.onAllNodes(hasContentDescription(Words.Card.issuedByNamed("You"), substring = true) and hasAnyAncestor(hasTestTag(tag)))
      .fetchSemanticsNodes().last().boundsInRoot
    val front = face("stack")
    return Triple(face("usual"), face("large"), compose.onNode(hasTestTag("stack")).fetchSemanticsNode().boundsInRoot.bottom - front.bottom)
  }

  /** A Card keeps a card's shape at the usual text size; at twice the size it grows rather than cut off who issued it and until
   *  when, and a stack grows with it, its front Card's foot inside the stack, never over what follows. */
  @Test fun aCardKeepsItsShapeAndGrowsOnlyForLargerText() {
    val (usual, large, belowFront) = cards()
    assertEquals(usual.width / (85.6f / 54f), usual.height, 1.5f)
    assertTrue("${large.height} isn't taller than ${usual.height}", large.height > usual.height + 1f)
    assertTrue("the front Card's foot is ${-belowFront} below the stack's", belowFront >= -0.5f)
  }
}
