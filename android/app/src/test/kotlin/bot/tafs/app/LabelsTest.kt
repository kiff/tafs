package bot.tafs.app

import bot.tafs.app.kiff.Home
import bot.tafs.app.kiff.HomeHeadline
import bot.tafs.app.kiff.HomeLabel
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Card
import bot.tafs.app.model.Taf
import bot.tafs.app.views.allWords
import bot.tafs.app.views.chosenLabel
import bot.tafs.app.views.labelHeadline
import bot.tafs.app.views.labelTags
import bot.tafs.app.views.onLabel
import bot.tafs.app.views.shownLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Labels on the tafs list (docs/android-app.md, Labels): tafs cloud lists and counts them; the app only filters by them. */
class LabelsTest {
  private val money = HomeLabel("l_money", "Money", count = 5, waiting = 2, headline = HomeHeadline("2 tafs working · Money"))
  private val clients = HomeLabel("l_clients", "Clients", count = 2)
  private val support = HomeLabel("l_support", "Support", count = 1)
  private val labels = listOf(money, clients, support)
  private fun taf(name: String, vararg labels: String) = Taf(id = name, name = name, job = "", tools = emptyList(), colorIndex = 0, card = Card(emptyList(), "o"), labels = labels.toList())

  @Test fun theRowShowsTwoLabelsThenTheRestAsANumber() {
    assertEquals(listOf(money, clients) to 1, shownLabels(labels, expanded = false, selected = null))
    // "+1" opened: every label.
    assertEquals(labels to 0, shownLabels(labels, expanded = true, selected = null))
    // Two or fewer: all show, with no "+N".
    assertEquals(listOf(money, clients) to 0, shownLabels(listOf(money, clients), expanded = false, selected = null))
    // The chosen one further on stays in sight.
    val five = labels + HomeLabel("l_a", "A") + HomeLabel("l_b", "B")
    assertEquals(five to 0, shownLabels(five, expanded = false, selected = "l_b"))
  }

  @Test fun aLabelFiltersTheListToItsTafs() {
    val tafs = listOf(taf("Bills in", "l_money"), taf("New clients", "l_money", "l_clients"), taf("Support replies", "l_support"), taf("Unlabelled"))
    assertEquals(tafs, onLabel(tafs, null))
    assertEquals(listOf("Bills in", "New clients"), onLabel(tafs, "l_money").map { it.name })
    assertEquals(listOf("New clients"), onLabel(tafs, "l_clients").map { it.name })
  }

  @Test fun aTafShowsItsLabelsByNameInTheAccountsOrder() {
    assertEquals(listOf("Money", "Clients"), labelTags(taf("New clients", "l_clients", "l_money"), labels))
    // An id the account no longer lists shows nothing.
    assertEquals(emptyList<String>(), labelTags(taf("Old", "l_gone"), labels))
  }

  @Test fun theFilteredHeadlineIsTafsCloudsElseTheLabelsName() {
    assertEquals("2 tafs working · Money", labelHeadline(money))
    assertEquals("Clients", labelHeadline(clients))
  }

  // The list opens on All; a label that went with a refresh falls back to All.
  @Test fun aLabelNoLongerListedFallsBackToAll() {
    assertEquals(money, chosenLabel(labels, "l_money"))
    assertNull(chosenLabel(labels, "l_gone"))
    assertNull(chosenLabel(labels, null))
  }

  @Test fun allIsTafsCloudsWordElseTheApps() {
    assertEquals("Toutes", allWords("Toutes"))
    assertEquals("All", allWords(null))
  }

  @Test fun homeReadsTheLabelsAndEachTafsLabels() {
    val home = KiffClient.json.decodeFromString<Home>(
      """{"tafs":[{"id":"taf_1","kiff_agent":"bills","name":"Bills in","labels":["l_money"]}],"others":[],"removing":[],
         "labels":[{"id":"l_money","name":"Money","color":"money","count":5,"waiting":2,"headline":{"words":"2 tafs working · Money"}}],
         "labels_words":{"all":"All"}}""")
    assertEquals(listOf("l_money"), home.tafs.single().labels)
    assertEquals(money.copy(color = "money"), home.labels!!.single())
    assertEquals("All", home.labelsWords?.all)
    // A tafs cloud from before labels: none, and no row.
    assertNull(KiffClient.json.decodeFromString<Home>("""{"tafs":[],"others":[],"removing":[]}""").labels)
  }
}
