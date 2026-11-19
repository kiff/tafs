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

        enum CodingKeys: String, CodingKey { case id, state, taf, line, words }

        init(id: String, state: String, taf: Taf? = nil, line: String? = nil, words: String = "") {
            self.id = id; self.state = state; self.taf = taf; self.line = line; self.words = words
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            state = try c.decode(String.self, forKey: .state)
            taf = try? c.decodeIfPresent(Taf.self, forKey: .taf)
            line = (try? c.decodeIfPresent(String.self, forKey: .line)).flatMap { $0.isEmpty ? nil : $0 }
            words = (try? c.decode(String.self, forKey: .words)) ?? ""
        }

        /// What the watch shows: the line when there is one, else tafs cloud's words for the state.
        var shown: String { line ?? words }
        /// Done, no taf took it, or it failed: nothing more comes.
        var ended: Bool { ["done", "no_taf", "failed"].contains(state) }
        /// A call the taf's Cards hold waits for the owner on the phone: the request stays `working`, with a line saying so.
        var held: Bool { state == "working" && line != nil }
    }

    static let watchAsksPath = "/v1/watch/asks"

    /// The body a watch request sends, as the device key signs it: these exact bytes, `{client_id, text, reply_to?}` and
    /// nothing else (tafs cloud refuses any other field).
    nonisolated static func watchAskBody(clientID: String, text: String, replyTo: String?) -> Data {
        func quoted(_ s: String) -> String {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.withoutEscapingSlashes]
            return (try? encoder.encode(s)).map { String(decoding: $0, as: UTF8.self) } ?? "\"\""
        }
        var fields = ["\"client_id\":\(quoted(clientID))", "\"text\":\(quoted(text))"]
        if let replyTo { fields.append("\"reply_to\":\(quoted(replyTo))") }
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

    /// One of the person's own watch requests, by its id.
    func watchAsk(_ id: String) async throws -> WatchAsk {
        struct Answer: Decodable { let ask: WatchAsk }
        let r: Answer = try await send("GET", "\(Self.watchAsksPath)/\(escaped(id))")
        return r.ask
    }
}
