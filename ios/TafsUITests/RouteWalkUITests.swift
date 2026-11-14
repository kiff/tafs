import XCTest

/// The route walk: every route of the app map (app/routes.json), opened in the sample on an iPhone,
/// written as the map (app/map/ios.json). The app is launched once, with its debug-only hook
/// (`-routeWalk YES`, ios/Tafs/App/RouteWalk.swift); each route's path is typed into the hook's
/// field, which puts the screens back where they start and asks `AppModel.open(route:)`.
///
/// For each route the map says whether it opened; whether its English title (app/strings/en) is
/// then on screen; and, for one that didn't open, why: what it needs (`manages` or a capability)
/// when the person hasn't it, else `signed-in`, a screen only someone signed in to KIFF opens.
/// A route that opened and doesn't show its title fails the walk, and so does a map that differs
/// from app/map/ios.json: the walk prints its map between two markers, and attaches it.
final class RouteWalkUITests: XCTestCase {
    static let begin = "----- BEGIN app/map/ios.json -----"
    static let end = "----- END app/map/ios.json -----"

    struct Entry: Codable, Equatable {
        var route: String
        var opened: Bool
        var title_shown: Bool
        var refused_because: String?
    }

    struct Map: Codable, Equatable {
        var generated_by: String
        var mode: String
        var routes: [Entry]
    }

    /// The repository, from this file's place in it.
    static var repo: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    }

    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testEveryRouteInTheSample() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .phone, "the map is the iPhone's")
        let routes = try Self.routes()
        let titles = try Self.english()

        let app = XCUIApplication()
        app.launchArguments = ["-screenshots", "YES", "-tab", "tafs", "-routeWalk", "YES"]
        app.launch()

        // The sample's first taf, which `{taf}` names.
        let state = app.staticTexts["route-walk.state"]
        XCTAssertTrue(state.waitForExistence(timeout: 30), "the route walk's hook (a debug build, -routeWalk YES)")
        XCTAssertTrue(within(30) { state.label.hasPrefix("sample ") && !state.label.hasSuffix(" -") }, "the sample's tafs: \(state.label)")
        let taf = String(state.label.dropFirst("sample ".count))

        let field = app.textFields["route-walk.path"]
        let answer = app.staticTexts["route-walk.answer"]
        continueAfterFailure = true
        var entries: [Entry] = []
        for route in routes {
            let path = route.route.replacingOccurrences(of: "{taf}", with: taf)
            // The field takes the keyboard a moment after the tap on a slow simulator. Tapped where
            // it is, as the hook's window is its own, above the app's.
            XCTAssertTrue(within(10) {
                if (field.value(forKey: "hasKeyboardFocus") as? Bool) == true { return true }
                field.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                RunLoop.current.run(until: Date().addingTimeInterval(0.3))
                return (field.value(forKey: "hasKeyboardFocus") as? Bool) == true
            }, "\(route.route): the hook's field has the keyboard")
            field.typeText(path + "\n")
            guard within(10, { answer.label.hasPrefix(path + "|") }) else {
                XCTFail("\(route.route): no answer for \(path) (\(answer.label))")
                continue
            }
            let said = String(answer.label.dropFirst(path.count + 1))
            var entry = Entry(route: route.route, opened: said == "opened", title_shown: false)
            if entry.opened {
                let title = try XCTUnwrap(titles[route.title], "\(route.route): \(route.title) is in app/strings/en")
                entry.title_shown = shows(title, in: app)
                XCTAssertTrue(entry.title_shown, "\(route.route) opened and doesn't show its title, \"\(title)\"")
            } else {
                entry.refused_because = said.hasPrefix("refused:") ? String(said.dropFirst("refused:".count)) : said
                XCTAssertTrue(["signed-in", "manages", "cards.manage", "billing.manage", "members.manage", "account.delete"]
                                .contains(entry.refused_because ?? ""), "\(route.route): refused as \(said)")
            }
            entries.append(entry)
        }

        let map = Map(generated_by: "ios/TafsUITests/RouteWalkUITests.swift", mode: "sample",
                      routes: entries.sorted { $0.route < $1.route })
        let text = Self.text(map)
        print(Self.begin)
        print(text, terminator: "")
        print(Self.end)
        let attachment = XCTAttachment(data: Data(text.utf8), uniformTypeIdentifier: "public.json")
        attachment.name = "ios.json"
        attachment.lifetime = .keepAlways
        add(attachment)

        // What app/map/ios.json says each route does: a route it opens shows its title.
        let file = Self.repo.appendingPathComponent("app/map/ios.json")
        let kept = try JSONDecoder().decode(Map.self, from: Data(contentsOf: file))
        for expected in kept.routes where expected.opened {
            let walked = map.routes.first { $0.route == expected.route }
            XCTAssertTrue(walked?.opened == true && walked?.title_shown == true,
                          "\(expected.route) opens in the sample and shows its title, per app/map/ios.json")
        }
        if map != kept {
            let differ = Set(map.routes.map(\.route) + kept.routes.map(\.route)).sorted().filter { route in
                map.routes.first { $0.route == route } != kept.routes.first { $0.route == route }
            }
            XCTFail("""
                The walk's map differs from app/map/ios.json (\(differ.isEmpty ? "its header" : differ.joined(separator: ", "))). \
                If the change is meant, update app/map/ios.json with the walk's map: printed in the test's \
                log between its BEGIN and END lines, and attached to its result as ios.json.
                """)
        }
    }

    /// Whether the title is on screen: any element that reads it (an eyebrow is in capitals, a
    /// line with the taf's name only starts with it), or the bar's item for it, selected. The bar's
    /// other items, and the hook's own lines, don't count. A list's rows below the fold are
    /// brought up by a swipe or two when the title isn't there by then.
    @MainActor
    private func shows(_ title: String, in app: XCUIApplication) -> Bool {
        let fixed = title.components(separatedBy: "{").first ?? title
        let reads = title.contains("{")
            ? NSPredicate(format: "label BEGINSWITH[c] %@ AND NOT (identifier BEGINSWITH 'bar.') AND NOT (identifier BEGINSWITH 'route-walk.')", fixed)
            : NSPredicate(format: "label ==[c] %@ AND NOT (identifier BEGINSWITH 'bar.') AND NOT (identifier BEGINSWITH 'route-walk.')", title)
        let element = app.descendants(matching: .any).matching(reads).firstMatch
        let bar = app.buttons["bar." + title]
        var swipes = 0
        let start = Date()
        return within(8) {
            if element.exists || (bar.exists && bar.isSelected) { return true }
            if swipes < 2, Date().timeIntervalSince(start) > 3 + 1.5 * Double(swipes) { app.swipeUp(); swipes += 1 }
            return false
        }
    }

    /// Polls until `check` holds or the time is up.
    @MainActor
    private func within(_ seconds: TimeInterval, _ check: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        repeat {
            if check() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < deadline
        return check()
    }

    // MARK: The map's sources and its text

    struct Route: Decodable {
        var route: String
        var title: String
        var needs: String?
    }

    /// app/routes.json's routes, by route.
    static func routes() throws -> [Route] {
        struct File: Decodable { var routes: [Route] }
        let data = try Data(contentsOf: repo.appendingPathComponent("app/routes.json"))
        return try JSONDecoder().decode(File.self, from: data).routes.sorted { $0.route < $1.route }
    }

    /// The English lines by key, from app/strings/en/.
    static func english() throws -> [String: String] {
        let folder = repo.appendingPathComponent("app/strings/en")
        var lines: [String: String] = [:]
        for file in try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
        where file.pathExtension == "json" {
            let part = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any] ?? [:]
            for (key, value) in part { if let line = value as? String { lines[key] = line } }
        }
        return lines
    }

    /// The map as app/map/ios.json keeps it: one route a line, in the order of the routes.
    static func text(_ map: Map) -> String {
        // Routes, modes and needs hold no quote or backslash (app/routes.py).
        func quoted(_ s: String) -> String { "\"" + s + "\"" }
        let lines = map.routes.map { e in
            var fields = ["\"route\": \(quoted(e.route))", "\"opened\": \(e.opened)", "\"title_shown\": \(e.title_shown)"]
            if let why = e.refused_because { fields.append("\"refused_because\": \(quoted(why))") }
            return "    {" + fields.joined(separator: ", ") + "}"
        }
        return """
            {
              "generated_by": \(quoted(map.generated_by)),
              "mode": \(quoted(map.mode)),
              "routes": [
            \(lines.joined(separator: ",\n"))
              ]
            }

            """
    }
}
