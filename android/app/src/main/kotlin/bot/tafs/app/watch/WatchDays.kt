package bot.tafs.app.watch

import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.TafsCloud
import bot.tafs.app.kiff.WireWatchDay
import bot.tafs.app.kiff.WireWatchDays
import bot.tafs.app.kiff.WireWatchTurn
import bot.tafs.app.kiff.WireWatchTurns
import java.time.Instant
import java.time.LocalDate

/** A day the person asked from their watch (`GET /v1/watch/days`): the day, in the account's zone, and how many requests. */
data class WatchDay(val date: LocalDate, val count: Int)

/**
 * The days, newest first as tafs cloud sends them, and [today] in the account's zone as tafs cloud gives it (`today`), so a
 * person on another zone than the account's sees "Today" on the right day. Null from a tafs cloud that doesn't give it.
 */
data class WatchDays(val days: List<WatchDay>, val today: LocalDate? = null)

/**
 * One turn of a day (`GET /v1/watch/days/{date}`): what the person said on the watch and when, the taf that took it (none when
 * no taf could), its state, the taf's own line where there is one, and tafs cloud's words for the state. [replyTo] names the
 * turn this one replies to: tafs cloud sends it as the next turn in order, and it's drawn under it.
 */
data class WatchTurn(
  val id: String,
  val said: String,
  val saidAt: Instant?,
  val tafName: String?,
  val state: String,
  val line: String?,
  val words: String,
  val replyTo: String? = null,
  /** tafs cloud says the answer went on past its line: the whole is read on a tap ([WatchDaysStore.full]). */
  val more: Boolean = false,
) {
  /** The taf's side: its line when there is one, else tafs cloud's words for the state, whatever the state. Never the app's own. */
  val answer: String get() = line?.ifBlank { null } ?: words
}

/**
 * tafs cloud's two routes for the watch's days, and nothing else: the seam the sheet is written against. Null from either is
 * tafs cloud's switch off (404 `watch_off`): nothing is drawn. [TafsCloudWatchDaysStore] is the real one; [StubWatchDaysStore]
 * stands in for the sheet's tests.
 */
interface WatchDaysStore {
  /** `GET /v1/watch/days`: the days with requests, newest first, and the account's today. */
  suspend fun days(): WatchDays?

  /** `GET /v1/watch/days/{yyyy-mm-dd}`: that day's turns, oldest first, as tafs cloud orders them. */
  suspend fun day(date: LocalDate): List<WatchTurn>?

  /** `GET /v1/watch/asks/{id}?full=1`: a turn's whole answer, for one tafs cloud says goes on (`more`); null when it gives none. */
  suspend fun full(id: String): String? = null
}

/**
 * tafs cloud's answers as the sheet holds them, in tafs cloud's order. One day or turn that can't be read is left out, never the
 * list; a day or a time that can't be read is never guessed.
 */
object WatchDaysWire {
  fun days(wire: WireWatchDays): WatchDays = WatchDays(
    days = wire.days.mapNotNull { item ->
      val d = runCatching { KiffClient.json.decodeFromJsonElement(WireWatchDay.serializer(), item) }.getOrNull() ?: return@mapNotNull null
      runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { WatchDay(it, d.count) }
    },
    today = wire.today?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
  )

  fun turns(wire: WireWatchTurns): List<WatchTurn> = wire.turns.mapNotNull { item ->
    val t = runCatching { KiffClient.json.decodeFromJsonElement(WireWatchTurn.serializer(), item) }.getOrNull() ?: return@mapNotNull null
    WatchTurn(
      id = t.id,
      said = t.said,
      saidAt = runCatching { Instant.parse(t.saidAt) }.getOrNull(),
      tafName = t.taf?.name,
      state = t.state,
      line = t.line?.ifBlank { null },
      words = t.words,
      replyTo = t.replyTo,
      more = t.more,
    )
  }
}

/** The real store: tafs cloud's routes, with this session's token, as `GET /v1/watch/asks` is read. */
class TafsCloudWatchDaysStore(private val cloud: TafsCloud) : WatchDaysStore {
  override suspend fun days(): WatchDays? = cloud.watchDays()?.let(WatchDaysWire::days)

  override suspend fun day(date: LocalDate): List<WatchTurn>? = cloud.watchDay(date.toString())?.let(WatchDaysWire::turns)

  override suspend fun full(id: String): String? = cloud.watchAskFull(id)
}

/**
 * Sample days from the sample tafs (Late parcels, Support replies, Lead follow-up), as tafs cloud's JSON, read the way the real
 * answers are. Held in memory; one turn's state is one the app doesn't know, drawn with tafs cloud's words.
 */
class StubWatchDaysStore(private val daysJson: String = DAYS) : WatchDaysStore {
  override suspend fun days(): WatchDays = WatchDaysWire.days(KiffClient.json.decodeFromString(WireWatchDays.serializer(), daysJson))

  override suspend fun day(date: LocalDate): List<WatchTurn> =
    TURNS[date.toString()]?.let { WatchDaysWire.turns(KiffClient.json.decodeFromString(WireWatchTurns.serializer(), it)) }.orEmpty()

  companion object {
    /** `GET /v1/watch/days`, newest first, with the account's today. */
    const val DAYS = """{"today":"2026-11-19","days":[{"date":"2026-11-19","count":3},{"date":"2026-11-18","count":1},{"date":"2026-11-16","count":2}]}"""

    /** `GET /v1/watch/days/{date}`, each oldest first. */
    val TURNS = mapOf(
      "2026-11-19" to """{"date":"2026-11-19","turns":[
        {"id":"wa_1","said":"Pause the support replies until noon","said_at":"2026-11-19T09:12:00Z","taf":{"id":"taf_support","name":"Support replies"},"state":"done","line":"Support replies is paused until 12:00.","words":"Done"},
        {"id":"wa_2","said":"And tell me when it starts again","said_at":"2026-11-19T09:14:00Z","taf":{"id":"taf_support","name":"Support replies"},"state":"accepted","words":"Support replies has it","reply_to":"wa_1"},
        {"id":"wa_3","said":"Which parcels are late today?","said_at":"2026-11-19T11:40:00Z","taf":{"id":"taf_parcels","name":"Late parcels"},"state":"working","words":"Late parcels is on it"}
      ]}""",
      "2026-11-18" to """{"date":"2026-11-18","turns":[
        {"id":"wa_4","said":"Book a table for Friday","said_at":"2026-11-18T18:05:00Z","state":"no_taf","words":"None of your tafs does this yet"}
      ]}""",
      "2026-11-16" to """{"date":"2026-11-16","turns":[
        {"id":"wa_5","said":"Follow up with Nord Atelier","said_at":"2026-11-16T08:30:00Z","taf":{"id":"taf_leads","name":"Lead follow-up"},"state":"done","line":"Sent Nord Atelier a follow-up about the 40 chairs.","words":"Done"},
        {"id":"wa_6","said":"Remind the new leads about Thursday","said_at":"2026-11-16T10:02:00Z","taf":{"id":"taf_leads","name":"Lead follow-up"},"state":"waiting_on_kiff","words":"Waiting on KIFF"}
      ]}""",
    )
  }
}
