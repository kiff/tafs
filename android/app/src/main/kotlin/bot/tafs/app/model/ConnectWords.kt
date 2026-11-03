package bot.tafs.app.model

/** Connecting an app from Connect apps, in the app's words where tafs cloud sends none. */
object ConnectWords {
  /** An API-key connection's field on its approval sheet, and its button: the key is given with the same confirmation. */
  const val KEY = "The key"
  fun giveKeyWith(biometry: String): String = "Give the key with $biometry"

  /** Where the approval sheet's own note is kept, apart from Coach's cards. */
  fun cardKey(grantId: String): String = "connect:$grantId"

  const val APPROVE_TITLE = "Approve this connection"
  const val WHICH_TAF = "Which taf is this for?"

  /** A row signed in at the app, its actions still to choose: it opens the taf's Coach. */
  const val SIGNED_IN_ROW = "Signed in · choose its actions"
  const val CHOOSE_ACTIONS = "Choose its actions"
  const val DONE = "Done"

  /** Said at once when a sign-in comes back: what happened, and the next step. */
  fun signedInFor(app: String, taf: String?): String =
    "$app is signed in" + (taf?.let { " for $it" } ?: "") + ". Next, Coach chooses its actions and you approve them."

  /** Said at once when a connection comes back connected. */
  fun connectedFor(app: String, taf: String?): String = "$app is connected" + (taf?.let { " for $it" } ?: "") + "."

  /** Under the question: what choosing a taf means. */
  fun oneTaf(app: String): String = "$app is connected for one taf, which uses it as its Card allows."
  const val NO_TAF = "Make a taf first: an app is connected for a taf to use."
  const val CANCEL = "Not now"
}
