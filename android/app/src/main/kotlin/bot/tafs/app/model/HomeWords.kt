package bot.tafs.app.model

/** The home list's own words, the same on iPhone and iPad. */
object HomeWords {
  // The gallery's words are tafs cloud's (`GET /v1/recipes/gallery`); the app's own stand in only while it sends none.
  private fun cloud(words: String?, own: String): String = words?.takeIf { it.isNotBlank() } ?: own

  /** + New's words. */
  fun newTaf(gallery: bot.tafs.app.kiff.RecipesGallery?): String = cloud(gallery?.words?.title, Words.Home.newTaf)

  /** Explore jobs: the row on Tafs, and the gallery's title while browsing. */
  fun explore(gallery: bot.tafs.app.kiff.RecipesGallery?): String = cloud(gallery?.words?.explore, Words.Home.exploreJobs)

  /** The row under the strip that opens every job. */
  fun seeAll(gallery: bot.tafs.app.kiff.RecipesGallery?): String = cloud(gallery?.words?.seeAll, Words.Home.seeAllJobs)

  /** A job's one action while browsing. */
  fun tryIt(gallery: bot.tafs.app.kiff.RecipesGallery?): String = cloud(gallery?.words?.tryIt, Words.Home.tryWithCoach)

  /** The label over a job's sample report. */
  fun sampleLabel(gallery: bot.tafs.app.kiff.RecipesGallery?): String = cloud(gallery?.words?.sample, Words.Home.sampleLabel)

  /** Under the sample's list: what's sample, and where a new taf starts once signed in (the sample has no Coach). */
  val SAMPLE_FOOT get() = Words.Home.sampleFoot

  /** Under "No tafs yet.": setting one up is Coach's, so someone who can't use Coach is told where tafs shared with them show. */
  fun empty(mayUseCoach: Boolean): String =
    if (mayUseCoach) Words.Home.Empty.coach else Words.Home.Empty.shared
}
