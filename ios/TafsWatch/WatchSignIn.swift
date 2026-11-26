import SwiftUI

/// Where the watch keeps that it was signed out here, and whether it's there.
protocol WatchSignInStore {
    func signedOut() -> Bool
    func set(_ signedOut: Bool)
}

/// v2's Sign out on this watch (docs/watch-app.md, "Sign out on this watch"), the owner's choice of 19 Nov, as Wear OS's: on
/// this watch alone, nothing is sent to the phone, and the phone stays signed in. The watch forgets everything it holds (its
/// Inbox, and what each page showed) and keeps one thing: that it was signed out here, as an empty file in its own
/// Application Support. It shows only "Signed out on this watch" and reads and sends nothing, until **the phone's owner says
/// yes** (row 18): Ask your phone to sign this watch in sends a one-time id, the phone asks "Sign this watch in as you?" with
/// Allow behind its own lock, and only the phone's Allow for that id, within `answerWithin`, signs the watch in. A tap on the
/// watch alone never does.
@MainActor
final class WatchSignIn: ObservableObject {
    typealias Store = WatchSignInStore

    /// Where the ask to the phone stands, on the signed-out screen.
    enum Asking: Equatable {
        case idle
        /// Asked: waiting for the phone owner's Allow or Deny.
        case waiting
        /// The phone's owner said Deny: the watch stays signed out.
        case denied
        /// The phone didn't take the ask: why, in the watch's words.
        case unreached(String)
    }

    /// How long the phone's Allow is taken after the ask: past it, the person asks again.
    nonisolated static let answerWithin: TimeInterval = 10 * 60

    /// The flag as a file, `tafs.signed_out_here`, protected like the Inbox's: its presence is the whole of it.
    struct FileStore: Store {
        let url: URL

        init(url: URL? = nil) {
            self.url = url ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("tafs.signed_out_here")
        }

        func signedOut() -> Bool { FileManager.default.fileExists(atPath: url.path) }

        func set(_ signedOut: Bool) {
            if signedOut {
                try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? Data().write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            } else {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    @Published private(set) var signedOutHere: Bool
    @Published private(set) var asking: Asking = .idle
    private let store: Store
    /// The phone to ask; none in the demo, whose watch signs in on its own tap.
    private let phone: PhoneAsking?
    private let clock: () -> Date
    /// The phone's mark heard with its Allow (`WatchAccountMark.heardAccount`): another account's wipes what's kept first.
    private let account: (String, Date) -> Void
    /// The ask in flight: its one-time id and when it went. In memory only: an app ended mid-ask asks again.
    private var pending: (id: String, at: Date)?
    /// The wait for `answerWithin`, after which an unanswered ask lapses. Real time on the watch; a test calls `lapse`.
    private let sleep: @Sendable (TimeInterval) async -> Void
    private var lapseTimer: Task<Void, Never>?

    init(store: Store = FileStore(), phone: PhoneAsking? = nil, clock: @escaping () -> Date = Date.init,
         account: @escaping (String, Date) -> Void = { _, _ in },
         sleep: @escaping @Sendable (TimeInterval) async -> Void = { try? await Task.sleep(nanoseconds: UInt64(max(0, $0) * 1_000_000_000)) }) {
        self.store = store
        self.phone = phone
        self.clock = clock
        self.account = account
        self.sleep = sleep
        signedOutHere = store.signedOut()
        WatchPushes.shared.listen(self)
    }

    /// Sign out on this watch: the Inbox goes (the ids taken away included), then the flag is kept. What the pages showed is
    /// the caller's to forget (`leave()`), as when the app is put away.
    func signOut(inbox: InboxModel) {
        inbox.clear()
        store.set(true)
        signedOutHere = true
    }

    /// Whether a data push from the phone (a thread, the Inbox's marks, held calls) may be taken: never while signed out here,
    /// so nothing of the account is kept again until the person signs in here. The phone isn't told, and may keep sending; the
    /// watch drops it. An account change (`WatchPush.Kind.account`) is heard either way, since it can only wipe.
    nonisolated static func takesData(signedOutHere: Bool) -> Bool { !signedOutHere }

    /// Ask your phone to sign this watch in: a one-time id to the phone (`sign_in_watch`), which asks its owner. The flag
    /// stays, and the screen says it's waiting for the phone, until the owner's Allow for this id comes back (`heard`).
    func askPhone() async {
        guard signedOutHere, let phone else { return }
        let id = UUID().uuidString.lowercased()
        pending = (id, clock())
        asking = .waiting
        let reached = await phone.reach(WatchRequest(.signInWatch, made: clock(), message: id))
        // Answered while this was on its way, or asked again since: what came of that stands.
        guard signedOutHere, pending?.id == id else { return }
        switch reached {
        case .reply(let reply) where reply.outcome == .ok:
            // Asked: an answer that never comes (the question swiped away and missed, the phone put down) lapses, and the
            // button is back, so the watch is never left waiting.
            let sleep = self.sleep
            lapseTimer?.cancel()
            lapseTimer = Task { [weak self] in
                await sleep(Self.answerWithin)
                guard !Task.isCancelled else { return }
                self?.lapse()
            }
            return
        case .reply(let reply) where reply.outcome == .signedOut:
            asking = .unreached(Words.Watch.signInOnPhone)
        case .reply(let reply) where reply.outcome == .failed:
            // The phone's own words: its notifications are off, so it can't ask.
            asking = .unreached(reply.line ?? Words.Watch.phoneCouldNotReachCloud)
        case .reply:
            asking = .unreached(Words.Watch.phoneCouldNotReachCloud)
        case .failed(let why):
            asking = .unreached(why.waiting)
        }
        pending = nil
    }

    /// An ask with no answer within `answerWithin` lapses: the wait goes, the button is back, and a late Allow is ignored.
    func lapse() {
        guard let pending, clock().timeIntervalSince(pending.at) >= Self.answerWithin else { return }
        self.pending = nil
        if asking == .waiting { asking = .idle }
    }

    /// The phone owner's answer: only for the id this watch asked with, within `answerWithin`, and with a mark the phone
    /// makes. Allow signs the watch in (another account's mark wipes what's kept first); Deny says so and it stays signed
    /// out. Anything else, stale or not asked for, changes nothing.
    func heard(_ answer: WatchSignInAnswer, mark: String?, at: Date) {
        guard signedOutHere, let pending, answer.id == pending.id,
              clock().timeIntervalSince(pending.at) <= Self.answerWithin else { return }
        self.pending = nil
        lapseTimer?.cancel()
        guard answer.allowed else { asking = .denied; return }
        guard WatchMark.isMark(mark), let mark else { asking = .idle; return }
        account(mark, at)
        signIn()
    }

    /// Signed in again: the flag goes, and the watch reads the phone again. Only the phone's Allow calls it (or the demo's
    /// tap, which has no phone).
    func signIn() {
        lapseTimer?.cancel()
        pending = nil
        asking = .idle
        store.set(false)
        signedOutHere = false
    }
}

/// What a watch signed out here shows, and all it shows: the line, and Ask your phone to sign this watch in, then the wait
/// for the phone, or why it didn't sign in.
struct SignedOutHereScreen: View {
    @ObservedObject var signIn: WatchSignIn

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                Text(Words.Watch.signedOutHere)
                    .multilineTextAlignment(.center)
                switch signIn.asking {
                case .waiting:
                    ProgressView()
                    Text(Words.Watch.waitingForPhone)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                case .denied:
                    Text(Words.Watch.signInDenied).font(.footnote).multilineTextAlignment(.center)
                case .unreached(let why):
                    Text(why).font(.footnote).multilineTextAlignment(.center)
                case .idle:
                    EmptyView()
                }
                // Always there, waiting included: a question missed on the phone is asked again with a new id.
                Button(Words.Watch.askPhoneSignIn) {
                    // The demo has no phone to ask: its watch signs in on the tap.
                    if WatchDemo.on { signIn.signIn() } else { Task { await signIn.askPhone() } }
                }
            }
            .padding(.top, 8)
        }
    }
}

/// The phone owner's answer comes as a push (`WatchPush.Kind.signIn`), let through while signed out (`PhoneLink`).
extension WatchSignIn: WatchPushListening {
    func heard(_ push: WatchPush) {
        guard push.kind == .signIn, let answer = push.signIn else { return }
        heard(answer, mark: push.mark, at: push.at)
    }
}
