import UIKit

extension DriveDiagnosticsReport {
    /// The app as it is now, and what the diagnostics kept.
    @MainActor static func now() -> DriveDiagnosticsReport {
        let info = Bundle.main.infoDictionary ?? [:]
        let window = DriveTargets.appWindows().last
        let traits = window?.traitCollection ?? UITraitCollection.current
        let size = window?.bounds.size ?? .zero
        let read = DriveDiagnostics.shared.read()
        return DriveDiagnosticsReport(
            app: App(version: info["CFBundleShortVersionString"] as? String ?? "",
                     build: info["CFBundleVersion"] as? String ?? "",
                     platform: "ios " + UIDevice.current.systemVersion,
                     orientation: size.width > size.height ? "landscape" : "portrait",
                     sizeClass: traits.horizontalSizeClass == .regular ? "regular" : "compact",
                     dark: traits.userInterfaceStyle == .dark,
                     fontScale: Double((UIFontMetrics.default.scaledValue(for: 100)).rounded()) / 100),
            errors: read.errors, requests: read.requests)
    }
}
