package bot.tafs.app.model

/** A taf's Setup tab, in the app's words where tafs cloud sends none yet: how it runs, where its results go. */
object SetupWords {
  const val TITLE = "Setup"
  const val JOB = "The job"
  const val STARTS_WHEN = "Starts when"
  const val CONNECTED_APPS = "Connected apps"
  const val RESULTS_GO_TO = "Results go to"
  const val OPEN = "Open ›"
  const val EDIT = "Edit"
  const val ADD_DOOR = "Add a door…"
  const val CONNECT_APP = "Connect an app…"
  const val CHOOSE_ACTIONS = "Choose actions"
  const val NO_START = "When you tell it, in its chat."

  const val SCHEDULE = "Its schedule"
  const val IF_BUSY = "If it is still working when the next start comes"
  const val SKIP_IT = "Skip it"
  const val RUN_AFTER = "Run after"
  const val APP_EVENTS = "App events"
  const val APPROVE = "Approve"
  const val LISTENING = "Listening"
  const val APPROVE_NOTE = "Approve signs on this phone; the app starts telling the taf only after that."
  const val DOORS = "Doors"
  const val FOLLOW_UPS = "Follow-ups it set itself"
  const val REMOVE = "Remove"
  const val PAUSE_EVERY = "Pause every start"
  const val RESUME_EVERY = "Resume every start"
  const val NOT_EVERY_PAUSED = "Not every start could be paused. Try again."

  const val AT_LEAST_ONE = "At least one place stays on."
  const val FROM_TAFS = "From tafs"
  const val IN_APPS = "In your connected apps"
  const val ADD_PLACE = "Add a place from a connected app…"
  const val NOT_SENT = "Not sent"
  const val SEND_AGAIN = "Send again"

  /** tafs' own places, by `place`: what each is called, and what it sends. */
  fun placeTitle(place: String): String = when (place) {
    "door_reply" -> "Reply to the sender"
    "app" -> "Alert our phones"
    "owner_email" -> "Email me"
    else -> place
  }

  fun placeWords(place: String, ownerEmail: String?): String = when (place) {
    "door_reply" -> "On the same thread, through the door it came in on"
    "app" -> "Who was answered and through which door, never its words"
    "owner_email" -> "Its answer to ${ownerEmail ?: "your email"}; not when you wrote the message"
    else -> ""
  }

  /** tafs' own places, in the order they're shown. */
  val TAFS_PLACES = listOf("door_reply", "app", "owner_email")

  /** Under its schedule: "Next run at 13:00 · Europe/Paris". */
  fun nextRun(time: String, zone: String?): String = "Next run at $time" + (zone?.let { " · $it" } ?: "")
}
