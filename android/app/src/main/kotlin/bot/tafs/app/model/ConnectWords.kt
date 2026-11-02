package bot.tafs.app.model

/** Connecting an app from Connect apps, in the app's words where tafs cloud sends none. */
object ConnectWords {
  /** A vendor that takes an API key: giving one is Coach's for now. */
  fun needsKey(app: String): String = "$app connects with a key. Ask Coach to set it up, and you'll give the key there."

  /** Where the approval sheet's own note is kept, apart from Coach's cards. */
  fun cardKey(grantId: String): String = "connect:$grantId"

  const val APPROVE_TITLE = "Approve this connection"
  const val CANCEL = "Not now"
}
