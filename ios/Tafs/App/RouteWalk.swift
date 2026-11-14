import SwiftUI

#if DEBUG
import UIKit

/// `-routeWalk YES`, in a debug build only: the route walk (RouteWalkUITests) asks the app to open
/// each route of the app map (app/routes.json) and reads what `AppModel.open(route:)` answered.
/// A release build has none of it (`#else` below): the modifier is the view itself.
///
/// A small window of its own, above the app's and its sheets, holds a text field the test types a
/// path into, then Return; a label with the answer, `path|opened` or `path|refused:why`; and a
/// label with the mode and the first taf's id, which the test fills `{taf}` with. Before each
/// path the screens go back to where they start: every request is cleared, and the whole tree is
/// built again (its navigation pushed nothing, its sheets are gone), on the launch's tab.
@MainActor
final class RouteWalk: NSObject, ObservableObject, UITextFieldDelegate {
    nonisolated static var enabled: Bool { UserDefaults.standard.bool(forKey: "routeWalk") }

    /// Changes for each path: the screens are built again from the start.
    @Published private(set) var epoch = 0
    private weak var model: AppModel?
    private var window: UIWindow?
    private let field = UITextField()
    private let answer = UILabel()
    private let state = UILabel()

    func install(_ model: AppModel) {
        self.model = model
        show(model)
        guard window == nil,
              let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let window = UIWindow(windowScene: scene)
        window.windowLevel = .alert + 1
        window.frame = CGRect(x: 0, y: 0, width: 160, height: 48)
        let root = UIViewController()
        root.view.backgroundColor = UIColor.systemBackground.withAlphaComponent(0.6)
        field.frame = CGRect(x: 2, y: 2, width: 156, height: 22)
        field.font = .systemFont(ofSize: 9)
        field.borderStyle = .line
        field.autocapitalizationType = .none
        field.autocorrectionType = .no
        field.spellCheckingType = .no
        field.smartDashesType = .no
        field.smartQuotesType = .no
        field.smartInsertDeleteType = .no
        field.delegate = self
        field.accessibilityIdentifier = "route-walk.path"
        for (i, label) in [answer, state].enumerated() {
            label.frame = CGRect(x: 2, y: 25 + CGFloat(i) * 11, width: 156, height: 11)
            label.font = .systemFont(ofSize: 8)
            root.view.addSubview(label)
        }
        answer.accessibilityIdentifier = "route-walk.answer"
        state.accessibilityIdentifier = "route-walk.state"
        answer.text = "-"
        root.view.addSubview(field)
        window.rootViewController = root
        window.isHidden = false
        self.window = window
    }

    /// The mode and the first taf's id, once the sample's tafs are in.
    func show(_ model: AppModel) {
        state.text = model.mode.rawValue + " " + (model.tafs.first?.id.uuidString ?? "-")
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        let path = textField.text ?? ""
        textField.text = ""
        textField.resignFirstResponder()
        window?.windowScene?.windows.first { $0 !== window && $0.windowLevel == .normal }?.makeKey()
        walk(to: path)
        return false
    }

    /// Back to where the screens start, then the path, and its answer once it's asked.
    private func walk(to path: String) {
        guard let model else { return }
        answer.text = "-"
        model.requestedTab = nil; model.requestedTafPage = nil; model.requestedAccountPage = nil
        model.calendarRequested = false; model.usageRequested = false
        model.haltRequested = false; model.haltRequestedTaf = nil; model.coachOpen = false
        epoch += 1
        Task {
            // The screens built again first, so each request lands on a fresh tree.
            try? await Task.sleep(nanoseconds: 500_000_000)
            let opened = model.open(route: path)
            self.answer.text = path + "|" + (opened ? "opened" : "refused:" + Self.refusal(path, model))
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
