import SwiftUI
import UIKit
import XCTest
@testable import Tafs

/// The one-day spike for "Coach drives the app" (kiff/tafs#544): can the app see its own windows
/// and act on its own controls through public APIs only, with VoiceOver off? Each check prints a
/// `DRIVE-SPIKE` line for the CI log, and asserts what the design relies on.
@MainActor
final class DriveSpikeTests: XCTestCase {
    /// What the spike's screen did, read by the checks.
    final class Probe: ObservableObject {
        @Published var runs = 0
        @Published var paused = false
        @Published var archived = false
        @Published var sheet = false
        @Published var alert = false
        @Published var limit = ""
        @Published var halted = false
    }

    /// A screen built from the patterns the app uses: a list of buttons, a row with swipe actions
    /// and a context menu, a long scroll, a sheet with a text field, and an alert.
    struct SpikeScreen: View {
        @ObservedObject var probe: Probe

        var body: some View {
            NavigationStack {
                List {
                    Section {
                        Button("Run now") { probe.runs += 1 }
                            .accessibilityIdentifier("spike.run")
                        Text("Refunds")
                            .swipeActions { Button("Pause") { probe.paused = true } }
                            .contextMenu { Button("Archive") { probe.archived = true } }
                        Button("Open sheet") { probe.sheet = true }
                        Button("Halt") { probe.alert = true }
                    }
                    ForEach(0..<60, id: \.self) { i in Text("Row \(i)") }
                }
                .navigationTitle("Spike")
                .sheet(isPresented: $probe.sheet) {
                    VStack(spacing: 20) {
                        TextField("Limit", text: $probe.limit).textFieldStyle(.roundedBorder)
                        Button("Close") { probe.sheet = false }
                    }
                    .padding()
                }
                .alert("Halt Refunds?", isPresented: $probe.alert) {
                    Button("Halt", role: .destructive) { probe.halted = true }
                    Button("Cancel", role: .cancel) {}
                }
            }
        }
    }

    // MARK: - The drive's tools, as the app would build them

    /// One accessibility element, as `app_look {tree}` would list it.
    struct Element {
        let object: NSObject
        let label: String
        let identifier: String
        let traits: UIAccessibilityTraits
        let frame: CGRect
        let actions: [String]

        var line: String {
            var t: [String] = []
            if traits.contains(.button) { t.append("button") }
            if traits.contains(.header) { t.append("header") }
            if traits.contains(.staticText) { t.append("text") }
            if traits.contains(.adjustable) { t.append("adjustable") }
            let id = identifier.isEmpty ? "" : " #\(identifier)"
            let acts = actions.isEmpty ? "" : " actions=\(actions)"
            return "\"\(label)\"\(id) [\(t.joined(separator: ","))] \(Int(frame.minX)),\(Int(frame.minY)) \(Int(frame.width))x\(Int(frame.height))\(acts)"
        }
    }

    /// Every accessibility element under `root`, in reading order: what SwiftUI builds when asked.
    static func elements(in root: NSObject) -> [Element] {
        var out: [Element] = []
        var seen = Set<ObjectIdentifier>()
        func visit(_ o: NSObject, _ depth: Int) {
            guard depth < 80, seen.insert(ObjectIdentifier(o)).inserted else { return }
            if let v = o as? UIView, v.isHidden || v.alpha == 0 { return }
            if o.isAccessibilityElement {
                out.append(Element(object: o, label: o.accessibilityLabel ?? "",
                                   identifier: (o as? UIAccessibilityIdentification)?.accessibilityIdentifier ?? "",
                                   traits: o.accessibilityTraits, frame: o.accessibilityFrame,
                                   actions: (o.accessibilityCustomActions ?? []).map(\.name)))
            }
            if let children = o.accessibilityElements as? [NSObject], !children.isEmpty {
                for c in children { visit(c, depth + 1) }
            } else {
                let n = o.accessibilityElementCount()
                if n != NSNotFound, n > 0 {
                    for i in 0..<n { if let c = o.accessibilityElement(at: i) as? NSObject { visit(c, depth + 1) } }
                }
            }
            if let v = o as? UIView { for s in v.subviews { visit(s, depth + 1) } }
        }
        visit(root, 0)
        return out
    }

    /// The smallest element that contains a point on the screen: what `app_tap {x, y}` acts on.
    static func hitTest(_ point: CGPoint, in elements: [Element]) -> Element? {
        elements.filter { $0.frame.contains(point) && $0.frame.width > 0 }
            .min { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }
    }

    /// Runs a custom action (a swipe action, a context menu item) by its name.
    @discardableResult
    static func perform(_ name: String, on e: Element) -> Bool {
        guard let a = e.object.accessibilityCustomActions?.first(where: { $0.name == name }) else { return false }
        if let handler = a.actionHandler { return handler(a) }
        if let target = a.target as? NSObject {
            _ = target.perform(a.selector, with: a)
            return true
        }
        return false
    }

    /// The window drawn as the owner sees it: `app_look`'s screenshot.
    static func draw(_ window: UIWindow) -> UIImage {
        UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            _ = window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
    }

    /// How many distinct colours a sample of the image has: 1 is blank.
    static func colours(_ image: UIImage) -> Int {
        guard let cg = image.cgImage, let data = cg.dataProvider?.data, let p = CFDataGetBytePtr(data) else { return 0 }
        var seen = Set<UInt32>()
        let bpr = cg.bytesPerRow, bpp = cg.bitsPerPixel / 8
        for y in stride(from: 0, to: cg.height, by: max(1, cg.height / 60)) {
            for x in stride(from: 0, to: cg.width, by: max(1, cg.width / 40)) {
                let i = y * bpr + x * bpp
                seen.insert(UInt32(p[i]) << 16 | UInt32(p[i + 1]) << 8 | UInt32(p[i + 2]))
            }
        }
        return seen.count
    }

    // MARK: - Hosting

    private var window: UIWindow!

    private func show<V: View>(_ view: V) async throws -> UIWindow {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let w = UIWindow(windowScene: scene)
        w.frame = scene.coordinateSpace.bounds
        w.rootViewController = UIHostingController(rootView: view)
        w.makeKeyAndVisible()
        window = w
        await settle()
        return w
    }

    private func settle(_ seconds: Double = 0.6) async {
        window?.layoutIfNeeded()
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        window?.layoutIfNeeded()
    }

    override func tearDown() {
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    private func log(_ s: String) { print("DRIVE-SPIKE \(name): \(s)") }

    private func element(_ label: String, in w: UIWindow) throws -> Element {
        let all = Self.elements(in: w)
        return try XCTUnwrap(all.first { $0.label == label }, "no element \"\(label)\" among \(all.map(\.label))")
    }

    // MARK: - The checks

    /// Seeing: the app's window drawn with drawHierarchy, no recording, VoiceOver off.
    func testSeeing() async throws {
        let w = try await show(SpikeScreen(probe: Probe()))
        let image = Self.draw(w)
        log("VoiceOver running: \(UIAccessibility.isVoiceOverRunning); image \(Int(image.size.width))x\(Int(image.size.height)) @\(image.scale)x, \(Self.colours(image)) colours")
        XCTAssertFalse(UIAccessibility.isVoiceOverRunning)
        XCTAssertGreaterThan(Self.colours(image), 2, "the drawn window is blank")
    }

    /// The tree: SwiftUI builds its elements when asked, with VoiceOver off, each with its words,
    /// role and frame.
    func testTheTree() async throws {
        let w = try await show(SpikeScreen(probe: Probe()))
        let all = Self.elements(in: w)
        log("\(all.count) elements; first: " + all.prefix(12).map(\.line).joined(separator: " | "))
        for label in ["Run now", "Refunds", "Open sheet", "Halt"] {
            let e = try element(label, in: w)
            XCTAssertGreaterThan(e.frame.width, 0, label)
        }
        XCTAssertTrue(try element("Run now", in: w).traits.contains(.button))
        XCTAssertEqual(try element("Run now", in: w).identifier, "spike.run")
    }

    /// A tap: a point hit-tested to its element, then accessibilityActivate() runs the button's action.
    func testTapActivatesTheControl() async throws {
        let probe = Probe()
        let w = try await show(SpikeScreen(probe: probe))
        let run = try element("Run now", in: w)
        let hit = try XCTUnwrap(Self.hitTest(CGPoint(x: run.frame.midX, y: run.frame.midY), in: Self.elements(in: w)))
        XCTAssertEqual(hit.label, "Run now")
        let activated = hit.object.accessibilityActivate()
        await settle(0.2)
        log("activate returned \(activated); runs \(probe.runs)")
        XCTAssertEqual(probe.runs, 1, "accessibilityActivate() didn't run the button's action")
    }

    /// A swipe on a row, and a long press: the row's swipe actions and context menu as custom actions.
    func testSwipeAndLongPressAreCustomActions() async throws {
        let probe = Probe()
        let w = try await show(SpikeScreen(probe: probe))
        let row = try element("Refunds", in: w)
        log("Refunds' custom actions: \(row.actions)")
        XCTAssertTrue(Self.perform("Pause", on: row), "no Pause custom action: \(row.actions)")
        await settle(0.3)
        XCTAssertTrue(probe.paused, "the swipe action didn't run")
        let again = try element("Refunds", in: w)
        let menu = Self.perform("Archive", on: again)
        await settle(0.3)
        log("context menu as a custom action: \(menu), archived \(probe.archived)")
    }

    /// A scroll: accessibilityScroll on the list, else its scroll view's offset; the list's far rows
    /// come into view.
    func testScroll() async throws {
        let w = try await show(SpikeScreen(probe: Probe()))
        func visible(_ label: String) -> Bool {
            Self.elements(in: w).contains { $0.label == label && w.bounds.intersects($0.frame) }
        }
        XCTAssertFalse(visible("Row 59"))
        let scrolls = Self.views(UIScrollView.self, in: w)
        let byAccessibility = scrolls.contains { $0.accessibilityScroll(.down) }
        await settle(0.5)
        log("scroll views \(scrolls.count); accessibilityScroll(.down) handled: \(byAccessibility)")
        let list = try XCTUnwrap(scrolls.max { $0.contentSize.height < $1.contentSize.height })
        list.setContentOffset(CGPoint(x: 0, y: list.contentSize.height - list.bounds.height + list.adjustedContentInset.bottom), animated: false)
        await settle(0.5)
        log("after setting the offset, Row 59 visible: \(visible("Row 59"))")
        XCTAssertTrue(visible("Row 59"))
    }

    /// A sheet: opened by a tap, drawn with the window, its field typed into, closed by escape.
    func testSheetTypeAndEscape() async throws {
        let probe = Probe()
        let w = try await show(SpikeScreen(probe: probe))
        let before = Self.draw(w).pngData()
        XCTAssertTrue(try element("Open sheet", in: w).object.accessibilityActivate() || probe.sheet)
        await settle(1.0)
        XCTAssertTrue(probe.sheet)
        let after = Self.draw(w).pngData()
        log("drawn with the sheet differs: \(before != after); elements: " + Self.elements(in: w).filter { ["Limit", "Close"].contains($0.label) }.map(\.line).joined(separator: " | "))
        XCTAssertNotEqual(before, after, "the sheet isn't in the drawn window")
        let field = try XCTUnwrap(Self.views(UITextField.self, in: w).first, "no text field behind the sheet's TextField")
        XCTAssertTrue(field.becomeFirstResponder())
        field.insertText("50")
        await settle(0.3)
        log("typed: \(probe.limit)")
        XCTAssertEqual(probe.limit, "50")
        field.resignFirstResponder()
        let sheetRoot = try XCTUnwrap(w.rootViewController?.presentedViewController?.view)
        let escaped = sheetRoot.accessibilityPerformEscape()
            || Self.elements(in: sheetRoot).contains { $0.object.accessibilityPerformEscape() }
        await settle(1.0)
        log("escape handled: \(escaped); sheet open: \(probe.sheet)")
        XCTAssertFalse(probe.sheet, "accessibilityPerformEscape() didn't close the sheet")
    }

    /// An alert: in the window, its buttons are elements, and Cancel activates.
    func testAlert() async throws {
        let probe = Probe()
        let w = try await show(SpikeScreen(probe: probe))
        _ = try element("Halt", in: w).object.accessibilityActivate()
        await settle(1.0)
        XCTAssertTrue(probe.alert)
        let labels = Self.elements(in: w).map(\.label)
        log("alert elements: \(labels.filter { ["Halt Refunds?", "Halt", "Cancel"].contains($0) })")
        let cancel = try XCTUnwrap(Self.elements(in: w).last { $0.label == "Cancel" })
        _ = cancel.object.accessibilityActivate()
        await settle(1.0)
        XCTAssertFalse(probe.alert, "Cancel didn't close the alert")
        XCTAssertFalse(probe.halted)
    }

    /// The real app, in sample mode: Home's tree, and a tap on a taf's row opens it.
    func testTheRealApp() async throws {
        let model = AppModel(signer: StubSigner(), store: SampleStore())
        await model.startSample()
        let w = try await show(RootView().environmentObject(model).tint(Theme.amber))
        await settle(1.5)
        let home = Self.elements(in: w)
        log("Home: \(home.count) elements, \(home.filter { $0.traits.contains(.button) }.count) buttons, \(Self.colours(Self.draw(w))) colours; " + home.prefix(25).map(\.line).joined(separator: " | "))
        XCTAssertGreaterThan(home.count, 5)
        guard let row = home.first(where: { $0.identifier == "taf-row" }) ?? home.first(where: { $0.traits.contains(.button) && !$0.label.isEmpty }) else {
            return XCTFail("no taf row or button on Home")
        }
        let activated = row.object.accessibilityActivate()
        await settle(1.5)
        let next = Self.elements(in: w)
        let changed = Set(next.map(\.label)) != Set(home.map(\.label))
        log("activated \"\(row.label)\" #\(row.identifier): \(activated); the screen changed: \(changed); now: " + next.prefix(25).map(\.line).joined(separator: " | "))
        XCTAssertTrue(changed, "activating \"\(row.label)\" changed nothing")
    }

    /// Every view of a kind under a view.
    static func views<T: UIView>(_ kind: T.Type, in root: UIView) -> [T] {
        var out: [T] = []
        func visit(_ v: UIView) {
            if let t = v as? T { out.append(t) }
            v.subviews.forEach(visit)
        }
        visit(root)
        return out
    }
}
