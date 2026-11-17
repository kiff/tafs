package bot.tafs.app.model

import bot.tafs.app.app.AppState
import bot.tafs.app.app.COACH_CHAT
import bot.tafs.app.kiff.AgentDraft
import bot.tafs.app.kiff.CardAnswer
import bot.tafs.app.kiff.Grant
import bot.tafs.app.kiff.SetupComponent
import bot.tafs.app.kiff.ShownComponent
import java.time.Instant

/**
 * What Coach's conversation has made so far, for the "Taking shape" panel beside it on a wide screen (as iPad's): the taf's
 * name, the plan, the tools it connects and the Card it drafts. Read only from what the app already holds: the thread,
 * planque's grants and KIFF's drafts. Nothing here asks a server for more.
 */
data class SetupShape(
  /** The taf's name, once Coach hands it over. */
  val name: String? = null,
  /** The plan's title, such as "Connect Stripe". */
  val title: String? = null,
  val steps: List<Step> = emptyList(),
  val tools: List<Tool> = emptyList(),
  val card: Card? = null,
) {
  data class Step(val label: String, val state: State) {
    enum class State { done, needsYou, now, toDo, failed, skipped }
  }

  /** A vendor the conversation connects, from planque's grant. */
  data class Tool(
    /** planque's grant id. */
    val id: String,
    val vendor: String,
    val state: State,
    /** The tools the taf calls through it, once connected. */
    val actions: List<String> = emptyList(),
  ) {
    sealed interface State {
      data object Connected : State
      data object SignedIn : State
      data object Waiting : State
      /** planque failed or disconnected it, with its reason when it gives one. */
      data class Failed(val why: String?) : State
    }
  }

  /** The taf's Cards as KIFF drafted them: a draft until the owner confirms it. */
  data class Card(
    val agent: String,
    /** What the owner reads it as: "Refunds assistant's Card". */
    val title: String,
    val lines: List<String>,
    val confirmed: Boolean,
  )

  /** Where one of Coach's cards stands: waiting for the owner, at work, or answered with its outcome (as iOS's CardState). */
  sealed interface CardState {
    data object NeedsYou : CardState
    data object Working : CardState
    data object Done : CardState
    data object Declined : CardState
    data class Failed(val why: String?) : CardState
  }

  val isEmpty: Boolean get() = name == null && steps.isEmpty() && tools.isEmpty() && card == null

  /** An owner's answer to a card in the form the cards read: "approved", "declined", "signed in", "failed: why", a choice's value. */
  internal data class Answer(val text: String) {
    val failed: Boolean get() = text.startsWith("failed")
    val why: String? get() = if (!failed) null else text.removePrefix("failed").trimStart(':', ' ').ifEmpty { null }
  }

  /** Coach's thread as the panel reads it: its folded cards and messages, planque's grants, and the cards answered on this phone. */
  class Thread(
    val components: List<ShownComponent>,
    val messages: List<ChatMessage>,
    val grants: List<Grant> = emptyList(),
    /** The grant being connected now. */
    val connecting: String? = null,
    /** Cards answered on this phone, by [ShownComponent.ref], before the thread holds the answer. */
    val answered: Set<String> = emptySet(),
  ) {
    /** The cards that ask the owner to act, in the thread's order. */
    val asks: List<ShownComponent>
      get() = CoachWords.items(messages, components).distinctBy { it.id }
        .filterIsInstance<ChatItem.Card>().map { it.shown }.filter { it.component.type in NEEDS_OWNER }

    /**
     * The owner's latest answer to a card, as the thread has it: the answer recorded on the card, else one sent the old way, as a
     * message written since the card appeared.
     */
    internal fun answer(id: String): Answer? {
      val shown = components.lastOrNull { it.component.id == id }
      shown?.answer?.let { recorded(it, shown.component) }?.let { return it }
      val since = shown?.firstAt ?: Instant.MIN
      return messages.asReversed().asSequence()
        .filter { it.from == ChatMessage.From.owner && it.at >= since }
        .mapNotNull { CoachAnswer.parse(it.text) }
        .firstOrNull { it.id == id }?.let { Answer(it.text) }
    }

    /** An approval's or a sign-in's state, from planque's grant and the owner's answer in the thread. */
    fun cardState(c: SetupComponent): CardState {
      val grant = grants.firstOrNull { it.id == c.grant }
      if (c.grant != null && connecting == c.grant) return CardState.Working
      val answer = answer(c.id)
      if (c.type == "approval" && approvalFinished(c, answer)) return CardState.Done
      if (answer?.text == "declined" || answer?.text == "not now") return CardState.Declined
      if (answer != null && answer.failed) return CardState.Failed(answer.why)
      if (c.type == "sign_in" && (answer != null || grant?.connected == true || grant?.listed == true)) return CardState.Done
      if (grant != null && (grant.status == "failed" || grant.status == "disconnected")) return CardState.Failed(grant.reason)
      return CardState.NeedsYou
    }

    /** Finished only by its answer in the thread: approved, or already approved. planque's state alone never finishes it. */
    private fun approvalFinished(c: SetupComponent, answer: Answer?): Boolean =
      answer != null && !answer.failed && (answer.text.startsWith("approved") || answer.text.startsWith("already approved"))

    /** The card waiting for the owner, if any: the latest approval not answered, or a question or sign-in not answered or written past. */
    val waiting: ShownComponent?
      get() = asks.lastOrNull { shown ->
        val c = shown.component
        if (shown.ref in answered) return@lastOrNull false
        when (c.type) {
          "approval" -> cardState(c) == CardState.NeedsYou
          "sign_in" -> cardState(c) == CardState.NeedsYou && !wroteSince(shown.firstAt)
          else -> answer(c.id) == null && !wroteSince(shown.firstAt)
        }
      }

    private fun wroteSince(at: Instant): Boolean = messages.any { it.from == ChatMessage.From.owner && it.at > at }
  }

  companion object {
    private val NEEDS_OWNER = setOf("confirm", "questions", "approval", "sign_in", "drive")

    /** What tafs cloud's English words for a failed answer put before why. */
    private const val DIDNT_GO_THROUGH = " · it didn't go through: "

    /**
     * A card's recorded answer in the form the cards read. One that stopped waiting with no answer, or that the owner wrote past,
     * is none: the owner's message is in the thread. An approval or a sign-in reads its outcome from tafs cloud's stored [CardAnswer.answer],
     * which is in no language; its [CardAnswer.words] are in the owner's, so they're never read for an outcome but in English, the
     * only words this build knows. **Nothing reads as approved by default:** a value or words it doesn't know read as not approved.
     */
    internal fun recorded(recorded: CardAnswer, c: SetupComponent): Answer? {
      if (recorded.state != "answered") return null
      val words = recorded.words
      val stored = recorded.answer?.takeIf { it.isNotBlank() }
      val text = when (c.type) {
        "confirm" -> c.options?.firstOrNull { it.label == words }?.value ?: words
        "approval" -> if (stored != null) approvalFromStored(stored, words) else approvalFromWords(words)
        "sign_in" -> if (stored != null) signInFromStored(stored) else signInFromWords(words)
        // A drive card is approved only by its signed start; anything else said of it is a no.
        "drive" -> if (words.lowercase().startsWith("approved")) "approved" else "declined"
        else -> words
      }
      return Answer(text)
    }

    /** An approval's stored answer: approved, or connected with its Card next, is done; declined; failed with why; else not approved. */
    internal fun approvalFromStored(stored: String, words: String): String = when {
      stored == "approved" || stored == "connected" -> "approved"
      stored.startsWith("already approved") -> stored
      stored == "declined" -> "declined"
      stored.startsWith("failed: ") -> stored
      else -> notApproved(words)
    }

    /** From a tafs cloud that sends only words: tafs cloud's exact English for approved or already approved; anything else isn't. */
    internal fun approvalFromWords(words: String): String {
      words.indexOf(DIDNT_GO_THROUGH).takeIf { it >= 0 }?.let { return "failed: " + words.substring(it + DIDNT_GO_THROUGH.length) }
      return when {
        words == "Approved" -> "approved"
        words == "Already approved" -> "already approved"
        words == "Declined" -> "declined"
        else -> notApproved(words)
      }
    }

    /** A sign-in's stored answer: signed in; failed with why; anything else is not now. */
    internal fun signInFromStored(stored: String): String = when {
      stored == "signed in" -> "signed in"
      stored.startsWith("failed: ") -> stored
      else -> "not now"
    }

    internal fun signInFromWords(words: String): String {
      words.indexOf(DIDNT_GO_THROUGH).takeIf { it >= 0 }?.let { return "failed: " + words.substring(it + DIDNT_GO_THROUGH.length) }
      return if (words == "Signed in") "signed in" else "not now"
    }

    /** An answer this build can't read as an outcome: not approved, said in tafs cloud's own words, as a card that didn't go through. */
    private fun notApproved(words: String): String = "failed: " + words

    /**
     * The shape of [thread]: [drafts] are KIFF's drafts by id; [waiting] is the card waiting for the owner, by id; [states] each
     * card's state, by id, as [Thread.cardState] says.
     */
    fun of(
      thread: Thread,
      drafts: Map<String, AgentDraft>,
      waiting: String?,
      states: Map<String, CardState>,
    ): SetupShape {
      val shown = thread.components.map { it.component }
      val grants = thread.grants
      val name = shown.lastOrNull { it.type == "result" && it.taf != null }?.taf?.name?.takeIf { it.isNotBlank() }

      // The plan, as Coach keeps it. Its step at work waits for the owner when a card does.
      val plan = CoachWords.plan(thread.components)?.component
      val title = plan?.title
      val steps = if (plan != null) {
        var waited = false
        plan.steps.orEmpty().map { step ->
          when (step.status) {
            "done" -> Step(step.label, Step.State.done)
            "failed" -> Step(step.label, Step.State.failed)
            "skipped" -> Step(step.label, Step.State.skipped)
            "active" -> {
              val needsYou = waiting != null && !waited
              waited = waited || needsYou
              Step(step.label, if (needsYou) Step.State.needsYou else Step.State.now)
            }
            else -> Step(step.label, Step.State.toDo)
          }
        }
      } else {
        // No plan shown: what Coach asked of the owner, and where each stands.
        thread.asks.map { ask ->
          val c = ask.component
          val vendor = grants.firstOrNull { it.id == c.grant }?.vendor ?: c.service
          val label = when (c.type) {
            "approval" -> Words.Coach.Shape.approveNamed(vendor ?: Words.Coach.Shape.theConnection)
            "sign_in" -> vendor?.let { Words.Coach.SignIn.at(it) } ?: Words.Coach.SignIn.atVendor
            else -> c.text ?: c.title ?: Words.NeedsYou.answer
          }
          when {
            ask.id == waiting -> Step(label, Step.State.needsYou)
            // Answered, or written past: the conversation went on without it.
            c.type == "confirm" -> Step(label, if (thread.answer(c.id) != null) Step.State.done else Step.State.skipped)
            else -> Step(label, when (states[c.id] ?: CardState.NeedsYou) {
              CardState.Done -> Step.State.done
              CardState.Declined -> Step.State.skipped
              is CardState.Failed -> Step.State.failed
              CardState.Working -> Step.State.now
              CardState.NeedsYou -> Step.State.toDo
            })
          }
        }
      }

      // The grants the conversation's cards name, in the order they came, each once.
      val seen = mutableSetOf<String>()
      val tools = shown.mapNotNull { c ->
        val id = c.grant ?: return@mapNotNull null
        if (id in seen) return@mapNotNull null
        val grant = grants.firstOrNull { it.id == id } ?: return@mapNotNull null
        seen += id
        val state = when {
          grant.connected -> Tool.State.Connected
          grant.status == "failed" || grant.status == "disconnected" -> Tool.State.Failed(grant.reason)
          grant.holdsSignIn -> Tool.State.SignedIn
          else -> Tool.State.Waiting
        }
        Tool(id, grant.vendor, state, if (grant.connected) grant.request.tools.map { it.tool } else emptyList())
      }

      // The latest draft an approval names that KIFF has shown the app; Coach's own no-tools draft isn't the taf's Card.
      val draft = shown.filter { it.type == "approval" }.mapNotNull { c -> c.draft?.let { drafts[it] } }.lastOrNull { it.noTools != true }
      val card = draft?.let { Card(it.agentId, cardTitle(it), it.cardLines.flatten(), it.confirmed) }

      return SetupShape(name, title, steps, tools, card)
    }

    /** "Refunds assistant's Card": the name it was submitted with, never its agent id. */
    fun cardTitle(draft: AgentDraft): String =
      draft.label?.trim()?.takeIf { it.isNotEmpty() }?.let { Words.Coach.cardOf(it) } ?: Words.Card.theTafsCard
  }
}

/** Coach's thread as the "Taking shape" panel reads it. */
val AppState.setupThread: SetupShape.Thread
  get() = SetupShape.Thread(
    components = coachComponents,
    messages = chats[COACH_CHAT].orEmpty(),
    grants = grants,
    connecting = connecting,
    answered = cardsAnswering + cardsTaken,
  )

/** Coach's conversation so far, for the "Taking shape" panel beside it on a wide screen. */
val AppState.setupShape: SetupShape
  get() {
    val thread = setupThread
    val states = thread.asks.distinctBy { it.id }.associate { it.id to thread.cardState(it.component) }
    return SetupShape.of(thread, setupDrafts, thread.waiting?.id, states)
  }
