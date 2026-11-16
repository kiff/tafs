import SwiftUI

#if DEBUG
import UIKit

/// `-routeWalk YES`, in a debug build only: the route walk (RouteWalkUITests) asks the app to open
/// each route of the app map (app/routes.json) and reads what `AppModel.open(route:)` answered.
/// A release build has none of it (`#else` below): the modifier is the view itself.
///
/// A small window of its own, above the app's and its sheets and clear of the status bar, holds
/// a field (the app's own `DriveField`) the test types a path into, then Return; a line with the
/// answer, `path|opened` or `path|refused:why`; and a line with the mode and the first taf's id,
/// which the test fills `{taf}` with. Before each path the screens go back to where they start:
/// every request is cleared, and the whole tree is built again (its navigation pushed nothing,
/// its sheets are gone), on the launch's tab.
@MainActor
final class RouteWalk: ObservableObject {
    nonisolated static var enabled: Bool { UserDefaults.standard.bool(forKey: "routeWalk") }

    /// Changes for each path: the screens are built again from the start.
    @Published private(set) var epoch = 0
    @Published var path = ""
    @Published private(set) var answer = "-"
    @Published private(set) var state = "-"
    private weak var model: AppModel?
    private var window: UIWindow?

    func install(_ model: AppModel) {
        self.model = model
        show(model)
        guard window == nil,
              let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let window = UIWindow(windowScene: scene)
        window.windowLevel = .alert + 1
        // At the right edge, halfway down: off the status bar, whose taps XCUITest can't reach.
        let screen = scene.screen.bounds
        window.frame = CGRect(x: screen.width - 170, y: screen.height * 0.5, width: 170, height: 64)
        let root = UIHostingController(rootView: RouteWalkPanel(walk: self))
        root.view.backgroundColor = UIColor.systemBackground.withAlphaComponent(0.6)
        window.rootViewController = root
        window.isHidden = false
        self.window = window
    }

    /// The mode and the first taf's id, once the sample's tafs are in.
    func show(_ model: AppModel) {
        state = model.mode.rawValue + " " + (model.tafs.first?.id.uuidString ?? "-")
    }

    /// The field's Return: the app's own window takes the keys back, and the path is walked.
    func submit() {
        let typed = path
        path = ""
        window?.windowScene?.windows.first { $0 !== window && $0.windowLevel == .normal }?.makeKey()
        walk(to: typed)
    }

    /// Back to where the screens start, then the path, and its answer once it's asked.
    private func walk(to path: String) {
        guard let model else { return }
        answer = "-"
        model.requestedTab = nil; model.requestedTafPage = nil; model.requestedAccountPage = nil
        model.calendarRequested = false; model.usageRequested = false
        model.haltRequested = false; model.haltRequestedTaf = nil; model.coachOpen = false
        epoch += 1
        Task {
            // The screens built again first, so each request lands on a fresh tree.
            try? await Task.sleep(nanoseconds: 500_000_000)
            let opened = model.open(route: path)
            self.answer = path + "|" + (opened ? "opened" : "refused:" + Self.refusal(path, model))
        }
    }

    /// Why a path didn't open: what its route needs (app/routes.json), when the person hasn't
    /// it; else `signed-in`, a screen only someone signed in to KIFF opens. A path that names no
    /// route or no taf the app holds says so.
    static func refusal(_ path: String, _ model: AppModel) -> String {
        guard let match = AppRoute.match(path) else { return "no-route" }
        let id = match.values["taf"].flatMap { model.tafNamed($0) }
        if match.route.parameters.contains("taf"), id == nil { return "no-taf" }
        if let needs = match.route.needs, !model.hasNeeds(of: match.route, taf: id) { return needs }
        return "signed-in"
    }
}

/// The hook's field and its two lines.
private struct RouteWalkPanel: View {
    @ObservedObject var walk: RouteWalk

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            DriveField("route", text: $walk.path)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
                .onSubmit { walk.submit() }
                .accessibilityIdentifier("route-walk.path")
            Text(walk.answer).accessibilityIdentifier("route-walk.answer")
            Text(walk.state).accessibilityIdentifier("route-walk.state")
        }
        .font(.system(size: 9))
        .padding(4)
    }
}

private struct RouteWalkHook: ViewModifier {
    @EnvironmentObject private var model: AppModel
    @StateObject private var walk = RouteWalk()

    func body(content: Content) -> some View {
        content
            .id(walk.epoch)
            .onAppear { walk.install(model) }
            .onChange(of: model.tafs.first?.id) { _ in walk.show(model) }
            .onChange(of: model.mode) { _ in walk.show(model) }
    }
}

extension View {
    /// The route walk's hook, with `-routeWalk YES` in a debug build; otherwise the view itself.
    @MainActor @ViewBuilder func routeWalk() -> some View {
        if RouteWalk.enabled { modifier(RouteWalkHook()) } else { self }
    }
}
#else
extension View {
    /// A release build has no route walk.
    @MainActor func routeWalk() -> some View { self }
}
#endif
