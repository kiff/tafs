import SwiftUI

/// v2's Inbox page (docs/watch-app.md, "Step (iv)"): the lines kept on the watch, newest first, each with its taf, its words and
/// a blue dot while it's unread; Dismiss on each, and Clear read. With no passcode on this watch nothing is kept, and the page
/// is Needs you as the phone answered now, so a call that needs the person is never hidden behind "Nothing new".
struct InboxScreen: View {
    @ObservedObject var inbox: InboxModel
    @ObservedObject var needsYou: NeedsYouModel

    var body: some View {
        if inbox.kept {
            NavigationStack {
                List {
                    if inbox.lines.isEmpty {
                        Text(Words.Watch.inboxEmpty)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(inbox.lines) { line in
                        NavigationLink {
                            InboxLineScreen(line: line, inbox: inbox, needsYou: needsYou)
                        } label: {
                            InboxRow(line: line)
                        }
                        .swipeActions {
                            Button(Words.Watch.dismiss, role: .destructive) { inbox.dismiss(line.id) }
                        }
                    }
                    if inbox.lines.contains(where: \.read) {
                        Button(Words.Watch.clearRead) { inbox.clearRead() }
                    }
                }
                .navigationTitle(Words.Watch.Page.inbox)
            }
            .task { await needsYou.load() }
        } else {
            NeedsYouScreen(model: needsYou)
        }
    }
}

/// One line: its taf (Coach for an answer), its words, and a blue dot while it's unread.
struct InboxRow: View {
    let line: InboxLine

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            Circle()
                .fill(line.read ? Color.clear : WatchLook.coach)
                .frame(width: 6, height: 6)
                .padding(.top, 6)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(InboxLineScreen.title(line))
                    .font(.headline)
                Text(line.line)
                    .font(.footnote)
                    .lineLimit(2)
            }
        }
    }
}

/// A line opened, which reads it: Coach's answer whole; a held call whole while Needs you still holds it, with Refuse and
/// Approve on your phone as Needs you has them, else "Already answered, or it ended.". Dismiss at the foot.
struct InboxLineScreen: View {
    let line: InboxLine
    @ObservedObject var inbox: InboxModel
    @ObservedObject var needsYou: NeedsYouModel
    @Environment(\.dismiss) private var close

    /// The line's heading: its taf, Coach for Coach's answer, or a taf when the phone couldn't name it.
    static func title(_ line: InboxLine) -> String {
        line.title ?? (line.kind == .answer ? Words.Coach.title : Words.NeedsYou.aTaf)
    }

    /// The held call, while Needs you as the phone read it still lists it.
    static func held(_ line: InboxLine, in state: NeedsYouModel.State) -> WatchNeedsYou.Call? {
        guard line.kind == .held, case .loaded(let page) = state else { return nil }
        return page.calls.first { $0.id == line.ref }
    }

    var body: some View {
        List {
            switch line.kind {
            case .answer:
                VStack(alignment: .leading, spacing: 4) {
                    Text(Self.title(line)).font(.headline)
                    Text(line.line)
                }
            case .held:
                if let call = Self.held(line, in: needsYou.state) {
                    // The call whole, as the phone's Needs you words it; Refuse here, Approve only on the phone.
                    HeldCallRow(call: call, answer: needsYou.answers[call.id]) {
                        Task { await needsYou.refuse(call.id) }
                    }
                    Text(Words.Watch.nothingSentUntil)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(Self.title(line)).font(.headline)
                        Text(line.line)
                        Text(Words.Watch.alreadyAnswered)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Button(Words.Watch.dismiss, role: .destructive) {
                inbox.dismiss(line.id)
                close()
            }
        }
        .navigationTitle(Words.Watch.Page.inbox)
        .onAppear { inbox.markRead(line.id) }
    }
}
