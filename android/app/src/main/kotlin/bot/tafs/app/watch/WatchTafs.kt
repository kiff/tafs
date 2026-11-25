package bot.tafs.app.watch

import bot.tafs.app.app.AppState
import bot.tafs.app.app.Mode
import bot.tafs.watchlink.TafLine
import bot.tafs.watchlink.WatchLink
import bot.tafs.watchlink.WatchReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The account's taf list, kept for the watch as it changes on this phone (docs/watch-wear.md, Delivery): after a sign-in, a taf
 * added, removed, paused, resumed or halted, or a name changed, the phone puts the list the watch's Tafs page draws as one urgent
 * Data Layer item (`kept/tafs`), with the account mark, so the watch's list follows the phone's without asking, and never goes
 * empty because one read was slow. Only once the watch has used tafs in this sign-in ([AppState.watchServed]), only on a change
 * (compared with the last put), never on a timer.
 */
class WatchTafs(
  private val keep: KeptPut,
  /** The phone's account mark now ([bot.tafs.app.app.AppModel.watchMark]). */
  private val mark: () -> String? = { null },
  private val encoder: TafsListEncoder = KeptTafsJson,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private var sent: WatchReply.Tafs? = null

  /** The item to put for [state], or null: nothing read yet, signed out, the watch not served, or the list as last put. */
  @Synchronized fun changed(state: AppState): Pair<String, ByteArray>? {
    if (state.mode != Mode.kiff || !state.listLoaded || state.listUnread || !state.watchServed) return null
    val list = WatchRelay.tafs(state)
    if (list == sent) return null
    sent = list
    return encoder.path to encoder.encode(list, mark(), now())
  }

  /** Signed out on the phone: the next sign-in's list is put whole, whatever was put before. */
  @Synchronized fun forget() {
    sent = null
  }

  /** Follows the phone's state in [scope] (the app's), putting each change of the list for the watch. */
  fun follow(states: Flow<AppState>, scope: CoroutineScope) {
    scope.launch {
      states.map { it }.distinctUntilChanged { a, b -> a.tafs == b.tafs && a.listLoaded == b.listLoaded && a.listUnread == b.listUnread && a.mode == b.mode && a.halts == b.halts && a.watchServed == b.watchServed }
        .collect { state -> changed(state)?.let { (path, data) -> runCatching { keep.put(path, data) } } }
    }
  }
}

/**
 * How the taf list is written for the watch: where (`kept/tafs`) and in what shape. The watch reads it with watchlink's own type;
 * the phone writes the same JSON through this, so the shared type replaces [KeptTafsJson] in one place.
 */
interface TafsListEncoder {
  val path: String
  fun encode(list: WatchReply.Tafs, mark: String?, putAt: Long): ByteArray
}

/**
 * `kept/tafs`: `{put_at, tafs: [TafLine], all_halted, zone?, account_mark?}`, the fields of [WatchReply.Tafs] with when it was put
 * and the phone's account mark, as every other kept item.
 */
object KeptTafsJson : TafsListEncoder {
  override val path: String = WatchLink.KEPT + "tafs"

  @Serializable
  private data class Kept(
    @SerialName("put_at") val putAt: Long,
    val tafs: List<TafLine>,
    @SerialName("all_halted") val allHalted: Boolean,
    val zone: String? = null,
    @SerialName(WatchLink.MARK_KEY) val mark: String? = null,
  )

  private val json = Json { explicitNulls = false; encodeDefaults = true }

  override fun encode(list: WatchReply.Tafs, mark: String?, putAt: Long): ByteArray =
    json.encodeToString(Kept.serializer(), Kept(putAt, list.tafs, list.allHalted, list.zone, mark?.takeIf(WatchLink::isMark))).encodeToByteArray()
}
