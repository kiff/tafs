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
      is PlanqueError.Server -> e.status
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

  const val OFFLINE = "It didn't reach the server. Try again once you're online."
  const val SIGNED_OUT = "You're signed out. Sign in again."
  const val NOT_ALLOWED = "Your role on this account doesn't allow this."
  const val CHANGED = "It changed in the meantime. What's shown now is how it is."
  const val BUSY = "Too many tries just now. Try again in a minute."
  const val DOWN = "It isn't working on our side right now. Nothing to do on your side; try again later."
  const val OURS = "This phone and the server didn't understand each other. Nothing to do on your side; we're on it."
}

/** A failure the app words itself, for the owner as it is: [ErrorWords] passes it on unchanged. */
class OwnWords(message: String) : Exception(message)
