import Foundation

/// Amounts out of words a watch keeps (its Inbox, watch v2): any figure with a currency sign, code or word before or after it
/// goes, in the ways tafs' languages write money, so "never an amount" holds for every line kept on the watch. Both apps
/// compile this file: the phone strips a held call's summary with it (`WatchRelay.withoutAmount`, after the call's own
/// amount), and the watch strips Coach's replies before it keeps them. What's on screen, in memory, keeps its amounts.
enum WatchAmount {
    /// A currency as titles write it in tafs' languages: a sign, złoty, crowns, Swiss francs, a code, or a word, written
    /// apart or right after its figure ("150zł", "150EUR"), and never inside a longer word ("EUROPE").
    static let currency = #"(?:[€$£¥₹]|(?<!\p{L})(?:zł|kr|Fr\.|EUR|USD|GBP|CHF|JPY|CAD|AUD|PLN|SEK|NOK|DKK"#
        + #"|euros?|dollars?|dolar(?:y|ów)?|pounds?|francs?|franken|złot(?:y|e|ych))(?!\p{L}))"#
    /// A figure grouped by spaces in threes: "1 250,50".
    static let spacedFigure = #"(?<![\d.,'’])\d{1,3}(?:[   ]\d{3})+(?:[.,]\d+)?(?![\d])"#
    /// A figure with commas, points or the Swiss apostrophe: "150", "150.00", "1,234.56", "1.234,56", "1'234.50".
    static let plainFigure = #"(?<![\d.,'’])\d+(?:[.,'’]\d{3})*(?:[.,]\d+)?(?![\d])"#

    /// The words with every figure beside a currency taken out, and tidied of the commas and dashes left around it. A
    /// currency before its figure goes first, then after it: one pattern with both would take "1042 EUR" from "order 1042
    /// EUR 150.00", leaving the amount and cutting the order's number.
    static func strip(_ text: String) -> String {
        var words = text
        for figure in [spacedFigure, plainFigure] {
            for money in [currency + #"\s?"# + figure, figure + #"\s?"# + currency] {
                words = words.replacingOccurrences(of: money, with: " ", options: [.regularExpression, .caseInsensitive])
            }
        }
        return tidy(words)
    }

    /// The words with their spacing tidied and stray marks off the edges, as `strip` leaves them, with nothing taken out.
    static func tidy(_ text: String) -> String {
        var words = text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        words = words.replacingOccurrences(of: #"\s+([,.;:])"#, with: "$1", options: .regularExpression)
        let edges = CharacterSet.whitespaces.union(CharacterSet(charactersIn: ",;:-–—·"))
        return words.trimmingCharacters(in: edges)
    }

    /// Whether `strip` takes any amount out of the words: spacing and edges aside, they differ (as watchlink's
    /// `Amounts.hasAmount`).
    static func hasAmount(_ text: String) -> Bool { strip(text) != tidy(text) }
}
