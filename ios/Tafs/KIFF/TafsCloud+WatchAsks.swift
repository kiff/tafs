import Foundation

/// Asking from the watch (docs/watch-ask.md): the watch's words as a request of their own, a Coach conversation of the
/// watch's, sent by the phone with its device key. tafs cloud answers at once with the request, then the request is read
/// back by its id until it ends.
extension TafsCloud {
    /// A watch request as tafs cloud shows it: its state, the taf that took it, the line saying how it went, and tafs
    /// cloud's words for the state, in the person's language.
    struct WatchAsk: Decodable, Equatable {
        struct Taf: Decodable, Equatable {
            let id: String
            let name: String
        }

        let id: String
        let state: String
        var taf: Taf? = nil
        var line: String? = nil
        var words: String = ""
        /// Chatting with a taf: the taf waits on the person (a held call, a question, a paused run), which is answered on
        /// the phone. The request ends `done` with it.
        var waitsOnPerson: Bool = false
        /// The taf's answer went on past its 400 characters: the rest is on the phone.
        var more: Bool = false
        /// Read more (`?full=1` only): the taf's whole answer, cleaned as the line is and never cut, at most 16 KB. tafs cloud
        /// sends it beside `ask`, not in it; none when the run hasn't finished or the person may no longer use the taf.
        var text: String? = nil
        /// When tafs cloud last changed it (`updated`): for an ended request, when its answer came (row 44, C).
        var updated: Date? = nil

        enum CodingKeys: String, CodingKey { case id, state, taf, line, words, held, more, updated }

        init(id: String, state: String, taf: Taf? = nil, line: String? = nil, words: String = "", waitsOnPerson: Bool = false,
             more: Bool = false) {
            self.id = id; self.state = state; self.taf = taf; self.line = line; self.words = words
            self.waitsOnPerson = waitsOnPerson; self.more = more
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            state = try c.decode(String.self, forKey: .state)
            taf = try? c.decodeIfPresent(Taf.self, forKey: .taf)
            line = (try? c.decodeIfPresent(String.self, forKey: .line)).flatMap { $0.isEmpty ? nil : $0 }
            words = (try? c.decode(String.self, forKey: .words)) ?? ""
            waitsOnPerson = (try? c.decodeIfPresent(Bool.self, forKey: .held)) ?? false
            more = (try? c.decodeIfPresent(Bool.self, forKey: .more)) ?? false
            updated = (try? c.decodeIfPresent(String.self, forKey: .updated)).flatMap { Self.instant($0) }
        }

        /// An ISO-8601 time, with or without fractions of a second, as tafs cloud writes it.
        static func instant(_ text: String) -> Date? {
            let f = ISO8601DateFormatter()
            f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let d = f.date(from: text) { return d }
            f.formatOptions = [.withInternetDateTime]
            return f.date(from: text)
        }

        /// What the watch shows: the line when there is one, else tafs cloud's words for the state.
        var shown: String { line ?? words }
        /// Done, no taf took it, or it failed: nothing more comes.
        var ended: Bool { ["done", "no_taf", "failed"].contains(state) }
        /// A call the taf's Cards hold waits for the owner on the phone: Coach's request stays `working`, with a line saying
        /// so; a taf chat's ends with `held: true`.
        var held: Bool { waitsOnPerson || (state == "working" && line != nil) }
    }

    static let watchAsksPath = "/v1/watch/asks"

    /// The body a watch request sends, as the device key signs it: these exact bytes, `{client_id, text, reply_to?, taf_id?}`
    /// and nothing else (tafs cloud refuses any other field). The signature covers `taf_id`, so it can't be changed after.
    nonisolated static func watchAskBody(clientID: String, text: String, replyTo: String?, tafID: String? = nil) -> Data {
        func quoted(_ s: String) -> String {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.withoutEscapingSlashes]
            return (try? encoder.encode(s)).map { String(decoding: $0, as: UTF8.self) } ?? "\"\""
        }
        var fields = ["\"client_id\":\(quoted(clientID))", "\"text\":\(quoted(text))"]
        if let replyTo { fields.append("\"reply_to\":\(quoted(replyTo))") }
        if let tafID { fields.append("\"taf_id\":\(quoted(tafID))") }
        return Data(("{" + fields.joined(separator: ",") + "}").utf8)
    }

    /// Sends a watch request: `body` is `watchAskBody`'s bytes, `signature` the `KIFF-Device-Signature` header made over them.
    /// The same `client_id` again gets the same request back, and starts nothing.
    func watchAsk(body: Data, signature: String) async throws -> WatchAsk {
        struct Answer: Decodable { let ask: WatchAsk }
        let data = try await sendData("POST", Self.watchAsksPath, body: KIFFClient.RawJSON(data: body),
                                      headers: ["KIFF-Device-Signature": signature])
        return try KIFFClient.decoder.decode(Answer.self, from: data).ask
    }

    /// One of the person's own watch requests, by its id; `full`, its whole answer (Read more), cleaned as the line is but not
    /// cut, at most 16 KB.
    func watchAsk(_ id: String, full: Bool = false) async throws -> WatchAsk {
        // `{ask, text?}`: the whole answer is beside the request, never in it.
        struct Answer: Decodable { let ask: WatchAsk; let text: String? }
        let r: Answer = try await send("GET", "\(Self.watchAsksPath)/\(escaped(id))" + (full ? "?full=1" : ""))
        var asked = r.ask
        if full { asked.text = r.text.flatMap { $0.isEmpty ? nil : $0 } }
        return asked
    }
}
