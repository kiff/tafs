package bot.tafs.app.model

import bot.tafs.app.kiff.CloudError
import bot.tafs.app.kiff.KiffError
import bot.tafs.app.kiff.PlanqueError
import bot.tafs.app.signing.SignerException

/**
 * Why something didn't go through, in the app's own words: what happened, then what the owner can do, if anything. A server's
 * text never reaches the screen, except tafs cloud's `words`, which it writes for the owner (a run kiff/agents refused, a switch
 * a run blocks): those show as they come, on every path, and "try again" is said only where trying again can help: offline, busy, or briefly down. A
 * refusal the owner can't change says there's nothing to do on their side. The app's own errors (signing, signing in, a request
 * that doesn't match) keep their words, which already say this.
 */
object ErrorWords {
  fun of(e: Throwable): String {
    val status = when (e) {
      is CloudError -> e.words?.let { return it } ?: e.status
      is KiffError.Server -> e.status
      // A vendor that refuses planque's client (Qonto allow-lists who may sign in): planque's own words, which say whose it is,
      // not "it isn't working on our side".
      is PlanqueError.Server -> if (e.code in PLANQUE_WORDED && !e.message.isNullOrBlank()) return e.message!! else e.status
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

  /** planque's codes whose words are shown as planque says them. */
  val PLANQUE_WORDED = setOf("vendor_refused_client")

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
