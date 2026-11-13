import CryptoKit
import XCTest
@testable import Tafs

/// Tools added to a connected app and the taf's Card over them, under one Face ID
/// (`tools_and_cards`, docs/secure-connection.md): planque's approve built before it's signed, the
/// phone's last check that the ask is this card's, and the flow's order.
@MainActor
final class ToolsAndCardsTests: XCTestCase {
    private func planque() -> PlanqueClient {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: URLSession(configuration: config))
    }

    private func ask(step: String = "tools_and_cards", grant: String = "g_3", digest: String = "d1", profile: String? = nil,
                     draft: String = "dr_1", version: String = "v7") throws -> TafsCloud.ApprovalDecision.Ask {
        let kiffProfile = profile.map { #","kiff_profile":{"profile_sha256":"\#($0)","words_sha256":"w1"}"# } ?? ""
        let json = #"{"id":"ask_1","step":"\#(step)","scheme":"kiff.requests.v1","message":"","reason":"Connect Sheets for Invoices","key":"critical","planque":{"grant":"\#(grant)","body":{"digest":"\#(digest)"\#(kiffProfile)}},"kiff":{"draft":"\#(draft)","version":"\#(version)"}}"#
        return try KIFFClient.decoder.decode(TafsCloud.ApprovalDecision.Ask.self, from: Data(json.utf8))
    }

    /// The approve is built before it's signed: its message is planque's own over the exact body,
    /// and it's sent with `v3`, `with` and this device, and nothing signs it again.
    func testTheApproveIsSentAsItWasSigned() async throws {
        let planque = planque()
        let body = PlanqueClient.ApproveBody(digest: "d1", kiffProfile: nil)
        XCTAssertThrowsError(try planque.prepareApprove(grantID: "g_3", body: body, signedAt: "T"), "no account, nothing to sign")
        planque.tenant = "t_01"
        planque.deviceID = "dev_phone01"
        let prepared = try planque.prepareApprove(grantID: "g_3", body: body, signedAt: "2026-11-13T21:00:00.000Z")
        XCTAssertEqual(String(decoding: prepared.body, as: UTF8.self), #"{"digest":"d1"}"#)
        XCTAssertEqual(String(decoding: prepared.message, as: UTF8.self),
                       "planque.request.v1\nhttps://planque.test\nt_01\nPOST\n/v1/accounts/t_01/grants/g_3/approve\n\(RequestsTogether.hash(prepared.body))\n2026-11-13T21:00:00.000Z")

        var signedHere = 0
        planque.signer = { _ in signedHere += 1; return "other" }
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_3/approve"] = (200, #"{"status":"connected"}"#)
        let other = String(repeating: "c", count: 64)
        let approved = try await planque.approve(prepared, signature: "sig", with: other)
        XCTAssertEqual(approved.status, "connected")
        XCTAssertNil(approved.signInURL)
        let sent = try XCTUnwrap(StubProtocol.sent.last)
        XCTAssertEqual(sent.value(forHTTPHeaderField: "Planque-Device-Signature"), "t=2026-11-13T21:00:00.000Z,v3=sig,with=sha256:\(other),d=dev_phone01")
        XCTAssertEqual(sent.httpBody, prepared.body, "the bytes that were signed")
        XCTAssertEqual(signedHere, 0, "nothing signs it again")
    }

    /// The ask is signed only when it's this card's, for what the person was shown: planque's
    /// approve exactly as this phone builds it, and the Card's draft at the version shown.
    func testTheAskMustBeThisCardsForWhatWasShown() throws {
        let body = PlanqueClient.ApproveBody(digest: "d1", kiffProfile: nil)
        func mine(_ a: TafsCloud.ApprovalDecision.Ask, body: PlanqueClient.ApproveBody = body) -> Bool {
            AppModel.toolsAndCardIsThisCards(a, grant: "g_3", body: body, shownDigest: "d1", draft: "dr_1", shownDraft: "v7")
        }
        XCTAssertTrue(mine(try ask()))
        XCTAssertFalse(mine(try ask(grant: "g_9")), "another connection")
        XCTAssertFalse(mine(try ask(digest: "d2")), "tools the person wasn't shown")
        XCTAssertFalse(mine(try ask(), body: .init(digest: "d2", kiffProfile: nil)), "a grant read since, at another digest")
        XCTAssertFalse(mine(try ask(profile: "p1")), "a profile this phone didn't read KIFF's words for")
        let withWords = PlanqueClient.ApproveBody(digest: "d1", kiffProfile: .init(profileSHA256: "p1", wordsSHA256: "w1"))
        XCTAssertTrue(mine(try ask(profile: "p1"), body: withWords), "the profile and KIFF's words this phone checked")
        XCTAssertFalse(mine(try ask(profile: "p2"), body: withWords), "another profile")
        XCTAssertFalse(mine(try ask(draft: "dr_9")), "another Card's draft")
        XCTAssertFalse(mine(try ask(version: "v8")), "a version the person wasn't shown")
        XCTAssertFalse(mine(try ask(step: "key_and_cards")), "another step")
        let noKIFF = try KIFFClient.decoder.decode(TafsCloud.ApprovalDecision.Ask.self, from: Data(#"{"id":"a","step":"tools_and_cards","message":"","reason":"r","planque":{"grant":"g_3","body":{"digest":"d1"}}}"#.utf8))
        XCTAssertFalse(mine(noKIFF), "no draft named")
        // The older steps sign nothing for it: only this check lets it through.
        XCTAssertNil(AppModel.expectedApprovalMessage(try ask(), component: try SetupTests.component(#"{"type":"approval","id":"c","grant":"g_3","draft":"dr_1"}"#),
                                                      tenant: "t_01", shownDigest: "d1", shownDraft: "v7"))
    }

    /// Show what you sign: the combined step is offered only for a Card the card drew, read and
    /// at the version shown; and Approve waits for the Card to be read.
    func testOnlyACardDrawnOnTheCardIsSignedWithTheTools() throws {
        func draft(_ json: String) throws -> KIFFClient.AgentDraft {
            try KIFFClient.decoder.decode(KIFFClient.AgentDraft.self, from: Data(json.utf8))
        }
        let read = try draft(#"{"id":"dr_1","agent_id":"mail","status":"pending","version":"v7","cards":[{"grants":[{"action":"gmail_list_threads","per_action_max":[{"quantity":"count","max":100}]}]}]}"#)
        XCTAssertTrue(AppModel.cardDrawn(read, shownDraft: "v7"))
        XCTAssertFalse(AppModel.cardDrawn(read, shownDraft: "v8"), "another version than the one shown")
        XCTAssertFalse(AppModel.cardDrawn(read, shownDraft: nil), "nothing shown")
        XCTAssertFalse(AppModel.cardDrawn(nil, shownDraft: "v7"), "not read yet")
        XCTAssertFalse(AppModel.cardDrawn(try draft(#"{"id":"dr_1","agent_id":"mail","status":"pending","version":"v7"}"#), shownDraft: "v7"), "no Cards to draw")
        XCTAssertFalse(AppModel.cardDrawn(try draft(#"{"id":"dr_1","agent_id":"mail","status":"pending","version":"v7","cards":[]}"#), shownDraft: "v7"), "an empty list: only the eyebrow is drawn")
        XCTAssertFalse(AppModel.cardDrawn(try draft(#"{"id":"dr_1","agent_id":"mail","status":"pending","version":"v7","cards":[{"grants":[]}]}"#), shownDraft: "v7"), "a Card with no lines")
        XCTAssertFalse(AppModel.cardDrawn(try draft(#"{"id":"dr_1","agent_id":"mail","status":"pending","version":"v7","cards":[],"no_tools":true}"#), shownDraft: "v7"), "a draft with no tools")

        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let chat = try String(contentsOf: views.appendingPathComponent("SetupChatView.swift"), encoding: .utf8)
        XCTAssertTrue(chat.contains("|| (component.draft != nil && draft == nil) ||"), "Approve waits for the Card")
    }

    /// The flow's order, read from its source: Approve on Coach's card says it can sign the step
    /// when the card has a Card drafted; the ask is checked before anything is signed; the
    /// approval key signs once; the tools go before the Card, and the Card only once connected.
    func testTheFlowSignsOnceAndSendsTheToolsFirst() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/App/AppModel.swift")
        let file = try String(contentsOf: app, encoding: .utf8)
        let approve = try XCTUnwrap(file.range(of: "func approve(_ component: SetupComponent, shown: String?, shownDraft: String?) async {"))
        let approveEnd = try XCTUnwrap(file.range(of: "nonisolated static let signInNotConfirmed", range: approve.upperBound..<file.endIndex))
        let card = String(file[approve.upperBound..<approveEnd.lowerBound])
        XCTAssertTrue(card.contains("Self.cardDrawn(drawn, shownDraft: shownDraft) ? [RequestsTogether.toolsAndCards] : nil"), "it says it can, with the Card drawn")
        XCTAssertTrue(card.contains("can: can), proof: proof)"))
        let branch = try XCTUnwrap(card.range(of: "if ask.step == RequestsTogether.toolsAndCards {"))
        let older = try XCTUnwrap(card.range(of: "ask.message == Self.expectedApprovalMessage("))
        XCTAssertTrue(branch.lowerBound < older.lowerBound, "its own check, not the older steps' message")
        XCTAssertTrue(card.contains("came = together"), "what came of it is reported as any approval's")

        let start = try XCTUnwrap(file.range(of: "private func approveToolsAndCard("))
        let end = try XCTUnwrap(file.range(of: "nonisolated static func toolsAndCardIsThisCards(", range: start.upperBound..<file.endIndex))
        let flow = String(file[start.upperBound..<end.lowerBound])
        func at(_ s: String) throws -> String.Index { try XCTUnwrap(flow.range(of: s), s).lowerBound }
        XCTAssertTrue(try at("Self.toolsAndCardIsThisCards(") < (try at("signer.signApproval(")), "the ask is checked before anything is signed")
        XCTAssertTrue(try at("PlanqueClient.approveBody(grant, words: words)") < (try at("Self.toolsAndCardIsThisCards(")), "the body is the phone's own, from the grant it read")
        XCTAssertEqual(flow.components(separatedBy: "signer.signApproval(").count - 1, 1, "one Face ID")
        XCTAssertFalse(flow.contains("signer.sign("), "no second prompt")
        XCTAssertTrue(try at("planque.approve(approveRequest") < (try at("kiff.confirmDraft(confirm")), "the tools first")
        XCTAssertTrue(try at("guard tools.result == \"connected\"") < (try at("kiff.confirmDraft(confirm")), "the Card only once connected")
        XCTAssertTrue(flow.contains("prepareConfirmDraft(id: draftID, version: shownDraft"), "the version the person was shown is what's confirmed")
    }
}
