import UIKit
import UserNotifications

/// Apple push for held calls: iOS gives the phone's token here, wakes the app here when a
/// held call was answered elsewhere so its alert goes, and says here which alert was
/// tapped. tafs cloud sends the pushes (from KIFF's alerts); `AppModel` hands it the token.
@MainActor
final class PushDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    var onToken: (@MainActor (Data) -> Void)?
    /// A held call changed elsewhere: reload what needs the owner.
    var onHeldChanged: (@MainActor () async -> Void)?
    /// Opens a screen ("needs-you"). A tap that launched the app waits here until it is set.
    var onOpen: (@MainActor (String) -> Void)? {
        didSet { if let onOpen, let waiting = pendingOpen { pendingOpen = nil; onOpen(waiting) } }
    }
    private(set) var pendingOpen: String?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Set before launch ends, so a tap that launched the app is delivered here.
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /// The owner tapped an alert: open where it points, now or once the app is ready.
    func open(_ destination: String) {
        if let onOpen { onOpen(destination) } else { pendingOpen = destination }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let destination = response.notification.request.content.userInfo["open"] as? String
        completionHandler()
        guard let destination else { return }
        Task { @MainActor in self.open(destination) }
    }

    /// With tafs open, an alert still shows, and the app reloads what needs the owner.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
        Task { @MainActor in await self.onHeldChanged?() }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        onToken?(deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // No token, no alerts while tafs is closed; the app still finds held calls when open.
    }

    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        guard let hold = userInfo["hold_id"] as? String else { completionHandler(.noData); return }
        if userInfo["answered"] as? Bool == true { Self.removeAlerts(forHold: hold) }
        Task {
            await onHeldChanged?()
            completionHandler(.newData)
        }
    }

    /// Asks iOS for the push token, once the owner has allowed notifications: a token
    /// handed to tafs cloud before that would only get silent pushes.
    static func registerIfAllowed() {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let allowed = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
            guard allowed else { return }
            Task { @MainActor in UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    static func register() {
        UIApplication.shared.registerForRemoteNotifications()
    }

    /// Asks the owner to allow alerts. True when they are allowed.
    static func askPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Takes away the alerts shown for a held call that was answered.
    private static func removeAlerts(forHold hold: String) {
        UNUserNotificationCenter.current().getDeliveredNotifications { delivered in
            let ids = delivered.filter { $0.request.content.userInfo["hold_id"] as? String == hold }.map(\.request.identifier)
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        }
    }
}
