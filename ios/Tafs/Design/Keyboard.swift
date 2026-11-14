import SwiftUI

/// The hardware keyboard's shortcuts (kiff/tafs#210): on iPad, listed when ⌘ is held.
enum KeyCommand: CaseIterable {
    /// ⌘↩ sends what's written, in a taf's chat and in Coach mode.
    case send
    /// Esc closes Coach mode and sheets.
    case close
    /// ⌘N starts a new taf, in Coach mode.
    case newTaf
    /// ⌘1, ⌘2, ⌘3: Tafs, Needs you and Record; ⌘, Account.
    case tafs, needsYou, record, account
    /// ⌘. stops the run in progress.
    case stop
    /// ⌘K opens Coach mode.
    case coach

    var title: String {
        switch self {
        case .send: Words.Common.send
        case .close: Words.Common.close
        case .newTaf: Words.Home.newTaf
        case .tafs: Words.Home.title
        case .needsYou: Words.NeedsYou.title
        case .record: Words.Record.title
        case .account: Words.Settings.title
        case .stop: Words.Work.stopRun
        case .coach: Words.Coach.coachMode
        }
    }

    var key: KeyEquivalent {
        switch self {
        case .send: .return
        case .close: .escape
        case .newTaf: "n"
        case .tafs: "1"
        case .needsYou: "2"
        case .record: "3"
        case .account: ","
        case .stop: "."
        case .coach: "k"
        }
    }

    var modifiers: EventModifiers { self == .close ? [] : .command }
}

extension KeyCommand {
    /// The shortcuts that work on every screen. The others (send, close, stop) belong to the
    /// screen that shows their button.
    static let global: [KeyCommand] = [.newTaf, .tafs, .needsYou, .record, .account, .coach]
}

/// Where each of the global shortcuts sits in the Mac's menu bar (docs/mac-app.md): the
/// system's own menus, so no menu has a name to translate.
enum MacMenu {
    /// The File menu, in place of "New Window".
    static let file: [KeyCommand] = [.newTaf]
    /// The app's menu, in place of "Settings…".
    static let app: [KeyCommand] = [.account]
    /// The View menu.
    static let view: [KeyCommand] = [.tafs, .needsYou, .record, .coach]
}

extension AppModel {
    /// Coach mode works with the owner's KIFF account, once signed in.
    /// Coach only for those who may use it: it was shown to a Viewer, who got no_access, or
    /// Touch ID and then a refusal (the 805 retest).
    var coachAvailable: Bool { signedIn && mode == .kiff && mayUseCoach }

    /// Does what a global shortcut does, from the Mac's menu bar. A screen's own shortcut
    /// (send, close, stop) does nothing here: its button is the screen's.
    func run(_ command: KeyCommand) {
        switch command {
        case .newTaf: startNewTaf()
        case .tafs: requestedTab = .tafs
        case .needsYou: requestedTab = .needsYou
        case .record: requestedTab = .record
        case .account: requestedTab = .account
        case .coach: coachOpen = true
        case .send, .close, .stop: break
        }
    }

    /// Whether a global shortcut can be used now: signed in, and Coach's only where Coach is.
    func allows(_ command: KeyCommand) -> Bool {
        guard signedIn else { return false }
        switch command {
        case .newTaf: return coachAvailable
        case .coach: return coachAvailable && !coachOpen
        case .tafs, .needsYou, .record, .account: return true
        case .send, .close, .stop: return false
        }
    }
}

extension View {
    /// This control's keyboard shortcut (kiff/tafs#210).
    func shortcut(_ command: KeyCommand) -> some View {
        keyboardShortcut(command.key, modifiers: command.modifiers)
    }
}

/// The shortcuts that work on every screen: a new taf, the bar's tabs and Coach mode. Buttons
/// that take no space and that VoiceOver skips, there for the keyboard and its ⌘ list.
struct GlobalKeyCommands: View {
    @EnvironmentObject private var model: AppModel
    @Binding var tab: Tab
    let coachAvailable: Bool

    var body: some View {
        ZStack {
            Button(KeyCommand.newTaf.title) { model.startNewTaf() }.shortcut(.newTaf).disabled(!coachAvailable)
            Button(KeyCommand.tafs.title) { tab = .tafs }.shortcut(.tafs)
            Button(KeyCommand.needsYou.title) { tab = .needsYou }.shortcut(.needsYou)
            Button(KeyCommand.record.title) { tab = .record }.shortcut(.record)
            Button(KeyCommand.account.title) { tab = .account }.shortcut(.account)
            Button(KeyCommand.coach.title) { model.coachOpen = true }.shortcut(.coach).disabled(!coachAvailable || model.coachOpen)
        }
        .opacity(0)
        .frame(width: 0, height: 0)
        .accessibilityHidden(true)
    }
}
