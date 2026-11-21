package bot.tafs.watchlink

/**
 * Right to left, the same on the phone and the watch (docs/android-app.md, "Right to left"; docs/watch-wear.md): which languages
 * are written right to left, a value set apart inside a sentence, and text with its direction characters taken out. The phone's
 * `Res` and the watch's `Res` both fill their lines through [isolated], so a Latin name or an amount keeps its own direction in
 * an Arabic sentence on either screen, as iOS's `Words.isolated` (#1557).
 */
object Bidi {
  /** The languages written right to left (their codes as `languages.json` and Android name them). */
  private val RIGHT_TO_LEFT = setOf("ar", "he", "iw", "fa", "ur", "yi", "ji", "ps", "sd", "ug", "dv", "ckb")

  fun rightToLeft(language: String): Boolean = language.substringBefore('-').substringBefore('_').lowercase() in RIGHT_TO_LEFT

  /**
   * In a right-to-left language each text value is set apart (U+2068 FIRST STRONG ISOLATE … U+2069 POP DIRECTIONAL ISOLATE),
   * so a taf's or person's name in Latin letters takes its own direction without turning the sentence around it. Inside, the
   * value carries no direction character of its own, so none can close it early, whoever calls. Left to right, and for numbers,
   * values are exactly as passed.
   */
  fun isolated(args: Array<out Any?>, language: String): Array<out Any?> =
    if (!rightToLeft(language)) args else Array(args.size) { i -> (args[i] as? CharSequence)?.let { "⁨${withoutDirection(it.toString())}⁩" } ?: args[i] }

  /** The text with U+202A–U+202E, U+2066–U+2069, U+200E, U+200F and U+061C taken out, and nothing else changed. */
  fun withoutDirection(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
      val c = text.codePointAt(i)
      if (!(c in 0x202A..0x202E || c in 0x2066..0x2069 || c == 0x200E || c == 0x200F || c == 0x061C)) out.appendCodePoint(c)
      i += Character.charCount(c)
    }
    return out.toString()
  }
}
