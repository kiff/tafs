import SwiftUI

/// v2's Account (docs/watch-app.md, "Step (vi)"), as Wear OS's: read from the phone (`account`) each time the page is shown,
/// in memory only. Who is signed in, the plan's line as the phone's Account shows it, this watch's language and version, and
/// what the watch holds and can't do.
@MainActor
final class AccountPageModel: ObservableObject {
    enum State: Equatable {
        case reading
        case loaded(WatchAccount)
        case unreachable(PhoneFailure)
        case signedOut
        /// A phone from before the read: "Update tafs on your phone".
        case updatePhone
        case failed(String)
    }

    @Published private(set) var state: State = .reading
    private let phone: PhoneAsking
    private let clock: () -> Date
    /// Counts the reads asked: an answer for one asked before the last, or after the page left, is never drawn.
    private var asked = 0

    init(phone: PhoneAsking, clock: @escaping () -> Date = Date.init) {
        self.phone = phone
        self.clock = clock
    }

    func load() async {
        asked += 1
        let this = asked
        if case .loaded = state {} else { state = .reading }
        let reached = await phone.reach(WatchRequest(.account, made: clock()))
        guard this == asked else { return }
        let reply: WatchReply
        switch reached {
        case .reply(let answered): reply = answered
        case .failed(let why): state = .unreachable(why); return
        }
        switch reply.outcome {
        case .ok:
            guard let account = reply.account else { state = .failed(reply.line ?? Words.Watch.phoneCouldNotReachCloud); return }
            state = .loaded(account)
        case .signedOut:
            state = .signedOut
        case .failed:
            state = .failed(reply.line ?? Words.Watch.phoneCouldNotReachCloud)
        case .notYet:
            state = .updatePhone
        case .refused, .stale, .answered:
            state = .unreachable(.notReachable)
        }
    }

    /// The page left the screen: nothing of it is kept.
    func leave() {
        asked += 1
        state = .reading
    }

    /// This watch's language, in itself.
    static func language(_ locale: Locale = .current) -> String {
        let code = locale.language.languageCode?.identifier ?? locale.identifier
        return locale.localizedString(forLanguageCode: code)?.localizedCapitalized ?? code
    }

    /// The account's name as Signed in shows it under the email: none when it's the email itself (a personal account).
    static func accountName(_ account: WatchAccount) -> String? {
        guard let name = account.account?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return nil }
        guard let email = account.email?.trimmingCharacters(in: .whitespacesAndNewlines) else { return name }
        return name.caseInsensitiveCompare(email) == .orderedSame ? nil : name
    }

    /// This watch app's version.
    static func version(_ bundle: Bundle = .main) -> String {
        bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }
}

extension TafsModel {
    /// Account, read through the same phone as Your tafs.
    func accountPage() -> AccountPageModel {
        AccountPageModel(phone: phone, clock: clock)
    }
}

/// Account: Signed in (the email and the account's name), Plan, the watch's language and version, Sign out on this watch
/// (asked once more), and the trust line.
struct WatchAccountScreen: View {
    @StateObject private var model: AccountPageModel
    private let signOut: () -> Void
    @State private var confirming = false

    init(model: @autoclosure @escaping () -> AccountPageModel, signOut: @escaping () -> Void = {}) {
        _model = StateObject(wrappedValue: model())
        self.signOut = signOut
    }

    var body: some View {
        NavigationStack {
            List {
                switch model.state {
                case .reading:
                    ProgressView()
                case .loaded(let account):
                    if account.email != nil || account.account != nil {
                        Section(Words.Watch.signedIn) {
                            if let email = account.email { Text(verbatim: email).font(.footnote) }
                            // A personal account is named by its email: the name only when it says something more.
                            if let name = AccountPageModel.accountName(account) { Text(verbatim: name).font(.footnote) }
                        }
                    }
                    if let plan = account.plan {
                        Section(Words.Settings.plan) { Text(plan).font(.footnote) }
                    }
                case .unreachable(let why):
                    notice(why.line)
                case .signedOut:
                    notice(Words.Watch.signInOnPhone)
                case .updatePhone:
                    notice(Words.Watch.updatePhone)
                case .failed(let why):
                    notice(why)
                }
                Section {
                    LabeledContent(Words.Settings.languageTitle, value: AccountPageModel.language())
                    LabeledContent(Words.Settings.version, value: AccountPageModel.version())
                }
                // The watch's own: it works with the phone out of reach too.
                Button(Words.Settings.signOut, role: .destructive) { confirming = true }
                    .tint(WatchLook.danger)
                Text(Words.Watch.trust)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .listRowBackground(Color.clear)
            }
            .navigationTitle(Words.Settings.title)
            .confirmationDialog(Words.Watch.signOutQ, isPresented: $confirming, titleVisibility: .visible) {
                Button(Words.Settings.signOut, role: .destructive) { signOut() }
            } message: {
                Text(Words.Watch.signOutSub)
            }
        }
        .task { await model.load() }
        .onDisappear { model.leave() }
    }

    /// Why the phone's part isn't shown, with Try again; the watch's own lines stay below it.
    @ViewBuilder private func notice(_ line: String) -> some View {
        Text(line).font(.footnote)
        Button(Words.Common.tryAgain) { Task { await model.load() } }
    }
}
