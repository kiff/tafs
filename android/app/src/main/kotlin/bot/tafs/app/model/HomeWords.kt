package bot.tafs.app.model

/** The home list's own words, the same on iPhone and iPad. */
object HomeWords {
  /** Under the sample's list: what's sample, and where a new taf starts once signed in (the sample has no Coach). */
  val SAMPLE_FOOT get() = Words.Home.sampleFoot

  /** Under "No tafs yet.": setting one up is Coach's, so someone who can't use Coach is told where tafs shared with them show. */
  fun empty(mayUseCoach: Boolean): String =
    if (mayUseCoach) Words.Home.Empty.coach else Words.Home.Empty.shared
}
