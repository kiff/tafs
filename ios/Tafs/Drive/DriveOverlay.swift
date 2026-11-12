import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

/// The window above the app while Coach drives (docs/drive.md): it draws the border, the caption and
/// the dot, which the screenshot leaves out. It takes no touch but the caption's Continue and Stop, so
/// every other touch of the person's reaches the app under it. Coach's own steps never come through
/// touches.
final class DriveOverlayWindow: UIWindow {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard DriveSession.shared.isOn, DriveOverlay.shared.takes(point) else { return nil }
        return super.hitTest(point, with: event)
    }
}

/// Sees each touch of the person's on one of the app's windows as it begins, and takes none
/// (docs/drive.md): it never recognizes, so the control under the touch acts as usual.
final class DriveTouchWatch: UIGestureRecognizer, UIGestureRecognizerDelegate {
    static func make() -> DriveTouchWatch {
        let watch = DriveTouchWatch(target: nil, action: nil)
        watch.cancelsTouchesInView = false
        watch.delaysTouchesBegan = false
        watch.delaysTouchesEnded = false
        watch.delegate = watch
        return watch
    }

    /// The touch this sequence follows: the first finger down.
    private weak var first: UITouch?

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard first == nil, let touch = touches.first else { return }
        first = touch
        DriveSession.shared.personBegan(at: touch.location(in: nil))
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) { lifted(touches) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) { lifted(touches) }

    private func lifted(_ touches: Set<UITouch>) {
        if let touch = first, touches.contains(touch) {
            first = nil
            DriveSession.shared.personEnded(at: touch.location(in: nil))
        }
        // Never recognized: failing ends this sequence for the watch alone.
        if first == nil { state = .failed }
    }

    override func reset() {
        super.reset()
        first = nil
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool { false }
}

/// The watch on each of the app's windows while a drive is on.
@MainActor
enum DriveTouches {
    static func attach() {
        for window in DriveTargets.appWindows() where !(window.gestureRecognizers ?? []).contains(where: { $0 is DriveTouchWatch }) {
            window.addGestureRecognizer(DriveTouchWatch.make())
        }
    }

    static func detach() {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows {
                for watch in (window.gestureRecognizers ?? []) where watch is DriveTouchWatch { window.removeGestureRecognizer(watch) }
            }
        }
    }

    static func watched(_ window: UIWindow) -> Bool {
        (window.gestureRecognizers ?? []).contains { $0 is DriveTouchWatch }
    }
}

@MainActor
final class DriveOverlay {
    static let shared = DriveOverlay()
    private var window: DriveOverlayWindow?
    /// Where the caption's Stop is, and its Continue while the drive is paused, in the overlay's points.
    var stopFrame: CGRect = .null
    var continueFrame: CGRect = .null

    /// Whether a touch at a point is the overlay's own: on Continue or on Stop.
    func takes(_ point: CGPoint) -> Bool { stopFrame.contains(point) || continueFrame.contains(point) }

    /// What the overlay's window answers a touch at a point with: nothing, anywhere but on its buttons.
    func hit(_ point: CGPoint) -> UIView? { window?.hitTest(point, with: nil) }

    func show() {
        guard window == nil else { return }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first else { return }
        let w = DriveOverlayWindow(windowScene: scene)
        w.windowLevel = .alert + 1
        w.backgroundColor = .clear
        let host = UIHostingController(rootView: DriveOverlayView())
        host.view.backgroundColor = .clear
        w.rootViewController = host
        if let main = DriveTargets.appWindows().last { w.frame = main.frame }
        // Shown, never key: the app's window keeps the focus, so Coach types into its fields.
        w.isHidden = false
        window = w
    }

    func hide() {
        window?.isHidden = true
        window = nil
        stopFrame = .null
        continueFrame = .null
    }

    var showing: Bool { window != nil }
}

/// The border, the caption and the dot.
struct DriveOverlayView: View {
    @ObservedObject private var drive = DriveSession.shared

    var body: some View {
        ZStack {
            if drive.isOn {
                // Paused: the border dims and holds still.
                if drive.paused {
                    RoundedRectangle(cornerRadius: 40, style: .continuous)
                        .strokeBorder(Theme.amber.opacity(0.25), lineWidth: 3)
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
                } else {
                    DriveBorder().allowsHitTesting(false)
                }
            }
            if let dot = drive.dot {
                Circle().fill(Theme.amber.opacity(0.85)).frame(width: 26, height: 26)
                    .overlay(Circle().stroke(Color.white, lineWidth: 2))
                    .position(dot)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }
            if let caption = drive.caption {
                VStack {
                    Spacer()
                    HStack(spacing: 10) {
                        Image(systemName: drive.isOn && drive.paused ? "pause.fill" : "sparkle").foregroundColor(Theme.amber)
                        Text(drive.isOn && drive.paused ? Words.Drive.paused : caption)
                            .scaledFont(14, weight: .medium).foregroundColor(Theme.ink).lineLimit(2)
                        if drive.isOn {
                            if drive.paused {
                                Button { drive.resume() } label: { pill(Words.Common.continue, filled: true) }
                                    .buttonStyle(.plain)
                                    .background(place { DriveOverlay.shared.continueFrame = $0 })
                                    .onDisappear { DriveOverlay.shared.continueFrame = .null }
                            }
                            Button { drive.stop("stopped") } label: { pill(Words.Chat.stop, filled: !drive.paused) }
                                .buttonStyle(.plain)
                                .background(place { DriveOverlay.shared.stopFrame = $0 })
                        }
                    }
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule())
                    .shadow(color: .black.opacity(0.15), radius: 10, y: 4)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 92)
                }
            }
        }
    }

    private func pill(_ words: String, filled: Bool) -> some View {
        Text(words).scaledFont(14, weight: .semibold).foregroundColor(filled ? Theme.amberInk : Theme.ink)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(Capsule().fill(filled ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Theme.ink.opacity(0.08))))
            .contentShape(Capsule())
    }

    /// Reports a button's place to the overlay window, a little wider than it draws, so the window
    /// takes the touches on it and no others.
    private func place(_ report: @escaping (CGRect) -> Void) -> some View {
        GeometryReader { g in
            Color.clear
                .onAppear { report(g.frame(in: .global).insetBy(dx: -8, dy: -8)) }
                .onChange(of: g.frame(in: .global)) { report($0.insetBy(dx: -8, dy: -8)) }
        }
    }
}

/// The border while Coach acts: it pulses.
private struct DriveBorder: View {
    @State private var pulse = false

    var body: some View {
        RoundedRectangle(cornerRadius: 40, style: .continuous)
            .strokeBorder(Theme.amber.opacity(pulse ? 0.95 : 0.45), lineWidth: pulse ? 6 : 3)
            .ignoresSafeArea()
            .onAppear { withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) { pulse = true } }
    }
}
