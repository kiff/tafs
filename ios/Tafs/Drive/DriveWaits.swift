import UIKit

/// The events Coach can wait for (docs/drive.md): the app says when a refresh ends, a prompt shows
/// and goes, or a notification shows; a screen or a sheet changing is seen on screen.
@MainActor
enum DriveWaits {
    static let events = ["screen_shown", "sheet_opened", "sheet_closed", "refreshed", "prompt_shown", "prompt_done", "notification_shown"]
    private static var fired: [String: Date] = [:]

    /// The app says an event happened.
    static func emit(_ event: String) { fired[event] = Date() }

    /// How many sheets or alerts are up.
    static func depth() -> Int {
        guard var top = DriveTargets.appWindows().last(where: { $0.rootViewController != nil })?.rootViewController else { return 0 }
        var n = 0
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next; n += 1 }
        return n
    }

    /// What screen is up: the sheets, and the top of each navigation stack that shows.
    static func screen() -> [ObjectIdentifier] {
        guard let root = DriveTargets.appWindows().last(where: { $0.rootViewController != nil })?.rootViewController else { return [] }
        var out: [ObjectIdentifier] = []
        func visit(_ vc: UIViewController) {
            if let nav = vc as? UINavigationController, DriveTargets.visibleFrame(nav.view) != nil, let top = nav.topViewController {
                out.append(ObjectIdentifier(top))
            }
            vc.children.forEach(visit)
        }
        var top: UIViewController? = root
        while let vc = top {
            out.append(ObjectIdentifier(vc))
            visit(vc)
            top = vc.presentedViewController
        }
        return out
    }

    /// Waits at most `timeout` seconds for `event`: true when it came.
    static func wait(_ event: String, timeout: Int) async -> Bool {
        let since = Date()
        let deadline = since.addingTimeInterval(Double(max(1, min(timeout, 20))))
        let depth0 = depth()
        let screen0 = screen()
        while Date() < deadline {
            if let at = fired[event], at >= since { return true }
            switch event {
            case "sheet_opened": if depth() > depth0 { return true }
            case "sheet_closed": if depth() < depth0 { return true }
            case "screen_shown": if screen() != screen0 { try? await Task.sleep(nanoseconds: 350_000_000); return true }
            default: break
            }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        return false
    }
}
