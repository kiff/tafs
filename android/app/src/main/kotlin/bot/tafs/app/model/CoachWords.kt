package bot.tafs.app.model

import bot.tafs.app.kiff.SetupStep
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.SetupQuestion
import bot.tafs.app.kiff.ShownComponent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * An owner's answer to a card in the form the app sent before cards were answered as data, "[<type> <id>] <answer>": read only to
 * show older Coach threads in the owner's words, since tafs cloud's thread still holds them as written. Nothing sends it now.
 */
data class CoachAnswer(val type: String, val id: String, val text: String) {
  val failed: Boolean get() = text.startsWith("failed")

  companion object {
    /** Null when the message isn't an answer to a card. */
    fun parse(message: String): CoachAnswer? {
      if (!message.startsWith("[")) return null
      val close = message.indexOf(']')
      if (close < 0) return null
      val head = message.substring(1, close).split(" ", limit = 2)
      if (head.size != 2 || head[0] !in SetupComponent.KINDS) return null
      return CoachAnswer(head[0], head[1], message.substring(close + 1).trim())
    }
  }
}

/**
 * The owner's answers to a questions card, as one message to the agent: `{"<question>": {"value": …} | {"values": […]} |
 * {"own": "…"}}`. Each answer is a choice of the agent's, several (a multi question), or the owner's own words.
 */
object QuestionAnswers {
  sealed interface Answer {
    data class Choice(val value: String) : Answer
    data class Choices(val values: List<String>) : Answer
    data class Own(val text: String) : Answer
  }

  /** The message's answer text: JSON, keys sorted, so the same answers send the same words. */
  fun encode(answers: Map<String, Answer>): String {
    val out = buildJsonObject {
      for (id in answers.keys.sorted()) {
        put(id, buildJsonObject {
          when (val a = answers.getValue(id)) {
            is Answer.Choice -> put("value", a.value)
            is Answer.Choices -> put("values", buildJsonArray { a.values.forEach { add(JsonPrimitive(it)) } })
            is Answer.Own -> put("own", a.text)
          }
        })
      }
    }
    return KiffClient.json.encodeToString(JsonObject.serializer(), out)
  }

  fun decode(text: String): Map<String, Answer>? {
    val obj = runCatching { KiffClient.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val out = mutableMapOf<String, Answer>()
    for ((id, a) in obj) {
      val o = (a as? JsonObject) ?: return null
      val value = (o["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
      val values = (o["values"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      val own = (o["own"] as? JsonPrimitive)?.takeIf { it.isString }?.content
      when {
        value != null -> out[id] = Answer.Choice(value)
        values != null -> out[id] = Answer.Choices(values)
        own != null -> out[id] = Answer.Own(own)
      }
    }
    return out
  }

  /** The answers in the owner's words, in the card's order: "Test mode · Just one · In your words: “300 on weekdays”". Null when the text isn't answers. */
  fun words(text: String, questions: List<SetupQuestion>): String? {
    val answers = decode(text) ?: return null
    val order = questions.map { it.id } + answers.keys.filter { id -> questions.none { it.id == id } }.sorted()
    val parts = order.mapNotNull { id ->
      val a = answers[id] ?: return@mapNotNull null
      val labels = questions.firstOrNull { it.id == id }?.options.orEmpty()
      fun label(v: String) = labels.firstOrNull { it.value == v }?.label ?: v
      when (a) {
        is Answer.Choice -> label(a.value)
        is Answer.Choices -> a.values.joinToString(", ") { label(it) }
        is Answer.Own -> "In your words: “${a.text}”"
      }
    }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
  }
}

/** What the owner has picked on a questions card so far: a question's answer is one of its choices (several, on a multi question) or their own words, never both. */
data class QuestionSelection(
  val picked: Map<String, Set<String>> = emptyMap(),
  val own: Map<String, String> = emptyMap(),
  val ownOpen: Set<String> = emptySet(),
) {
  /** Opening "Something else…" clears the picks. */
  fun toggleOwn(q: SetupQuestion): QuestionSelection =
    if (q.id in ownOpen) copy(ownOpen = ownOpen - q.id) else copy(ownOpen = ownOpen + q.id, picked = picked + (q.id to emptySet()))

  /** Picking a choice closes the own field. */
  fun pick(q: SetupQuestion, value: String): QuestionSelection {
    val now = picked[q.id].orEmpty()
    val next = if (q.multi == true) (if (value in now) now - value else now + value) else setOf(value)
    return copy(picked = picked + (q.id to next), ownOpen = ownOpen - q.id)
  }

  fun write(q: SetupQuestion, text: String): QuestionSelection = copy(own = own + (q.id to text))

  /** The question's answer, if it has one. */
  fun answer(q: SetupQuestion): QuestionAnswers.Answer? {
    if (q.id in ownOpen) return own[q.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { QuestionAnswers.Answer.Own(it) }
    val picks = picked[q.id].orEmpty()
    if (picks.isEmpty()) return null
    // In the agent's order, not the order they were tapped.
    val ordered = q.options.map { it.value }.filter { it in picks }
    return if (q.multi == true) QuestionAnswers.Answer.Choices(ordered) else QuestionAnswers.Answer.Choice(ordered.first())
  }

  /** Every question answered: only then does the card send. */
  fun complete(questions: List<SetupQuestion>): Boolean = questions.isNotEmpty() && questions.all { answer(it) != null }

  fun message(questions: List<SetupQuestion>): String =
    QuestionAnswers.encode(questions.mapNotNull { q -> answer(q)?.let { q.id to it } }.toMap())
}

/** What Coach's chat says, and how its thread is laid out. */
object CoachWords {
  const val NOT_ALLOWED = "Coach is for the people KIFF lets use it, by default the account's admins."

  /** Where a new conversation can start (kiff/tafs#138), as iOS offers them. */
  val EXAMPLES = listOf("Make sure customers can get their refunds", "Answer customer questions about orders", "Connect my payment provider")

  /** Ways to start improving a taf (kiff/tafs#107). */
  val IMPROVE_EXAMPLES = listOf("How is it going?", "Why was something refused or held?", "It can't find the work it should do")

  const val ASK_ANYTHING = "Ask anything about your tafs"

  const val TOOLS_UNKNOWN = "KIFF didn't answer about Coach's tools. Try again in a moment."

  /** A vendor's tool holds one of Coach's names, as tafs cloud says: Coach's Cards grant by name, so it can't have its tools. */
  fun taken(name: String?) = "Coach can't have its tools yet: another tool in KIFF is named ${name ?: "as one of them"}. Rename or remove it in KIFF."
  const val ASK_ANYTHING_LINE = "Set up a new taf, connect a tool, or change how one works. Each connection and each Card waits for your approval."

  fun improveLine(name: String) = "Say what should change, or ask how it's going. Coach mode starts from what $name did and what its Card allows."
  const val NOT_CONNECTABLE = "Connecting tools isn't set up for this account from this phone. Open Coach to start it."

  /** Starting Coach, planque couldn't check this phone with KIFF: the session still works, so it's never "signed out". */
  const val CONNECTING_FAILED = "Connecting your tools didn't work. Tap Start to try again."
  const val DIGEST_MISMATCH = "This request doesn't match what it asks for, so it can't be approved. Ask Coach to propose it again."
  const val WAITING_FOR_VENDOR = "If the vendor's sign-in page opened, finish it there and come back."

  /** Back from the vendor's page, while planque says whether the connection took. */
  const val CHECKING_SIGN_IN = "Checking the sign-in…"

  /** The vendor's page came back, but planque doesn't hold the connection as connected. */
  fun notConnected(vendor: String, status: String) =
    "The sign-in at $vendor didn't connect: planque has it as ${status.replace('_', ' ')}. Nothing was connected. Try signing in again."

  /** An approval with nothing to sign: the connection is connected and it brings no Card. Nothing is approved. */
  fun nothingToApprove(vendor: String) = "$vendor is already connected. There's nothing new to approve, so nothing was approved."

  /** An approval signed earlier (on another phone, or a tap before this one): connected, its Card confirmed in KIFF. */
  const val ALREADY_APPROVED = "Already approved"

  const val INTRO = "Name a tool a taf should work with, like your payment provider. You sign in on the tool's own page, and your sign-in is kept sealed: no taf ever sees it."
  const val SIGN_IN_NOTE = "The vendor's own page opens. Your password and keys are never shown here, and your sign-in is kept sealed."
  const val COACH_KNOWS = "Coach knows, and hands it over again."

  /** A hand-over as the card says it, from tafs cloud's answer: set up, why it wasn't, or not answered yet. */
  sealed interface HandOver {
    data object SetUp : HandOver
    data class Failed(val why: String) : HandOver
    data object SettingUp : HandOver
  }

  /** [listed]: the taf is already on the account's list. */
  fun handOver(answer: bot.tafs.app.kiff.CardAnswer?, listed: Boolean): HandOver {
    val outcome = answer?.outcome
    return when {
      outcome?.failed != null -> HandOver.Failed(outcome.failed)
      outcome?.tafId != null || listed -> HandOver.SetUp
      else -> HandOver.SettingUp
    }
  }

  fun settingUp(name: String) = "Setting up $name…"
  fun notSetUp(name: String, why: String) = "$name wasn't set up: ${why.trimEnd('.')}."

  /** What became of an improvement result, as tafs cloud answered it: the version it made, why it made none, or not yet. */
  sealed interface Improvement {
    data class Made(val version: Int) : Improvement
    data class Failed(val why: String) : Improvement
    data object Preparing : Improvement
  }

  fun improvement(answer: bot.tafs.app.kiff.CardAnswer?): Improvement {
    val outcome = answer?.outcome
    return when {
      outcome?.failed != null -> Improvement.Failed(outcome.failed)
      outcome?.version != null -> Improvement.Made(outcome.version)
      else -> Improvement.Preparing
    }
  }

  fun notImproved(name: String, why: String) = "${possessive(name)} new version wasn't made: ${why.trimEnd('.')}."

  /** "Coach's", "Refunds'": a name ending in s takes the apostrophe alone. */
  fun possessive(name: String) = if (name.endsWith("s")) "$name'" else "$name's"

  /** An owner's message in their own words: an answer to a card reads as what they chose, never as the contract's form. */
  fun ownerWords(text: String, components: List<ShownComponent>): String {
    val a = CoachAnswer.parse(text) ?: return text
    val component = components.firstOrNull { it.id == a.id }?.component
    return when (a.type) {
      "confirm" -> component?.options?.firstOrNull { it.value == a.text }?.label ?: a.text
      "approval" -> if (a.failed) "Approve · it didn't go through" else if (a.text == "declined") "Decline" else "Approve"
      "sign_in" -> if (a.failed) "Sign in · it didn't go through" else if (a.text == "not now") "Not now" else "Signed in"
      "questions" -> QuestionAnswers.words(a.text, component?.questions.orEmpty()) ?: a.text
      else -> a.text
    }
  }

  /**
   * A card's answer as the thread has it (`answer` on the card), in kiff/agents' own words: what was chosen, that the owner
   * wrote instead, or that it stopped waiting with none. The phone keeps no answer of its own.
   */
  fun answered(a: bot.tafs.app.kiff.CardAnswer): String = when (a.state) {
    "wrote" -> "You answered in your own words."
    "no_answer" -> "It stopped waiting, with no answer."
    else -> a.words.ifEmpty { "Answered." }
  }

  /** The tafs a new Coach conversation can start on, to improve one: those tafs cloud runs that this person manages. */
  fun improvable(state: bot.tafs.app.app.AppState): List<bot.tafs.app.model.Taf> =
    state.tafs.filter { it.cloudId != null && state.manages(it.id) }

  const val ANSWERING = "Sending your answer…"

  /**
   * An answered approval, in one line: the answer, then what it was ("Approved · Connect Stripe"). What it was is Coach's
   * title for the card, else planque's words for the connection, else KIFF's name for the draft's Card, else its service.
   */
  fun answeredApproval(answer: String, c: SetupComponent, grantText: String?, draftLabel: String?): String {
    val what = c.title?.takeIf { it.isNotBlank() }
      ?: grantText?.takeIf { it.isNotBlank() }
      ?: draftLabel?.takeIf { it.isNotBlank() }?.let { possessive(it) + " Card" }
      ?: c.service?.let { "Connect $it" }
    return if (what == null) answer else "$answer · $what"
  }

  /** Taken by tafs cloud, before the thread shows it in kiff/agents' words. */
  const val TAKEN = "Answered."

  /**
   * Folds components as the conversation lists them, oldest first: the latest version of each card ([ShownComponent.ref], never
   * the bare id: Coach can use one again in another conversation), at the time it first appeared. An earlier answer carries over
   * only to the same card: a new card under an id used before waits for its own answer.
   */
  fun fold(shown: List<ShownComponent>): List<ShownComponent> {
    val out = mutableListOf<ShownComponent>()
    for (s in shown) {
      val i = out.indexOfFirst { it.ref == s.ref }
      if (i >= 0) out[i] = out[i].copy(component = s.component, answer = s.answer ?: out[i].answer?.takeIf { out[i].component == s.component }) else out += s
    }
    return out
  }

  /**
   * A card of Coach's on the phone, by its conversation and its id: Coach can use an id again in another conversation, and a
   * card answered in one is never answered in the other.
   */
  fun cardKey(session: String?, id: String): String = "${session.orEmpty()}#$id"

  private val ASKING = setOf("confirm", "questions", "approval", "sign_in", "result")

  /** A plan's one line, as tafs cloud writes it; before it does, the plan's own title. Never counted on the phone. */
  fun planLine(plan: SetupComponent): String = plan.line?.takeIf { it.isNotBlank() } ?: plan.title?.takeIf { it.isNotBlank() } ?: "Plan"

  /** The plan, shown pinned above the thread rather than in it. */
  fun plan(components: List<ShownComponent>): ShownComponent? = components.lastOrNull { it.component.type == "steps" }

  /**
   * The thread in order, the plan left out. As it happened, with one exception: a card that waits for the owner to act comes
   * after what Coach wrote with it, so its words lead to the card: Coach's messages in the same session after the card, up to
   * the owner's next message. Once answered, it goes back to its own time, in line with what Coach said around it.
   */
  fun items(messages: List<ChatMessage>, components: List<ShownComponent>): List<ChatItem> {
    fun placedAt(c: ShownComponent): java.time.Instant {
      if (c.component.type !in ASKING || c.answer != null) return c.firstAt
      val nextOwner = messages.firstOrNull { it.from == ChatMessage.From.owner && it.at > c.firstAt }?.at
      val after = messages.filter { it.from == ChatMessage.From.taf && it.session == c.session && it.at >= c.firstAt && (nextOwner == null || it.at < nextOwner) }
      return after.lastOrNull()?.at ?: c.firstAt
    }
    val placed = messages.map { ChatItem.Message(it) as ChatItem to it.at } +
      components.filter { it.component.type != "steps" }.map { ChatItem.Card(it, placedAt(it)) as ChatItem to placedAt(it) }
    // A message before a card placed at its time.
    return placed.withIndex().sortedWith(compareBy({ it.value.second }, { it.index })).map { it.value.first }
  }

  /** The step the plan is at: the active one, else the first not yet done, else the last (as iOS's SetupPlan.current). */
  fun currentStep(steps: List<SetupStep>): SetupStep? =
    steps.firstOrNull { it.status == "active" } ?: steps.firstOrNull { it.status == "pending" || it.status == "failed" } ?: steps.lastOrNull()

  /** A step's mark in the plan. */
  fun stepMark(status: String): String = when (status) {
    "done" -> "✓"
    "active" -> "●"
    "failed" -> "✕"
    "skipped" -> "–"
    else -> "○"
  }
}
