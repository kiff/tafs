import XCTest
@testable import Tafs

/// The approved illustrations (docs/ios-app.md › Illustrations): drawn in code, shapes only (rule 14: no
/// letter, number or word in a drawing), hidden from VoiceOver, and each on the screen its board names.
final class IllustrationsTests: XCTestCase {
    private static let tafs = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Tafs")

    private func source(_ path: String) throws -> String {
        try String(contentsOf: Self.tafs.appendingPathComponent(path), encoding: .utf8)
    }

    /// Each board, the screen's file it's on, and how that file draws it.
    private static let boards: [(drawing: String, file: String)] = [
        ("Illustration.firstRun", "Views/OnboardingView.swift"),
        ("Illustration.noTafs", "Views/HomeView.swift"),
        ("Illustration.noTafs", "Views/HomeSplitView.swift"),
        ("Illustration.briefClear", "Views/BriefView.swift"),
        ("Illustration.allHalted", "Views/HaltScreen.swift"),
        ("Illustration.appConnected", "Views/ConnectAppsView.swift"),
        ("Illustration.nothingWaiting", "Views/NeedsYouView.swift"),
        ("Illustration.coachFirst", "Views/SetupChatView.swift"),
        ("Illustration.newTaf", "Views/RecipeGallery.swift"),
        ("Illustration.coachStarting", "Views/SetupChatView.swift"),
        ("Illustration.overview", "Views/ShareViews.swift"),
    ]

    /// No words in a drawing: no Text, no string literal at all (not even a symbol's name), no image file.
    func testTheDrawingsHaveNoWordsAndNoImages() throws {
        let file = try source("Design/Illustrations.swift")
        XCTAssertFalse(file.contains("Text("), "a drawing has no text")
        XCTAssertFalse(file.contains("\""), "a drawing has no string literal")
        XCTAssertFalse(file.contains("Image("), "a drawing is shapes, never an image or a symbol")
        XCTAssertFalse(file.contains("Color(hex:"), "a drawing's colours are the theme's, for light and dark")
        XCTAssertTrue(file.contains("Theme.brand"), "the mark is the brand's sweep, shared with the sign-in")
        XCTAssertTrue(try source("Views/SignInView.swift").contains("private static let mark = Theme.brand"))
    }

    /// The ten, each one board, each drawn through `Board`, which scales it and hides it from VoiceOver.
    func testEachBoardIsDrawnScaledAndHidden() throws {
        let file = try source("Design/Illustrations.swift")
        let names = Set(Self.boards.map { $0.drawing })
        XCTAssertEqual(names.count, 10)
        for name in names {
            let property = name.replacingOccurrences(of: "Illustration.", with: "")
            XCTAssertTrue(file.contains("static var \(property): some View {"), name)
        }
        XCTAssertEqual(file.components(separatedBy: "Board(width:").count - 1, 10, "every board is scaled by Board")
        for board in 1...10 { XCTAssertTrue(file.contains("/// Board \(board), "), "board \(board)'s doc comment") }
        XCTAssertTrue(file.contains(".accessibilityHidden(true)"))
        XCTAssertTrue(file.contains(".frame(maxWidth: shown)"), "scaled to a width, never a fixed screen size")
    }

    /// Each screen draws its board; the iPad's two only at a regular width.
    func testEachScreenDrawsItsBoard() throws {
        for (drawing, file) in Self.boards {
            XCTAssertTrue(try source(file).contains(drawing), "\(file) draws \(drawing)")
        }
        XCTAssertTrue(try source("Views/ShareViews.swift").contains("if sizeClass == .regular { Illustration.overview"))
        let coach = try source("Views/SetupChatView.swift")
        XCTAssertEqual(coach.components(separatedBy: "if sizeClass == .regular { Illustration.coachStarting").count - 1, 3,
                       "each of Coach's starts (before tafs cloud's words, with them, and the Card step), at a regular width only")
        XCTAssertTrue(coach.contains("if model.tafs.count <= 1 { Illustration.coachFirst }"))
        XCTAssertTrue(try source("Views/HaltScreen.swift").contains("if tafID == nil, h.state == .halted {"))
    }
}
