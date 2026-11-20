import XCTest

/// Right to left (Arabic, Hebrew), the app mirrors: a row's chevron points the way the reading goes, and Back points
/// back. `chevron.forward` and `chevron.backward` mirror by themselves; `chevron.right` and `chevron.left` never do, so
/// in Arabic a row would point the wrong way and the calendar's previous and next would swap (screenshots: `rtl`).
final class RightToLeftTests: XCTestCase {
    func testNoFixedLeftOrRightArrows() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        var found: [String] = []
        for dir in ["Tafs", "TafsWatch", "Notify", "Shared", "TafsWidgets", "WatchShared"] {
            guard let files = FileManager.default.enumerator(at: root.appendingPathComponent(dir), includingPropertiesForKeys: nil) else { continue }
            for case let file as URL in files where file.pathExtension == "swift" {
                let text = try String(contentsOf: file, encoding: .utf8)
                for symbol in ["\"chevron.right\"", "\"chevron.left\"", "\"arrow.right\"", "\"arrow.left\""] where text.contains(symbol) {
                    found.append("\(dir)/\(file.lastPathComponent): \(symbol)")
                }
            }
        }
        XCTAssertEqual(found, [], "use chevron.forward / chevron.backward (arrow.forward / arrow.backward), which mirror right to left")
    }
}
