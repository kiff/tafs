import UIKit
import UserNotifications

/// Apple push for held calls: iOS gives the phone's token here, and wakes the app here when
/// a held call was answered elsewhere, so its alert goes. tafs cloud sends both (from
/// KIFF's alerts); `AppModel` hands tafs cloud the token.
@MainActor
final class PushDelegate: NSObject, UIApplicationDelegate {
    var onToken: (@MainActor (Data) -> Void)?
    /// A held call changed elsewhere: reload what needs the owner.
    var onHeldChanged: (@MainActor () async -> Void)?

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
