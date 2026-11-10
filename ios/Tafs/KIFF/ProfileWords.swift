import CryptoKit
import Foundation

/// KIFF's words for a connection's profile (`kiff.words.v1`): what the owner reads before
/// approving a connection, and signs. KIFF writes them, never Coach or
/// the app: the app shows each line's text verbatim, in order, and `kind` only groups and
/// styles it.
///
/// The phone reads them from KIFF with its own session, for the profile in the grant's
/// request, and checks both digests before showing them: the words must be KIFF's render of
/// exactly that profile. Approving then signs both digests into planque's approve body.
struct ProfileWords: Equatable {
    struct Line: Equatable {
        /// The operation it's about; none for a line about the whole connection ("unpinned").
        let op: String?
        let kind: String
        let text: String
        /// What KIFF can't check for this connection, probation and an unpinned vendor:
        /// shown in amber.
        var caution: Bool { ProfileWords.cautionKinds.contains(kind) }
    }

    static let version = "kiff.words.v1"
    static let cautionKinds: Set<String> = ["missing", "probation", "unpinned"]

    enum Failure: Error, Equatable, LocalizedError {
        /// KIFF answered something that isn't words this app reads.
        case notWords
        /// The words are for another profile than the one shown.
        case otherProfile
        /// The words aren't the ones their digest names.
        case wordsChanged

        var errorDescription: String? {
            switch self {
            case .notWords: return Words.Connect.kiffWordsConnectionCouldNot
            case .otherProfile: return Words.Connect.wordsAnotherConnectionThanOne
            case .wordsChanged: return Words.Kiff.kiffWordsDoNotMatch
            }
        }
    }

    let lines: [Line]
    let profileSHA256: String
    let wordsSHA256: String

    /// The hex SHA-256 of a JSON value's RFC 8785 form (A2).
    static func digest(_ value: JCS.Value) throws -> String {
        SHA256.hash(data: try JCS.canonical(value)).map { String(format: "%02x", $0) }.joined()
    }

    /// KIFF's answer to `POST /v1/profiles/words`, checked against `profile`, the profile the
    /// owner is shown: its digest, the words' version and their own digest.
    static func checked(_ data: Data, profile: JCS.Value) throws -> ProfileWords {
        guard let answer = try? JCS.parse(data), let words = answer["words"], words["v"]?.string == version,
              let profileSHA = answer["profile_sha256"]?.string, let wordsSHA = answer["words_sha256"]?.string,
              let raw = words["lines"]?.array else { throw Failure.notWords }
        guard try digest(profile) == profileSHA else { throw Failure.otherProfile }
        guard try digest(words) == wordsSHA else { throw Failure.wordsChanged }
        let lines = try raw.map { line -> Line in
            guard let kind = line["kind"]?.string, let text = line["text"]?.string else { throw Failure.notWords }
            return Line(op: line["op"]?.string, kind: kind, text: text)
        }
        return ProfileWords(lines: lines, profileSHA256: profileSHA, wordsSHA256: wordsSHA)
    }
}
