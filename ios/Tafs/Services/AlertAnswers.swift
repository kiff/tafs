#if os(iOS)
import ActivityKit
#endif
import Foundation
import UserNotifications

/// Approve and Refuse on a hold's notification (kiff/tafs#210, part 2). Both need the device
/// unlocked, and both are the owner's signed answer.
///
/// - Refuse is signed and sent from the notification, as Halt all is from the app: it only
///   takes authority away, so it asks for no Face ID. If it can't be sent from there, a
///   notification says so, and tapping it opens tafs on that hold with Refuse chosen.
/// - Approve is never sent from the notification: it opens tafs on that hold with Approve
///   chosen, and is signed only once Face ID confirms it there.
enum AlertAnswers {
    static let holdCategory = "hold"
    static let haltCategory = "halt"
    static let approve = "approve"
    static let refuse = "refuse"

    /// The actions on tafs' notifications, registered at launch.
    static func categories() -> Set<UNNotificationCategory> {
        let approve = UNNotificationAction(identifier: approve, title: "Approve once…", options: [.authenticationRequired, .foreground])
        let refuse = UNNotificationAction(identifier: refuse, title: "Refuse", options: [.authenticationRequired, .destructive])
        let hold = UNNotificationCategory(identifier: holdCategory, actions: [approve, refuse], intentIdentifiers: [],
                                          hiddenPreviewsBodyPlaceholder: "A taf needs you", options: [])
        let halt = UNNotificationCategory(identifier: haltCategory, actions: [], intentIdentifiers: [], options: [])
        return [hold, halt]
    }

    /// Where a hold's notification opens: that hold, with the answer chosen on it if any.
    static func destination(hold: String, answer: String? = nil) -> String {
        "hold:\(hold)" + (answer.map { ":\($0)" } ?? "")
    }

    /// A hold and the answer chosen on its notification, from `destination(hold:answer:)`.
    static func request(from destination: String) -> AppModel.HoldRequest? {
        let parts = destination.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 2, parts[0] == "hold", !parts[1].isEmpty else { return nil }
        let answer = parts.count > 2 && (parts[2] == approve || parts[2] == refuse) ? parts[2] : nil
        return AppModel.HoldRequest(kiffID: parts[1], answer: answer)
    }
}

#if os(iOS)
/// Refuse, sent from a hold's notification with the session and key the app keeps.
@MainActor
enum NotificationRefuse {
    enum Outcome: Equatable {
        /// Refused; `left` is how many other calls still wait for the owner.
        case refused(left: Int)
        /// No longer held: answered elsewhere, or expired.
        case gone
        /// It couldn't be sent from the notification: why, for the notification that opens tafs.
        case needsApp(String)
    }

    static func refuse(hold: String, kiff: KIFFClient, signer: ApprovalSigning, tenant: String?, keyed: Bool, now: Date) async -> Outcome {
        guard kiff.token != nil, let tenant, keyed, kiff.serverVerifiesSignatures else {
            return .needsApp("Open tafs to answer it: this phone can't sign it from the notification.")
        }
        let held: [KIFFClient.CardException]
        do { held = try await kiff.heldCalls() } catch {
            return .needsApp("tafs couldn't reach KIFF to refuse it. Open tafs to answer it.")
        }
        guard let call = held.first(where: { $0.id == hold }) else { return .gone }
        guard let version = call.accountingVersion else {
            return .needsApp("Open tafs to answer it: KIFF didn't say which version of it you saw.")
        }
        // KIFF's message, as the app signs it: this hold, refused, the version shown, now.
        let signedAt = ISO8601DateFormatter().string(from: now)
        let message = KIFFClient.answerMessage(tenant: tenant, exceptionID: hold, answer: "reject", nextStep: nil,
                                               accountingVersion: version, signedAt: signedAt)
        do {
            let value = try signer.signConfirmed(message)
            try await kiff.reject(hold, signature: KIFFClient.AnswerSignature(signedAt: signedAt, value: value))
        } catch {
            return .needsApp("KIFF didn't take the refusal. Open tafs to answer it.")
        }
        return .refused(left: held.count - 1)
    }

    /// Refuses from the notification with what the app keeps, then puts the lock screen in
    /// step: the hold's alerts go, and the Live Activity ends once nothing else waits.
    static func refuseKept(hold: String) async {
        let kiff = KIFFClient()
        kiff.token = Keychain.load(AppModel.tokenAccount)
        let outcome = await refuse(hold: hold, kiff: kiff, signer: ApprovalSigner(),
                                   tenant: Keychain.load(AppModel.tenantAccount),
                                   keyed: Keychain.load(AppModel.keyedAccount) == "1", now: Date())
        switch outcome {
        case .refused(let left):
            PushDelegate.removeAlerts(forHold: hold)
            if left == 0, #available(iOS 16.1, *) {
                for activity in Activity<HeldActivityAttributes>.activities { await activity.end(dismissalPolicy: .immediate) }
            }
        case .gone:
            PushDelegate.removeAlerts(forHold: hold)
        case .needsApp(let why):
            let content = UNMutableNotificationContent()
            content.title = "Not refused"
            content.body = why
            content.categoryIdentifier = AlertAnswers.holdCategory
            content.interruptionLevel = .timeSensitive
            content.userInfo = ["open": AlertAnswers.destination(hold: hold, answer: AlertAnswers.refuse), "hold_id": hold]
            try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "refuse-\(hold)", content: content, trigger: nil))
        }
    }
}
#endif
