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
    /// A hold's alert showed with tafs open: reload until Needs you has that hold.
    var onHoldAlert: (@MainActor (String) async -> Void)?
    /// Opens a screen ("needs-you"). A tap that launched the app waits here until it is set.
    var onOpen: (@MainActor (String) -> Void)? {
        didSet { if let onOpen, let waiting = pendingOpen { pendingOpen = nil; onOpen(waiting) } }
    }
    private(set) var pendingOpen: String?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Set before launch ends, so a tap that launched the app is delivered here.
        UNUserNotificationCenter.current().delegate = self
        // Approve and Refuse on a hold's notification (kiff/tafs#210).
        UNUserNotificationCenter.current().setNotificationCategories(AlertAnswers.categories())
        return true
    }

    /// The owner tapped an alert: open where it points, now or once the app is ready.
    func open(_ destination: String) {
        if let onOpen { onOpen(destination) } else { pendingOpen = destination }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let destination = info["open"] as? String
        let hold = info["hold_id"] as? String
        let action = response.actionIdentifier
        // Refuse is sent from the notification, iOS waiting until it's done (kiff/tafs#210).
        if let hold, action == AlertAnswers.refuse {
            let done = Done(completionHandler)
            Task { @MainActor in
                await NotificationRefuse.refuseKept(hold: hold)
                await self.onHeldChanged?()
                done.call()
            }
            return
        }
        completionHandler()
        // A hold opens itself, not the home screen; Approve opens it with Approve chosen.
        let target: String?
        if let hold, action == AlertAnswers.approve {
            target = AlertAnswers.destination(hold: hold, answer: AlertAnswers.approve)
        } else if let hold, destination == "needs-you" {
            target = AlertAnswers.destination(hold: hold)
        } else {
            target = destination
        }
        guard let target else { return }
        Task { @MainActor in self.open(target) }
    }

    /// iOS's completion handler, handed to the main actor once the answer is sent.
    private struct Done: @unchecked Sendable {
        let call: () -> Void
        init(_ call: @escaping () -> Void) { self.call = call }
    }

    /// With tafs open, an alert still shows, and the app reloads what needs the owner. A halt
    /// or a quiet line only goes in the list: the screen already shows it.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let content = notification.request.content
        completionHandler(content.categoryIdentifier == AlertAnswers.haltCategory || content.interruptionLevel == .passive
                          ? [.list] : [.banner, .list, .sound])
        let hold = content.userInfo["hold_id"] as? String
        Task { @MainActor in
            // The alert can come before KIFF's list of held calls has the hold: Needs you then
            // said "Nothing is waiting" under it (the 755 retest). It reads again until it's there.
            if let hold, let onHoldAlert = self.onHoldAlert { await onHoldAlert(hold) } else { await self.onHeldChanged?() }
        }
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
    static func removeAlerts(forHold hold: String) {
        UNUserNotificationCenter.current().getDeliveredNotifications { delivered in
            let ids = delivered.filter { $0.request.content.userInfo["hold_id"] as? String == hold }.map(\.request.identifier)
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        }
    }
}
