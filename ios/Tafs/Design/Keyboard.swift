import SwiftUI

/// The hardware keyboard's shortcuts (kiff/tafs#210): on iPad, listed when ⌘ is held.
enum KeyCommand: CaseIterable {
    /// ⌘↩ sends what's written, in a taf's chat and in Coach mode.
    case send
    /// Esc closes Coach mode and sheets.
    case close
    /// ⌘N starts a new taf, in Coach mode.
    case newTaf
    /// ⌘1, ⌘2, ⌘3: Tafs, Needs you and Record.
    case tafs, needsYou, record
    /// ⌘. stops the run in progress.
    case stop
    /// ⌘K opens Coach mode.
    case coach

    var title: String {
        switch self {
        case .send: "Send"
        case .close: "Close"
        case .newTaf: "New taf"
        case .tafs: "Tafs"
        case .needsYou: "Needs you"
        case .record: "Record"
        case .stop: "Stop the run"
        case .coach: "Coach mode"
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
        case .stop: "."
        case .coach: "k"
        }
    }

    var modifiers: EventModifiers { self == .close ? [] : .command }
}

extension View {
    /// This control's keyboard shortcut (kiff/tafs#210).
    func shortcut(_ command: KeyCommand) -> some View {
        keyboardShortcut(command.key, modifiers: command.modifiers)
    }
}

/// The shortcuts that work on every screen: a new taf, the tabs and Coach mode. Buttons
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
            Button(KeyCommand.coach.title) { model.coachOpen = true }.shortcut(.coach).disabled(!coachAvailable || model.coachOpen)
        }
        .opacity(0)
        .frame(width: 0, height: 0)
        .accessibilityHidden(true)
    }
}
