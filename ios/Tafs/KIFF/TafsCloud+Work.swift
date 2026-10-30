import Foundation

extension TafsCloud {
    /// Where a taf's work stands, as tafs cloud says it (`GET /v1/tafs/{id}/work`,
    /// docs/reliability.md): its summary line, and its recent requests and skipped firings.
    /// Every line and time is tafs cloud's, in the account's zone: the app formats none.
    struct TafWork: Decodable, Equatable {
        struct Summary: Decodable, Equatable {
            var status = "idle"
            var line = ""
            var needsYou = 0
            enum CodingKeys: String, CodingKey { case status, line, needsYou = "needs_you" }
            init(status: String = "idle", line: String = "", needsYou: Int = 0) {
                self.status = status; self.line = line; self.needsYou = needsYou
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                status = (try? c.decode(String.self, forKey: .status)) ?? "idle"
                line = (try? c.decode(String.self, forKey: .line)) ?? ""
                needsYou = (try? c.decode(Int.self, forKey: .needsYou)) ?? 0
            }
        }

        /// One request: done, working, retrying, needs_you, waiting, stopped or failed, in
        /// tafs cloud's words, with where its result went.
        struct Request: Decodable, Equatable, Identifiable {
            let id: String
            let at: Date
            var state = ""
            var line = ""
            var deliveries: [Delivery] = []
            enum CodingKeys: String, CodingKey { case id, at, state, line, deliveries }
            init(id: String, at: Date, state: String = "", line: String = "", deliveries: [Delivery] = []) {
                self.id = id; self.at = at; self.state = state; self.line = line; self.deliveries = deliveries
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                id = try c.decode(String.self, forKey: .id)
                at = try c.decode(Date.self, forKey: .at)
                state = (try? c.decode(String.self, forKey: .state)) ?? ""
                line = (try? c.decode(String.self, forKey: .line)) ?? ""
                deliveries = (try? c.decode([Delivery].self, forKey: .deliveries)) ?? []
            }
        }

        /// Where a request's result went, and whether it can be sent again: a reply given
        /// up on after its tries.
        struct Delivery: Decodable, Equatable {
            var place = "door_reply"
            var state = ""
            var line = ""
            var sendAgain = false
            enum CodingKeys: String, CodingKey { case place, state, line, sendAgain = "send_again" }
            init(place: String = "door_reply", state: String = "", line: String = "", sendAgain: Bool = false) {
                self.place = place; self.state = state; self.line = line; self.sendAgain = sendAgain
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                place = (try? c.decode(String.self, forKey: .place)) ?? "door_reply"
                state = (try? c.decode(String.self, forKey: .state)) ?? ""
                line = (try? c.decode(String.self, forKey: .line)) ?? ""
                sendAgain = (try? c.decode(Bool.self, forKey: .sendAgain)) ?? false
            }
        }

        /// A scheduled run that was skipped: when it was due, why (missed or busy), and tafs
        /// cloud's words for it. Not a failure: nothing ran.
        struct Skipped: Decodable, Equatable {
            let due: Date
            var why = ""
            var words = ""
        }

        /// A row of the work: a request, or a scheduled run that was skipped.
        enum Row: Equatable, Identifiable {
            case request(Request)
            case skipped(Skipped)
            var at: Date {
                switch self {
                case .request(let r): return r.at
                case .skipped(let s): return s.due
                }
            }
            var id: String {
                switch self {
                case .request(let r): return "r|" + r.id
                case .skipped(let s): return "s|\(s.due.timeIntervalSince1970)"
                }
            }
        }

        var summary: Summary?
        var requests: [Request] = []
        var skipped: [Skipped] = []

        enum CodingKeys: String, CodingKey { case summary, requests, skipped }
        init(summary: Summary? = nil, requests: [Request] = [], skipped: [Skipped] = []) {
            self.summary = summary; self.requests = requests; self.skipped = skipped
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            summary = try? c.decodeIfPresent(Summary.self, forKey: .summary)
            requests = (try? c.decode([Request].self, forKey: .requests)) ?? []
            // An older tafs cloud sends none.
            skipped = (try? c.decode([Skipped].self, forKey: .skipped)) ?? []
        }

        /// The requests and skipped firings, each in its own place by time, newest first.
        var rows: [Row] {
            (requests.map(Row.request) + skipped.map(Row.skipped)).sorted { $0.at > $1.at }
        }
    }

    /// Where the taf's work stands (docs/reliability.md, "The taf's view").
    func work(_ id: String) async throws -> TafWork {
        try await send("GET", "/v1/tafs/\(escaped(id))/work")
    }

    /// Send again: the door reply of `run`, given up on after its tries, sent once more. It
    /// does no work and signs nothing. Returns the delivery's state: sent, not_sent or waiting.
    @discardableResult
    func sendAgain(_ id: String, run: String, place: String = "door_reply") async throws -> String {
        struct Response: Decodable { var state: String? }
        let r: Response = try await send("POST", "/v1/tafs/\(escaped(id))/requests/\(escaped(run))/deliveries/\(escaped(place))/send",
                                          body: [String: String]())
        return r.state ?? ""
    }
}
