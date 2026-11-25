package bot.tafs.app

import bot.tafs.app.kiff.CardStepCheck
import bot.tafs.app.kiff.CoachStart
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.model.Words
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Card step before Coach's first fingerprint (docs/coach.md, "Coach's tools step"): drawn only when every line came, and
 * its plain list shown = signed, failing closed ([CardStepCheck]), read from tafs cloud's own answer (contracts/setup-start.json).
 * The iPhone holds the same rule (`CardStepTests`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardStepTest {
  private val start: CoachStart =
    CoachStart.read(KiffClient.json.parseToJsonElement(File("../../contracts/setup-start.json").readText()).jsonObject["answer"]!!.jsonObject["start"])!!

  private val draft: JsonObject get() = start.draft!!.body

  /** The draft with its Cards changed by [f], as JSON. */
  private fun cards(f: (MutableList<JsonObject>) -> Unit): JsonObject {
    val list = draft["cards"]!!.jsonArray.map { it.jsonObject }.toMutableList()
    f(list)
    return JsonObject(draft + ("cards" to JsonArray(list)))
  }

  private fun JsonObject.with(key: String, value: JsonElement?): JsonObject =
    JsonObject(if (value == null) this - key else this + (key to value))

  private fun first(f: (JsonObject) -> JsonObject): JsonObject = cards { it[0] = f(it[0]) }

  private fun aggregate(card: JsonObject, f: (JsonObject) -> JsonObject): JsonObject =
    card.with("aggregates", JsonArray(listOf(f(card["aggregates"]!!.jsonArray[0].jsonObject))))

  private fun matches(body: JsonObject, step: bot.tafs.app.kiff.CoachToolsStep = start.step!!) = CardStepCheck.matches(step, body, start.agentId)

  @Test fun tafsCloudsStartIsTheCardStepAndItsListIsWhatTheDraftSigns() {
    assertTrue("the fingerprint's sentence came", start.cardStep(Words.Approval.Biometry.fingerprint))
    assertTrue("the face's sentence came", start.cardStep(Words.Approval.Biometry.face))
    assertFalse("a phone that only has its screen lock gets Start's screen as before", start.cardStep(Words.Approval.Biometry.screenLock))
    assertTrue(start.cardStepSigns)
    assertEquals(start.words!!.buttons.fingerprint, start.words!!.give(Words.Approval.Biometry.fingerprint))
    assertTrue("the locked box's line came", start.words!!.locked.isNotBlank())
    // Without `locked`, still the Card step: the box stays locked under the app's own line, never drawn open.
    assertTrue("no locked line, still the step", start.words!!.copy(locked = "").cardStep(Words.Approval.Biometry.fingerprint))
  }

  @Test fun anyChangeToASignedCardFailsClosed() {
    val changes: Map<String, JsonObject> = mapOf(
      "a higher limit" to first { c -> aggregate(c) { it.with("limit", JsonPrimitive(99_999)) } },
      "another window" to first { c -> aggregate(c) { it.with("window", JsonPrimitive("week")) } },
      "a sum" to first { c -> aggregate(c) { it.with("quantity", JsonPrimitive("sum")) } },
      "extra room on the aggregate" to first { c -> aggregate(c) { it.with("extra_room", JsonPrimitive(5)) } },
      "a limit as a string" to first { c -> aggregate(c) { it.with("limit", JsonPrimitive("500")) } },
      "a fractional limit" to first { c -> aggregate(c) { it.with("limit", JsonPrimitive(500.5)) } },
      "a boolean limit" to first { c -> aggregate(c) { it.with("limit", JsonPrimitive(true)) } },
      "a second aggregate" to first { c -> c.with("aggregates", JsonArray(c["aggregates"]!!.jsonArray + c["aggregates"]!!.jsonArray)) },
      "no aggregate" to first { c -> c.with("aggregates", JsonArray(emptyList())) },
      "a per-call cap" to first { c -> c.with("per_action_max", JsonPrimitive(1)) },
      "an extra action" to first { c -> c.with("grants", JsonArray(c["grants"]!!.jsonArray + JsonObject(mapOf("action" to JsonPrimitive("web_read"))))) },
      "a grant with more than its action" to first { c -> c.with("grants", JsonArray(listOf(JsonObject(c["grants"]!!.jsonArray[0].jsonObject + ("resource" to JsonPrimitive("*")))))) },
      "another subject" to first { it.with("subject", JsonPrimitive("someone-else")) },
      "no domain" to first { it.with("domain", null) },
      "another domain" to first { it.with("domain", JsonPrimitive("api")) },
      "on_exceed flipped" to first { it.with("on_exceed", JsonPrimitive("owner")) },
      "an unknown on_exceed" to first { it.with("on_exceed", JsonPrimitive("allow")) },
      "a hold's expiry on a refusing Card" to first { it.with("hold_expiry", JsonPrimitive("1h")) },
      "an environment" to first { it.with("environment", JsonPrimitive("live")) },
      "a renamed Card" to first { it.with("id", JsonPrimitive("coach-other")) },
      "a missing Card" to cards { it.removeAt(it.lastIndex) },
      "an extra Card" to cards { it += it[0].with("id", JsonPrimitive("coach-extra")).with("grants", JsonArray(listOf(JsonObject(mapOf("action" to JsonPrimitive("extra_tool")))))) },
      "another agent's draft" to JsonObject(draft + ("agent_id" to JsonPrimitive("not-coach"))),
      "no Cards" to JsonObject(draft + ("cards" to JsonArray(emptyList()))),
    )
    assertTrue("the draft as sent matches", matches(draft))
    for ((what, body) in changes) assertFalse("$what must fail closed", matches(body))
  }

  @Test fun aListThatDoesntSayAllOfItFailsClosed() {
    val step = start.step!!
    fun tools(f: (bot.tafs.app.kiff.CoachToolsStep.Tool) -> bot.tafs.app.kiff.CoachToolsStep.Tool) =
      step.copy(groups = step.groups.mapIndexed { i, g -> if (i == 0) g.copy(tools = g.tools.mapIndexed { j, t -> if (j == 0) f(t) else t }) else g })
    assertFalse("no limit", matches(draft, tools { it.copy(limit = null) }))
    assertFalse("another limit", matches(draft, tools { it.copy(limit = (it.limit ?: 0) + 1) }))
    assertFalse("asks flipped", matches(draft, tools { it.copy(asks = !(it.asks ?: false)) }))
    assertFalse("no tool", matches(draft, tools { it.copy(tool = null) }))
    assertFalse("a tool twice", matches(draft, step.copy(groups = step.groups + step.groups.first())))
    assertFalse("nothing shown", matches(draft, step.copy(groups = emptyList())))
  }

  @Test fun aLaterApprovalComparesOnlyTheToolsInTheDraft() {
    val step = start.step!!
    // The first Card stays out of the draft: its tools are marked not in it, the rest in it.
    val out = draft["cards"]!!.jsonArray[0].jsonObject["grants"]!!.jsonArray.map { (it.jsonObject["action"] as JsonPrimitive).content }.toSet()
    val marked = step.copy(groups = step.groups.map { g -> g.copy(tools = g.tools.map { t -> t.copy(inDraft = t.tool !in out) }) })
    val partial = cards { it.removeAt(0) }
    assertTrue("the drafted Cards, as marked", CardStepCheck.matches(marked, partial, ""))
    assertTrue(marked.marksDraft)
    assertFalse("unmarked, every tool must be in the draft", CardStepCheck.matches(step, partial, ""))
  }
}
