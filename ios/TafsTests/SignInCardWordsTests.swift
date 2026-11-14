import XCTest
@testable import Tafs

/// A sign-in card names its app and never shows a connection's raw status (as Android 1.27.8): the
/// owner's card read "Sign in at the vendor" for Gmail, and "This connection is listed." under it.
@MainActor
final class SignInCardWordsTests: XCTestCase {
    private func grant(status: String, vendor: String? = "Gmail") throws -> PlanqueClient.Grant {
        let named = vendor.map { #","vendor":"\#($0)""# } ?? ""
        let json = #"{"id":"g_1","status":"\#(status)","text":"t","digest":"d","events":[]\#(named),"request":{"server":"https://mail.test","agent":"inbox","tools":[]}}"#
        return try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8))
    }

    /// The card's service, else the connection's app; "the vendor" only when neither is known.
    func testTheCardNamesTheAppFromItsServiceElseItsConnection() {
        XCTAssertEqual(AppModel.signInApp(service: "Stripe", app: "Gmail"), "Stripe")
        XCTAssertEqual(AppModel.signInApp(service: nil, app: "Gmail"), "Gmail")
        XCTAssertEqual(AppModel.signInApp(service: " ", app: "Gmail"), "Gmail")
        XCTAssertNil(AppModel.signInApp(service: nil, app: nil))
        XCTAssertEqual(AppModel.signInWords(app: "Gmail"), "Sign in at Gmail")
        XCTAssertEqual(AppModel.signInWords(app: nil), "Sign in at the vendor")
    }

    /// The title never carries a connection's status, whatever planque's status is.
    func testTheTitleNeverShowsARawStatus() throws {
        let model = AppModel(signer: StubSigner())
        for status in ["signed_in", "needs_reconnect", "listed", "pending", "revoked"] {
            let g = try grant(status: status)
            let title = AppModel.signInWords(app: AppModel.signInApp(service: nil, app: model.appName(g)))
            XCTAssertEqual(title, "Sign in at Gmail", status)
            XCTAssertFalse(title.contains(status), status)
            XCTAssertFalse(title.contains(status.replacingOccurrences(of: "_", with: " ")), status)
        }
    }

    /// Without Connect apps read, the app is planque's vendor in people's words, never its server.
    func testTheAppNameFallsBackToPlanquesVendor() throws {
        let model = AppModel(signer: StubSigner())
        XCTAssertNil(model.connectApps)
        XCTAssertEqual(model.appName(try grant(status: "pending")), "Gmail")
    }

    /// The card draws the app's name in its title and its answered lines, and a connection that takes
    /// no approval now asks tafs cloud for its words before the app's own line.
    func testTheCardDrawsTheAppAndAsksTafsCloudForAConnectionPastApproving() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")
        let view = try String(contentsOf: root.appendingPathComponent("Views/SetupChatView.swift"), encoding: .utf8)
        let card = String(view[try XCTUnwrap(view.range(of: "@ViewBuilder private func signIn(_ c: SetupComponent) -> some View {")).upperBound...].prefix(2400))
        XCTAssertTrue(card.contains("let app = AppModel.signInApp(service: c.service, app: model.grants.first { $0.id == c.grant }.map { model.appName($0) })"))
        XCTAssertTrue(card.contains("Text(signedOut?.signedOutWords ?? AppModel.signInWords(app: app))"))
        XCTAssertFalse(card.contains("c.service ?? Words.Coach.theVendor"), "never \"the vendor\" while the app is known")
        XCTAssertFalse(card.contains(".status"), "the card draws no status of its own")

        let model = try String(contentsOf: root.appendingPathComponent("App/AppModel.swift"), encoding: .utf8)
        let again = String(model[try XCTUnwrap(model.range(of: "func signInAgain(_ component: SetupComponent) async {")).upperBound...].prefix(1600))
        let asks = try XCTUnwrap(again.range(of: "if done == nil, notApprovable {"))
        let fails = try XCTUnwrap(again.range(of: "await answer(component, \"failed: \\(e)\")"))
        XCTAssertLessThan(asks.lowerBound, fails.lowerBound, "tafs cloud's words first; the app's line only where it can't be asked")
        XCTAssertTrue(model.contains("let decision = try? await cloud.approval(s.id, component: component.id, body: .init(grant: grantID, session: session), proof: proof)"))
    }
}
