import Foundation

/// A door "From another application" (`api`): a company's own software handing a taf work over the API, by a key the owner
/// makes on the phone (docs/doors.md). Its keys are made, revoked and its door opened again only with the owner's signature:
/// `kiff.request.v1` over the method, path and raw body, after Face ID or the fingerprint, as a taf's removal is signed.
/// Pausing it needs none: stopping is always safe.
enum APIDoorFeature {
    /// Off until tafs cloud's routes, the Eval case and the privacy line are in, and the owner says yes.
    static let on = false
}

extension TafsCloud {
    /// A key as tafs cloud makes it: shown in this one answer only, never again.
    struct IssuedKey: Decodable, Equatable {
        let id: String
        let key: String
        let last4: String
        let madeAt: String

        enum CodingKeys: String, CodingKey { case id, key, last4, madeAt = "made_at" }
    }

    /// The path of a taf's door, as it's sent and as it's signed.
    func doorPath(_ taf: String, door: String) -> String { "/v1/tafs/\(escaped(taf))/doors/\(escaped(door))" }
    /// Its keys, as sent and signed.
    func doorKeysPath(_ taf: String, door: String) -> String { doorPath(taf, door: door) + "/keys" }
    /// One key, as sent and signed.
    func doorKeyPath(_ taf: String, door: String, key: String) -> String { doorKeysPath(taf, door: door) + "/\(escaped(key))" }

    /// A JSON object of the given string fields, in this order: the exact bytes that are signed and sent.
    nonisolated static func signedBody(_ fields: [(String, String)]) -> Data {
        func quoted(_ s: String) -> String {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.withoutEscapingSlashes]
            return (try? encoder.encode(s)).map { String(decoding: $0, as: UTF8.self) } ?? "\"\""
        }
        return Data(("{" + fields.map { "\(quoted($0.0)):\(quoted($0.1))" }.joined(separator: ",") + "}").utf8)
    }

    /// New key's body: its name.
    nonisolated static func newKeyBody(name: String) -> Data { signedBody([("name", name)]) }
    /// Opening a paused door again: the one change to a door that's signed.
    nonisolated static let reopenBody = Data(#"{"open":true}"#.utf8)

    /// Makes a door's new key (at most two live). `signature` is the `KIFF-Device-Signature` made over `newKeyBody`'s bytes.
    func newKey(_ taf: String, door: String, body: Data, signature: String) async throws -> IssuedKey {
        let data = try await sendData("POST", doorKeysPath(taf, door: door), body: KIFFClient.RawJSON(data: body),
                                      headers: ["KIFF-Device-Signature": signature])
        return try KIFFClient.decoder.decode(IssuedKey.self, from: data)
    }

    /// Revokes a door's key: its next request is 404. Signed over the DELETE and its empty body.
    func revokeKey(_ taf: String, door: String, key: String, signature: String) async throws {
        _ = try await sendData("DELETE", doorKeyPath(taf, door: door, key: key), body: Optional<[String: String]>.none,
                               headers: ["KIFF-Device-Signature": signature])
    }

    /// Removes an `api` door, signed over the DELETE and its empty body: its keys go with it.
    func removeAPIDoor(_ taf: String, door: String, signature: String) async throws {
        _ = try await sendData("DELETE", doorPath(taf, door: door), body: Optional<[String: String]>.none,
                               headers: ["KIFF-Device-Signature": signature])
    }

    /// Opens a paused door again, signed: it re-admits a caller the owner may have paused for misuse.
    func reopenDoor(_ taf: String, door: String, signature: String) async throws -> Door {
        struct Answer: Decodable { let door: Door }
        let data = try await sendData("PATCH", doorPath(taf, door: door), body: KIFFClient.RawJSON(data: Self.reopenBody),
                                      headers: ["KIFF-Device-Signature": signature])
        return try KIFFClient.decoder.decode(Answer.self, from: data).door
    }
}

// MARK: Coach's drafted door, approved signed

extension TafsCloud {
    /// The door Coach drafted for a taf (`GET /v1/tafs/{id}/doors/drafts/{draft}`, `contracts/doors-api-draft.json`), as the
    /// owner approves it. What's drawn is read from `body`, the bytes the phone signs and sends, never from a copy beside it,
    /// so the owner approves exactly what they see.
    struct DoorDraft: Equatable {
        /// One field the application sends: its name, and for a choice, its values.
        struct Field: Decodable, Equatable {
            let name: String
            let type: String
            var values: [String]? = nil
        }

        /// The application's name, the fields it sends, what the taf does with them, what it gets back, and whether the
        /// taf may answer in its own words.
        struct Draft: Equatable {
            let name: String
            let fields: [Field]
            let job: String
            let outcomes: [String]
            let text: Bool
        }

        let id: String
        let draft: Draft
        /// `body` in its canonical form (RFC 8785): what's signed, and the same bytes sent to `POST …/doors`.
        let body: Data

        /// Read from tafs cloud's answer; nil for one with no draft, or a draft with no name or job.
        static func read(_ data: Data) throws -> DoorDraft? {
            let answer = try JCS.parse(data)
            guard let id = answer["draft_id"]?.string, let bodyValue = answer["body"],
                  case .object = bodyValue, bodyValue["kind"]?.string == "api", let draftValue = bodyValue["draft"] else { return nil }
            let body = try JCS.canonical(bodyValue)
            let shown = try JCS.canonical(draftValue)
            struct Raw: Decodable {
                let name: String?
                let fields: [LenientItem<Field>]?
                let job: String?
                let outcomes: [String]?
                let text: Bool?
            }
            let raw = try JSONDecoder().decode(Raw.self, from: shown)
            let name = raw.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let job = raw.job?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !name.isEmpty, !job.isEmpty else { return nil }
            let draft = Draft(name: name, fields: (raw.fields ?? []).compactMap(\.value), job: job, outcomes: raw.outcomes ?? [],
                              text: raw.text ?? false)
            return DoorDraft(id: id, draft: draft, body: body)
        }
    }

    /// A taf's doors, as approving a draft posts to them.
    func doorsPath(_ taf: String) -> String { "/v1/tafs/\(escaped(taf))/doors" }
    /// One of its drafts, as read.
    func doorDraftPath(_ taf: String, draft: String) -> String { doorsPath(taf) + "/drafts/\(escaped(draft))" }

    /// Reads a draft Coach made; nil when tafs cloud's answer holds none the app can draw.
    func doorDraft(_ taf: String, draft: String) async throws -> DoorDraft? {
        try DoorDraft.read(try await sendData("GET", doorDraftPath(taf, draft: draft), body: Optional<[String: String]>.none))
    }

    /// The door the approval opened, and its first key, shown in this one answer only.
    struct AddedAPIDoor: Decodable {
        let door: Door
        let key: IssuedKey
    }

    /// Approves Coach's draft: `sign` makes the `KIFF-Device-Signature` over `POST`, the doors' path and the draft's exact
    /// `body` bytes (Face ID or Touch ID), and those same bytes are sent. A cancelled signature sends nothing.
    func approveDoorDraft(_ taf: String, draft: DoorDraft,
                          sign: (_ method: String, _ path: String, _ body: Data) async throws -> String) async throws -> AddedAPIDoor {
        let path = doorsPath(taf)
        let signature = try await sign("POST", path, draft.body)
        let data = try await sendData("POST", path, body: KIFFClient.RawJSON(data: draft.body), headers: ["KIFF-Device-Signature": signature])
        return try KIFFClient.decoder.decode(AddedAPIDoor.self, from: data)
    }
}
