import CryptoKit
import XCTest
@testable import Tafs

/// A key and its Card under one Face ID (`kiff.requests.v1`, docs/secure-connection.md): what
/// the approval key signs, how each request carries the one signature, and the phone's last
/// check that the ask is this card's.
@MainActor
final class KeyAndCardsTests: XCTestCase {
    private func hex(_ s: String) -> String { SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined() }

    private func clients() -> (KIFFClient, PlanqueClient) {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let kiff = KIFFClient(baseURL: URL(string: "https://api.test")!, gatewayURL: URL(string: "https://mcp.test")!, token: "kiff_dev_x", session: session)
        return (kiff, PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: session))
    }

    private func keyGrant(status: String = "pending") throws -> PlanqueClient.Grant {
        let request = #"{"agent":"desk","grant":"g_2","kind":"api_key","server":"https://desk.example","tenant":"t_01","tools":[],"v":"planque.grant.v1"}"#
        let digest = SHA256.hash(data: try JCS.canonical(Data(request.utf8))).map { String(format: "%02x", $0) }.joined()
        let json = #"{"id":"g_2","status":"\#(status)","text":"desk may read tickets at desk.example","digest":"\#(digest)","request":\#(request),"vendor":"Desk","events":[]}"#
        return try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8))
    }

    private func ask(step: String = "key_and_cards", grant: String = "g_2", digest: String = "d1", draft: String = "dr_1", version: String = "v7") throws -> TafsCloud.ApprovalDecision.Ask {
        let json = #"{"id":"ask_1","step":"\#(step)","scheme":"kiff.requests.v1","message":"","reason":"Connect Desk for Tickets","key":"critical","planque":{"grant":"\#(grant)","body":{"digest":"\#(digest)"}},"kiff":{"draft":"\#(draft)","version":"\#(version)"}}"#
        return try KIFFClient.decoder.decode(TafsCloud.ApprovalDecision.Ask.self, from: Data(json.utf8))
    }

    // MARK: What's signed

    /// The scheme, the account, the one signed_at, and the two hashes sorted, a line each: the
    /// same bytes whichever request is named first.
    func testTheSignedMessageIsTheTwoHashesSorted() {
        let a = String(repeating: "a", count: 64), b = String(repeating: "b", count: 64)
        let expected = "kiff.requests.v1\nt_01\n2026-11-12T19:00:00.000Z\n\(a)\n\(b)"
        XCTAssertEqual(String(decoding: RequestsTogether.message(tenant: "t_01", signedAt: "2026-11-12T19:00:00.000Z", a, b), as: UTF8.self), expected)
        XCTAssertEqual(String(decoding: RequestsTogether.message(tenant: "t_01", signedAt: "2026-11-12T19:00:00.000Z", b, a), as: UTF8.self), expected, "sorted, whichever comes first")
        XCTAssertEqual(RequestsTogether.hash(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "lowercase hex SHA-256")
        XCTAssertEqual(RequestsTogether.header(signedAt: "T", signature: "sig", with: a), "t=T,v3=sig,with=sha256:\(a)")
    }

    /// The confirm is built before it's signed: its message is KIFF's `kiff.request.v1` over the
    /// exact body sent, and the request carries the signature header it's given, unchanged.
    func testTheConfirmIsSentAsItWasSigned() async throws {
        let (kiff, _) = clients()
        XCTAssertThrowsError(try kiff.prepareConfirmDraft(id: "dr_1", version: "v7", signedAt: "T"), "no account, nothing to sign")
        kiff.tenant = "t_01"
        let prepared = try kiff.prepareConfirmDraft(id: "dr_1", version: "v7", signedAt: "2026-11-12T19:00:00.000Z")
        XCTAssertEqual(String(decoding: prepared.body, as: UTF8.self), #"{"version":"v7"}"#)
        XCTAssertEqual(String(decoding: prepared.message, as: UTF8.self),
                       "kiff.request.v1\nt_01\nPOST\n/v1/me/agent-drafts/dr_1/confirm\n\(hex(#"{"version":"v7"}"#))\n2026-11-12T19:00:00.000Z")
        var signedHere = 0
        kiff.setupSigner = { _ in signedHere += 1; return "other" }
        StubProtocol.responses["POST /v1/me/agent-drafts/dr_1/confirm"] = (200, #"{"id":"dr_1","agent_id":"desk","status":"confirmed","version":"v7"}"#)
        let header = RequestsTogether.header(signedAt: prepared.signedAt, signature: "sig", with: String(repeating: "b", count: 64))
        let confirmed = try await kiff.confirmDraft(prepared, signatureHeader: header)
        XCTAssertTrue(confirmed.confirmed)
        let sent = try XCTUnwrap(StubProtocol.sent.last)
        XCTAssertEqual(sent.value(forHTTPHeaderField: "KIFF-Device-Signature"), header, "the one signature, as given")
        XCTAssertEqual(sent.httpBody, prepared.body, "the bytes that were signed")
        XCTAssertEqual(signedHere, 0, "nothing signs it again")
    }

    /// The key request is built before it's signed: its message is planque's own over the exact
    /// body, and it's sent with `v3`, `with` and this device, the key in its header, and no signer.
    func testTheKeyRequestIsSentAsItWasSigned() async throws {
        let (_, planque) = clients()
        let grant = try keyGrant()
        XCTAssertThrowsError(try planque.prepareKey(grant, key: "desk_live_secret_123", signedAt: "T"), "no account, nothing to sign")
        planque.tenant = "t_01"
        planque.deviceID = "dev_phone01"
        let prepared = try planque.prepareKey(grant, key: " desk_live_secret_123\n", signedAt: "2026-11-12T19:00:00.000Z")
        XCTAssertEqual(prepared.key, "desk_live_secret_123", "trimmed")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: prepared.body) as? [String: Any])
        XCTAssertEqual(body["digest"] as? String, grant.digest)
        XCTAssertEqual(body["key_sha256"] as? String, hex("desk_live_secret_123"))
        XCTAssertFalse(String(decoding: prepared.body, as: UTF8.self).contains("desk_live_secret_123"), "the key is never in the body")
        XCTAssertEqual(String(decoding: prepared.message, as: UTF8.self),
                       "planque.request.v1\nhttps://planque.test\nt_01\nPOST\n/v1/accounts/t_01/grants/g_2/key\n\(RequestsTogether.hash(prepared.body))\n2026-11-12T19:00:00.000Z")

        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_2/key"] = (200, String(decoding: try JSONSerialization.data(withJSONObject: [
            "id": "g_2", "status": "connected", "text": "", "digest": grant.digest, "vendor": "Desk", "events": [],
            "request": ["agent": "desk", "grant": "g_2", "kind": "api_key", "server": "https://desk.example", "tenant": "t_01", "tools": [], "v": "planque.grant.v1"],
        ]), as: UTF8.self))
        let other = String(repeating: "c", count: 64)
        let given = try await planque.giveKey(prepared, signature: "sig", with: other)
        XCTAssertTrue(given.connected)
        let sent = try XCTUnwrap(StubProtocol.sent.last)
        XCTAssertEqual(sent.value(forHTTPHeaderField: "Planque-Device-Signature"), "t=2026-11-12T19:00:00.000Z,v3=sig,with=sha256:\(other),d=dev_phone01")
        XCTAssertEqual(sent.value(forHTTPHeaderField: PlanqueClient.keyHeader), "desk_live_secret_123")
        XCTAssertEqual(sent.httpBody, prepared.body, "the bytes that were signed")
    }

    // MARK: The phone's last check

    /// The ask is signed only when it's this card's, for what the person was shown.
    func testTheAskMustBeThisCardsForWhatWasShown() throws {
        func mine(_ a: TafsCloud.ApprovalDecision.Ask) -> Bool {
            AppModel.keyAndCardIsThisCards(a, grant: "g_2", shownDigest: "d1", draft: "dr_1", shownDraft: "v7")
        }
        XCTAssertTrue(mine(try ask()))
        XCTAssertFalse(mine(try ask(grant: "g_9")), "another connection")
        XCTAssertFalse(mine(try ask(digest: "d2")), "terms the person wasn't shown")
        XCTAssertFalse(mine(try ask(draft: "dr_9")), "another Card's draft")
        XCTAssertFalse(mine(try ask(version: "v8")), "a version the person wasn't shown")
        XCTAssertFalse(mine(try ask(step: "cards")), "another step")
        let noKIFF = try KIFFClient.decoder.decode(TafsCloud.ApprovalDecision.Ask.self, from: Data(#"{"id":"a","step":"key_and_cards","message":"","reason":"r","planque":{"grant":"g_2","body":{"digest":"d1"}}}"#.utf8))
        XCTAssertFalse(mine(noKIFF), "no draft named")
        // The two older steps still sign nothing for it: only this check lets it through.
        XCTAssertNil(AppModel.expectedApprovalMessage(try ask(), component: try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_2","draft":"dr_1"}"#),
                                                      tenant: "t_01", shownDigest: "d1", shownDraft: "v7"))
    }

    /// The phone says it can sign the step, so tafs cloud may ask for it; an ordinary approval says nothing.
    func testThePhoneSaysItCanSignTheStep() throws {
        let with = try JSONSerialization.jsonObject(with: JSONEncoder().encode(TafsCloud.ApprovalAskBody(grant: "g_2", shownDigest: "d1", draft: "dr_1", shownDraft: "v7", can: [RequestsTogether.keyAndCards]))) as? [String: Any]
        XCTAssertEqual(with?["can"] as? [String], ["key_and_cards"])
        let without = try JSONSerialization.jsonObject(with: JSONEncoder().encode(TafsCloud.ApprovalAskBody(grant: "g_2", shownDigest: "d1"))) as? [String: Any]
        XCTAssertNil(without?["can"], "an approval that isn't from the key's sheet")
    }

    /// The flow's order, read from its source: the ask is checked before anything is signed, the
    /// approval key signs once, the key goes before the Card, and the Card only for a key that went in.
    func testTheFlowSignsOnceAndSendsTheKeyFirst() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/App/AppModel.swift")
        let file = try String(contentsOf: app, encoding: .utf8)
        let start = try XCTUnwrap(file.range(of: "func approveKeyAndCard("))
        let end = try XCTUnwrap(file.range(of: "nonisolated static func keyAndCardIsThisCards(", range: start.upperBound..<file.endIndex))
        let flow = String(file[start.upperBound..<end.lowerBound])
        func at(_ s: String) throws -> String.Index { try XCTUnwrap(flow.range(of: s), s).lowerBound }
        XCTAssertTrue(flow.contains("can: [RequestsTogether.keyAndCards]"), "it says it can sign the step")
        XCTAssertTrue(try at("ask.step == RequestsTogether.keyAndCards else { return .notOffered }") < (try at("signer.signApproval(")), "another step signs nothing here")
        XCTAssertTrue(try at("Self.keyAndCardIsThisCards(") < (try at("signer.signApproval(")), "the ask is checked before anything is signed")
        XCTAssertEqual(flow.components(separatedBy: "signer.signApproval(").count - 1, 1, "one Face ID")
        XCTAssertFalse(flow.contains("signer.sign("), "no second prompt")
        XCTAssertTrue(try at("planque.giveKey(keyRequest") < (try at("kiff.confirmDraft(confirm")), "the key first")
        XCTAssertTrue(try at("if given.connected {") < (try at("kiff.confirmDraft(confirm")), "the Card only for a key that went in")
        XCTAssertTrue(flow.contains("prepareConfirmDraft(id: draftID, version: shownDraft"), "the version the person was shown is what's confirmed")
        XCTAssertFalse(flow.contains("cloud.") && flow.contains("key: key, session"), "the key never goes to tafs cloud")
    }

    /// On the sheet: under one signature the card is answered already, so closing the receipt doesn't approve it again.
    func testTheSheetDoesNotApproveTheCardTwice() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let file = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        XCTAssertTrue(file.contains("if !together { onGiven?() }"))
        let together = try XCTUnwrap(file.range(of: "switch await model.approveKeyAndCard("))
        let alone = try XCTUnwrap(file.range(of: "if await model.giveKey(grantID:"))
        XCTAssertTrue(together.lowerBound < alone.lowerBound, "the one signature is tried first; the key by itself only when it isn't asked")
        let between = String(file[together.upperBound..<alone.lowerBound])
        XCTAssertTrue(between.contains("case .failed:\n                        return"), "a failed one-signature step never falls through to giving the key alone")
        XCTAssertTrue(file.contains("shownDraft: draft.version"), "the version signed is the draft's the sheet drew")
        let chat = try String(contentsOf: views.appendingPathComponent("SetupChatView.swift"), encoding: .utf8)
        XCTAssertTrue(chat.contains("}, card: component)"), "Coach's card is passed to the key's sheet")
    }
}
