package bot.tafs.app.model

/** Connecting an app from Connect apps, in the app's words where tafs cloud sends none. */
object ConnectWords {
  /** An API-key connection's field on its approval sheet, and its button: the key is given with the same confirmation. */
  val KEY get() = Words.Connect.key
  fun giveKeyWith(biometry: String): String = Words.Connect.giveKeyWith(biometry)

  /** Where the approval sheet's own note is kept, apart from Coach's cards. */
  fun cardKey(grantId: String): String = "connect:$grantId"

  val APPROVE_TITLE get() = Words.Connect.approveTitle
  val WHICH_TAF get() = Words.Connect.whichTaf

  /** A row signed in at the app, its actions still to choose: it opens the taf's Coach. */
  val SIGNED_IN_ROW get() = Words.Connect.signedInRow
  val CHOOSE_ACTIONS get() = Words.Connect.chooseActions
  val DONE get() = Words.Common.done

  /** Said at once when a sign-in comes back: what happened, and the next step. */
  fun signedInFor(app: String, taf: String?): String =
    if (taf == null) Words.Connect.signedIn(app) else Words.Connect.signedInFor(app, taf)

  /** Said at once when a connection comes back connected. */
  fun connectedFor(app: String, taf: String?): String = if (taf == null) Words.Connect.connectedApp(app) else Words.Connect.connectedAppFor(app, taf)

  /** Under the question: what choosing a taf means. */
  fun oneTaf(app: String): String = Words.Connect.oneTaf(app)
  val NO_TAF get() = Words.Connect.noTaf
  val CANCEL get() = Words.Common.notNow
}
