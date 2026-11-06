import SwiftUI
import UIKit

/// What a drive can reach on screen (docs/drive.md). With VoiceOver off SwiftUI builds no
/// accessibility elements, so each control Coach may use registers itself here: through
/// `.drivable`, the app's button styles, or the field wrapper. Each entry keeps a small
/// anchor view behind its control, so its frame is read where it is now, scrolled, in a
/// sheet, or gone, and never from a stale copy.
@MainActor
final class DriveTargets {
    static let shared = DriveTargets()

    /// The roles both apps name in the tree (docs/drive.md).
    enum Role: String, Sendable { case button, tab, textField = "text_field", toggle, checkbox, radio, image, text, scroll, menu }

    /// The actions an entry may have: a tap's, a long press's, a row's swipe either way.
    enum Action: String, Sendable, CaseIterable { case activate, longPress = "long_press", swipeLeft = "swipe_left", swipeRight = "swipe_right" }

    struct Entry {
        let id: String
        var words: String
        var role: Role
        /// A control that confirms (docs/drive.md, "Coach never confirms"): a driven touch on it is refused.
        var confirm: Bool
        /// A field marked secret: blanked in the screenshot, its words left out of the tree.
        var secret: Bool
        /// An open approval's terms: blanked, and named only by `words`.
        var terms: Bool
        var actions: [Action: @MainActor () -> Void]
        weak var anchor: UIView?
    }

    /// An entry as it is on screen now: its frame in the screen's points.
    struct Shown {
        let entry: Entry
        let frame: CGRect
    }

    private(set) var entries: [String: Entry] = [:]

    func set(_ entry: Entry) { entries[entry.id] = entry }

    /// Removes the entry only while it's still this anchor's: a view that came back under the same id keeps its own.
    func remove(_ id: String, anchor: UIView) {
        if entries[id]?.anchor === anchor || entries[id]?.anchor == nil { entries[id] = nil }
    }

    func reset() { entries = [:] }

    // MARK: What's on screen

    /// The windows of the app's scene that are the app's own, bottom first: never the drive's overlay, the
    /// keyboard's or the system's.
    static func appWindows() -> [UIWindow] {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        return (scene?.windows ?? [])
            .filter { w in
                guard !w.isHidden, w.alpha > 0.01, !(w is DriveOverlayWindow) else { return false }
                let name = String(describing: type(of: w))
                return !name.contains("Keyboard") && !name.contains("TextEffects") && !name.contains("Remote")
            }
            .sorted { $0.windowLevel.rawValue < $1.windowLevel.rawValue }
    }

    /// The topmost screen the person sees: the last presented controller (a sheet, an alert), else the
    /// root's. Only what's in it can be reached: a sheet covers the screen behind it.
    static func topView() -> UIView? {
        guard let window = appWindows().last(where: { $0.rootViewController != nil }), var top = window.rootViewController else { return nil }
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next }
        return top.view
    }

    /// The topmost controller, for a sheet's or an alert's dismissal.
    static func topPresented() -> UIViewController? {
        guard let root = appWindows().last(where: { $0.rootViewController != nil })?.rootViewController else { return nil }
        var top = root
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next }
        return top === root ? nil : top
    }

    /// The entries the person can see now, in the topmost screen, each with its frame on the screen.
    func shown() -> [Shown] {
        guard let top = Self.topView() else { return [] }
        return entries.values.compactMap { e in
            guard let anchor = e.anchor, anchor.isDescendant(of: top), let frame = Self.visibleFrame(anchor) else { return nil }
            return Shown(entry: e, frame: frame)
        }
        .sorted { ($0.frame.minY, $0.frame.minX, $0.entry.id) < ($1.frame.minY, $1.frame.minX, $1.entry.id) }
    }

    /// Where a view is on the screen, clipped by what clips it; nil when hidden, see-through or off screen.
    static func visibleFrame(_ view: UIView) -> CGRect? {
        guard let window = view.window else { return nil }
        var layer: CALayer? = view.layer
        while let l = layer {
            if l.isHidden || l.opacity < 0.01 { return nil }
            layer = l.superlayer
        }
        var frame = view.convert(view.bounds, to: nil)
        var v = view.superview
        while let s = v {
            if s.clipsToBounds { frame = frame.intersection(s.convert(s.bounds, to: nil)) }
            v = s.superview
        }
        frame = frame.intersection(window.bounds)
        guard !frame.isNull, frame.width >= 1, frame.height >= 1 else { return nil }
        return frame.offsetBy(dx: window.frame.minX, dy: window.frame.minY)
    }

    /// The smallest entry under a point on the screen: what a touch there reaches.
    func hit(_ point: CGPoint, in shown: [Shown]? = nil) -> Shown? {
        (shown ?? self.shown()).filter { $0.frame.contains(point) }
            .min { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }
    }

    /// Whether a touch at a point lands on a control that confirms: any marked control under it, not only
    /// the smallest, so a confirming row isn't reached through a control drawn on it.
    func confirms(_ point: CGPoint, in shown: [Shown]? = nil) -> Bool {
        (shown ?? self.shown()).contains { $0.entry.confirm && $0.frame.contains(point) }
    }

    func shown(id: String) -> Shown? { shown().first { $0.entry.id == id } }
}

// MARK: - Registering

/// What the views around a control say of it: that it confirms, its words, its id. Set on the control
/// (`.confirming()`, `.driveWords`, `.driveID`) and read by the style or wrapper that registers it.
private struct DriveConfirmKey: EnvironmentKey { static let defaultValue = false }
private struct DriveWordsKey: EnvironmentKey { static let defaultValue: String? = nil }
private struct DriveIDKey: EnvironmentKey { static let defaultValue: String? = nil }

extension EnvironmentValues {
    var driveConfirm: Bool {
        get { self[DriveConfirmKey.self] }
        set { self[DriveConfirmKey.self] = newValue }
    }
    var driveWords: String? {
        get { self[DriveWordsKey.self] }
        set { self[DriveWordsKey.self] = newValue }
    }
    var driveID: String? {
        get { self[DriveIDKey.self] }
        set { self[DriveIDKey.self] = newValue }
    }
}

extension View {
    /// The confirm mark (docs/drive.md): a control that approves, disconnects, removes, halts or resumes,
    /// answers a check, buys, or dismisses a card that signs. A driven touch on it is refused before
    /// anything runs.
    func confirming() -> some View { environment(\.driveConfirm, true) }

    /// The words a drive names this control by, where its style registers it.
    func driveWords(_ words: String) -> some View { environment(\.driveWords, words) }

    /// A stable id for this control in the drive's tree.
    func driveID(_ id: String) -> some View { environment(\.driveID, id) }

    /// Registers this view with the drive: its words, role and actions. `activate` is its tap.
    func drivable(_ words: String, role: DriveTargets.Role = .button, id: String? = nil, confirm: Bool = false, secret: Bool = false,
                  actions: [DriveTargets.Action: @MainActor () -> Void] = [:], activate: (@MainActor () -> Void)? = nil) -> some View {
        var all = actions
        if let activate { all[.activate] = activate }
        return modifier(Drivable(words: words, role: role, id: id, confirm: confirm, secret: secret, terms: false, actions: all))
    }

    /// An open approval's terms: blanked in the screenshot, and named in the tree only as "An approval is
    /// open: <title>".
    func driveTerms(_ title: String) -> some View {
        modifier(Drivable(words: "An approval is open: \(title)", role: .text, id: nil, confirm: false, secret: false, terms: true, actions: [:]))
    }
}

struct Drivable: ViewModifier {
    let words: String
    let role: DriveTargets.Role
    let id: String?
    let confirm: Bool
    let secret: Bool
    let terms: Bool
    let actions: [DriveTargets.Action: @MainActor () -> Void]
    @Environment(\.driveConfirm) private var marked
    @Environment(\.driveID) private var givenID
    @Environment(\.isEnabled) private var enabled
    @State private var made = "d" + UUID().uuidString.prefix(8).lowercased()

    func body(content: Content) -> some View {
        content.background(DriveAnchor(entry: DriveTargets.Entry(
            id: id ?? givenID ?? made, words: words, role: role, confirm: confirm || marked, secret: secret, terms: terms,
            actions: enabled ? actions : [:], anchor: nil)).accessibilityHidden(true))
    }
}

/// The view behind a registered control: it registers the control while it's in a window, and its
/// place is the control's.
struct DriveAnchor: UIViewRepresentable {
    let entry: DriveTargets.Entry

    final class Anchor: UIView {
        var entry: DriveTargets.Entry?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            backgroundColor = .clear
            // Never an element of its own: the control's identifier and label stay the control's.
            isAccessibilityElement = false
            accessibilityElementsHidden = true
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            register()
        }

        func register() {
            guard var e = entry else { return }
            if window == nil {
                DriveTargets.shared.remove(e.id, anchor: self)
            } else {
                e.anchor = self
                DriveTargets.shared.set(e)
            }
        }
    }

    func makeUIView(context: Context) -> Anchor {
        let a = Anchor(frame: .zero)
        a.entry = entry
        return a
    }

    func updateUIView(_ view: Anchor, context: Context) {
        if let old = view.entry, old.id != entry.id { DriveTargets.shared.remove(old.id, anchor: view) }
        view.entry = entry
        view.register()
    }

    static func dismantleUIView(_ view: Anchor, coordinator: ()) {
        if let id = view.entry?.id { DriveTargets.shared.remove(id, anchor: view) }
    }
}

// MARK: - The app's buttons, drivable

/// A button style that registers its button with the drive: its tap is the button's own action
/// (`trigger`), and it's drawn by `inner`, so it looks as it did.
struct DriveButton<Inner: ButtonStyle>: PrimitiveButtonStyle {
    let inner: Inner

    func makeBody(configuration: Configuration) -> some View {
        Registered(configuration: configuration, inner: inner)
    }

    private struct Registered: View {
        let configuration: Configuration
        let inner: Inner
        @Environment(\.driveWords) private var words

        var body: some View {
            Button(role: configuration.role, action: configuration.trigger) { configuration.label }
                .buttonStyle(inner)
                .modifier(Drivable(words: words ?? "", role: .button, id: nil, confirm: false, secret: false, terms: false,
                                   actions: [.activate: { configuration.trigger() }]))
        }
    }
}

/// `.plain`, drivable.
struct DrivablePlainButtonStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        DriveButton(inner: PlainInner()).makeBody(configuration: configuration)
    }

    /// The plain look: the label as it is, a little faded while pressed.
    struct PlainInner: ButtonStyle {
        func makeBody(configuration: Configuration) -> some View {
            configuration.label.opacity(configuration.isPressed ? 0.6 : 1)
        }
    }
}

extension PrimitiveButtonStyle where Self == DrivablePlainButtonStyle {
    /// A plain button Coach can drive.
    static var drivablePlain: DrivablePlainButtonStyle { DrivablePlainButtonStyle() }
}
