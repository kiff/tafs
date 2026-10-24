import Foundation

/// A taf's doors (docs/doors.md): where work reaches it from outside the app, with the
/// answer going back the same way. tafs cloud keeps them; the phone opens, pairs and
/// closes them with the owner's session.
extension TafsCloud {
    /// One door: an email address or a WhatsApp number.
    struct Door: Decodable, Equatable, Identifiable {
        struct Key: Decodable, Equatable, Identifiable {
            let id: String
            var name: String? = nil
            /// Paired by tafs: the owner's own sign-in email.
            var auto: Bool = false

            enum CodingKeys: String, CodingKey { case id, name, auto }
            init(id: String, name: String? = nil, auto: Bool = false) {
                self.id = id; self.name = name; self.auto = auto
            }
            init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                id = try c.decode(String.self, forKey: .id)
                name = try? c.decodeIfPresent(String.self, forKey: .name)
                auto = (try? c.decode(Bool.self, forKey: .auto)) ?? false
            }
        }

        let id: String
        let app: String
        let kind: String
        let label: String
        var open: Bool
        let address: String
        /// "proven": it can tell who wrote, so it holds keys. "none": everything knocks.
        let proof: String
        /// How it knows who's writing, in a line.
        let how: String
        var keys: [Key]
        /// Today's messages from the owner and paired senders, and their cap.
        var today: Int
        var limit: Int
        var resting: Bool
        /// Today's messages from anyone else, and their own cap.
        var othersToday: Int? = nil
        var othersLimit: Int? = nil

        enum CodingKeys: String, CodingKey {
            case id, app, kind, label, open, address, proof, how, keys, today, limit, resting
            case othersToday = "others_today", othersLimit = "others_limit"
        }

        var holdsKeys: Bool { proof == "proven" }
    }

    /// An app a door can open on here.
    struct DoorApp: Decodable, Equatable, Identifiable {
        let id: String
        let label: String
        let kind: String
        let proof: String
        let how: String
    }

    /// Someone without a key wrote: the owner decides.
    struct Knock: Decodable, Equatable, Identifiable {
        let id: String
        let door: String
        let app: String
        let from: String
        var name: String? = nil
        /// "pass", "fail" (the sender check failed) or "none" (the door can't tell).
        let proof: String
        var subject: String? = nil
        let text: String
        var attachments: Int = 0
        let at: Date
        let state: String
        /// Not a message: the line saying the door had its day's messages, from the owner and
        /// paired senders ("keys") or from anyone else ("others"), and how many more were dropped.
        var rest: String? = nil
        var dropped: Int = 0

        enum CodingKeys: String, CodingKey { case id, door, app, from, name, proof, subject, text, attachments, at, state, rest, dropped }
        init(id: String, door: String, app: String, from: String, name: String? = nil, proof: String, subject: String? = nil,
             text: String, attachments: Int = 0, at: Date, state: String = "open", rest: String? = nil, dropped: Int = 0) {
            self.id = id; self.door = door; self.app = app; self.from = from; self.name = name; self.proof = proof
            self.subject = subject; self.text = text; self.attachments = attachments; self.at = at; self.state = state
            self.rest = rest; self.dropped = dropped
        }

        var isLine: Bool { rest != nil }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            door = try c.decode(String.self, forKey: .door)
            app = try c.decode(String.self, forKey: .app)
            from = try c.decode(String.self, forKey: .from)
            name = try? c.decodeIfPresent(String.self, forKey: .name)
            proof = (try? c.decode(String.self, forKey: .proof)) ?? "none"
            subject = try? c.decodeIfPresent(String.self, forKey: .subject)
            text = (try? c.decode(String.self, forKey: .text)) ?? ""
            attachments = (try? c.decode(Int.self, forKey: .attachments)) ?? 0
            at = try c.decode(Date.self, forKey: .at)
            state = (try? c.decode(String.self, forKey: .state)) ?? "open"
            rest = try? c.decodeIfPresent(String.self, forKey: .rest)
            dropped = (try? c.decode(Int.self, forKey: .dropped)) ?? 0
        }

        /// Who wrote, as the owner reads it.
        var who: String { name.map { "\($0) (\(from))" } ?? from }
    }

    struct Doors: Decodable, Equatable {
        var doors: [Door]
        var apps: [DoorApp]
        var knocks: [Knock]
    }

    private struct DoorResponse: Decodable { let door: Door }

    func doors(_ id: String) async throws -> Doors {
        try await send("GET", "/v1/tafs/\(escaped(id))/doors")
    }

    /// Turns a door on. Email needs nothing more; WhatsApp, its signup's code and ids.
    func addDoor(_ id: String, app: String, extra: [String: String] = [:]) async throws -> Door {
        var body = extra
        body["app"] = app
        let r: DoorResponse = try await send("POST", "/v1/tafs/\(escaped(id))/doors", body: body)
        return r.door
    }

    func setDoorOpen(_ id: String, door: String, open: Bool) async throws -> Door {
        let r: DoorResponse = try await send("PATCH", "/v1/tafs/\(escaped(id))/doors/\(escaped(door))", body: ["open": open])
        return r.door
    }

    func removeDoor(_ id: String, door: String) async throws {
        struct Response: Decodable { let removed: String }
        let _: Response = try await send("DELETE", "/v1/tafs/\(escaped(id))/doors/\(escaped(door))")
    }

    func pair(_ id: String, door: String, sender: String, name: String?) async throws -> Door {
        var body = ["id": sender]
        if let name, !name.isEmpty { body["name"] = name }
        let r: DoorResponse = try await send("POST", "/v1/tafs/\(escaped(id))/doors/\(escaped(door))/keys", body: body)
        return r.door
    }

    func unpair(_ id: String, door: String, sender: String) async throws -> Door {
        let r: DoorResponse = try await send("DELETE", "/v1/tafs/\(escaped(id))/doors/\(escaped(door))/keys/\(escaped(sender))")
        return r.door
    }

    /// The owner's answer to a knock: "do" (with their own words, if they wrote some),
    /// "pair" or "ignore".
    func answerKnock(_ id: String, knock: String, action: String, say: String? = nil) async throws {
        struct Response: Decodable { let state: String }
        var body = ["action": action]
        if let say, !say.isEmpty { body["say"] = say }
        let _: Response = try await send("POST", "/v1/tafs/\(escaped(id))/doors/knocks/\(escaped(knock))", body: body)
    }

    /// The page that runs WhatsApp's signup for this taf, served by tafs cloud.
    func whatsappSignupURL(_ id: String) -> URL? {
        var c = URLComponents(url: baseURL.appendingPathComponent("doors/whatsapp/connect"), resolvingAgainstBaseURL: false)
        c?.queryItems = [URLQueryItem(name: "taf", value: id)]
        return c?.url
    }
}
