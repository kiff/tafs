import SwiftUI

/// W11, "From your watch" (docs/watch-app.md, "W11"): in Coach's Previous conversations, the watch's conversations, one a
/// day, newest first, apart from the chats started on the phone. Each opens that day.
struct WatchDaysSection: View {
    @ObservedObject var model: WatchDaysModel

    var body: some View {
        if !model.days.isEmpty {
            Section {
                ForEach(model.days) { day in
                    NavigationLink {
                        WatchDayView(model: model, date: day.date)
                    } label: {
                        HStack {
                            Label(model.title(day.date), systemImage: "applewatch")
                                .scaledFont(15).foregroundColor(Theme.ink)
                            Spacer()
                            Text(verbatim: "\(day.count)").scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                        }
                    }
                    .listRowBackground(Theme.panel)
                }
            } header: {
                Text(Words.Coach.Chats.fromYourWatch)
            }
        }
    }
}

/// One day from the watch, oldest first: each thing said, labelled as sent from the watch, then where it went and what came
/// of it. Read only: a reply goes on in a Coach chat, as any other.
struct WatchDayView: View {
    @ObservedObject var model: WatchDaysModel
    let date: String

    var body: some View {
        List {
            switch model.day {
            case .reading:
                ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
            case .failed(let why):
                Text(why).scaledFont(14).foregroundColor(Theme.no).listRowBackground(Color.clear)
                Button(Words.Common.tryAgain) { Task { await model.open(date) } }.listRowBackground(Theme.panel)
            case .loaded(let turns):
                ForEach(turns) { turn in
                    WatchTurnRow(turn: turn, zone: model.zone, whole: model.whole[turn.id]) {
                        Task { await model.readMore(turn.id) }
                    }
                    .padding(.leading, turn.replyTo == nil ? 0 : 16)
                    .listRowBackground(Theme.panel)
                }
            }
        }
        .hiddenScrollBackground()
        .screenBackground()
        .navigationTitle(model.title(date))
        .inlineTitle()
        .task { await model.open(date) }
        .onDisappear { model.close() }
    }
}

/// One turn: "⌚ Sent from your watch" and when, the person's words, then the taf that took it and its line, or tafs
/// cloud's words for where it is.
struct WatchTurnRow: View {
    let turn: TafsCloud.WatchTurn
    var zone: TimeZone = .current
    var whole: WatchDaysModel.Whole? = nil
    var readMore: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(Words.Coach.Chats.sentFromYourWatch)
                if let at = WatchTurnRow.time(turn.saidAt, zone: zone) { Text(verbatim: "· " + at) }
            }
            .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
            Text(verbatim: turn.said).scaledFont(15).foregroundColor(Theme.ink)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                if let taf = turn.taf { Text(verbatim: taf.name).scaledFont(13, relativeTo: .footnote).bold() }
                Text(verbatim: WatchTurnRow.shown(turn, whole: whole)).scaledFont(13, relativeTo: .footnote)
            }
            .foregroundColor(Theme.dim)
            // Read more (the owner's call 8): the whole answer in place, read only on the tap.
            if WatchTurnRow.offersMore(turn, whole: whole) {
                if whole == .reading {
                    ProgressView()
                } else {
                    Button(Words.Watch.readMore, action: readMore)
                        .scaledFont(13, relativeTo: .footnote)
                        .buttonStyle(.borderless)
                }
            }
        }
        .accessibilityElement(children: .contain)
    }

    /// The turn's outcome as shown: the whole answer once Read more has it, else tafs cloud's line.
    static func shown(_ turn: TafsCloud.WatchTurn, whole: WatchDaysModel.Whole?) -> String {
        if case .read(let all)? = whole { return all }
        return turn.outcome
    }

    /// Read more is offered while the line is cut and the whole isn't read yet.
    static func offersMore(_ turn: TafsCloud.WatchTurn, whole: WatchDaysModel.Whole?) -> Bool {
        guard turn.more else { return false }
        if case .read? = whole { return false }
        return true
    }

    /// The time it was said, in the phone's own short form, in `zone` (the account's, as the day is); nil for one tafs
    /// cloud didn't give.
    static func time(_ at: String, zone: TimeZone = .current, locale: Locale = .current) -> String? {
        let parse = ISO8601DateFormatter()
        parse.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = parse.date(from: at) ?? ISO8601DateFormatter().date(from: at) else { return nil }
        return date.formatted(Date.FormatStyle(date: .omitted, time: .shortened, locale: locale, timeZone: zone))
    }
}
