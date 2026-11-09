package bot.tafs.app.model

/** A taf's Setup tab, in the app's words where tafs cloud sends none yet: how it runs, where its results go. */
object SetupWords {
  val TITLE get() = Words.Setup.title
  val JOB get() = Words.Setup.job
  val STARTS_WHEN get() = Words.Setup.startsWhen
  val CONNECTED_APPS get() = Words.Setup.connectedApps
  val RESULTS_GO_TO get() = Words.Setup.resultsGoTo
  val OPEN get() = Words.Setup.open
  val EDIT get() = Words.Setup.edit
  val ADD_DOOR get() = Words.Setup.addDoor
  val CONNECT_APP get() = Words.Setup.connectApp
  val CHOOSE_ACTIONS get() = Words.Setup.chooseActions
  val NO_START get() = Words.Setup.noStart

  val SCHEDULE get() = Words.Setup.schedule
  val IF_BUSY get() = Words.Setup.ifBusy
  val SKIP_IT get() = Words.Setup.skipIt
  val RUN_AFTER get() = Words.Setup.runAfter
  val APP_EVENTS get() = Words.Setup.appEvents
  val APPROVE get() = Words.Setup.approve
  val LISTENING get() = Words.Setup.listening
  val STOP get() = Words.Setup.stop
  val APPROVE_NOTE get() = Words.Setup.approveNote
  val DOORS get() = Words.Setup.doors
  val FOLLOW_UPS get() = Words.Setup.followUps
  val REMOVE get() = Words.Setup.remove
  val PAUSE_EVERY get() = Words.Setup.pauseEvery
  val RESUME_EVERY get() = Words.Setup.resumeEvery
  val NOT_EVERY_PAUSED get() = Words.Setup.notEveryPaused

  val AT_LEAST_ONE get() = Words.Setup.atLeastOne
  val FROM_TAFS get() = Words.Setup.fromTafs
  val IN_APPS get() = Words.Setup.inApps
  val ADD_PLACE get() = Words.Setup.addPlace
  val NOT_SENT get() = Words.Setup.notSent
  val SEND_AGAIN get() = Words.Setup.sendAgain

  /** tafs' own places, by `place`: what each is called, and what it sends. */
  fun placeTitle(place: String): String = when (place) {
    "door_reply" -> Words.Setup.Place.replyTitle
    "app" -> Words.Setup.Place.appTitle
    "owner_email" -> Words.Setup.Place.emailTitle
    else -> place
  }

  fun placeWords(place: String, ownerEmail: String?): String = when (place) {
    "door_reply" -> Words.Setup.Place.replyWords
    "app" -> Words.Setup.Place.appWords
    "owner_email" -> ownerEmail?.let { Words.Setup.Place.emailWords(it) } ?: Words.Setup.Place.emailWordsYours
    else -> ""
  }

  /** tafs' own places, in the order they're shown. */
  val TAFS_PLACES = listOf("door_reply", "app", "owner_email")

  /** Under its schedule: "Next run at 13:00 · Europe/Paris". */
  fun nextRun(time: String, zone: String?): String = Words.Setup.nextRun(time) + (zone?.let { " · $it" } ?: "")
}
