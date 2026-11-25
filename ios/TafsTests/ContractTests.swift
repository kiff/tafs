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

    /// The Card step as tafs cloud sends it (docs/ios-app.md, "The Card step"): every line of its words, for a Face ID phone
    /// and a Touch ID one, and the plain list it shows equal to the draft it signs, compared on the allowlist. A later read of
    /// a Coach that exists, with only tools to connect, marks no tool in a draft and signs no Card (`draftSigns`).
    func testTheCardStepReadsAsTafsCloudSendsItAndItsListIsWhatItSigns() throws {
        for file in ["setup-start.json", "setup-start-draft-alone.json"] {
            let start = try XCTUnwrap(TafsCloud.CoachStart.read(try answer(file)), "\(file): no start read")
            XCTAssertTrue(start.cardStep(face: true), "\(file): the Card step's words, for Face ID")
            XCTAssertTrue(start.cardStep(face: false), "\(file): the Card step's words, for Touch ID")
            XCTAssertFalse(start.words?.locked.isEmpty ?? true, "\(file): the locked line")
            XCTAssertTrue(start.cardStepSigns, "\(file): the plain list is what the draft signs")
        }
        let tools = try XCTUnwrap(TafsCloud.CoachTools.read(try answer("setup-coach.json")), "setup-coach.json: no tools read")
        XCTAssertTrue(tools.draftSigns, "tools to connect, no Card drafted: nothing to compare")
    }

    /// Coach's drafted door as tafs cloud sends it (`doors-api-draft.json`): drawn from `body`, whose canonical bytes are what
    /// the owner signs, with the draft's own id inside them.
    func testCoachsDraftedDoorReadsAsTafsCloudSendsIt() throws {
        let draft = try XCTUnwrap(TafsCloud.DoorDraft.read(try answer("doors-api-draft.json")), "doors-api-draft.json: no draft read")
        XCTAssertFalse(draft.draft.name.isEmpty)
        XCTAssertFalse(draft.draft.job.isEmpty)
        let body = try JCS.parse(draft.body)
        XCTAssertEqual(body["kind"]?.string, "api")
        XCTAssertEqual(body["draft_id"]?.string, draft.id, "the draft's id is signed with it")
    }

    /// Home's part of the recipes answer, for someone who manages the tafs, with no live taf.
    func testHomesRecipesReadAsTafsCloudSendsThem() throws {
        let read = try JSONDecoder().decode(TafsCloud.Recipes.self, from: try answer("recipes-home.json"))
        XCTAssertEqual(read.home?.shown, true)
        XCTAssertNotNil(read.homeWords, "Home's line")
        XCTAssertNotNil(read.labels, "the sheet's words")
    }

    /// One contract, decoded with the app's decoder as the type its read decodes; a failure names the file and why.
    private func decodes<T: Decodable>(_ type: T.Type, _ file: String, line: UInt = #line) -> T? {
        do {
            return try KIFFClient.decoder.decode(T.self, from: try answer(file))
        } catch {
            XCTFail("\(file) doesn't read as \(T.self): \(error)", line: line)
            return nil
        }
    }

    /// A taf's Activity as tafs cloud sends it: each entry's call comes without `held`, which only KIFF's own read
    /// sends, so it reads as not held. It was required, and the page never loaded (found while porting).
    func testATafsActivityReadsAsTafsCloudSendsIt() throws {
        let page = try XCTUnwrap(decodes(TafsCloud.ActivityPage.self, "taf-activity.json"))
        XCTAssertFalse(page.entries.isEmpty)
        XCTAssertTrue(page.entries.allSatisfy { !$0.call.held })
        let raw = try JSONSerialization.jsonObject(with: try answer("taf-activity.json")) as? [String: Any]
        let calls = (raw?["entries"] as? [[String: Any]] ?? []).compactMap { $0["call"] as? [String: Any] }
        XCTAssertTrue(calls.allSatisfy { $0["held"] == nil }, "the case this reads: no held at all")
    }

    /// Every other answer the iPad reads that tafs cloud keeps as a contract, as the app's own read decodes it.
    func testEveryContractTheIPadReadsDecodes() {
        for file in ["taf-setup.json", "taf-setup-connected.json", "taf-setup-reconnect.json"] {
            _ = decodes(TafsCloud.SetupView.self, file)
        }
        struct Runs: Decodable { let runs: [TafsCloud.Run] }
        XCTAssertFalse(decodes(Runs.self, "taf-runs.json")?.runs.isEmpty ?? true)
        struct Reported: Decodable { let report: TafsCloud.Report }
        for file in ["taf-run-report.json", "taf-run-report-running.json", "taf-run-report-terminated.json"] {
            _ = decodes(Reported.self, file)
        }
        struct Listed: Decodable { let tafs: [TafsCloud.CloudTaf]; let removing: [TafsCloud.PendingRemoval]? }
        for file in ["tafs.json", "tafs-paused.json"] {
            XCTAssertFalse(decodes(Listed.self, file)?.tafs.isEmpty ?? true, file)
        }
        for file in ["home.json", "home-paused.json"] {
            _ = decodes(TafsCloud.Home.self, file)
        }
        struct Setup: Decodable { let setup: TafsCloud.CloudTaf? }
        XCTAssertNotNil(decodes(Setup.self, "setup-coach.json")?.setup, "Coach as a taf")
        struct Chats: Decodable { let chats: [TafsCloud.Chat] }
        XCTAssertFalse(decodes(Chats.self, "coach-chats.json")?.chats.isEmpty ?? true)
        do {
            let thread = try TafsCloud.setupThread(decoding: try answer("coach-conversation.json"))
            XCTAssertFalse(thread.messages.isEmpty)
            XCTAssertFalse(thread.components.isEmpty)
        } catch {
            XCTFail("coach-conversation.json doesn't read as Coach's thread: \(error)")
        }
        // A card whose sign-in planque couldn't narrow carries tafs cloud's wider line: the card is where it's said.
        do {
            let thread = try TafsCloud.setupThread(decoding: try answer("coach-wider.json"))
            let wider = thread.components.compactMap(\.component.widerWords)
            XCTAssertFalse(wider.isEmpty, "coach-wider.json: no card reads its wider line")
            XCTAssertFalse(wider.contains { $0.isEmpty })
        } catch {
            XCTFail("coach-wider.json doesn't read as Coach's thread: \(error)")
        }
        for file in ["connect-apps.json", "connect-apps-search.json", "connect-apps-next.json", "connect-apps-removed.json"] {
            XCTAssertFalse(decodes(TafsCloud.ConnectApps.self, file)?.apps.isEmpty ?? true, file)
        }
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
