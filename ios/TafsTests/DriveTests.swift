import CryptoKit
import SwiftUI
import UIKit
import XCTest
@testable import Tafs

/// Coach drives the app (docs/drive.md): the iPhone's side of the contract, against a screen built
/// from the app's own controls, in a window of its own.
@MainActor
final class DriveTests: XCTestCase {
    final class Probe: ObservableObject {
        @Published var taps = 0
        @Published var confirmed = 0
        @Published var sheet = false
        @Published var sheetTaps = 0
        @Published var text = ""
        @Published var secret = ""
        @Published var path: [Int] = []
    }

    struct Screen: View {
        @ObservedObject var probe: Probe

        var body: some View {
            NavigationStack(path: $probe.path) {
                ScrollView {
                    VStack(spacing: 16) {
                        Button("Run now") { probe.taps += 1 }.buttonStyle(PrimaryButtonStyle()).driveID("t.run").driveWords("Run now")
                        Button("Approve") { probe.confirmed += 1 }.buttonStyle(PrimaryButtonStyle()).confirming().driveID("t.approve")
                        Button("Open") { probe.sheet = true }.buttonStyle(OutlineButtonStyle()).driveID("t.open").driveWords("Open")
                        NavigationLink(value: 1) { Text("Details") }.buttonStyle(.drivablePlain).driveID("t.push").driveWords("Details")
                        DriveField("Limit", text: $probe.text).textFieldStyle(.roundedBorder)
                        DriveField("The key", text: $probe.secret, kind: .secure).textFieldStyle(.roundedBorder)
                        ForEach(0..<40, id: \.self) { i in Text("Row \(i)").frame(height: 44) }
                    }
                    .padding()
                }
                .navigationDestination(for: Int.self) { _ in Text("Pushed") }
                .sheet(isPresented: $probe.sheet) {
                    VStack {
                        Button("Sheet button") { probe.sheetTaps += 1 }.buttonStyle(PrimaryButtonStyle())
                            .driveID("t.sheet.button").driveWords("Sheet button")
                    }
                    .padding()
                }
            }
        }
    }

    private var window: UIWindow?
    private var drive: DriveSession { DriveSession.shared }
    private var targets: DriveTargets { DriveTargets.shared }

    override func tearDown() {
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    private func settle(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    private func show(_ probe: Probe) async throws -> UIWindow {
        targets.reset()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let w = UIWindow(windowScene: scene)
        w.windowLevel = .normal + 1
        w.frame = scene.coordinateSpace.bounds
        w.rootViewController = UIHostingController(rootView: Screen(probe: probe))
        w.makeKeyAndVisible()
        window = w
        await settle(1.2)
        return w
    }

    private func act(_ action: String, element: String? = nil, at: CGPoint? = nil, to: CGPoint? = nil, direction: String? = nil, text: String? = nil) async -> TafsCloud.DriveResult {
        await drive.run(TafsCloud.DriveRequest(id: "r", tool: "app_act", action: action, line: "Coach's step", at: at, element: element, to: to, direction: direction, text: text))
    }

    // MARK: The start's signature

    /// docs/drive.md's test vector: the message the approval key signs for a drive's start.
    func testTheStartsSignedMessageIsTheContractsVector() {
        let body = TafsCloud.driveStartBody(session: "s_1", task: "Run Chargebacks", component: "d1")
        XCTAssertEqual(String(decoding: body, as: UTF8.self), #"{"session":"s_1","task":"Run Chargebacks","component":"d1"}"#)
        XCTAssertEqual(SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined(),
                       "fea4f2ebc2091cd2cbf48e1cc3b6285603bcc194cddea23f5737dfdc2a78414f")
        let message = KIFFClient.requestMessage(tenant: "t_acme", method: "POST", uri: TafsCloud.drivePath, body: body, signedAt: "2026-11-06T00:30:00Z")
        XCTAssertEqual(String(decoding: message, as: UTF8.self), """
        kiff.request.v1
        t_acme
        POST
        /v1/coach/drive
        fea4f2ebc2091cd2cbf48e1cc3b6285603bcc194cddea23f5737dfdc2a78414f
        2026-11-06T00:30:00Z
        """)
    }

    // MARK: The stream

    func testTheStreamsEventsAreRead() throws {
        let raw = #"{"id":"q1","tool":"app_act","args":{"action":"swipe","line":"Swiping","at":{"x":10,"y":20},"to":{"x":12,"y":400},"ms":300},"at":"2026-11-06T00:30:00Z","start_within_ms":17000}"#
        guard case .request(let r)? = TafsCloud.driveEvent("request", data: Data(raw.utf8)) else { return XCTFail("no request") }
        XCTAssertEqual(r.id, "q1")
        XCTAssertEqual(r.tool, "app_act")
        XCTAssertEqual(r.action, "swipe")
        XCTAssertEqual(r.at, CGPoint(x: 10, y: 20))
        XCTAssertEqual(r.to, CGPoint(x: 12, y: 400))
        XCTAssertEqual(r.ms, 300)
        XCTAssertEqual(r.startWithinMs, 17000)
        let look = #"{"id":"q2","tool":"app_look","args":{"tree":true,"wait":"sheet_opened","timeout_s":5},"at":"x"}"#
        guard case .request(let l)? = TafsCloud.driveEvent("request", data: Data(look.utf8)) else { return XCTFail("no look") }
        XCTAssertTrue(l.tree)
        XCTAssertEqual(l.wait, "sheet_opened")
        XCTAssertEqual(l.timeoutS, 5)
        XCTAssertEqual(TafsCloud.driveEvent("ended", data: Data(#"{"why":"stopped","words":"the owner stopped it"}"#.utf8)), .ended(why: "stopped", words: "the owner stopped it"))
        XCTAssertEqual(TafsCloud.driveEvent("reopen", data: Data("{}".utf8)), .reopen)
        XCTAssertEqual(TafsCloud.driveEvent("drive", data: Data(#"{"drive":"dr1"}"#.utf8)), .drive("dr1"))
        XCTAssertNil(TafsCloud.driveEvent("other", data: Data("{}".utf8)))
        XCTAssertEqual(DriveSession.sentence("the owner stopped it"), "The owner stopped it")
    }

    func testARequestPastItsStartIsTooLateAndNotDone() async throws {
        let probe = Probe()
        _ = try await show(probe)
        let late = TafsCloud.DriveRequest(id: "r", tool: "app_act", startWithinMs: 500, action: "tap", line: "Tapping Run now",
                                          element: "t.run", came: Date().addingTimeInterval(-2))
        let result = await drive.run(late)
        XCTAssertEqual(result.outcome, "refused")
        XCTAssertEqual(result.line, "Too late")
        XCTAssertNotNil(result.image)
        XCTAssertEqual(probe.taps, 0)
    }

    // MARK: Touches

    func testATapRunsTheControlsOwnActionAndNamesWhatItTouched() async throws {
        let probe = Probe()
        _ = try await show(probe)
        let result = await act("tap", element: "t.run")
        XCTAssertEqual(result.outcome, "done", result.line)
        XCTAssertEqual(result.line, "Tapped Run now")
        XCTAssertEqual(result.touched?.words, "Run now")
        XCTAssertEqual(probe.taps, 1)
        let image = try XCTUnwrap(result.image)
        XCTAssertEqual(image.mediaType, "image/jpeg")
        XCTAssertLessThanOrEqual(Data(base64Encoded: image.data)?.count ?? .max, 200_000)
    }

    func testATouchOnAConfirmingControlIsRefusedBeforeAnythingRuns() async throws {
        let probe = Probe()
        _ = try await show(probe)
        let byElement = await act("tap", element: "t.approve")
        XCTAssertEqual(byElement.outcome, "refused")
        XCTAssertEqual(byElement.line, "That's yours to confirm")
        XCTAssertNotNil(byElement.image)
        let approve = try XCTUnwrap(targets.shown(id: "t.approve"))
        let geo = DriveCapture.geometry()
        let px = geo.toPixels(approve.frame)
        let byPoint = await act("long_press", at: CGPoint(x: px.midX, y: px.midY))
        XCTAssertEqual(byPoint.line, "That's yours to confirm")
        XCTAssertEqual(probe.confirmed, 0)
        let nothing = await act("tap", at: CGPoint(x: 2, y: geo.pixels.height - 2))
        XCTAssertEqual(nothing.outcome, "refused")
        XCTAssertEqual(nothing.line, "Nothing Coach can use there")
        let gone = await act("tap", element: "t.nowhere")
        XCTAssertEqual(gone.line, "That isn't on screen now")
        let unknown = await act("pinch")
        XCTAssertEqual(unknown.line, "This app can't do that step")
    }

    /// A point on the scaled screenshot, on a sheet: the sheet's control is hit, and the screen behind
    /// the sheet can't be reached.
    func testAPointOnTheScaledScreenshotReachesTheSheet() async throws {
        let probe = Probe()
        _ = try await show(probe)
        let open = await act("tap", element: "t.open")
        XCTAssertEqual(open.outcome, "done", open.line)
        await settle(1.0)
        XCTAssertTrue(probe.sheet)
        let geo = DriveCapture.geometry()
        XCTAssertEqual(max(geo.pixels.width, geo.pixels.height), 1100, accuracy: 1)
        XCTAssertLessThan(geo.scale, 3)
        let button = try XCTUnwrap(targets.shown(id: "t.sheet.button"), "the sheet's button isn't reachable")
        let px = geo.toPixels(button.frame)
        let back = geo.toScreen(CGPoint(x: px.midX, y: px.midY))
        XCTAssertEqual(back.x, button.frame.midX, accuracy: 0.01)
        XCTAssertEqual(back.y, button.frame.midY, accuracy: 0.01)
        XCTAssertNil(targets.shown(id: "t.run"), "the screen behind the sheet is reachable")
        let tap = await act("tap", at: CGPoint(x: px.midX, y: px.midY))
        XCTAssertEqual(tap.line, "Tapped Sheet button")
        XCTAssertEqual(probe.sheetTaps, 1)
        let closed = await act("back")
        XCTAssertEqual(closed.line, "Went back")
        await settle(1.0)
        XCTAssertFalse(probe.sheet)
    }

    func testANavigationLinkGoesAndBackComesBack() async throws {
        let probe = Probe()
        _ = try await show(probe)
        let push = await act("tap", element: "t.push")
        XCTAssertEqual(push.outcome, "done", push.line)
        await settle(1.0)
        XCTAssertEqual(probe.path, [1])
        let back = await act("back")
        XCTAssertEqual(back.line, "Went back")
        await settle(1.0)
        XCTAssertEqual(probe.path, [])
        let none = await act("back")
        XCTAssertEqual(none.line, "Nothing to go back to")
    }

    func testTypingGoesIntoTheFocusedField() async throws {
        let probe = Probe()
        _ = try await show(probe)
        let none = await act("type", text: "50")
        XCTAssertEqual(none.line, "No field is focused")
        let focus = await act("tap", element: "field.limit")
        XCTAssertEqual(focus.outcome, "done", focus.line)
        let typed = await act("type", text: "50")
        XCTAssertEqual(typed.line, "Typed into Limit")
        XCTAssertEqual(probe.text, "50")
    }

    func testAScrollShowsWhatsBelow() async throws {
        let probe = Probe()
        _ = try await show(probe)
        let down = await act("scroll", direction: "down")
        XCTAssertEqual(down.line, "Scrolled down")
        await settle(0.6)
        let up = await act("scroll", direction: "up")
        XCTAssertEqual(up.line, "Scrolled up")
        await settle(0.6)
        let again = await act("scroll", direction: "up")
        XCTAssertEqual(again.line, "Can't scroll up there")
    }

    // MARK: The look

    func testTheTreeNamesControlsMarksAndHidesSecrets() async throws {
        let probe = Probe()
        _ = try await show(probe)
        let look = await drive.run(TafsCloud.DriveRequest(id: "r", tool: "app_look", tree: true, diagnostics: true))
        XCTAssertEqual(look.line, "Looked")
        let tree = try XCTUnwrap(look.tree)
        let run = try XCTUnwrap(tree.first { $0.id == "t.run" })
        XCTAssertEqual(run.words, "Run now")
        XCTAssertEqual(run.role, "button")
        XCTAssertEqual(run.actions, ["tap"])
        XCTAssertNil(run.confirm)
        XCTAssertEqual(tree.first { $0.id == "t.approve" }?.confirm, true)
        let key = try XCTUnwrap(tree.first { $0.id == "field.the-key" })
        XCTAssertEqual(key.words, "Secret field")
        XCTAssertEqual(key.role, "text_field")
        XCTAssertTrue(key.actions.contains("type"))
        // Bounds in the screenshot's pixels.
        let geo = DriveCapture.geometry()
        let frame = try XCTUnwrap(targets.shown(id: "t.run")).frame
        XCTAssertEqual(Double(run.bounds.x), Double(frame.minX * geo.scale), accuracy: 1)
        XCTAssertEqual(Double(run.bounds.w), Double(frame.width * geo.scale), accuracy: 1)
        let diagnostics = try XCTUnwrap(look.diagnostics)
        XCTAssertTrue(diagnostics.app.platform.hasPrefix("ios "))
        let json = try XCTUnwrap(String(data: JSONEncoder().encode(look), encoding: .utf8))
        XCTAssertTrue(json.contains(#""size_class""#))
        XCTAssertLessThan((try JSONEncoder().encode(tree)).count, 60_000)
        let waited = await drive.run(TafsCloud.DriveRequest(id: "r", tool: "app_look", wait: "sheet_opened", timeoutS: 1))
        XCTAssertEqual(waited.line, "Looked: sheet_opened didn't come in time")
        XCTAssertEqual(waited.waited, TafsCloud.DriveResult.Waited(event: "sheet_opened", came: false))
    }

    func testFailedRequestsKeepOnlyTheirRoute() {
        XCTAssertEqual(DriveDiagnostics.route("/v1/tafs/7c1e2b9a-0000-4000-8000-000000000001/work?after=3"), "/v1/tafs/{id}/work")
        XCTAssertEqual(DriveDiagnostics.route("/v1/coach/drive/dr_12/results/q_3"), "/v1/coach/drive/{id}/results/{id}")
        XCTAssertEqual(DriveDiagnostics.route("/v1/home"), "/v1/home")
        DriveDiagnostics.shared.reset()
        for i in 0..<40 { DriveDiagnostics.shared.failed(method: "GET", path: "/v1/tafs/t\(i)/work", status: 500) }
        let read = DriveDiagnostics.shared.read()
        XCTAssertEqual(read.requests.count, 30)
        XCTAssertEqual(read.requests.last?.route, "GET /v1/tafs/{id}/work")
    }

    // MARK: The marks, checked in the sources

    private static let sources = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs")

    private func source(_ path: String) throws -> String {
        try String(contentsOf: Self.sources.appendingPathComponent(path), encoding: .utf8)
    }

    /// Every control docs/drive.md lists as confirming carries the confirm mark: approve, disconnect,
    /// remove, halt and resume, the answers to "Did this happen?", extra room, buying, dismissing a card
    /// that signs. Approvals and those answers go through ApprovalGate, which marks them.
    static let confirming: [(String, String)] = [
        ("Views/TafSetupViews.swift", #"Button("Approve") { approving = item }"#),
        ("Views/TafSetupViews.swift", #"Button("Remove") {"#),
        ("Views/TafSetupViews.swift", #"Button(cloud.paused ? "Resume every start""#),
        ("Views/TafDetailView.swift", #"Button(taf.state == .paused ? "Resume" : "Pause")"#),
        ("Views/TafDetailView.swift", #"Button("Remove for good")"#),
        ("Views/TafDetailView.swift", #"Button("Disconnect") { confirming = grant }"#),
        ("Views/UsageView.swift", #"Button("Approve") {"#),
        ("Views/TasksViews.swift", #"Button(purchases.products[pack.appleProduct]?.displayPrice"#),
        ("Views/TasksViews.swift", #"Button(SoloView.action(product))"#),
        ("Views/HaltScreen.swift", #"Button(busy ? "Halting…""#),
        ("Views/HaltScreen.swift", #"Button("Halt again")"#),
        ("Views/HaltScreen.swift", #"Button(busy ? "Resuming…""#),
        ("Views/ConnectAppsView.swift", #"Button(busy == g.id ? "Disconnecting…""#),
        ("Views/TafPresentationViews.swift", #"Button(ask.dismiss_title"#),
        ("Views/TafPresentationViews.swift", #"Button("Disconnect", role: .destructive) { disconnect = true }"#),
        ("Views/DoorsView.swift", #"Button("Remove door""#),
        ("Views/DeleteAccountView.swift", #"Button(role: .destructive) {"#),
        ("Views/DevicesView.swift", #"Button("Sign out") { removing = device }"#),
    ]

    func testEveryListedConfirmingControlCarriesTheMark() throws {
        for (file, start) in Self.confirming {
            let s = try source(file)
            let at = try XCTUnwrap(s.range(of: start), "\(file): \(start) isn't there any more: list where it went")
            let rest = s[at.upperBound...]
            let end = rest.range(of: "Button(")?.lowerBound ?? rest.endIndex
            XCTAssertTrue(rest[..<end].contains(".confirming()"), "\(file): \(start) has no confirm mark")
        }
        let gate = try source("Views/ApprovalSetupView.swift")
        XCTAssertEqual(gate.components(separatedBy: "button().confirming()").count - 1, 2, "ApprovalGate doesn't mark its approvals")
        let gated = try ["Views/NeedsYouView.swift", "Views/DriveViews.swift", "Views/SetupChatView.swift", "Views/AppEventApprovalSheet.swift", "Views/ConnectAppsView.swift"]
            .map { try source($0) }.joined()
        for words in [#"Button("Yes, it did")"#, #"Button("No")"#, "Approve once with", "Extra room with", #""Approve with \(Biometry.name)""#] {
            let at = try XCTUnwrap(gated.range(of: words), words)
            let before = gated[..<at.lowerBound].suffix(400)
            XCTAssertTrue(before.contains("ApprovalGate {"), "\(words) isn't in an ApprovalGate")
        }
        XCTAssertTrue(try source("Views/TafKnobRemote.swift").contains(#".environment(\.driveConfirm, power)"#))
    }

    /// Every field goes through the wrapper, and a field for a key, a password or a recovery code is secret.
    func testEveryFieldIsTheWrappersAndSecretsAreMarked() throws {
        let files = try XCTUnwrap(FileManager.default.enumerator(at: Self.sources, includingPropertiesForKeys: nil))
            .compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }
        XCTAssertGreaterThan(files.count, 50)
        for url in files where url.lastPathComponent != "DriveFields.swift" {
            let s = try String(contentsOf: url, encoding: .utf8)
            for raw in ["TextField(", "SecureField(", "TextEditor("] {
                XCTAssertFalse(s.contains(raw), "\(url.lastPathComponent) has a raw \(raw): use DriveField")
            }
            for line in s.components(separatedBy: "\n") where line.contains("DriveField(\"") {
                let lower = line.lowercased()
                if ["key", "password", "recovery"].contains(where: lower.contains) && !lower.contains("search") {
                    XCTAssertTrue(line.contains("kind: .secure") || line.contains("secret: true"), "\(url.lastPathComponent): \(line.trimmingCharacters(in: .whitespaces)) isn't marked secret")
                }
            }
        }
    }
}
