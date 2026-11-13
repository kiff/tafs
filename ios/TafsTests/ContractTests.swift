import XCTest
@testable import Tafs

/// The contract with tafs cloud (contracts/README.md): tafs cloud's answers, as its own handler
/// writes them (runtime/test/contract_apps.test.ts), read here as the app reads them. An answer
/// whose shape changed fails here, never first on a phone.
@MainActor
final class ContractTests: XCTestCase {
    private func cases(_ file: String) throws -> [[String: Any]] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("contracts/\(file)")
        let root = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        return try XCTUnwrap(root?["cases"] as? [[String: Any]], "\(file) has no cases")
    }

    /// One answer kept as a contract file (`{about, answer}`, runtime/test/contract.ts), as the bytes the app would read.
    private func answer(_ file: String) throws -> Data {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("contracts/\(file)")
        let root = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        return try JSONSerialization.data(withJSONObject: try XCTUnwrap(root?["answer"], "\(file) has no answer"))
    }

    /// Coach's first start as tafs cloud sends it: the pair under one signature, and the draft alone. Each is one the
    /// phone may sign, with KIFF's words for the Cards to show first.
    func testCoachsFirstStartReadsAsTafsCloudSendsIt() throws {
        let pair = try XCTUnwrap(TafsCloud.CoachStart.read(try answer("setup-start.json")), "setup-start.json: no start read")
        XCTAssertTrue(pair.together, "the batch and the draft, signed once")
        XCTAssertTrue(pair.startable, "a start the phone may sign")
        XCTAssertFalse(pair.draft?.cards.isEmpty ?? true, "the Cards in KIFF's words, to show before signing")
        let alone = try XCTUnwrap(TafsCloud.CoachStart.read(try answer("setup-start-draft-alone.json")), "setup-start-draft-alone.json: no start read")
        XCTAssertFalse(alone.together)
        XCTAssertNil(alone.connect)
        XCTAssertTrue(alone.startable, "the draft alone, the phone may sign")
        XCTAssertFalse(alone.draft?.cards.isEmpty ?? true)
        XCTAssertNil(TafsCloud.CoachStart.read(try answer("setup-time-zone.json")), "no start in an answer without one")
    }

    /// Home's part of the recipes answer, for someone who manages the tafs, with no live taf.
    func testHomesRecipesReadAsTafsCloudSendsThem() throws {
        let read = try JSONDecoder().decode(TafsCloud.Recipes.self, from: try answer("recipes-home.json"))
        XCTAssertEqual(read.home?.shown, true)
        XCTAssertNotNil(read.homeWords, "Home's line")
        XCTAssertNotNil(read.labels, "the sheet's words")
    }

    func testTheAIConsentReadsAsTafsCloudAnswersIt() throws {
        let all = try cases("ai-consent.json")
        XCTAssertGreaterThanOrEqual(all.count, 5)
        struct Answer: Decodable { let consent: TafsCloud.AIConsent }
        for c in all {
            let name = c["name"] as? String ?? "?"
            let status = try XCTUnwrap(c["status"] as? Int, name)
            let body = try JSONSerialization.data(withJSONObject: try XCTUnwrap(c["body"], name))
            switch status {
            case 200:
                let consent = try JSONDecoder().decode(Answer.self, from: body).consent
                XCTAssertTrue(consent.drawable, "\(name): the screen has its words")
                XCTAssertEqual(consent.given, name == "given", name)
            case 403:
                let refused = TafsCloud.refusal(status, body)
                XCTAssertEqual(refused.code, TafsCloud.aiConsentRequired, name)
                XCTAssertEqual(refused.consent?.drawable, true, "\(name): the refusal brings the screen up")
            case 409:
                let refused = TafsCloud.refusal(status, body)
                XCTAssertEqual(refused.consent?.drawable, true, "\(name): the newer words come with it")
                XCTAssertEqual(refused.consent?.given, false, name)
            default:
                XCTFail("\(name): an answer the app doesn't read (\(status))")
            }
        }
    }
}
