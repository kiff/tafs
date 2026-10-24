#if os(iOS)
import AppIntents
import Foundation

/// "Halt all tafs" from Siri and Shortcuts (kiff/tafs#210): the emergency brake, without
/// finding the button. The same request as Halt all in the app, with the session the app
/// keeps, and like it no Face ID: it only takes authority away. The device must be
/// unlocked. It never fails silently: it says what happened, and when it can't finish it
/// says why and opens tafs on Halt all (iOS 17 and later; before, Siri says to open it).
struct HaltAllTafsIntent: AppIntent {
    static let title: LocalizedStringResource = "Halt all tafs"
    static let description = IntentDescription("Stops every taf at once. Nothing new is sent until you resume them in tafs.")
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        switch await HaltAll.run(cloud: HaltAll.signedInCloud()) {
        case .done(let said):
            return .result(dialog: IntentDialog(stringLiteral: said))
        case .needsApp(let said):
            if #available(iOS 17.0, *) {
                throw needsToContinueInForegroundError(IntentDialog(stringLiteral: said)) { Routes.shared.open("halt") }
            }
            throw HaltAll.Failure(said: said)
        }
    }
}

@available(iOS 17.0, *)
extension HaltAllTafsIntent: ForegroundContinuableIntent {}

/// Siri's phrases for it. Resume isn't one: it stays in the app, with Face ID. Each names
/// the app, as Siri requires; "Stop" and "Pause" are heard where "halt" often isn't, and
/// the app answers to other spoken names too ("my agents", Info.plist's
/// INAlternativeAppNames), in English, Spanish and French (kiff/tafs#236, item 28).
struct TafsShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: HaltAllTafsIntent(), phrases: [
            "Stop my \(.applicationName)",
            "Stop all \(.applicationName)",
            "Stop \(.applicationName)",
            "Pause my \(.applicationName)",
            "Pause \(.applicationName)",
            "Halt my \(.applicationName)",
            "Halt all \(.applicationName)",
        ])
    }
}

/// Halt all as the intent runs it, apart from Siri so it can be tested.
@MainActor
enum HaltAll {
    enum Outcome: Equatable {
        /// Halted, or halting with what's left to confirm: Siri says it.
        case done(String)
        /// It couldn't finish here: Siri says why, and tafs opens on Halt all.
        case needsApp(String)
    }

    struct Failure: Error, CustomLocalizedStringResourceConvertible {
        let said: String
        var localizedStringResource: LocalizedStringResource { "\(said)" }
    }

    /// How often, a second apart, KIFF is asked again while the halt is still halting.
    static let confirmTries = 5

    /// tafs cloud with the session the app keeps; nil when signed out.
    static func signedInCloud() -> TafsCloud? {
        Keychain.load(AppModel.tokenAccount).map { TafsCloud(token: $0) }
    }

    static func run(cloud: TafsCloud?, wait: () async -> Void = { try? await Task.sleep(nanoseconds: 1_000_000_000) }) async -> Outcome {
        guard let cloud else { return .needsApp("You're signed out of tafs. Sign in to halt your tafs.") }
        let count = try? await cloud.tafs().count
        var h: TafsCloud.Halt
        do { h = try await cloud.halt(nil) } catch {
            return .needsApp("tafs couldn't halt your tafs: \(error.localizedDescription) Open tafs to halt them.")
        }
        // KIFF's gateway and planque confirm in a moment: it says halted once they have.
        var tries = 0
        while h.state == .halting, tries < confirmTries {
            await wait()
            if let read = try? await cloud.halted(nil) { h = read }
            tries += 1
        }
        return said(h, count: count)
    }

    static func said(_ h: TafsCloud.Halt, count: Int?) -> Outcome {
        let tafs = count.map { $0 == 1 ? "1 taf" : "\($0) tafs" } ?? "your tafs"
        switch h.state {
        case .halted: return .done("Halted \(tafs). Nothing new is sent until you resume.")
        case .halting: return .done("Halting \(tafs): no run starts. KIFF hasn't confirmed every part yet; tafs shows when it has.")
        case .running: return .needsApp("tafs couldn't halt your tafs. Open tafs to halt them.")
        }
    }
}
#endif
