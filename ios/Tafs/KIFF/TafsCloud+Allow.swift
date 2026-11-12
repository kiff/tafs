import Foundation

extension TafsCloud {
    /// `POST /v1/tafs/{id}/holds/{exception}/allow` (docs/allow-from-now-on.md): tafs cloud's
    /// offer to change the Card so calls like a held one go on their own, or why there's none
    /// (`none`, with tafs cloud's `words`). A `none` this app doesn't know reads as any other:
    /// its words, and nothing to sign.
    struct AllowAnswer: Decodable, Equatable {
        var offer: AllowOffer?
        var none: String?
        var words: String?
    }

    /// The offer: the narrowest change, what it would have let go over the last 30 days, and
    /// the draft KIFF holds for it.
    struct AllowOffer: Decodable, Equatable {
        /// One limit the offer changes: on which Card, what kind, from what to what, and in
        /// tafs cloud's words.
        struct Change: Decodable, Equatable {
            let mandateID: String
            let kind: String
            var before: Int64?
            var after: Int64?
            var unit: String?
            var window: String?
            /// For a `resource` change: the parameter whose scope grows, and the one value added.
            var parameter: String?
            var value: String?
            var wordsBefore: String?
            var wordsAfter: String?
            var rounded: Bool?
            enum CodingKeys: String, CodingKey {
                case mandateID = "mandate_id", kind, before, after, unit, window, parameter, value, rounded
                case wordsBefore = "words_before", wordsAfter = "words_after"
            }
        }
        struct Would: Decodable, Equatable {
            var calls: Int?
            var words: String?
        }
        /// What rounding would give, named before any tap. The app never rounds.
        struct Round: Decodable, Equatable {
            var after: Int64?
            var words: String?
        }
        let changes: [Change]
        /// Left out when KIFF's replay couldn't be read: the change is still offered.
        var would: Would?
        let draft: String
        var confirmKey: String?
        var sendHeld: Bool?
        /// Absent when rounding changes nothing.
        var round: Round?
        enum CodingKeys: String, CodingKey { case changes, would, draft, confirmKey = "confirm_key", sendHeld = "send_held", round }
    }

    /// Asks tafs cloud for the offer on a held call, made on the tap and never before: the
    /// exact figure, or the rounded one when `round`.
    func allowOffer(taf: String, exception: String, round: Bool) async throws -> AllowAnswer {
        func escaped(_ s: String) -> String {
            s.addingPercentEncoding(withAllowedCharacters: CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
        }
        return try await send("POST", "/v1/tafs/\(escaped(taf))/holds/\(escaped(exception))/allow", body: round ? ["round": true] : [String: Bool]())
    }
}
