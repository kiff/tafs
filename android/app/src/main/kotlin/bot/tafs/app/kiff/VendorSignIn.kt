package bot.tafs.app.kiff

import bot.tafs.app.model.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The owner closed the vendor's sign-in page, or left it unfinished: nothing was connected. */
class VendorSignInCancelled : Exception() {
  override val message: String get() = Words.Connect.signInClosed
}

/**
 * The vendor's own sign-in page, which the phone opens and which planque sends the owner back from with a confirmation code:
 * the phone never sees the vendor's credentials. The app opens it in a browser tab; tests stand in.
 */
interface VendorSignIn {
  /** Opens [url] and answers the link the vendor's sign-in came back to (`tafs://planque/signed-in?...`); throws [VendorSignInCancelled] if it never does. */
  suspend fun signIn(url: String): String

  /** The owner came back to tafs with no link: the vendor's page may still be open behind, or was closed. Nothing is dropped. */
  val left: StateFlow<Boolean> get() = NeverLeft

  /** Opens the same vendor's page again, for a sign-in [left] (planque takes the same link until its approve expires). */
  fun reopen() {}
}

private val NeverLeft: StateFlow<Boolean> = MutableStateFlow(false)

/** A build or test with no browser to open: every sign-in is closed. */
object NoVendorSignIn : VendorSignIn {
  override suspend fun signIn(url: String): String = throw VendorSignInCancelled()
}
