package bot.tafs.watchlink

/**
 * Amounts out of words a watch keeps (docs/watch-wear.md, "What the watch keeps"): the one rule the phone's relay and the watch
 * share, so "never an amount" on the watch is one promise kept in one place. Any figure with a currency beside it, spaced or not
 * (150zł, 150 EUR, €84): a sign, a code or a word (€, zł, kr, Fr., PLN, euros, dollars, Franken, dolarów…), before it first and
 * then after it, so in "order 1042 EUR 150.00" the order's number stays. A figure never starts inside a number, and a space groups
 * thousands only after 1 to 3 digits, so "order 4502 150 €" keeps 4502. A figure with no currency is never cut. What's left is
 * tidied of the spaces, commas and dashes the amount leaves. The phone's relay takes a held call's own amount out first, however
 * it's written (`WatchRelay.withoutAmount`).
 */
object Amounts {
  fun strip(words: String): String = tidy(words.replace(CURRENCY_FIRST, " ").replace(CURRENCY_AFTER, " "))

  /** Whether [strip] takes any amount out of [words]: spacing and edges aside, they differ. */
  fun hasAmount(words: String): Boolean = strip(words) != tidy(words)

  /** The words with their spacing tidied and stray marks off the edges, as [strip] leaves them, with nothing taken out. */
  fun tidy(words: String): String =
    words.replace(SPACES, " ")
      .replace(SPACE_BEFORE_MARK, "$1")
      .trim { it.isWhitespace() || it in ",;:-–—·" }

  /** How thousands are grouped: none, a comma, a point, a space (plain, no-break or narrow) or an apostrophe. */
  val GROUPS = listOf("", ",", ".", " ", " ", " ", "'", "’")
  private const val FIGURE =
    """(?<![\d.,'’])(?:\d{1,3}(?:[   ]\d{3})+(?:[.,]\d+)?|\d+(?:[.,'’]\d{3})*(?:[.,]\d+)?)"""
  const val CURRENCY =
    """(?:[€$£¥₹]|(?<![\p{L}])(?:EUR|USD|GBP|CHF|JPY|CAD|AUD|PLN|SEK|NOK|DKK|zł|złot(?:y|e|ych)|kr|Fr\.|euros?|dollars?|dolar(?:y|ów)?|pounds?|francs?|franken)(?![\p{L}]))"""
  private val CURRENCY_FIRST = Regex("""$CURRENCY\s?$FIGURE""", RegexOption.IGNORE_CASE)
  private val CURRENCY_AFTER = Regex("""$FIGURE\s?$CURRENCY""", RegexOption.IGNORE_CASE)
  private val SPACES = Regex("""\s+""")
  private val SPACE_BEFORE_MARK = Regex("""\s+([,.;:])""")
}
