package bot.tafs.wear

/**
 * An answer's words as the watch draws and speaks them: plain text, its markdown taken away (the owner's Galaxy Watch 5 re-test
 * showed `**Versailles**` as written). The watch draws no rich text: bold and italics keep their words, a link its words, code its
 * text, a heading its line, and a list item a bullet. Anything else is left as tafs cloud wrote it.
 */
object Plain {
  private val FENCE = Regex("^\\s*```.*$", RegexOption.MULTILINE)
  private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+", RegexOption.MULTILINE)
  private val QUOTE = Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE)
  private val BULLET = Regex("^(\\s*)[*+-]\\s+", RegexOption.MULTILINE)
  private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
  private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
  private val CODE = Regex("`([^`]+)`")
  private val STRONG = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
  private val STAR = Regex("(?<![\\w*])\\*(?=\\S)([^*]+?)(?<=\\S)\\*(?![\\w*])")
  private val UNDERSCORE = Regex("(?<![\\w_])_(?=\\S)([^_]+?)(?<=\\S)_(?![\\w_])")
  private val BLANKS = Regex("\\n{3,}")

  fun of(text: String): String {
    var s = text
    s = FENCE.replace(s, "")
    s = HEADING.replace(s, "")
    s = QUOTE.replace(s, "")
    s = BULLET.replace(s) { "${it.groupValues[1]}• " }
    s = IMAGE.replace(s) { it.groupValues[1] }
    s = LINK.replace(s) { it.groupValues[1] }
    s = CODE.replace(s) { it.groupValues[1] }
    s = STRONG.replace(s) { it.groupValues[2] }
    s = STAR.replace(s) { it.groupValues[1] }
    s = UNDERSCORE.replace(s) { it.groupValues[1] }
    return BLANKS.replace(s, "\n\n").trim()
  }
}
