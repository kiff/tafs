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

    /// Opens a paused door again, signed: it re-admits a caller the owner may have paused for misuse.
    func reopenDoor(_ taf: String, door: String, signature: String) async throws -> Door {
        struct Answer: Decodable { let door: Door }
        let data = try await sendData("PATCH", doorPath(taf, door: door), body: KIFFClient.RawJSON(data: Self.reopenBody),
                                      headers: ["KIFF-Device-Signature": signature])
        return try KIFFClient.decoder.decode(Answer.self, from: data).door
    }
}
