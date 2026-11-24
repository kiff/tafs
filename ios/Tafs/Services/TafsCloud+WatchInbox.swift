import Foundation

/// Here, beside the relay, not in `Tafs/KIFF`: the notification extension (TafsNotify) compiles all of `Tafs/KIFF` and not
/// `WatchShared`, where `WatchInboxMarks` is.
extension TafsCloud {
    /// Row 21: the watch Inbox's lines the person read and dismissed, by id alone (`/v1/watch/inbox`), so each of their
    /// watches shows the same. A held call's id or the id of the message an answer is to; never a line's words.
    static let watchInboxPath = "/v1/watch/inbox"

    /// The marks as tafs cloud keeps them.
    func watchInbox() async throws -> WatchInboxMarks {
        Self.watchInboxMarks(decoding: try await sendData("GET", Self.watchInboxPath, body: Optional<[String: String]>.none))
    }

    /// Marks lines read or dismissed, and answers the marks as they stand. Only ids tafs cloud takes are sent, and an empty
    /// list isn't sent at all.
    func markWatchInbox(_ marks: WatchInboxMarks) async throws -> WatchInboxMarks {
        let checked = marks.checked
        guard checked.isComplete else { throw CloudError(status: 400, message: "bad_marks") }
        let body = Mark(read: checked.read.isEmpty ? nil : checked.read, dismissed: checked.dismissed.isEmpty ? nil : checked.dismissed)
        return Self.watchInboxMarks(decoding: try await sendData("POST", Self.watchInboxPath, body: body))
    }

    /// The body: `{read?: [id], dismissed?: [id]}`.
    private struct Mark: Encodable {
        let read: [String]?
        let dismissed: [String]?
    }

    /// The marks from tafs cloud's answer: only ids the watch can use, each once; a dismissed line is never also read.
    static func watchInboxMarks(decoding data: Data) -> WatchInboxMarks {
        struct Response: Decodable { let read: [LenientItem<String>]?; let dismissed: [LenientItem<String>]? }
        let read = try? JSONDecoder().decode(Response.self, from: data)
        let marks = WatchInboxMarks(read: (read?.read ?? []).compactMap(\.value),
                                    dismissed: (read?.dismissed ?? []).compactMap(\.value)).checked
        let gone = Set(marks.dismissed)
        return WatchInboxMarks(read: marks.read.filter { !gone.contains($0) }, dismissed: marks.dismissed)
    }
}
