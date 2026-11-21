package bot.tafs.app.model

import bot.tafs.app.kiff.CloudError
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.kiff.PlanqueError
import bot.tafs.app.signing.SignerException

/**
 * Why something didn't go through, in the app's own words: what happened, then what the owner can do, if anything. A server's
 * text never reaches the screen, except tafs cloud's `words`, which it writes for the owner (a run kiff/agents refused, a switch
 * a run blocks), and KIFF's words for a refusal by a code in [KIFF_WORDED] (not offered where the person is): those show as they come, on every path, and "try again" is said only where trying again can help: offline, busy, or briefly down. A
 * refusal the owner can't change says there's nothing to do on their side. The app's own errors (signing, signing in, a request
 * that doesn't match) keep their words, which already say this.
 */
object ErrorWords {
  fun of(e: Throwable): String {
    val status = when (e) {
      is CloudError -> e.words?.let { return it }
        // A message id already used for other words (docs/api.md, "One message, sent once"): never "It changed in the meantime".
        ?: if (e.status == 409 && e.code == "dedup_key_conflict") return Words.Error.sentWithOtherText
        else if (e.code in CLOUD_WORDED && !e.message.isNullOrBlank()) return e.message!! else e.status
      is KiffError.Server -> kiffWords(e)?.let { return it } ?: e.status
      // A vendor that refuses planque's client (one that allow-lists who may sign in): planque's own words, which say whose it is,
      // not "it isn't working on our side".
      // planque's own line for the person, when it sends one: never a line of the app's over it ("It changed in the meantime"
      // read as "try again" over a sign-in to another account, and the owner looped).
      is PlanqueError.Server -> if (!e.words.isNullOrBlank()) return e.words
        else if (e.code in PLANQUE_WORDED && !e.message.isNullOrBlank()) return e.message!!
        // A connect that failed on planque's side (planque#329): its reason is for Coach, never "It changed in the meantime",
        // which its 409 would otherwise read as.
        else if (e.code == CONNECT_FAILED) return DOWN else e.status
      is SignerException, is KiffError, is PlanqueError, is OwnWords -> return e.message.orEmpty()
      is java.io.IOException -> return OFFLINE
      else -> return OURS
    }
    return of(status)
  }

  fun of(status: Int): String = when (status) {
    401 -> SIGNED_OUT
    403 -> NOT_ALLOWED
    409 -> CHANGED
    429 -> BUSY
    in 500..599 -> DOWN
    else -> OURS
  }

  /**
   * KIFF's own words for a refusal the person can't change from the app, by its stable code: where KIFF isn't offered
   * (`region_unsupported`), its words say so in the app's language. Shown as sent, never "You're not allowed" over them.
   */
  val KIFF_WORDED = setOf("region_unsupported")

  /** KIFF's words for [e], when it's one of [KIFF_WORDED]'s refusals and KIFF sent them. */
  fun kiffWords(e: Throwable): String? = (e as? KiffError.Server)?.takeIf { it.code in KIFF_WORDED }?.words?.takeIf { it.isNotBlank() }

  /** tafs cloud's codes whose `error` is shown as sent, where it sends no `words`: "‹taf› doesn't use this app", never "It changed". */
  val CLOUD_WORDED = setOf("not_needed")

  /** planque's codes whose `error` is shown as planque says it, from before planque sent `words`. */
  val PLANQUE_WORDED = setOf("vendor_refused_client")

  /**
   * planque's 409 for a connect that ended failed (planque#329): its `error` is the reason for the asking agent (a tool name the
   * vendor no longer lists, an argument's type), with connector ids, so the person reads that it isn't working on our side.
   */
  const val CONNECT_FAILED = "connect_failed"

  val OFFLINE get() = Words.Error.offline
  val SIGNED_OUT get() = Words.Error.youreSignedOut
  val NOT_ALLOWED get() = Words.Error.notAllowed
  val CHANGED get() = Words.Error.changed
  val BUSY get() = Words.Error.busy
  val DOWN get() = Words.Error.down
  val OURS get() = Words.Error.ours
}

/** A failure the app words itself, for the owner as it is: [ErrorWords] passes it on unchanged. */
class OwnWords(message: String) : Exception(message)
