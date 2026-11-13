package bot.tafs.app.kiff

import bot.tafs.app.model.Words
import bot.tafs.app.model.AttachmentRef
import bot.tafs.app.model.ChatMessage
import bot.tafs.app.model.ChatExport
import bot.tafs.app.model.CoachWords
import bot.tafs.app.model.DoorMark
import bot.tafs.app.model.InstantAsString
import bot.tafs.app.model.MemoryNote
import java.time.Instant
import bot.tafs.app.model.Refused
import bot.tafs.app.model.Work
import bot.tafs.app.model.WorkStep
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** tafs cloud's refusal: its words. */
/** [words]: tafs cloud's own words for what's in the way, where it sends them (a switch a run blocks). */
class CloudError(
  val status: Int,
  message: String,
  val blocking: List<BlockingRun> = emptyList(),
  val words: String? = null,
  /** tafs cloud's `code` for the refusal, where it names one ("held_full"). */
  val code: String? = null,
  /** For `code: "halted"`: what is halted, `account` or `taf` (docs/approvals.md). */
  val halted: String? = null,
) : Exception(message)

/**
 * tafs cloud: the calls that start, follow, chat with and remove tafs, which run on kiff/agents; the
 * phone never holds a taf's key. It keeps no accounts: every call carries the person's KIFF session,
 * and tafs cloud asks KIFF who that is.
 */
class TafsCloud(
  private val transport: Transport,
  val baseUrl: String = DEFAULT_URL,
  var token: String? = null,
  private val signingTime: SigningTime = SigningTime(),
) : bot.tafs.app.drive.DriveApi {
  /**
   * The home list: the account's tafs, its other agents with Cards, and the removals still to finish,
   * made by tafs cloud from its tafs and KIFF's Cards and held calls.
   */
  suspend fun home(): Home = get("/v1/home")

  /** The scheduled work to come on the dates [from] to [to] (YYYY-MM-DD, in the account's zone): kiff/tafs#534. */
  suspend fun calendar(from: String, to: String): CalendarRead = get("/v1/calendar?from=$from&to=$to")

  /** A taf's thread, oldest first, and the run at work in it now, if any. */
  class Thread(
    val messages: List<ChatMessage>,
    val working: String?,
    val workingRun: String?,
    val capped: WireCapped?,
    /** Coach's cards, folded: the latest version of each id, where it first appeared. */
    val components: List<ShownComponent> = emptyList(),
    /** News of the tasks Coach handed tafs, until Coach reads it on its next turn (kiff/tafs#206). */
    val coachLines: List<CoachLine> = emptyList(),
    /** Each drive's end in Coach's chat, at its time (kiff/tafs#568). */
    val drives: List<bot.tafs.app.drive.DriveRecap> = emptyList(),
    /** What tafs cloud holds of the person's, for the thread's latest chat: what waits, and what a stopped run never read. */
    val steering: WireSteering? = null,
  )

  /** The account's Setup (Coach), or null before it is started. */
  suspend fun setup(): CloudSetup? = get<SetupResponse>("/v1/setup").setup

  /** Coach's tools as KIFF has them, read each time (`GET /v1/setup`'s `tools`): what's still to give, in bodies to sign as they come. */
  suspend fun coachTools(): CoachTools? = get<SetupResponse>("/v1/setup").tools

  /** The account's Coach and its tools, from one read. */
  suspend fun setupAndTools(): Pair<CloudSetup?, CoachTools?> = get<SetupResponse>("/v1/setup").let { it.setup to it.tools }

  /** The account's Coach, its tools and, before Coach exists, its first start under one fingerprint, from one read. */
  suspend fun setupRead(): SetupRead = get<SetupResponse>("/v1/setup").let { r ->
    SetupRead(r.setup, r.tools, if (r.setup == null) CoachStart.read(r.start) else null)
  }

  /** What `GET /v1/setup` answers. */
  data class SetupRead(val setup: CloudSetup?, val tools: CoachTools?, val start: CoachStart?)

  /** The recipes a new Coach conversation opens to, by category (kiff/tafs#476). */
  suspend fun recipes(): Recipes = get("/v1/recipes")

  /** Every job a taf can take, for + New and the strip under the tafs list (docs/android-app.md). */
  suspend fun gallery(): RecipesGallery = get("/v1/recipes/gallery")

  /**
   * A logo's image, from tafs cloud only: [path] must be one of its `/v1/logos/{key}` paths, never a URL, so no request ever leaves for
   * a third party. No session goes with it: logos are public. Null for anything else, or when tafs cloud has none.
   */
  suspend fun logo(path: String): ByteArray? {
    if (!isLogoPath(path)) return null
    val response = transport.send(HttpRequest("GET", baseUrl + path, mapOf("Accept" to "image/png")))
    return response.body.takeIf { response.status in 200..299 && it.isNotEmpty() }
  }

  /** The account's "Show suggestions" setting, with tafs cloud's words for it. */
  suspend fun suggestions(): Suggestions = get<SuggestionsAnswer>("/v1/me/suggestions").suggestions

  /** Turns the account's suggestions off for good, or on again: for the people who manage its tafs (403 otherwise). */
  suspend fun setSuggestions(show: Boolean): Suggestions = KiffClient.json.decodeFromString<SuggestionsAnswer>(
    String(sendJson("PUT", "/v1/me/suggestions", kotlinx.serialization.json.JsonObject(mapOf("show" to kotlinx.serialization.json.JsonPrimitive(show))))),
  ).suggestions

  /** The person's AI-data consent, with the screen's words in their language (docs/accounts.md, "Your data and the AI"). */
  suspend fun aiConsent(): AIConsent = get<AIConsentAnswer>("/v1/me/ai-consent").consent

  /** The person allows AI use for the [version] of the words they read: 409 when those words changed since, with the new ones. */
  suspend fun giveAIConsent(version: String): AIConsent = KiffClient.json.decodeFromString<AIConsentAnswer>(
    String(sendJson("POST", "/v1/me/ai-consent", kotlinx.serialization.json.JsonObject(mapOf("version" to kotlinx.serialization.json.JsonPrimitive(version))))),
  ).consent

  /** The person withdraws it: from then on, nothing they write reaches the model until they allow it again. */
  suspend fun withdrawAIConsent(): AIConsent = KiffClient.json.decodeFromString<AIConsentAnswer>(String(send("DELETE", "/v1/me/ai-consent"))).consent

  /**
   * Told when tafs cloud refuses a model turn until the person allows AI use (`ai_consent_required`), with the consent screen it
   * sent, so the app shows it there and then. The refusal still throws, as any other.
   */
  var aiConsentNeeded: ((AIConsent) -> Unit)? = null

  private fun noteRefusal(body: ByteArray) {
    if (field(body, "code") != AI_CONSENT_REQUIRED) return
    val consent = runCatching {
      KiffClient.json.decodeFromJsonElement(AIConsent.serializer(), KiffClient.json.parseToJsonElement(String(body)).jsonObject["consent"]!!)
    }.getOrNull() ?: return
    aiConsentNeeded?.invoke(consent)
  }

  /** Where Coach's tools are, on tafs cloud: KIFF's gateway reaches them there, each call carrying KIFF's permit. */
  val mcpUrl: String get() = "$baseUrl/v1/mcp"

  /** Starts the account's Coach from a confirmed no-tools KIFF draft: tafs cloud claims the agent's key with the claim secret, as for a taf. */
  suspend fun startSetup(draft: String, claimSecret: String): CloudSetup =
    KiffClient.json.decodeFromString<SetupResponse>(String(send("POST", "/v1/setup", jsonBody("draft_id" to draft, "claim_secret" to claimSecret)))).setup
      ?: throw CloudError(502, Words.Error.cloudNoCoach)

  private fun jsonBody(vararg fields: Pair<String, String>): ByteArray =
    KiffClient.json.encodeToString(
      kotlinx.serialization.json.JsonObject.serializer(),
      kotlinx.serialization.json.JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) }),
    ).toByteArray()

  /** The taf's thread. A message the app can't read as the owner's or the taf's is left out, never the thread with it. With [session], that one chat only (kiff/tafs#73). */
  suspend fun conversation(id: String, session: String? = null): Thread =
    thread("/v1/tafs/${escaped(id)}/conversation" + (session?.let { "/${escaped(it)}" } ?: ""))

  /** The trial's chat, read as a taf's thread is. */
  suspend fun trialConversation(id: String): Thread = thread("/v1/tafs/${escaped(id)}/trial/conversation")

  private suspend fun thread(path: String): Thread {
    val wire = get<WireThread>(path)
    return Thread(
      messages = wire.messages.mapNotNull { m ->
        val from = when (m.from) {
          "owner" -> ChatMessage.From.owner
          "taf" -> ChatMessage.From.taf
          else -> return@mapNotNull null
        }
        ChatMessage(
          at = m.at, from = from, text = m.text, session = m.session, serverId = m.id, answers = m.answers,
          delivered = m.delivered?.trim()?.ifEmpty { null },
          counted = m.counted?.trim()?.ifEmpty { null },
          refused = m.refused?.let { r -> Refused(r.words, r.why?.trim()?.ifEmpty { null }, r.next) }?.takeIf { it.lines.isNotEmpty() },
          kind = if (m.kind == "kickoff") ChatMessage.Kind.kickoff else ChatMessage.Kind.message,
          work = m.work?.let { w -> Work(w.ms, w.steps.map { WorkStep(it.tool, it.error == true || it.failed == true, it.notMade == true, it.words?.trim()?.ifEmpty { null }, it.why) }) },
          // A mark the app can't read leaves the message as a plain one.
          door = m.door?.let { mark ->
            runCatching { KiffClient.json.decodeFromJsonElement(WireDoorMark.serializer(), mark) }.getOrNull()
              ?.let { DoorMark(it.app, it.from, it.subject, it.reply, it.why) }
          },
          attachments = (m.attachments as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { file ->
            runCatching { KiffClient.json.decodeFromJsonElement(AttachmentRef.serializer(), file) }.getOrNull()
          },
        )
      },
      working = wire.working?.session,
      workingRun = wire.working?.run,
      capped = wire.stopped ?: wire.capped,
      components = CoachWords.fold(wire.components.orEmpty().mapNotNull { shown(it) }),
      coachLines = wire.coachLines.orEmpty().mapNotNull { runCatching { KiffClient.json.decodeFromJsonElement(CoachLine.serializer(), it) }.getOrNull() },
      // A drive's end the app can't read is left out, never the thread with it.
      drives = wire.items.orEmpty().mapNotNull { item ->
        val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        if ((o["type"] as? JsonPrimitive)?.contentOrNull != "drive") return@mapNotNull null
        runCatching { KiffClient.json.decodeFromJsonElement(bot.tafs.app.drive.DriveRecap.serializer(), o.getValue("drive")) }.getOrNull()
          ?.takeIf { runCatching { java.time.Instant.parse(it.at) }.isSuccess }
      },
      steering = wire.steering?.let { runCatching { KiffClient.json.decodeFromJsonElement(WireSteering.serializer(), it) }.getOrNull() },
    )
  }

  /** One card as the conversation lists it; null for one the app doesn't know or can't read. */
  private fun shown(element: kotlinx.serialization.json.JsonElement): ShownComponent? = runCatching {
    val wire = KiffClient.json.decodeFromJsonElement(WireShown.serializer(), element)
    val component = KiffClient.json.decodeFromJsonElement(SetupComponent.serializer(), wire.component)
    val answer = wire.answer?.let { runCatching { KiffClient.json.decodeFromJsonElement(CardAnswer.serializer(), it) }.getOrNull() }
    if (component.type !in SetupComponent.KINDS) null else ShownComponent(wire.at, wire.session, wire.run, component, answer, wire.key)
  }.getOrNull()

  /**
   * Sends the owner's message, with the files they attached (docs/attachments.md): into the taf's live chat, which starts a
   * run; into [session], a chat the owner reopened; or, with [newChat], a new one (kiff/tafs#73).
   */
  suspend fun sendMessage(id: String, text: String, files: List<AttachmentRef> = emptyList(), session: String? = null, newChat: Boolean = false): Sent =
    KiffClient.json.decodeFromString(String(sendJson("POST", "/v1/tafs/${escaped(id)}/messages", messageBody(text, files, session, newChat))))

  /** A message's body: its words, its files by id when it has any, and the chat it goes into when it isn't the live one. */
  private fun messageBody(text: String, files: List<AttachmentRef>, session: String? = null, newChat: Boolean = false) = kotlinx.serialization.json.buildJsonObject {
    put("text", text)
    if (files.isNotEmpty()) put("attachments", kotlinx.serialization.json.JsonArray(files.map { JsonPrimitive(it.id) }))
    if (newChat) put("new_chat", true) else session?.let { put("session", it) }
  }

  // MARK: Conversations (kiff/tafs#73, #82, #97)

  /** One of a taf's chats, to reopen: named by its first words. */
  @kotlinx.serialization.Serializable
  data class Chat(
    val session: String,
    @kotlinx.serialization.Serializable(with = InstantAsString::class) val started: Instant,
    @kotlinx.serialization.Serializable(with = InstantAsString::class) val updated: Instant,
    val title: String = "",
  )

  @kotlinx.serialization.Serializable
  private data class Chats(val chats: List<Chat> = emptyList())

  /** The taf's chats, newest first. */
  suspend fun chats(id: String): List<Chat> = get<Chats>("/v1/tafs/${escaped(id)}/chats").chats

  /** The chats the owner archived, to find and reopen one. */
  suspend fun archivedChats(id: String): List<Chat> = get<Chats>("/v1/tafs/${escaped(id)}/chats/archived").chats

  /** Archives a chat: it leaves the list and the thread, on every device. */
  suspend fun archiveChat(id: String, session: String) {
    send("POST", "/v1/tafs/${escaped(id)}/chats/${escaped(session)}/archive", jsonBody())
  }

  /** Puts an archived chat back in the list. */
  suspend fun unarchiveChat(id: String, session: String) {
    send("POST", "/v1/tafs/${escaped(id)}/chats/${escaped(session)}/unarchive", jsonBody())
  }

  /** Opens a new conversation with the account's Coach on this taf (kiff/tafs#107): tafs cloud puts the taf's job, Card and latest calls in front of the owner's words. */
  suspend fun improve(id: String, text: String): Sent = post("/v1/tafs/${escaped(id)}/improve", mapOf("text" to text))

  /** A new conversation with Coach on one "Did this happen?" call, after KIFF took "Not sure": the owner still answers the card. */
  suspend fun checkCall(proposal: String): Sent = post("/v1/setup/check-call", mapOf("proposal" to proposal))

  /** How a task Coach handed a taf went (kiff/tafs#206), shown in Coach's chat until Coach reads it on its next turn. */
  @kotlinx.serialization.Serializable
  data class CoachLine(
    @kotlinx.serialization.Serializable(with = InstantAsString::class) val at: Instant,
    val taf: String,
    val said: String,
    /** Kept while the owner was away (kiff/tafs#210): shown under "While you were away". */
    val away: Boolean? = null,
  )

  // MARK: Photos and files on a message (docs/attachments.md)

  /** Where a file's bytes go: a PUT to [url] with [headers], sent as given, straight to storage, never through tafs cloud. It works for minutes. */
  @kotlinx.serialization.Serializable
  data class Upload(val url: String, val method: String = "PUT", val headers: Map<String, String> = emptyMap())

  @kotlinx.serialization.Serializable
  private data class Attached(val attachment: AttachmentRef, val upload: Upload)

  @kotlinx.serialization.Serializable
  private data class Download(val url: String)

  /**
   * Declares a file the person will attach to a message in their chat with the taf (or Coach): tafs cloud answers where to
   * upload it. A file over its limit is refused here, before any upload, and the refusal says which limit.
   */
  suspend fun attach(id: String, mediaType: String, name: String, size: Long, sha256: String): Pair<AttachmentRef, Upload> {
    val body = kotlinx.serialization.json.buildJsonObject {
      put("media_type", mediaType)
      put("name", name)
      put("size", size)
      put("sha256", sha256)
    }
    val attached = KiffClient.json.decodeFromString<Attached>(String(sendJson("POST", "/v1/tafs/${escaped(id)}/attachments", body)))
    return attached.attachment to attached.upload
  }

  /** Uploads a file's bytes where [attach] said: no KIFF session goes with them. */
  suspend fun upload(data: ByteArray, to: Upload) {
    val response = transport.send(HttpRequest(to.method, to.url, to.headers, data))
    if (response.status !in 200..299) throw bot.tafs.app.model.OwnWords(Words.Error.fileNotUploaded)
  }

  /** A file the person attached, downloaded: only their own; another's is a 404. */
  suspend fun attachment(id: String, file: String): ByteArray {
    val url = get<Download>("/v1/tafs/${escaped(id)}/attachments/${escaped(file)}").url
    val response = transport.send(HttpRequest("GET", url))
    if (response.status !in 200..299) throw bot.tafs.app.model.OwnWords(Words.Error.onOurSide)
    return response.body
  }

  /**
   * Writes to the run at work (kiff/tafs#64): it reads the message at its next step. A run that has ended throws
   * 409, and the message goes as a new one. Returns the run's status: a paused run reads it when it carries on.
   */
  /**
   * The owner's answer to a card that waits for it, as data (docs/chat.md): the run that paused on it goes on with it. [session] is
   * the chat that shows the card. 409 once the card doesn't wait any more.
   */
  suspend fun answerCard(id: String, component: String, answer: kotlinx.serialization.json.JsonElement, session: String? = null): CardAnswered {
    val body = kotlinx.serialization.json.buildJsonObject {
      put("answer", answer)
      session?.let { put("session", it) }
    }
    return KiffClient.json.decodeFromString(String(sendJson("POST", "/v1/tafs/${escaped(id)}/components/${escaped(component)}/answer", body)))
  }

  /**
   * Approve tapped on an approval card (kiff/tafs#413): tafs cloud reads planque's grant, as this phone (the signed read
   * [proof], forwarded), and KIFF's draft, and decides. `answered`: it told Coach itself, and [ApprovalDecision.note] is for
   * the card. `read_again`: the card is out of date; nothing was told. `sign`: one message for the device's key and the call
   * it goes into, exactly as the phone must make it.
   */
  suspend fun approval(id: String, component: String, body: kotlinx.serialization.json.JsonObject, proof: String?): ApprovalDecision =
    KiffClient.json.decodeFromString(
      String(send("POST", "/v1/tafs/${escaped(id)}/components/${escaped(component)}/approval", KiffClient.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body).toByteArray(),
        proof?.let { mapOf("Planque-Device-Signature" to it) } ?: emptyMap())),
    )

  /**
   * What came of the call made with the signature tafs cloud asked for, signed as a setup call to KIFF (`kiff.request.v1`
   * over this request, in `KIFF-Device-Signature`): KIFF tells tafs cloud this phone signed it, and planque or KIFF what
   * holds. tafs cloud answers Coach, and says what it answered.
   */
  suspend fun approvalReport(id: String, component: String, ask: String, body: kotlinx.serialization.json.JsonObject, proof: String?, tenant: String, sign: (ByteArray) -> String): ApprovalDecision {
    val path = "/v1/tafs/${escaped(id)}/components/${escaped(component)}/approval/${escaped(ask)}"
    val payload = KiffClient.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body).toByteArray()
    val signedAt = signingTime.next()
    val signature = sign(KiffClient.requestMessage(tenant, "POST", path, payload, signedAt))
    val headers = linkedMapOf("KIFF-Device-Signature" to "t=$signedAt,v1=$signature")
    proof?.let { headers["Planque-Device-Signature"] = it }
    return KiffClient.json.decodeFromString(String(send("POST", path, payload, headers)))
  }

  /**
   * Reports an answer Coach or the taf wrote (docs/api.md): by tafs cloud's id for the message, with the chat it's in. tafs cloud
   * finds and keeps the answer itself, never the app's copy. Reporting one again answers the first report, with `new` false.
   */
  suspend fun report(id: String, message: String, reason: String, note: String?, session: String?): AnswerReport {
    val body = kotlinx.serialization.json.buildJsonObject {
      put("reason", reason)
      note?.let { put("note", it) }
      session?.let { put("session", it) }
    }
    return KiffClient.json.decodeFromString<Reported>(String(sendJson("POST", "/v1/tafs/${escaped(id)}/messages/${escaped(message)}/report", body))).report
  }

  /**
   * Writes to the run at work, read at its next step (docs/chat.md). With `hold`, what the run doesn't take is tafs cloud's to keep,
   * in its chat, and to send once the taf is done: 202 with [Steered.sent] when it went at once as the next message, [Steered.held]
   * for what waits, both, or neither when another read already dealt with it. 409 `held_full` when too much waits already. An older
   * tafs cloud answers 409 with no code when the run took nothing, and the message goes as a new one.
   */
  suspend fun steer(id: String, run: String, text: String): Steered = KiffClient.json.decodeFromString(
    String(sendJson("POST", "/v1/tafs/${escaped(id)}/steer", kotlinx.serialization.json.JsonObject(mapOf("run" to JsonPrimitive(run), "text" to JsonPrimitive(text), "hold" to JsonPrimitive(true))))),
  )

  /**
   * The owner just answered one of the taf's held calls in KIFF (kiff/tafs#206): the run that paused on it goes
   * on now, not when KIFF's alert reaches it. tafs cloud asks KIFF for the answer itself, so this only asks
   * sooner. Returns the run's status, or "waiting" while KIFF still holds one of its calls.
   */
  suspend fun continueHeld(id: String, exception: String): String =
    KiffClient.json.decodeFromString<Steered>(String(send("POST", "/v1/tafs/${escaped(id)}/holds/${escaped(exception)}/continue"))).status

  /** The balance now, in tafs cloud's words; null from a tafs cloud from before the balance (404). */
  suspend fun balance(): Balance? = orNone { get<Balance>("/v1/usage/balance") }

  /** The period's credits, or the period before with [previous]; null from a tafs cloud from before credits (404). */
  suspend fun credits(previous: Boolean = false): Credits? = orNone {
    get<Credits>("/v1/usage/credits" + if (previous) "?period=previous" else "")
  }

  /** One taf's runs and their credits, a page at a time from [after]; null from a tafs cloud from before credits (404). */
  suspend fun tafCredits(id: String, previous: Boolean = false, after: String? = null): TafCredits? = orNone {
    val query = listOfNotNull("period=previous".takeIf { previous }, after?.let { "after=${escaped(it)}" })
    get<TafCredits>("/v1/usage/credits/tafs/${escaped(id)}" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?"))
  }

  private inline fun <T> orNone(read: () -> T): T? = try { read() } catch (e: CloudError) { if (e.status == 404) null else throw e }

  // MARK: Halt

  private fun haltPath(id: String?): String = id?.let { "/v1/tafs/${escaped(it)}/halt" } ?: "/v1/halt"

  /** Halts the taf, or with none every taf of the account, in kiff/agents and KIFF at once. */
  suspend fun halt(id: String?): Halt = post<HaltResponse>(haltPath(id), emptyMap()).halt

  /** Where a halt stands: kiff/agents, KIFF and planque each say whether they hold it. */
  suspend fun halted(id: String?): Halt = get<HaltResponse>(haltPath(id)).halt

  /**
   * Lets kiff/agents run it again, once KIFF has resumed it: the phone resumes it in KIFF first, signed
   * behind the owner's confirmation ([KiffClient.resume]), and tafs cloud checks that.
   */
  suspend fun releaseHalt(id: String?): Halt =
    KiffClient.json.decodeFromString<HaltResponse>(String(send("DELETE", haltPath(id)))).halt

  /**
   * Erases every taf, and what tafs cloud and kiff/agents keep for the account (kiff/tafs#185): done, or the step that
   * failed, which asking again goes on from. Only someone who may delete the account can.
   */
  suspend fun deleteAccount(): AccountDeletion = post("/v1/account/delete", emptyMap())

  // MARK: Alerts

  /** Where KIFF posts the account's held calls, for tafs cloud to push to its phones. */
  val alertsUrl: String get() = "$baseUrl/v1/kiff/alerts"

  /**
   * This phone gets the account's alerts at [deviceToken] (its Firebase token). With [target], also hands over the KIFF target's
   * signing secret, which checks KIFF's deliveries. `sound` false: this phone's alerts arrive quietly.
   */
  suspend fun registerPush(deviceToken: String, sound: Boolean = true, target: AlertTarget? = null, kinds: Map<String, Boolean>? = null): PushStatus {
    val body = kotlinx.serialization.json.buildJsonObject {
      put("device_token", deviceToken)
      put("platform", "android")
      // Firebase has one environment; tafs cloud's field is Apple's.
      put("environment", "production")
      put("sound", sound)
      kinds?.let { choices -> put("kinds", kotlinx.serialization.json.buildJsonObject { choices.forEach { (id, on) -> put(id, on) } }) }
      target?.let {
        put("alert_target", kotlinx.serialization.json.buildJsonObject {
          put("id", it.id)
          put("signing_secret", it.signingSecret)
        })
      }
    }
    return KiffClient.json.decodeFromString(String(sendJson("POST", "/v1/push", body)))
  }

  /** Whether tafs cloud holds KIFF's alert secret for the account. */
  suspend fun alertsKept(): Boolean = get<AlertsKept>("/v1/alerts").alerts

  /** Hands over KIFF's alert target for the account, with no phone's alerts: a paused run then hears the owner's answer whether or not a phone turned alerts on. */
  suspend fun keepAlerts(target: AlertTarget) {
    val body = kotlinx.serialization.json.buildJsonObject {
      put("alert_target", kotlinx.serialization.json.buildJsonObject {
        put("id", target.id)
        put("signing_secret", target.signingSecret)
      })
    }
    sendJson("POST", "/v1/alerts", body)
  }

  /** This phone stops getting the account's alerts. */
  suspend fun unregisterPhone(deviceToken: String) {
    sendJson("DELETE", "/v1/push", kotlinx.serialization.json.buildJsonObject {
      put("device_token", deviceToken); put("platform", "android"); put("environment", "production")
    })
  }

  suspend fun unregisterPush(deviceToken: String) {
    send("DELETE", "/v1/push/${escaped(deviceToken)}")
  }

  // MARK: Runs, memory and removing a taf

  /** One of the taf's runs, newest first: the chat it ran in, when, and why it didn't start, if it didn't. */
  @kotlinx.serialization.Serializable
  data class Run(val session: String? = null, @kotlinx.serialization.Serializable(with = InstantAsString::class) val at: Instant, val error: String? = null)

  /** A run's report: "running" while it works, "idle" once it has stopped, and what it wrote. */
  @kotlinx.serialization.Serializable
  data class Report(val status: String, val text: String = "")

  /** Where a taf's schedule stands, as tafs cloud answers a pause or resume. */
  @kotlinx.serialization.Serializable
  data class Schedule(
    val status: String = "active",
    @kotlinx.serialization.SerialName("next_run") @kotlinx.serialization.Serializable(with = InstantAsString::class) val nextRun: Instant? = null,
    val unscheduled: Boolean? = null,
  ) {
    val paused: Boolean get() = status == "paused"
  }

  /** What removing a taf did: done once every part is gone, each in [removed] or [failed]. A second remove finishes what the first left. */
  @kotlinx.serialization.Serializable
  data class Removal(val done: Boolean, val removed: List<String> = emptyList(), val failed: List<String> = emptyList())

  @kotlinx.serialization.Serializable
  private data class RunStarted(val session: String? = null, val outcome: String? = null, val words: String? = null)

  /** What Run now did: the run's [session], or none when its check found nothing new, with tafs cloud's [words] for it. */
  data class RunNow(val session: String?, val words: String? = null)

  @kotlinx.serialization.Serializable
  private data class ScheduleBody(val taf: Schedule)

  @kotlinx.serialization.Serializable
  private data class Runs(val runs: List<Run> = emptyList())

  @kotlinx.serialization.Serializable
  private data class ReportBody(val report: Report)

  @kotlinx.serialization.Serializable
  private data class Note(
    val id: String,
    val path: String,
    val content: String,
    @kotlinx.serialization.SerialName("updated_at") @kotlinx.serialization.Serializable(with = InstantAsString::class) val updatedAt: Instant,
  )

  @kotlinx.serialization.Serializable
  private data class Notes(val notes: List<Note> = emptyList())

  /** Runs the taf now, outside its schedule. Returns the run's session. */
  /**
   * Runs it now, by firing its schedule: the run's session, or null when a taf that checks first found nothing new and started
   * no run, which is no failure (docs/api.md).
   */
  suspend fun runNow(id: String): RunNow = post<RunStarted>("/v1/tafs/${escaped(id)}/run", emptyMap()).let { r ->
    // `outcome: "quiet"` comes with tafs cloud's words, in the person's language; the app makes up none of its own.
    RunNow(r.session, r.words?.takeIf { r.session == null && it.isNotBlank() })
  }

  /** Pauses or resumes its schedule: no run starts while it's paused. Its Cards aren't touched. */
  suspend fun setPaused(id: String, paused: Boolean): Schedule =
    post<ScheduleBody>("/v1/tafs/${escaped(id)}/${if (paused) "pause" else "resume"}", emptyMap()).taf

  /**
   * How long a case of the taf may wait before the phones that answer its holds are told (`PUT …/promise`, `cards.manage`): whole
   * hours, which tafs cloud checks (1 to 168), or null to clear it.
   */
  suspend fun setPromise(id: String, hours: Int?) {
    sendJson(
      "PUT", "/v1/tafs/${escaped(id)}/promise",
      kotlinx.serialization.json.JsonObject(mapOf("hours" to (hours?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull))),
    )
  }

  /**
   * Where its results go, of tafs' own places (`door_reply`, `app`, `owner_email`; `manages`), at once. Answers the places tafs
   * cloud now keeps, from its view of the taf; null when it sends none.
   */
  suspend fun setResults(id: String, places: List<String>): List<String>? {
    val answer = sendJson(
      "PUT", "/v1/tafs/${escaped(id)}/results",
      kotlinx.serialization.json.JsonObject(mapOf("places" to kotlinx.serialization.json.JsonArray(places.map { kotlinx.serialization.json.JsonPrimitive(it) }))),
    )
    return runCatching {
      val taf = KiffClient.json.parseToJsonElement(String(answer)).jsonObjectOrNull()?.get("taf")?.jsonObjectOrNull()
      (taf?.get("results") as? kotlinx.serialization.json.JsonArray)?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
    }.getOrNull()
  }

  private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() = this as? kotlinx.serialization.json.JsonObject

  suspend fun runs(id: String): List<Run> = get<Runs>("/v1/tafs/${escaped(id)}/runs").runs

  /** Where the taf's work stands (docs/reliability.md, "The taf's view"): every line and time in tafs cloud's words. */
  suspend fun work(id: String): TafWork = get<TafWork>("/v1/tafs/${escaped(id)}/work")

  /** One request, step by step, for the dial's rim and Activity. */
  suspend fun request(id: String, run: String): RequestDetail = get("/v1/tafs/${escaped(id)}/requests/${escaped(run)}")

  /** The dial changed (`PUT …/knob`): the taf's next version, made as an improvement makes one, and its words. */
  suspend fun changeKnob(id: String, setting: kotlinx.serialization.json.JsonObject): KnobWire.Change =
    KiffClient.json.decodeFromString(String(sendJson("PUT", "/v1/tafs/${escaped(id)}/knob", setting)))

  /** Removes a follow-up the taf set itself (`manages`). */
  suspend fun removeFollowUp(id: String, followUp: String) {
    send("DELETE", "/v1/tafs/${escaped(id)}/follow-ups/${escaped(followUp)}")
  }

  /**
   * Records planque's subscription for an app event the owner approved (`manages`): only while [version] is still live with
   * that trigger's same filters, else 409. Answers the dial as it is now.
   */
  suspend fun recordKnobSubscription(id: String, trigger: String, subscription: String, version: Int): KnobWire =
    KiffClient.json.decodeFromString(
      String(
        sendJson(
          "POST", "/v1/tafs/${escaped(id)}/knob/subscriptions",
          kotlinx.serialization.json.buildJsonObject {
            put("trigger", kotlinx.serialization.json.JsonPrimitive(trigger))
            put("subscription", kotlinx.serialization.json.JsonPrimitive(subscription))
            put("version", kotlinx.serialization.json.JsonPrimitive(version))
          },
        ),
      ),
    )

  /** A taf's dial (the knob API). */
  suspend fun knob(id: String, version: Int? = null): KnobWire = get("/v1/tafs/${escaped(id)}/knob" + (version?.let { "?version=$it" } ?: ""))

  /** Starts connecting [entry] for the taf [taf] (its tafs cloud id; `cards.manage`): its grant, waiting for the owner's approval. */
  suspend fun startConnect(entry: String, taf: String): ConnectStart = post("/v1/connect/apps/${escaped(entry)}", mapOf("taf" to taf))

  /**
   * Connect apps: the catalog, filtered by [q] when given. With [proof], this phone's signed grants read, tafs cloud also lists each
   * removed taf's connections with the live tafs whose Cards still use them (`removed_tafs[].grants`).
   */
  suspend fun connectApps(q: String? = null, provider: String? = null, cursor: String? = null, proof: String? = null): ConnectApps {
    val query = listOfNotNull(
      q?.takeIf { it.isNotBlank() }?.let { "q=" + escaped(it) },
      provider?.let { "provider=" + escaped(it) },
      cursor?.let { "cursor=" + escaped(it) },
    )
    val path = "/v1/connect/apps" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
    return KiffClient.json.decodeFromString(String(send("GET", path, extra = proof?.let { mapOf("Planque-Device-Signature" to it) } ?: emptyMap())))
  }

  /**
   * Send again: the door reply of [run], given up on after its tries, sent once more. It does no work and signs nothing.
   * Returns the delivery's state: sent, not_sent or waiting.
   */
  suspend fun sendAgain(id: String, run: String, place: String = "door_reply"): String =
    post<SentAgain>("/v1/tafs/${escaped(id)}/requests/${escaped(run)}/deliveries/${escaped(place)}/send", emptyMap()).state

  @kotlinx.serialization.Serializable
  private data class SentAgain(val state: String = "")

  suspend fun report(id: String, session: String): Report = get<ReportBody>("/v1/tafs/${escaped(id)}/runs/${escaped(session)}").report

  /** What the taf's calls through KIFF acted on (kiff/tafs#167): KIFF's key for the call, and the value of the argument its Card scopes on, kept 30 days. */
  @kotlinx.serialization.Serializable
  data class CallRef(val invocation: String, val value: String)

  @kotlinx.serialization.Serializable
  private data class CallRefs(val calls: List<CallRef> = emptyList())

  suspend fun calls(id: String): List<CallRef> = get<CallRefs>("/v1/tafs/${escaped(id)}/calls").calls

  /** What the taf remembers, newest first. */
  suspend fun memory(id: String, all: Boolean = false): List<MemoryNote> =
    get<Notes>("/v1/tafs/${escaped(id)}/memory" + if (all) "?all=true" else "").notes.map { MemoryNote(it.id, it.path, it.content, it.updatedAt) }

  /** Forgets one note: the taf won't see it on its next run or chat. */
  suspend fun forget(id: String, note: String) {
    send("DELETE", "/v1/tafs/${escaped(id)}/memory/${escaped(note)}")
  }

  /**
   * The person's confirmation of a removal, made before anything is undone: `kiff.request.v1` over `DELETE /v1/tafs/{id}` and its
   * empty body, as `KIFF-Device-Signature` carries it. [sign] asks for the fingerprint and builds the message only then, so the
   * signed time is when the person confirmed. tafs cloud asks KIFF whether this phone's approval key signed it; KIFF takes it once.
   */
  suspend fun removalSignature(id: String, tenant: String, sign: suspend (() -> ByteArray) -> String): String {
    var signedAt = ""
    val signature = sign {
      signedAt = signingTime.next()
      KiffClient.requestMessage(tenant, "DELETE", "/v1/tafs/${escaped(id)}", ByteArray(0), signedAt)
    }
    return "t=$signedAt,v1=$signature"
  }

  /**
   * Removes the taf for good: stops it, deletes its runs, chats, memory and key, and frees its name. Its Cards are KIFF's: the phone
   * revokes them first. [signature] is the person's confirmation from [removalSignature]; only finishing a removal tafs cloud lists
   * as begun goes without one.
   */
  suspend fun remove(id: String, signature: String?): Removal =
    KiffClient.json.decodeFromString(String(send("DELETE", "/v1/tafs/${escaped(id)}", extra = signature?.let { mapOf("KIFF-Device-Signature" to it) } ?: emptyMap())))

  // MARK: Doors

  /** A taf's doors, the apps a door can open on here, and the knocks waiting for the owner. */
  suspend fun doors(id: String): DoorsRead = get("/v1/tafs/${escaped(id)}/doors")

  /** What removing a taf takes with it, in tafs cloud's words, for the confirmation before the fingerprint. Nothing is ended. */
  suspend fun removal(id: String): RemovalPreview = get("/v1/tafs/${escaped(id)}/removal")

  /** Turns a door on. Email needs nothing more; WhatsApp, its signup's code and ids. */
  suspend fun addDoor(id: String, app: String, extra: Map<String, String> = emptyMap()): Door =
    door(post<DoorResponse>("/v1/tafs/${escaped(id)}/doors", extra + ("app" to app)))

  suspend fun setDoorOpen(id: String, door: String, open: Boolean): Door {
    val body = kotlinx.serialization.json.buildJsonObject { put("open", open) }
    return door(KiffClient.json.decodeFromString<DoorResponse>(String(sendJson("PATCH", "/v1/tafs/${escaped(id)}/doors/${escaped(door)}", body))))
  }

  suspend fun removeDoor(id: String, door: String) {
    send("DELETE", "/v1/tafs/${escaped(id)}/doors/${escaped(door)}")
  }

  /** Pairs a sender with a door: they can give the taf work from now on. */
  suspend fun pair(id: String, door: String, sender: String, name: String?): Door =
    door(post<DoorResponse>("/v1/tafs/${escaped(id)}/doors/${escaped(door)}/keys", buildMap {
      put("id", sender)
      if (!name.isNullOrEmpty()) put("name", name)
    }))

  suspend fun unpair(id: String, door: String, sender: String): Door =
    door(KiffClient.json.decodeFromString<DoorResponse>(String(send("DELETE", "/v1/tafs/${escaped(id)}/doors/${escaped(door)}/keys/${escaped(sender)}"))))

  /** The owner's answer to a knock: "do" (with their own words, if they wrote some), "pair" or "ignore". */
  suspend fun answerKnock(id: String, knock: String, action: String, say: String? = null) {
    post<KnockAnswered>("/v1/tafs/${escaped(id)}/doors/knocks/${escaped(knock)}", buildMap {
      put("action", action)
      if (!say.isNullOrEmpty()) put("say", say)
    })
  }

  private fun door(r: DoorResponse): Door = r.door

  // MARK: Versions

  /** The taf's versions, oldest first, the live one marked, and a switch under way, which tafs cloud settles first. */
  suspend fun versionState(id: String): VersionState = get("/v1/tafs/${escaped(id)}/versions")

  /**
   * Begins the switch to version n: from then on nothing of the taf runs until it ends. Refused (409, naming
   * `blocking`) while a run of the taf is going or waiting on the owner. tafs cloud prepares the Card draft it
   * waits for: the version's own, drafted again ([redraft], or once it expired), a restore of the Cards it ran
   * under when going back, or none.
   */
  suspend fun beginSwitch(id: String, version: Int, redraft: Boolean = false, stopBlocking: Boolean = false): Switching {
    val body = kotlinx.serialization.json.buildJsonObject {
      put("redraft", redraft)
      put("stop_blocking", stopBlocking)
    }
    return KiffClient.json.decodeFromString<SwitchResponse>(String(sendJson("POST", "/v1/tafs/${escaped(id)}/versions/$version/switch", body))).switching
  }

  /** Drops the switch under way while its Card isn't confirmed; once it is, tafs cloud ends it instead. True when one was dropped. */
  suspend fun cancelSwitch(id: String): Boolean =
    KiffClient.json.decodeFromString<CancelledResponse>(String(send("DELETE", "/v1/tafs/${escaped(id)}/switching"))).cancelled

  /** Makes version n live. tafs cloud refuses (409) while its Card revision isn't confirmed. */
  suspend fun makeLive(id: String, version: Int) {
    send("POST", "/v1/tafs/${escaped(id)}/versions/$version/live", "{}".toByteArray())
  }

  // MARK: Trying a version (kiff/tafs#173)

  /** The KIFF draft to confirm as version n's trial Card, signed by the phone. */
  suspend fun trialDraft(id: String, version: Int): String =
    KiffClient.json.decodeFromString<TrialDraftResponse>(String(send("POST", "/v1/tafs/${escaped(id)}/versions/$version/try", "{}".toByteArray()))).draft

  /** Opens the trial of version n, once KIFF has [draft] trying. 409 before that. */
  suspend fun openTrial(id: String, version: Int, draft: String): OpenTrial {
    val body = kotlinx.serialization.json.buildJsonObject {
      put("n", version)
      put("draft", draft)
    }
    return KiffClient.json.decodeFromString<TrialResponse>(String(sendJson("PUT", "/v1/tafs/${escaped(id)}/trial", body))).trial!!
  }

  /** The taf's open trial; null when it has none. */
  suspend fun openTrial(id: String): OpenTrial? = get<TrialResponse>("/v1/tafs/${escaped(id)}/trial").trial

  /** One message to the trial: a turn of the version being tried, in its own chat. */
  suspend fun trialMessage(id: String, text: String): TrialTurn =
    post("/v1/tafs/${escaped(id)}/trial/messages", mapOf("text" to text))

  /** Install: once the phone confirmed the trial's draft live in KIFF, that version goes live and the trial ends. 409 while KIFF hasn't confirmed it. */
  suspend fun installTrial(id: String): TrialSummary =
    KiffClient.json.decodeFromString<TrialInstalled>(String(send("POST", "/v1/tafs/${escaped(id)}/trial/install", "{}".toByteArray()))).ended

  /** Go back: KIFF ends the trial Cards, the trial's chat is deleted, and the live version stays as it was. */
  suspend fun endTrial(id: String): TrialSummary =
    KiffClient.json.decodeFromString<TrialInstalled>(String(send("DELETE", "/v1/tafs/${escaped(id)}/trial"))).ended

  /** The Record across the person's tafs, searched by [q] (an order, invoice, amount or action); [cursor] continues the same search. */
  suspend fun searchRecord(q: String, cursor: String? = null, limit: Int? = null): RecordSearchPage {
    val query = buildList {
      add("q=${escaped(q)}")
      cursor?.let { add("cursor=${escaped(it)}") }
      limit?.let { add("limit=$it") }
    }.joinToString("&")
    return get("/v1/record?$query")
  }

  /** One case: every call about [about], oldest first. A 404 says no call of the person's is about it. */
  suspend fun recordCase(about: String): RecordCase = get("/v1/record/cases/${escaped(about)}")

  /**
   * The receipts of [from] to [to] (YYYY-MM-DD, at most 92 days), for someone who sees every taf's Record. The JSON file is tafs
   * cloud's answer byte for byte, so KIFF's receipts are exactly as issued, never re-encoded; the CSV is as tafs cloud wrote it.
   */
  suspend fun exportReceipts(from: String, to: String): ReceiptsExport {
    val raw = String(send("GET", "/v1/record/receipts?from=${escaped(from)}&to=${escaped(to)}"))
    val body = KiffClient.json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
    fun text(key: String) = (body[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    return ReceiptsExport(from = text("from"), to = text("to"), receiptsJson = raw, csv = text("csv"), missing = text("missing").toIntOrNull() ?: 0)
  }

  /**
   * The person's own chats, trials and notes, for their own copy (docs/data.md): the data as JSON, and `readable`, the same as
   * text. Kept as tafs cloud wrote it: [ChatExport.of] splits it into the two files, and nothing in it is read on the phone.
   */
  suspend fun exportChats(): ChatExport = ChatExport.of(send("GET", "/v1/me/chats/export"))

  /**
   * After KIFF has the account's new time zone: tafs cloud moves every recurring schedule to the zone KIFF reports. A 503 says
   * the account changed but some schedules need another try; trying again moves only those.
   */
  suspend fun applyAccountTimeZone() { sendJson("POST", "/v1/account/time-zone", kotlinx.serialization.json.JsonObject(emptyMap())) }

  private suspend fun sendJson(method: String, path: String, body: kotlinx.serialization.json.JsonObject): ByteArray =
    send(method, path, KiffClient.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body).toByteArray())

  private suspend inline fun <reified T> post(path: String, body: Map<String, String>): T {
    val payload = KiffClient.json.encodeToString(
      kotlinx.serialization.json.JsonObject.serializer(),
      kotlinx.serialization.json.JsonObject(body.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v) }),
    ).toByteArray()
    return KiffClient.json.decodeFromString(String(send("POST", path, payload)))
  }

  private fun escaped(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

  /**
   * `POST /v1/tafs/{id}/holds/{exception}/allow` (docs/allow-from-now-on.md), on the owner's tap: tafs cloud makes the draft and asks
   * KIFF for the replay, then answers the offer, or why there's none. [round] asks for the rounded figure the offer named.
   */
  suspend fun allowOffer(id: String, exception: String, round: Boolean = false): AllowAnswer =
    KiffClient.json.decodeFromString(
      String(send("POST", "/v1/tafs/${escaped(id)}/holds/${escaped(exception)}/allow", (if (round) "{\"round\":true}" else "{}").toByteArray())),
    )

  /**
   * The secure connection sheet's words for key grant [grant] (docs/secure-connection.md), read with this phone's signed grants
   * read ([proof]): 403 without it, 404 for a grant this person can't see, 409 for one that takes no key.
   */
  suspend fun connectSheet(grant: String, proof: String?): SecureSheet = KiffClient.json.decodeFromString(
    String(send("GET", "/v1/connect/grants/${escaped(grant)}/sheet", extra = proof?.let { mapOf("Planque-Device-Signature" to it) } ?: emptyMap())),
  )

  private suspend inline fun <reified T> get(path: String): T {
    val bytes = send("GET", path)
    return KiffClient.json.decodeFromString(String(bytes))
  }

  /** Conditional reads keep already-read pages on this phone. A 304 carries no replacement data. */
  internal suspend fun representation(path: String, etag: String? = null, extra: Map<String, String> = emptyMap()): HttpResponse {
    val bearer = token ?: throw CloudError(401, Words.Error.signInFirst)
    require(path.startsWith("/v1/"))
    // The lines tafs cloud words for people come in the app's language (docs/api.md, The person's language).
    val headers = linkedMapOf("Accept" to "application/json", "Authorization" to "Bearer $bearer", "Accept-Language" to KiffClient.screenLanguage())
    etag?.let { headers["If-None-Match"] = it }; headers.putAll(extra)
    val response = transport.send(HttpRequest("GET", baseUrl + path, headers))
    if (response.status != 304 && response.status !in 200..299) throw CloudError(response.status, refusal(response.body) ?: Words.Error.cloudAnswered(status = response.status.toString()))
    return response
  }

  internal suspend fun send(method: String, path: String, body: ByteArray? = null, extra: Map<String, String> = emptyMap()): ByteArray {
    val bearer = token ?: throw CloudError(401, Words.Error.signInFirst)
    // The lines tafs cloud words for people come in the app's language (docs/api.md, The person's language).
    val headers = linkedMapOf("Accept" to "application/json", "Authorization" to "Bearer $bearer", "Accept-Language" to KiffClient.screenLanguage())
    if (body != null) headers["Content-Type"] = "application/json"
    headers.putAll(extra)
    val response = transport.send(HttpRequest(method, baseUrl + path, headers, body))
    if (response.status !in 200..299) {
      // Kept for Coach's diagnostics by the route's template and status only (docs/drive.md).
      bot.tafs.app.drive.DriveDiagnostics.failed(method, path, response.status)
      noteRefusal(response.body)
      throw CloudError(
        response.status, refusal(response.body) ?: Words.Error.cloudAnswered(status = response.status.toString()), blockingRuns(response.body), field(response.body, "words"),
        field(response.body, "code"), field(response.body, "halted"),
      )
    }
    return response.body
  }

  /** The runs that keep a switch from beginning, when tafs cloud names them (kiff/tafs#206). */
  private fun blockingRuns(body: ByteArray): List<BlockingRun> = runCatching {
    val runs = KiffClient.json.parseToJsonElement(String(body)).jsonObject["blocking"] ?: return emptyList()
    KiffClient.json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(BlockingRun.serializer()), runs)
  }.getOrDefault(emptyList())

  /** tafs cloud's own words for a refusal: its `error`. */
  private fun refusal(body: ByteArray): String? = field(body, "error")

  private fun field(body: ByteArray, name: String): String? = runCatching {
    (KiffClient.json.parseToJsonElement(String(body)).jsonObject[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
  }.getOrNull()

  // MARK: A chat, live (docs/chat.md)

  /** Where a run's answer streams: straight to the function, as a run streams for minutes, longer than a CDN waits between bytes. */
  private val streamUrl: String get() = if (baseUrl == DEFAULT_URL) DEFAULT_STREAM_URL else baseUrl

  /** Sends the owner's message with its run streamed: once tafs cloud took it, the run's events follow. A refusal throws, as [sendMessage] does. */
  suspend fun streamMessage(id: String, text: String, files: List<AttachmentRef> = emptyList(), session: String? = null, newChat: Boolean = false): kotlinx.coroutines.flow.Flow<StreamEvent> =
    events("POST", "/v1/tafs/${escaped(id)}/messages/stream", KiffClient.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), messageBody(text, files, session, newChat)).toByteArray())

  /** Picks a run of the person's back up: its steps so far after event [after], then the rest as it goes, until it stops. */
  suspend fun resumeRun(id: String, run: String, after: Int = 0): kotlinx.coroutines.flow.Flow<StreamEvent> =
    events("GET", "/v1/tafs/${escaped(id)}/runs/${escaped(run)}/stream?after=${after.coerceAtLeast(0)}", null)

  /** Stops what the taf is doing in [session]: its latest run is cancelled. One already ended stays as it ended. */
  suspend fun stop(id: String, session: String) {
    send("POST", "/v1/tafs/${escaped(id)}/stop", jsonBody("session" to session))
  }

  private suspend fun events(method: String, path: String, body: ByteArray?): kotlinx.coroutines.flow.Flow<StreamEvent> {
    val bearer = token ?: throw CloudError(401, Words.Error.signInFirst)
    val headers = linkedMapOf("Accept" to "text/event-stream", "Authorization" to "Bearer $bearer", "Accept-Language" to KiffClient.screenLanguage())
    if (body != null) headers["Content-Type"] = "application/json"
    val response = transport.stream(HttpRequest(method, streamUrl + path, headers, body))
    if (response.status != 200) noteRefusal(response.refusal)
    if (response.status != 200) throw CloudError(response.status, refusal(response.refusal) ?: Words.Error.cloudAnswered(status = response.status.toString()), blockingRuns(response.refusal), field(response.refusal, "words"), field(response.refusal, "code"), field(response.refusal, "halted"))
    return StreamEvent.parse(response.lines)
  }

  // MARK: Coach drives the app (docs/drive.md)

  /**
   * Starts the person's drive for one request: `POST /v1/coach/drive {session, task, component}`, signed with the approval key
   * as a setup call to KIFF (`kiff.request.v1` over this request, in `KIFF-Device-Signature`). [sign] asks for the fingerprint
   * and signs what it's given only then, so the signed time is when the person approved. tafs cloud starts it only for the
   * approval key, outside any window: 403 (`critical_key_required`) otherwise, and 404 for a card that isn't theirs.
   */
  suspend fun startDrive(session: String, task: String, component: String?, tenant: String, sign: suspend (() -> ByteArray) -> String): bot.tafs.app.drive.DriveInfo {
    val path = "/v1/coach/drive"
    val payload = KiffClient.json.encodeToString(
      kotlinx.serialization.json.JsonObject.serializer(),
      kotlinx.serialization.json.buildJsonObject {
        put("session", session)
        put("task", task)
        component?.let { put("component", it) }
      },
    ).toByteArray()
    var signedAt = ""
    val signature = sign {
      signedAt = signingTime.next()
      KiffClient.requestMessage(tenant, "POST", path, payload, signedAt)
    }
    val headers = mapOf("KIFF-Device-Signature" to "t=$signedAt,v1=$signature")
    return KiffClient.json.decodeFromString(bot.tafs.app.drive.DriveStarted.serializer(), String(send("POST", path, payload, headers))).drive
  }

  /** The drive's requests, on the streaming function's address (a stream lives 14 minutes, longer than a CDN waits). */
  override suspend fun driveStream(drive: String): kotlinx.coroutines.flow.Flow<bot.tafs.app.drive.DriveEvent> {
    val bearer = token ?: throw CloudError(401, Words.Error.signInFirst)
    val headers = linkedMapOf("Accept" to "text/event-stream", "Authorization" to "Bearer $bearer", "Accept-Language" to KiffClient.screenLanguage())
    val response = transport.stream(HttpRequest("GET", streamUrl + "/v1/coach/drive/${escaped(drive)}/stream", headers))
    if (response.status != 200) throw CloudError(response.status, refusal(response.refusal) ?: Words.Error.cloudAnswered(status = response.status.toString()))
    return bot.tafs.app.drive.DriveEvent.parse(response.lines)
  }

  override suspend fun driveAnswer(drive: String, request: String, answer: bot.tafs.app.drive.DriveAnswer) {
    sendJson("POST", "/v1/coach/drive/${escaped(drive)}/results/${escaped(request)}", answer.body)
  }

  override suspend fun stopDrive(drive: String, why: String) {
    send("POST", "/v1/coach/drive/stop", jsonBody("drive" to drive, "why" to why))
  }

  override suspend fun pauseDrive(drive: String) {
    send("POST", "/v1/coach/drive/pause", jsonBody("drive" to drive))
  }

  override suspend fun resumeDrive(drive: String): bot.tafs.app.drive.DriveResumed? {
    // A refusal is thrown (a 404 ends the drive); an answer that doesn't read is nothing of use.
    val body = send("POST", "/v1/coach/drive/resume", jsonBody("drive" to drive))
    return runCatching { KiffClient.json.decodeFromString<bot.tafs.app.drive.DriveResumed>(String(body)) }.getOrNull()
  }

  override suspend fun readDrive(): bot.tafs.app.drive.DriveRead = get("/v1/coach/drive")

  companion object {
    const val DEFAULT_URL = "https://cloud.tafs.kiff.dev"

    /** A logo's path on tafs cloud, `/v1/logos/` and its 32-hex key, and nothing else: no scheme, host, query or `..`. */
    fun isLogoPath(path: String?): Boolean = path != null && Regex("^/v1/logos/[0-9a-f]{32}$").matches(path)
    /** tafs cloud's streaming function, as the iPhone app reaches it. */
    const val DEFAULT_STREAM_URL = "https://ghjfunc5ppretjo22bl5izlnya0zrocm.lambda-url.eu-west-1.on.aws"
  }
}
