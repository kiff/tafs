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
  const val NO_TAF = "Make a taf first: an app is connected for a taf to use."
  const val CANCEL = "Not now"
}
