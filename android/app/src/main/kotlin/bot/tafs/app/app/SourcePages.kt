package bot.tafs.app.app

/** Cached service representations. The phone neither filters nor combines domain records. */
data class SourcePages<T>(val pages: List<T> = emptyList(), val firstEtag: String? = null, val loading: Boolean = false, val error: String? = null)
