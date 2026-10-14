import ActivityKit
import Foundation
import UserNotifications

/// Keeps the lock screen in step with what needs the owner: a notification for
/// each newly held call, and one Live Activity counting down the first one.
///
/// Notifications here are local, posted by the app while it runs. Push from a
/// server (so a held call reaches a closed app) comes with the tafs runtime.
@MainActor
final class LockScreen {
    private var notified: Set<UUID> = []
    private var askedPermission = false

    func update(held: [HeldCall], tafs: [Taf]) {
        notifyNew(held, tafs)
        if #available(iOS 16.1, *) { updateActivity(held, tafs) }
    }

    // MARK: Notifications

    private func notifyNew(_ held: [HeldCall], _ tafs: [Taf]) {
        let fresh = held.filter { !notified.contains($0.id) }
        notified = Set(held.map(\.id))
        guard !fresh.isEmpty else { return }
        Task {
            let center = UNUserNotificationCenter.current()
            if !askedPermission {
                askedPermission = true
                _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
            }
            for call in fresh {
                let content = UNMutableNotificationContent()
                let name = tafs.first { $0.id == call.tafID }?.name ?? "A taf"
                content.title = "\(name) needs you"
                content.body = [call.proposed.amount?.formatted, call.proposed.summary, "·", call.reason]
                    .compactMap { $0 }.joined(separator: " ")
                content.sound = .default
                content.userInfo = ["open": "needs-you"]
                content.interruptionLevel = .active
                let request = UNNotificationRequest(identifier: call.id.uuidString, content: content, trigger: nil)
                try? await center.add(request)
            }
        }
    }

    // MARK: Live Activity

    @available(iOS 16.1, *)
    private func updateActivity(_ held: [HeldCall], _ tafs: [Taf]) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let current = Activity<HeldActivityAttributes>.activities.first
        guard let first = held.sorted(by: { $0.expiresAt < $1.expiresAt }).first else {
            Task { await current?.end(dismissalPolicy: .immediate) }
            return
        }
        let taf = tafs.first { $0.id == first.tafID }
        let state = HeldActivityAttributes.ContentState(
            tafName: taf?.name ?? "A taf",
            tafInitial: taf?.initial ?? "t",
            summary: first.proposed.summary,
            amount: first.proposed.amount?.formatted,
            reason: first.reason,
            expiresAt: first.expiresAt,
            waiting: held.count
        )
        if let current {
            Task { await current.update(using: state) }
        } else {
            _ = try? Activity.request(attributes: HeldActivityAttributes(), contentState: state)
        }
    }
}
