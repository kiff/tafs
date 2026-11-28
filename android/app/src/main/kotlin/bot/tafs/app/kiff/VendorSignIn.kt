package bot.tafs.app.kiff


import bot.tafs.app.model.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The owner closed the vendor's sign-in page, or left it unfinished: nothing was connected. */
class VendorSignInCancelled : Exception() {
  override val message: String get() = Words.Connect.signInClosed
}

/**
 * The sign-in's link was given up for a new approval: a vendor's sign-in link is single-use, so a page left behind isn't
 * opened again. Nothing is answered or reported for it; the same card's approval runs again (`AppModel.reopenSignIn`).
 */
class VendorSignInReplaced : Exception()

/**
 * The vendor's own sign-in page, which the phone opens and which planque sends the owner back from with a confirmation code:
 * the phone never sees the vendor's credentials. The app opens it in a browser tab; tests stand in.
 */
interface VendorSignIn {
  /** Opens [url] and answers the link the vendor's sign-in came back to (`tafs://planque/signed-in?...`); throws [VendorSignInCancelled] if it never does. */
  suspend fun signIn(url: String): String

  /** The owner came back to tafs with no link: the vendor's page may still be open behind, or was closed. Nothing is dropped. */
  val left: StateFlow<Boolean> get() = NeverLeft

  /** Opens the same vendor's page again, for a sign-in [left]. Only where no new approval can run instead ([replace]). */
  fun reopen() {}

  /** Ends the wait on a sign-in [left] with [VendorSignInReplaced], so its approval runs again for planque's answer. */
  fun replace() {}

  /** The owner gave up waiting: the sign-in ends as closed ([VendorSignInCancelled]). */
  fun cancel() {}
}

private val NeverLeft: StateFlow<Boolean> = MutableStateFlow(false)

/** A build or test with no browser to open: every sign-in is closed. */
object NoVendorSignIn : VendorSignIn {
  override suspend fun signIn(url: String): String = throw VendorSignInCancelled()
}
