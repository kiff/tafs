import XCTest
@testable import Tafs

/// What the Face ID or Touch ID prompt names, the last thing read before signing: the app as people
/// say it and the taf by its name, never a server's slug or an agent id. And the Theme picker's words.
@MainActor
final class SigningPromptTests: XCTestCase {
    private func grant(vendor: String, agent: String, agentName: String? = nil) throws -> PlanqueClient.Grant {
        let name = agentName.map { #","agent_name":"\#($0)""# } ?? ""
        let json = #"{"id":"g1","status":"pending","text":"","digest":"d","vendor":"\#(vendor)"\#(name),"request":{"server":"https://mcp.stripe.com","agent":"\#(agent)","tools":[]},"events":[]}"#
        return try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8))
    }

    func testThePromptNamesTheAppNotItsSlug() throws {
        XCTAssertEqual(AppModel.promptApp(try grant(vendor: "stripe-mcp", agent: "refunds-x1")), "Stripe")
        XCTAssertEqual(AppModel.promptApp(try grant(vendor: "Stripe", agent: "refunds-x1")), "Stripe")
    }

    /// The taf's name, as planque keeps it, else the draft's label; with neither and no taf on
    /// Home by that agent, the shared "a taf", never the agent id.
    func testThePromptNamesTheTafNeverItsAgentID() throws {
        let model = AppModel(signer: StubSigner())
        XCTAssertEqual(model.promptTaf("refunds-x1", grant: try grant(vendor: "Stripe", agent: "refunds-x1", agentName: "Refunds")), "Refunds")
        XCTAssertEqual(model.promptTaf("refunds-x1", label: "Refunds"), "Refunds")
        XCTAssertEqual(model.promptTaf("refunds-x1", label: "  "), Words.NeedsYou.aTaf)
        XCTAssertEqual(model.promptTaf("refunds-x1"), Words.NeedsYou.aTaf)
        XCTAssertFalse(Words.Connect.Prompt.connect(vendor: "Stripe", agent: model.promptTaf("refunds-x1")).contains("refunds-x1"))
    }

    /// The Theme picker reads in the app's language, not "Light / Dark / System" in every one.
    func testThemeTitlesAreTheAppsWords() {
        XCTAssertEqual(ThemePreference.light.title, Words.Settings.Theme.light)
        XCTAssertEqual(ThemePreference.dark.title, Words.Settings.Theme.dark)
        XCTAssertEqual(ThemePreference.system.title, Words.Settings.Theme.system)
    }
}
