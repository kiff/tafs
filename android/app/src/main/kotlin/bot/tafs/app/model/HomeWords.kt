package bot.tafs.app.model

/** The home list's own words, the same on iPhone and iPad. */
object HomeWords {
  /** Under "No tafs yet.": setting one up is Coach's, so someone who can't use Coach is told where tafs shared with them show. */
  fun empty(mayUseCoach: Boolean): String =
    (if (mayUseCoach) "Connect a tool to set up your first taf." else "Tafs shared with you show here.") + " Pull down to refresh."
}
