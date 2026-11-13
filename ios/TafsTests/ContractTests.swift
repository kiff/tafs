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
