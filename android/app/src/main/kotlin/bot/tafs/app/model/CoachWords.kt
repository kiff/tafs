package bot.tafs.app.model

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

/** The owner's answer to a card, as the contract has it: "[<type> <id>] <answer>". */
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

    /** The message that answers a card. */
    fun message(type: String, id: String, answer: String): String = "[$type $id] $answer"
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
  const val NOT_CONNECTABLE = "Connecting tools isn't set up for this account from this phone. Open Coach to start it."
  const val DIGEST_MISMATCH = "This request doesn't match what it asks for, so it can't be approved. Ask Coach to propose it again."
  const val WAITING_FOR_VENDOR = "If the vendor's sign-in page opened, finish it there and come back."

  /** What an approval card says once it has an answer: approved, declined, or why it didn't go through. */
  fun approvalResult(answer: CoachAnswer): String = when {
    answer.failed -> "It didn't go through: " + (answer.text.removePrefix("failed").trimStart(':', ' ').ifEmpty { "nothing was approved" })
    answer.text == "declined" -> "You declined"
    else -> "You approved"
  }

  const val INTRO = "Name a tool a taf should work with, like your payment provider. You sign in on the tool's own page, and your sign-in is kept sealed: no taf ever sees it."
  const val SIGN_IN_NOTE = "The vendor's own page opens. Your password and keys are never shown here, and your sign-in is kept sealed."
  const val COACH_KNOWS = "Coach knows, and hands it over again."

  /**
   * An improvement result, by its card and what it carries (its draft, else its note). The card's id is Coach's to pick and comes
   * back in another conversation: keyed by the id alone, a new conversation's card showed the version an earlier one made.
   */
  fun improvementKey(c: SetupComponent): String {
    val what = c.taf?.draft ?: java.security.MessageDigest.getInstance("SHA-256").digest((c.taf?.note ?: "").toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    return "${c.id}|$what"
  }

  /** A sign-in card's state: where the owner is in it, from what the thread holds. */
  fun signInOutcome(answer: CoachAnswer?): String? = when {
    answer == null -> null
    answer.failed -> "failed"
    answer.text == "not now" -> "declined"
    else -> "done"
  }

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

  /** The owner's latest answer to a card, as the thread has it. */
  fun answerTo(id: String, messages: List<ChatMessage>): CoachAnswer? =
    messages.asReversed().filter { it.from == ChatMessage.From.owner }.mapNotNull { CoachAnswer.parse(it.text) }.firstOrNull { it.id == id }

  /** Folds components as the conversation lists them, oldest first: the latest version of each id, at the time it first appeared. */
  fun fold(shown: List<ShownComponent>): List<ShownComponent> {
    val out = mutableListOf<ShownComponent>()
    for (s in shown) {
      val i = out.indexOfFirst { it.id == s.id }
      if (i >= 0) out[i] = out[i].copy(component = s.component) else out += s
    }
    return out
  }

  private val ASKING = setOf("confirm", "questions", "approval", "sign_in", "result")

  /** The plan, shown pinned above the thread rather than in it. */
  fun plan(components: List<ShownComponent>): ShownComponent? = components.lastOrNull { it.component.type == "steps" }

  /**
   * The thread in order, the plan left out. As it happened, with one exception: a card that asks the owner to act comes after
   * what Coach wrote with it, so its words lead to the card: Coach's messages in the same session after the card, up to the
   * owner's next message.
   */
  fun items(messages: List<ChatMessage>, components: List<ShownComponent>): List<ChatItem> {
    fun placedAt(c: ShownComponent): java.time.Instant {
      if (c.component.type !in ASKING) return c.firstAt
      val nextOwner = messages.firstOrNull { it.from == ChatMessage.From.owner && it.at > c.firstAt }?.at
      val after = messages.filter { it.from == ChatMessage.From.taf && it.session == c.session && it.at >= c.firstAt && (nextOwner == null || it.at < nextOwner) }
      return after.lastOrNull()?.at ?: c.firstAt
    }
    val placed = messages.map { ChatItem.Message(it) as ChatItem to it.at } +
      components.filter { it.component.type != "steps" }.map { ChatItem.Card(it, placedAt(it)) as ChatItem to placedAt(it) }
    // A message before a card placed at its time.
    return placed.withIndex().sortedWith(compareBy({ it.value.second }, { it.index })).map { it.value.first }
  }

  /** A step's mark in the plan. */
  fun stepMark(status: String): String = when (status) {
    "done" -> "✓"
    "active" -> "●"
    "failed" -> "✕"
    "skipped" -> "–"
    else -> "○"
  }
}
