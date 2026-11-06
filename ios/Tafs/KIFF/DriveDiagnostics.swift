import Foundation

/// What `app_look {diagnostics}` reads (docs/drive.md): the app's last errors and refusals in its
/// own words, and its failed requests by route template and status only, never a body, header,
/// query or token. The newest 30 of each, kept in memory only.
final class DriveDiagnostics: @unchecked Sendable {
    static let shared = DriveDiagnostics()
    static let kept = 30

    struct Noted: Equatable, Encodable { let at: String; let words: String }
    struct Failed: Equatable, Encodable { let at: String; let route: String; let status: Int }

    private let lock = NSLock()
    private var errors: [Noted] = []
    private var failed: [Failed] = []

    private static func now() -> String { ISO8601DateFormatter().string(from: Date()) }

    func error(_ words: String) {
        let w = words.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !w.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        errors.append(Noted(at: Self.now(), words: String(w.prefix(300))))
        if errors.count > Self.kept { errors.removeFirst(errors.count - Self.kept) }
    }

    func failed(method: String, path: String, status: Int) {
        lock.lock(); defer { lock.unlock() }
        failed.append(Failed(at: Self.now(), route: method + " " + Self.route(path), status: status))
        if failed.count > Self.kept { failed.removeFirst(failed.count - Self.kept) }
    }

    func read() -> (errors: [Noted], requests: [Failed]) {
        lock.lock(); defer { lock.unlock() }
        return (errors, failed)
    }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        errors = []; failed = []
    }

    /// A path as its route's template: no query, and each id as `{id}` ("/v1/tafs/{id}/work").
    static func route(_ path: String) -> String {
        let bare = path.split(separator: "?", maxSplits: 1).first.map(String.init) ?? path
        let parts = bare.split(separator: "/", omittingEmptySubsequences: false).map { part -> String in
            let p = String(part)
            if p.isEmpty || p == "v1" { return p }
            let id = p.contains(where: \.isNumber) || p.contains("%") || p.contains("_") || p.contains("@") || p.count > 24
            return id ? "{id}" : p
        }
        return parts.joined(separator: "/")
    }
}

/// The diagnostics' JSON, as both apps send it.
struct DriveDiagnosticsReport: Encodable, Equatable {
    struct App: Encodable, Equatable {
        let version: String
        let build: String
        let platform: String
        let orientation: String
        let sizeClass: String
        let dark: Bool
        let fontScale: Double
        enum CodingKeys: String, CodingKey { case version, build, platform, orientation, dark, sizeClass = "size_class", fontScale = "font_scale" }
    }
    let app: App
    let errors: [DriveDiagnostics.Noted]
    let requests: [DriveDiagnostics.Failed]
}
