import SwiftUI
import UIKit

/// The window above the app while Coach drives (docs/drive.md): it draws the border, the caption and
/// the dot, which the screenshot leaves out, and it takes every touch, so none reaches the app. A
/// touch ends the drive: on Stop or the Coach button, or a swipe in from the left edge (the back
/// gesture), as `stopped`; anywhere else as `owner_took_over`. Coach's own steps never come through
/// touches.
final class DriveOverlayWindow: UIWindow {
    /// A touch that began at the left edge: a back gesture if it moves right, else the person's touch.
    private var edge: CGPoint?
    static let edgeWidth: CGFloat = 24

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        DriveSession.shared.isOn ? (rootViewController?.view ?? self) : nil
    }

    override func sendEvent(_ event: UIEvent) {
        guard DriveSession.shared.isOn, event.type == .touches else {
            super.sendEvent(event)
            return
        }
        // Not delivered: the touch only says who has the app now.
        for touch in event.allTouches ?? [] {
            let p = touch.location(in: self)
            switch touch.phase {
            case .began:
                if DriveOverlay.shared.stopFrame.contains(p) || DriveOverlay.coachButton()?.contains(p) == true {
                    DriveSession.shared.stop("stopped")
                } else if p.x <= Self.edgeWidth {
                    edge = p
                } else {
                    DriveSession.shared.stop("owner_took_over")
                }
            case .moved:
                if let e = edge, p.x - e.x > 40 { edge = nil; DriveSession.shared.stop("stopped") }
            case .ended, .cancelled:
                if edge != nil { edge = nil; DriveSession.shared.stop("owner_took_over") }
            default:
                break
            }
        }
    }
}

@MainActor
final class DriveOverlay {
    static let shared = DriveOverlay()
    private var window: DriveOverlayWindow?
    /// Where the caption's Stop is, in the overlay's points.
    var stopFrame: CGRect = .null

    /// The Coach button's place on screen, while it shows.
    static func coachButton() -> CGRect? {
        DriveTargets.shared.entries[DriveSession.coachButtonID]?.anchor.flatMap { DriveTargets.visibleFrame($0) }
    }

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
    }

    var showing: Bool { window != nil }
}

/// The border, the caption and the dot.
struct DriveOverlayView: View {
    @ObservedObject private var drive = DriveSession.shared
    @State private var pulse = false

    var body: some View {
        ZStack {
            if drive.isOn {
                RoundedRectangle(cornerRadius: 40, style: .continuous)
                    .strokeBorder(Theme.amber.opacity(pulse ? 0.95 : 0.45), lineWidth: pulse ? 6 : 3)
                    .ignoresSafeArea()
                    .onAppear { withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) { pulse = true } }
            }
            if let dot = drive.dot {
                Circle().fill(Theme.amber.opacity(0.85)).frame(width: 26, height: 26)
                    .overlay(Circle().stroke(Color.white, lineWidth: 2))
                    .position(dot)
                    .ignoresSafeArea()
            }
            if let caption = drive.caption {
                VStack {
                    Spacer()
                    HStack(spacing: 10) {
                        Image(systemName: "sparkle").foregroundColor(Theme.amber)
                        Text(caption).scaledFont(14, weight: .medium).foregroundColor(Theme.ink).lineLimit(2)
                        if drive.isOn {
                            Text("Stop").scaledFont(14, weight: .semibold).foregroundColor(Theme.amberInk)
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(Capsule().fill(Theme.brand))
                                .background(GeometryReader { g in
                                    Color.clear
                                        .onAppear { DriveOverlay.shared.stopFrame = g.frame(in: .global).insetBy(dx: -8, dy: -8) }
                                        .onChange(of: g.frame(in: .global)) { DriveOverlay.shared.stopFrame = $0.insetBy(dx: -8, dy: -8) }
                                })
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
        .allowsHitTesting(false)
    }
}
