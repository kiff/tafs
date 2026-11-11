import CryptoKit
import XCTest
@testable import Tafs

/// "Your step" (docs/api.md › Your step): a step a taf handed its owner, listed by tafs cloud and
/// answered to KIFF, "done" or "skipped", signed with the device key over `kiff.handoff.v1`.
@MainActor
final class YourStepTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount); Keychain.delete(AppModel.tenantAccount); Keychain.delete(AppModel.keyedAccount)
        super.tearDown()
    }

    // MARK: KIFF's pinned vector for the hand-off answer

    private static let vectorSeed = "kiff.handoff.v1 test vector"
    private static let vectorSPKIDigest = "85c559569edf864d4b3186426f3541f8bbf8c424d96de17f707b919b2c593970"
    private static let vectorMessage = "kiff.handoff.v1\nt_vector\nho_0123456789abcdef01234567\ndone\n1\n2026-11-11T17:00:00Z"
    private static let vectorSHA256 = "1b9f7f923489891b736af1fbc494c9348690494d4ae4901844aeb9a0b6df3b6f"
    private static let vectorSignature = "MEUCIQDJLYymcVMzMDhv+kRXK1BS2DhZvJROyBCjJLzzTy5NDgIgXe7a2OeeShmHHI5HazLS+/BzMjLVBYpjX7JT0Uy67RA="

    private static func hex(_ digest: some Sequence<UInt8>) -> String { digest.map { String(format: "%02x", $0) }.joined() }

    /// The message is KIFF's byte for byte, KIFF's own signature over it verifies with the
    /// vector's key, and so does one this app makes; neither stands for "skipped".
    func testTheAnswerMessageMatchesKIFFsVector() throws {
        let message = KIFFClient.handoffMessage(tenant: "t_vector", handoffID: "ho_0123456789abcdef01234567", answer: "done",
                                                signedAt: "2026-11-11T17:00:00Z")
        XCTAssertEqual(String(decoding: message, as: UTF8.self), Self.vectorMessage)
        XCTAssertEqual(Self.hex(SHA256.hash(data: message)), Self.vectorSHA256)

        let key = try P256.Signing.PrivateKey(rawRepresentation: Data(SHA256.hash(data: Data(Self.vectorSeed.utf8))))
        XCTAssertEqual(Self.hex(SHA256.hash(data: key.publicKey.derRepresentation)), Self.vectorSPKIDigest, "the vector's key moved")
        let theirs = try P256.Signing.ECDSASignature(derRepresentation: XCTUnwrap(Data(base64Encoded: Self.vectorSignature)))
        XCTAssertTrue(key.publicKey.isValidSignature(theirs, for: message), "KIFF's signature verifies over the app's message")
        let ours = try key.signature(for: message)
        XCTAssertTrue(key.publicKey.isValidSignature(ours, for: message))

        let skipped = KIFFClient.handoffMessage(tenant: "t_vector", handoffID: "ho_0123456789abcdef01234567", answer: "skipped",
                                                signedAt: "2026-11-11T17:00:00Z")
        XCTAssertFalse(key.publicKey.isValidSignature(theirs, for: skipped), "a signed \"done\" never stands for \"skipped\"")
        let hold = KIFFClient.answerMessage(tenant: "t_vector", exceptionID: "ho_0123456789abcdef01234567", answer: "done",
                                            nextStep: nil, accountingVersion: 1, signedAt: "2026-11-11T17:00:00Z")
        XCTAssertNotEqual(hold, message, "a hold's answer is never a hand-off's")
    }

    // MARK: What tafs cloud sends

    private static func step(handoff: String = "ho_1", link: String? = "https://app.qonto.com/transfers/tr_9",
                             host: String? = "app.qonto.com", open: String? = "Open in Qonto") -> String {
        let linkJSON = [link.map { #""link":"\#($0)""# }, host.map { #""link_host":"\#($0)""# }].compactMap { $0 }
        let openJSON = open.map { #","open":"\#($0)""# } ?? ""
        return #"{"handoff":"\#(handoff)","run":"run_1","taf":{"id":"depl_1","name":"Rae"},"what":"Approve the transfer of €1,200 to Acme","why":"Only you may approve a transfer.",\#(linkJSON.map { $0 + "," }.joined())"since":"2026-11-11T17:00:00Z","expires":"2026-11-18T17:00:00Z","words":{"title":"Your step","done":"Done","skip":"Skip it"\#(openJSON),"line":"Your step: Approve the transfer of €1,200 to Acme"}}"#
    }

    private func decode(_ json: String) throws -> TafsCloud.YourStep {
        try KIFFClient.decoder.decode(TafsCloud.YourStep.self, from: Data(json.utf8))
    }

    /// A button only for a link tafs cloud vouched for: https, its host given apart and the
    /// link's own, with the button's words. Anything else shows the words and no button.
    func testTheVendorButtonShowsOnlyForAVouchedHttpsLink() throws {
        let vouched = try decode(Self.step())
        XCTAssertEqual(vouched.openLink?.url.absoluteString, "https://app.qonto.com/transfers/tr_9")
        XCTAssertEqual(vouched.openLink?.host, "app.qonto.com")
        XCTAssertEqual(vouched.openLink?.words, "Open in Qonto")
        XCTAssertEqual(vouched.taf?.name, "Rae")
        XCTAssertEqual(vouched.words.line, "Your step: Approve the transfer of €1,200 to Acme")

        XCTAssertNil(try decode(Self.step(link: nil, host: nil, open: nil)).openLink, "no link: the words only")
        XCTAssertNil(try decode(Self.step(open: nil)).openLink, "no words for the button: no button")
        XCTAssertNil(try decode(Self.step(host: nil)).openLink, "no host apart: no button")
        XCTAssertNil(try decode(Self.step(link: "http://app.qonto.com/transfers/tr_9")).openLink, "never plain http")
        XCTAssertNil(try decode(Self.step(link: "https://app.qonto.com.evil.example/login")).openLink,
                     "the host under the button is the link's own")
        XCTAssertNil(try decode(Self.step(link: "javascript:alert(1)")).openLink)
    }

    func testHomeAndARequestCarryTheStep() throws {
        let list = HomeJSON.list([HomeJSON.rae])
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self,
                                                 from: Data((String(list.dropLast()) + #","your_steps":[\#(Self.step())]}"#).utf8))
        XCTAssertEqual(home.yourSteps?.map(\.handoff), ["ho_1"])
        XCTAssertNil(try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(list.utf8)).yourSteps)

        // A taf's request carries no link, and its line is the step's.
        let request = try KIFFClient.decoder.decode(TafsCloud.TafWork.Request.self, from: Data(
            #"{"id":"run_1","at":"2026-11-11T17:00:00Z","state":"waiting","line":"Your step: Approve the transfer of €1,200 to Acme","deliveries":[],"your_step":\#(Self.step(link: nil, host: nil, open: nil))}"#.utf8))
        XCTAssertEqual(request.yourStep?.handoff, "ho_1")
        XCTAssertNil(request.yourStep?.openLink)
        XCTAssertNil(try KIFFClient.decoder.decode(TafsCloud.TafWork.Request.self,
                                                   from: Data(#"{"id":"run_2","at":"2026-11-11T17:00:00Z"}"#.utf8)).yourStep)
    }

    // MARK: Answering

    private func model(signer: ApprovalSigning, steps: [String] = [step()]) async -> AppModel {
        StubProtocol.responses = [:]; StubProtocol.sent = []; StubProtocol.thenRespond = [:]
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: signer, kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, Self.home(steps))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        await model.useSession("synthetic-session", tenant: "t_01")
        return model
    }

    private static func home(_ steps: [String]) -> String {
        let list = HomeJSON.list([HomeJSON.rae])
        let count = steps.count
        return String(list.dropLast()) + #","your_steps":[\#(steps.joined(separator: ","))],"needs_you":{"count":\#(count),"words":"\#(count) need you"}}"#
    }

    private func sent(_ path: String) -> [URLRequest] { StubProtocol.sent.filter { $0.httpMethod == "POST" && $0.url?.path == path } }

    /// "Done" signs KIFF's message for this hand-off with the device key and sends it to KIFF,
    /// never to tafs cloud. Home may list it until the run hears the answer: it stays out of
    /// Needs you and its count meanwhile, and is forgotten once Home stops listing it.
    func testDoneIsSignedAndSentToKIFFAndLeavesNeedsYou() async throws {
        let signer = RecordingSigner()
        let model = await model(signer: signer)
        XCTAssertEqual(model.yourSteps.map(\.handoff), ["ho_1"])
        XCTAssertEqual(model.needsYouCount, 1)
        XCTAssertTrue(model.canAnswerSteps)
        let step = try XCTUnwrap(model.yourSteps.first)

        StubProtocol.responses["POST /v1/me/handoffs/ho_1/answer"] = (200, #"{"id":"ho_1","status":"done"}"#)
        await model.answerYourStep(step, done: true)

        let request = try XCTUnwrap(sent("/v1/me/handoffs/ho_1/answer").first)
        XCTAssertEqual(request.url?.host, "api.test", "the answer goes to KIFF")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        XCTAssertEqual(body["answer"] as? String, "done")
        let signature = try XCTUnwrap(body["signature"] as? [String: Any])
        let signedAt = try XCTUnwrap(signature["signed_at"] as? String)
        XCTAssertEqual(signature["value"] as? String, "signature")
        let expected = KIFFClient.handoffMessage(tenant: "t_01", handoffID: "ho_1", answer: "done", signedAt: signedAt)
        XCTAssertEqual(signer.signed, [String(decoding: expected, as: UTF8.self)])
        XCTAssertFalse(StubProtocol.sent.contains { $0.url?.host == "cloud.test" && $0.httpMethod == "POST" }, "tafs cloud is never told")

        // Home, read again, still lists it: it stays answered here.
        XCTAssertTrue(model.yourSteps.isEmpty)
        XCTAssertEqual(model.needsYouCount, 0)
        XCTAssertFalse(model.canAnswer(step: "ho_1"))
        await model.answerYourStep(step, done: false)
        XCTAssertEqual(sent("/v1/me/handoffs/ho_1/answer").count, 1, "an answered step sends nothing more")

        // Once Home stops listing it, it's forgotten.
        StubProtocol.responses["GET /v1/home"] = (200, Self.home([]))
        await model.refresh()
        XCTAssertTrue(model.canAnswer(step: "ho_1"))
        XCTAssertEqual(model.needsYouCount, 0)
    }

    func testSkipItSignsSkipped() async throws {
        let signer = RecordingSigner()
        let model = await model(signer: signer)
        StubProtocol.responses["POST /v1/me/handoffs/ho_1/answer"] = (200, #"{"id":"ho_1","status":"skipped"}"#)
        await model.answerYourStep(try XCTUnwrap(model.yourSteps.first), done: false)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(sent("/v1/me/handoffs/ho_1/answer").first?.httpBody)) as? [String: Any])
        XCTAssertEqual(body["answer"] as? String, "skipped")
        XCTAssertTrue(signer.signed.first?.hasPrefix("kiff.handoff.v1\nt_01\nho_1\nskipped\n1\n") == true, signer.signed.first ?? "")
    }

    /// Answered already, on another phone or before it expired: KIFF's 409 settles it here too.
    /// Any other refusal keeps the step, with words.
    func testAlreadyAnsweredSettlesAndARefusalKeepsTheStep() async throws {
        let model = await model(signer: RecordingSigner(), steps: [Self.step(), Self.step(handoff: "ho_2")])
        StubProtocol.responses["POST /v1/me/handoffs/ho_1/answer"] = (409, #"{"error":"already_answered"}"#)
        await model.answerYourStep(try XCTUnwrap(model.yourStep("ho_1")), done: true)
        XCTAssertEqual(model.yourSteps.map(\.handoff), ["ho_2"])

        model.lastError = nil
        StubProtocol.responses["POST /v1/me/handoffs/ho_2/answer"] = (403, #"{"error":"role_forbidden"}"#)
        await model.answerYourStep(try XCTUnwrap(model.yourStep("ho_2")), done: true)
        XCTAssertEqual(model.yourSteps.map(\.handoff), ["ho_2"])
        XCTAssertNotNil(model.lastError)
        XCTAssertTrue(model.answeringSteps.isEmpty)
    }

    /// No device key in the session: nothing is signed or sent, and the person is told why.
    func testWithoutTheDeviceKeyNothingIsSent() async throws {
        let signer = RecordingSigner()
        let model = await model(signer: signer)
        model.sessionHasKey = false
        XCTAssertFalse(model.canAnswerSteps)
        await model.answerYourStep(try XCTUnwrap(model.yourSteps.first), done: true)
        XCTAssertTrue(signer.signed.isEmpty)
        XCTAssertTrue(sent("/v1/me/handoffs/ho_1/answer").isEmpty)
        XCTAssertEqual(model.lastError, Words.NeedsYou.signInAgainToAnswer)
    }

    /// Its notification opens Needs you, where "Done" and "Skip it" are.
    func testItsNotificationOpensItInNeedsYou() {
        let model = AppModel(signer: StubSigner())
        model.open("step:ho_1")
        XCTAssertEqual(model.requestedTab, .needsYou)
        XCTAssertEqual(model.requestedStep, "ho_1")
    }
}
