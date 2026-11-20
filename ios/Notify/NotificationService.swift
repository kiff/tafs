import Foundation
import UserNotifications

/// Fills in a hold's notification before it shows (kiff/tafs#210): the taf, what it asks
/// and when it lapses, read from KIFF with the app's session (`HoldNotification`). iOS
/// gives it about 30 seconds; when they run out, the notification shows as it came.
final class NotificationService: UNNotificationServiceExtension, @unchecked Sendable {
    private let lock = NSLock()
    private var deliver: ((UNNotificationContent) -> Void)?
    private var content: UNMutableNotificationContent?

    /// The content, handed to the main actor that fills it; only `finish` reads it after.
    private struct Handed: @unchecked Sendable { let content: UNMutableNotificationContent }

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        guard let copy = request.content.mutableCopy() as? UNMutableNotificationContent else {
            contentHandler(request.content)
            return
        }
        lock.lock()
        deliver = contentHandler
        content = copy
        lock.unlock()
        let handed = Handed(content: copy)
        Task { @MainActor in
            await HoldNotification.fillKept(handed.content)
            self.finish()
        }
    }

    override func serviceExtensionTimeWillExpire() {
        finish()
    }

    /// Shows the notification once, filled in or as it came.
    private func finish() {
        lock.lock()
        let deliver = self.deliver, content = self.content
        self.deliver = nil
        lock.unlock()
        guard let deliver, let content else { return }
        // A push sits on the lock screen beside an amount: no direction character from tafs cloud or KIFF reaches it,
        // filled in or as it came (qa-kiff/agentgabo, 20 Nov; KIFFMapping.visible's set).
        content.title = FileText.withoutDirection(content.title)
        content.subtitle = FileText.withoutDirection(content.subtitle)
        content.body = FileText.withoutDirection(content.body)
        deliver(content)
    }
}
