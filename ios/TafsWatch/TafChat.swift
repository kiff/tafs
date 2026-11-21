import SwiftUI

/// Chatting with a taf from the wrist (docs/watch-app.md, "Chatting with a taf"; the owner's picks 1A and 2A). Your tafs opens
/// a taf's own chat, with its page one swipe below. The words go into that taf's chat as the person, through the phone, never
/// to Coach. Approvals stay on the phone, and nothing of the chat is kept on the watch.
enum WatchTafChat {
    /// On since the privacy paragraph went live (20 Nov). tafs cloud's `TAFS_WATCH_TAF_CHAT` still gates each account: where
    /// it's off, Send answers "Open on your phone" and nothing goes to Coach.
    static let on = true
}

/// What a taf's chat lets the person do, by its state: halted, nothing runs, so nothing is sent ("Resume on your phone");
/// paused, the message is taken and read when it's back (2A); otherwise, Speak, Type and Quick reply.
enum TafChatSend: Equatable {
    case open
    case paused
    case halted

    static func of(_ taf: WatchTaf) -> TafChatSend {
        if taf.halted { return .halted }
        return taf.paused == true ? .paused : .open
    }
}

/// A taf's chat, then its page one swipe below (1A).
struct TafChatScreen: View {
    let taf: WatchTaf
    @ObservedObject var chat: CoachModel
    let page: () -> TafPageModel
    let openInbox: () -> Void

    var body: some View {
        TabView {
            TafChatPage(taf: taf, chat: chat)
            TafPageScreen(model: page(), openInbox: openInbox)
        }
        .tabViewStyle(.verticalPage)
        .navigationTitle(taf.name)
    }
}

/// The chat itself: the taf's state, this visit's turns with the taf's answers, then the microphone (or, halted, "Resume on
/// your phone"). The newest turn sits just over the microphone, as Coach's do.
struct TafChatPage: View {
    let taf: WatchTaf
    @ObservedObject var chat: CoachModel
    @State private var asking = false

    var body: some View {
        List {
            if let line = taf.line {
                Text(line)
                    .font(.footnote)
                    .foregroundStyle(TafChatSend.of(taf) == .halted ? WatchLook.danger : TafChatSend.of(taf) == .paused ? WatchLook.held : WatchLook.taf)
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
            }
            // The last turns from before this visit, as the phone shows them, with a header where a day starts (row 44, C).
            let days = chat.history.map(\.at) + chat.turns.map(\.at)
            ForEach(Array(chat.history.enumerated()), id: \.element.id) { i, line in
                if ChatTime.startsDay(days, at: i, calendar: chat.calendar) { WatchChatDay(date: line.at, calendar: chat.calendar) }
                TafChatLineRow(line: line, zone: chat.zone)
            }
            ForEach(Array(chat.turns.enumerated()), id: \.element.id) { i, turn in
                if ChatTime.startsDay(days, at: chat.history.count + i, calendar: chat.calendar) { WatchChatDay(date: turn.at, calendar: chat.calendar) }
                TafTurnRow(turn: turn, taf: taf.name, whole: chat.whole[turn.id], zone: chat.zone) {
                    Task { await chat.readMore(turn.id) }
                }
            }
            switch TafChatSend.of(taf) {
            case .halted:
                Label(Words.Watch.resumeOnPhone, systemImage: "iphone")
                    .font(.footnote)
                    .foregroundStyle(WatchLook.danger)
                    .listRowBackground(Color.clear)
            case .paused, .open:
                if TafChatSend.of(taf) == .paused {
                    // 2A: it still takes the message; only its schedule is off, so it answers now.
                    Text(Words.Watch.pausedStillAnswers)
                        .font(.footnote)
                        .foregroundStyle(WatchLook.held)
                        .listRowBackground(Color.clear)
                }
                Button { Task { await WatchDictation.talk(to: chat); asking = true } } label: {
                    Label(Words.Chat.messageTaf(taf: taf.name), systemImage: "mic.fill")
                        .font(.headline)
                }
            }
        }
        .defaultScrollAnchor(.bottom)
        .task { await chat.loadHistory() }
        .sheet(isPresented: $asking) {
            CoachAsk(model: chat, title: Words.Chat.messageTaf(taf: taf.name), answerer: taf.name)
        }
    }
}

/// The header where a chat's day starts on the wrist: Today, Yesterday, then the date (`ChatTime.day`).
struct WatchChatDay: View {
    let date: Date
    let calendar: Calendar
    var body: some View {
        Text(ChatTime.day(date, calendar: calendar))
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear)
    }
}

/// A turn from before this visit: yours orange on the right, the taf's green on the left, the rest on the phone if cut.
struct TafChatLineRow: View {
    let line: WatchChatLine
    var zone: TimeZone = .current

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: line.text)
                .font(.footnote)
            if line.more {
                Text(Words.Watch.moreOnPhone).font(.caption2).foregroundStyle(.secondary)
            }
            // When it was sent (row 44, C), small, under its words.
            Text(ChatTime.time(line.at, zone: zone)).font(.caption2).monospacedDigit().opacity(0.75)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .foregroundStyle(line.mine ? WatchLook.onMineGround : WatchLook.onTafGround)
        .background(line.mine ? WatchLook.mineGround : WatchLook.tafGround, in: RoundedRectangle(cornerRadius: 12))
        .frame(maxWidth: .infinity, alignment: line.mine ? .trailing : .leading)
        .listRowBackground(Color.clear)
    }
}

/// One turn of a taf's chat: your words, then the taf's answer in green. A long answer shows its first part with Read more,
/// and the tap opens the whole answer in place, read only then (the owner's 01:10); if it can't be read, the rest is on the
/// phone.
struct TafTurnRow: View {
    let turn: CoachModel.Turn
    let taf: String
    let whole: CoachModel.Whole?
    var zone: TimeZone = .current
    let readMore: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            Text(ChatTime.time(turn.at, zone: zone))
                .font(.caption2)
                .monospacedDigit()
                .foregroundStyle(.secondary)
            Text(verbatim: turn.said)
                .font(.footnote)
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .foregroundStyle(WatchLook.onMineGround)
                .background(WatchLook.mineGround, in: RoundedRectangle(cornerRadius: 12))
                .frame(maxWidth: .infinity, alignment: .trailing)
            tafSide
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .listRowBackground(Color.clear)
    }

    @ViewBuilder private var tafSide: some View {
        switch turn.answer {
        case .sent:
            Label(Words.Watch.sent, systemImage: "checkmark")
                .font(.caption2)
                .foregroundStyle(WatchLook.done)
        case .answering:
            HStack(spacing: 4) {
                ProgressView().frame(width: 14, height: 14)
                Text(Words.Chat.tafIsAnswering(taf: taf)).font(.caption2).foregroundStyle(WatchLook.taf)
            }
            .accessibilityElement(children: .combine)
        case .answered(let answer):
            VStack(alignment: .leading, spacing: 4) {
                if let text = Self.shown(answer, whole: whole), !text.isEmpty {
                    Text(verbatim: text)
                        .font(.footnote)
                        .foregroundStyle(WatchLook.onTafGround)
                }
                if answer?.approveOnPhone == true {
                    Label(Words.Watch.approveOnPhone, systemImage: "iphone")
                        .font(.caption2)
                        .foregroundStyle(WatchLook.held)
                }
                switch Self.foot(answer, whole: whole) {
                case .readMore:
                    Button(Words.Watch.readMore, action: readMore)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(WatchLook.coach)
                        .buttonStyle(.plain)
                case .reading:
                    ProgressView().frame(width: 14, height: 14)
                case .onPhone:
                    Text(Words.Watch.moreOnPhone).font(.caption2).foregroundStyle(.secondary)
                case nil:
                    EmptyView()
                }
                // When the answer came (row 44, C), small, under it, as Wear OS shows it.
                if let at = answer?.at {
                    Text(ChatTime.time(at, zone: zone)).font(.caption2).monospacedDigit().opacity(0.75)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(WatchLook.tafGround, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    enum Foot: Equatable { case readMore, reading, onPhone }

    /// The answer's words as shown: the whole once Read more has it, else the first part.
    static func shown(_ answer: WatchCoachAnswer?, whole: CoachModel.Whole?) -> String? {
        if case .read(let all)? = whole { return all.text }
        return answer?.text
    }

    /// Under the answer: Read more while there's more and it hasn't been read, the wait while it's read, "The rest is on your
    /// phone" when it couldn't be (or there's no answer yet), nothing once it's whole.
    static func foot(_ answer: WatchCoachAnswer?, whole: CoachModel.Whole?) -> Foot? {
        switch whole {
        case .read?: return nil
        case .reading?: return .reading
        case .onPhone?: return .onPhone
        case nil:
            guard let answer else { return .onPhone }
            return answer.more ? .readMore : nil
        }
    }
}
