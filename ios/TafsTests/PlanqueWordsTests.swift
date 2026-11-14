import XCTest
@testable import Tafs

/// planque's own words for the person on a refusal are shown as they are, never a generic line over
/// them: a sign-in to another account read "It changed in the meantime", which read as "try again", and
/// the owner looped. Without words, the plain line by what happened, as before.
@MainActor
final class PlanqueWordsTests: XCTestCase {
    func testTheRefusalIsReadForItsReasonAndItsWords() {
        let both = PlanqueClient.refusal(Data(#"{"error":"that sign-in is to another account","words":"You signed in as another account. Sign in as gabrielm…, the account this taf uses."}"#.utf8))
        XCTAssertEqual(both.error, "that sign-in is to another account")
        XCTAssertEqual(both.words, "You signed in as another account. Sign in as gabrielm…, the account this taf uses.")
        XCTAssertNil(PlanqueClient.refusal(Data(#"{"error":"digest changed"}"#.utf8)).words)
        XCTAssertNil(PlanqueClient.refusal(Data(#"{"error":"x","words":"  "}"#.utf8)).words, "blank: none")
        XCTAssertNil(PlanqueClient.refusal(Data(#"{"error":"x","words":{"en":"y"}}"#.utf8)).words, "another shape: none")
        let none = PlanqueClient.refusal(Data("not json".utf8))
        XCTAssertNil(none.error)
        XCTAssertNil(none.words)
    }

    func testTheWordsAreWhatThePersonReads() {
        let other = PlanqueClient.PlanqueError.server(status: 409, message: "that sign-in is to another account", words: "You signed in as another account.")
        XCTAssertEqual(other.errorDescription, "You signed in as another account.", "planque's words, never 'changed'")
        XCTAssertEqual(PlanqueClient.PlanqueError.server(status: 409, message: "digest changed").errorDescription, Words.Error.changed,
                       "a real change with no words: as before")
        XCTAssertEqual(PlanqueClient.PlanqueError.server(status: 500, message: "boom").errorDescription, Words.Error.down)
        XCTAssertTrue(AppModel.refusesTheRequest(other), "still a refusal of the request, by its status")
    }

    /// The other-account case through the client: the confirm's 409 carries planque's words to the person.
    func testASignInToAnotherAccountSaysSo() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let planque = PlanqueClient(baseURL: URL(string: "https://planque.test")!, session: URLSession(configuration: config))
        planque.tenant = "t_01"
        planque.deviceID = "dev_1"
        planque.signer = { _ in "sig" }
        StubProtocol.responses["POST /v1/accounts/t_01/grants/g_1/confirm"] = (409, #"{"error":"that sign-in is to another account than this connection's","words":"You signed in as another account. Sign in as gabrielm…, the account this taf uses."}"#)
        do {
            _ = try await planque.confirm(grantID: "g_1", code: "c")
            XCTFail("refused")
        } catch {
            XCTAssertEqual(error.localizedDescription, "You signed in as another account. Sign in as gabrielm…, the account this taf uses.")
            guard case PlanqueClient.PlanqueError.server(409, let message, _) = error else { return XCTFail("\(error)") }
            XCTAssertEqual(message, "that sign-in is to another account than this connection's", "the reason stays for the code")
        }
    }
}
