package bot.tafs.app.model

/**
 * The ordinals the app writes itself, where the system's ICU writes none that fits a Card's "this would be the 6th call"
 * (`card.json`'s `{nth}` lines): Turkmen, Urdu and Mongolian get English's "6th" from ICU, Bengali gets English suffixes on
 * its own digits, and Hindi's are masculine while a call (कॉल) is feminine. The suffixes follow CLDR's ordinal classes for
 * each language; iOS's `Words.ordinal` and the desktops write the same. Every other language
 * stays the system's ([written] gives null).
 */
object Ordinals {
  /** [n] in order in [language], around [figure] (n as the app writes its figures), or null for the system's ordinal. */
  fun written(n: Long, language: String, figure: String): String? = when (language) {
    // Turkmen: "few" (n % 10 = 6 or 9, or n = 10) takes -njy, the rest -nji.
    "tk" -> if (n % 10 == 6L || n % 10 == 9L || n == 10L) "$figure-njy" else "$figure-nji"
    // Urdu: ویں after every number.
    "ur" -> "${figure}ویں"
    // Hindi, feminine: ली, री, री, थी, ठी, then वीं; 0 stays bare.
    "hi" -> if (n == 0L) figure else figure + when (n) {
      1L -> "ली"
      2L, 3L -> "री"
      4L -> "थी"
      6L -> "ठी"
      else -> "वीं"
    }
    // Bengali, by class: one ম, two য় (the two-character form), few র্থ, many ষ্ঠ, other তম.
    "bn" -> figure + when (n) {
      1L, 5L, 7L, 8L, 9L, 10L -> "ম"
      2L, 3L -> "য়"
      4L -> "র্থ"
      6L -> "ষ্ঠ"
      else -> "তম"
    }
    // Mongolian: -р after every number.
    "mn" -> "$figure-р"
    else -> null
  }
}
