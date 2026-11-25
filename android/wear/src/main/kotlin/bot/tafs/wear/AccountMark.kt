package bot.tafs.wear

import bot.tafs.watchlink.WatchReply

/**
 * The phone's account mark the watch last saw (docs/watch-wear.md, "What the watch keeps"): random, made by the phone at each
 * sign-in, so it says only "the same sign-in" or "not", never whose. Kept in the watch's private preferences.
 */
interface MarkStore {
  fun read(): String?
  fun write(mark: String)
  fun clear()
}

/** [MarkStore] in the watch's private preferences (`tafs.account`): the mark alone. */
class PreferencesMarkStore(context: android.content.Context) : MarkStore {
  private val prefs = context.getSharedPreferences("tafs.account", android.content.Context.MODE_PRIVATE)
  override fun read(): String? = prefs.getString(MARK, null)
  override fun write(mark: String) { prefs.edit().putString(MARK, mark).apply() }
  override fun clear() { prefs.edit().clear().apply() }

  private companion object {
    const val MARK = "mark"
  }
}

/**
 * What the watch does with the phone's account mark. It fails closed: once the watch holds a mark, anything of an account it keeps
 * is drawn only after a reply this process carried that same mark, and a reply with another mark, or with none, wipes first.
 */
object AccountMark {
  /** A reply that carries nothing of an account (a failure, a refusal, no offline speech, unsure, already answered): it proves nothing, and wipes nothing. */
  fun neutral(reply: WatchReply): Boolean = reply is WatchReply.Failed || reply is WatchReply.Refused ||
    reply == WatchReply.NoOfflineSpeech || reply == WatchReply.Unsure || reply == WatchReply.AlreadyAnswered

  enum class Seen {
    /** No mark held, none sent: a phone before marks, as before them. */
    Legacy,

    /** The first mark the watch sees: kept, nothing to wipe. */
    First,

    /** The mark held: what's kept is this sign-in's. */
    Same,

    /** Another sign-in: everything kept goes first. */
    Changed,

    /** A mark held, and a reply without one: fails closed, everything kept goes and the reply isn't shown. */
    Missing,
  }

  fun seen(stored: String?, mark: String?): Seen = when {
    mark == null -> if (stored == null) Seen.Legacy else Seen.Missing
    stored == null -> Seen.First
    stored == mark -> Seen.Same
    else -> Seen.Changed
  }

  /**
   * A kept item from the phone is taken in only when its mark is the one held. One with a mark, while the watch holds none, is
   * dropped (the next checked read brings it back); only an item with no mark (a phone before marks) is taken while it holds
   * none. A kept item never sets or changes the mark held: only a checked reply does.
   */
  fun keptTaken(stored: String?, mark: String?): Boolean = if (mark == null) stored == null else mark == stored

  /**
   * The phone's signed-out item wipes only the sign-in it names, or any while the watch holds none. It sits at one path, so the
   * Data Layer keeps only the newest; and whatever order it arrives in, one naming an earlier sign-in than the one held (or none,
   * once one is held) wipes nothing, so a late one never touches the next account. It only ever deletes: nothing comes back.
   */
  fun signedOutWipes(stored: String?, mark: String?): Boolean = stored == null || mark == stored
}
