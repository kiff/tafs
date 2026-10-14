import ActivityKit
import Foundation

/// The Live Activity for the held call that needs the owner first: on the lock
/// screen and in the Dynamic Island, with its countdown. Shared by the app and
/// its widget extension.
struct HeldActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var tafName: String
        var tafInitial: String
        var summary: String
        var amount: String?
        var reason: String
        var expiresAt: Date
        /// How many calls are waiting in all.
        var waiting: Int
    }
}
