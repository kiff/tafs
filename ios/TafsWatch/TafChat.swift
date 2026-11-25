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
    /// The watch's own recording, behind `WatchVoice.on` (row 44, A): its text then opens Check before sending.
    @State private var recording = false

    var body: some View {
        ScrollViewReader { proxy in
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
                TafChatLineRow(line: line, zone: chat.zone) { Task { await chat.openOnPhone() } }
            }
            ForEach(Array(chat.turns.enumerated()), id: \.element.id) { i, turn in
                if ChatTime.startsDay(days, at: chat.history.count + i, calendar: chat.calendar) { WatchChatDay(date: turn.at, calendar: chat.calendar) }
                TafTurnRow(turn: turn, taf: taf.name, whole: chat.whole[turn.id], zone: chat.zone,
                           readMore: { Task { await chat.readMore(turn.id) } },
                           viewOnPhone: { Task { await chat.openOnPhone() } })
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
                // Right under the newest message: the microphone and the keyboard (the owner's call 4).
                ThreadInput(speak: Words.Chat.messageTaf(taf: taf.name), listen: {
                    // The watch's own recording when it's on (row 44, A), else the system's dictation in one tap.
                    if WatchVoice.on, chat.listensFirst {
                        recording = true
                    } else {
                        Task { if await WatchDictation.talk(to: chat) { asking = true } }
                    }
                }, typed: { words in
                    chat.review(words)
                    if chat.reviewing { asking = true }
                })
            }
            Color.clear.frame(height: 1).listRowBackground(Color.clear).id(WatchThread.newest)
        }
        .defaultScrollAnchor(.bottom)
        // Opens on the newest message, and stays there as messages come in (the owner's calls 2 and 3).
        .onChange(of: WatchThread.shape(chat)) { _, _ in proxy.scrollTo(WatchThread.newest, anchor: .bottom) }
        }
        .task {
            chat.reopen()
            await chat.loadHistory()
        }
        .sheet(isPresented: $asking) {
            CoachAsk(model: chat, title: Words.Chat.messageTaf(taf: taf.name), answerer: taf.name)
        }
        .sheet(isPresented: $recording, onDismiss: { if chat.reviewing { asking = true } }) {
            VoiceRecordScreen(model: VoiceModel { words in chat.review(words) })
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
    var viewOnPhone: () -> Void = {}

    var body: some View {
        if line.mine && !line.more {
            MineBubble(said: line.text, at: line.at, zone: zone)
                .listRowBackground(Color.clear)
        } else {
            bubble
        }
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: line.text)
                .font(.footnote)
            if line.more {
                Text(Words.Watch.moreOnPhone).font(.caption2).foregroundStyle(.secondary)
                ViewOnPhone(open: viewOnPhone)
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
    var viewOnPhone: () -> Void = {}

    var body: some View {
        VStack(spacing: 4) {
            MineBubble(said: turn.said, at: turn.at, zone: zone)
            tafSide
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .listRowBackground(Color.clear)
    }

    @ViewBuilder private var tafSide: some View {
        switch turn.answer {
        case .sent:
            // The ✓ under the bubble says it went; nothing more until the taf starts.
            EmptyView()
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
                    ViewOnPhone(open: viewOnPhone)
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
                    if answer?.approveOnPhone != true { ViewOnPhone(open: viewOnPhone) }
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

/// Your own message in a thread (the owner's call 4, as a messaging app draws it): the words in a bubble on the right, and
/// under it, small, when it went and ✓, so a message sent from the watch reads as sent where it sits.
struct MineBubble: View {
    let said: String
    let at: Date
    var zone: TimeZone = .current
    var lineLimit: Int? = nil

    var body: some View {
        VStack(alignment: .trailing, spacing: 2) {
            Text(verbatim: said)
                .font(.footnote)
                .lineLimit(lineLimit)
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .foregroundStyle(WatchLook.onMineGround)
                .background(WatchLook.mineGround, in: RoundedRectangle(cornerRadius: 12))
            HStack(spacing: 2) {
                Text(ChatTime.time(at, zone: zone)).monospacedDigit()
                Image(systemName: "checkmark").accessibilityLabel(Words.Watch.sent)
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}

/// View on phone, inline where the rest of an answer is (the owner's call 5): the phone opens the chat (`CoachModel.openOnPhone`).
struct ViewOnPhone: View {
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            Label(Words.Watch.viewOnPhone, systemImage: "iphone")
                .font(.caption2.weight(.semibold))
        }
        .buttonStyle(.plain)
        .foregroundStyle(WatchLook.coach)
    }
}

/// Under the newest message (the owner's call 4): the microphone and the keyboard, side by side, round. The keyboard's words
/// open Check before sending, as the microphone's do.
struct ThreadInput: View {
    /// What VoiceOver says for the microphone: "Ask Coach", or "Message <taf>".
    let speak: String
    let listen: () -> Void
    let typed: (String) -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: listen) {
                Image(systemName: "mic.fill")
                    .font(.title3)
                    .frame(width: 44, height: 44)
                    .background(WatchLook.send, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(speak)
            TextFieldLink(prompt: Text(Words.Watch.typeHint)) {
                Image(systemName: "keyboard")
                    .font(.title3)
                    .frame(width: 44, height: 44)
                    .background(Color.gray.opacity(0.35), in: Circle())
            } onSubmit: { words in
                typed(words)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Words.Watch.type)
        }
        .frame(maxWidth: .infinity)
        .listRowBackground(Color.clear)
    }
}

/// Keeping a thread on its newest message (the owner's calls 2 and 3).
@MainActor
enum WatchThread {
    /// The id of the thread's last row, under the microphone and the keyboard.
    static let newest = "thread.newest"

    /// What changes when a message comes in or an answer moves on: how many lines, and the newest turn's state.
    static func shape(_ chat: CoachModel) -> [String] {
        var shape = ["\(chat.history.count)", "\(chat.turns.count)"]
        if let last = chat.turns.last { shape.append("\(last.answer)") }
        return shape
    }
}
