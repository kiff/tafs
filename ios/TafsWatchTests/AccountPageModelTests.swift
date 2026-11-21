import XCTest
@testable import TafsWatch

/// v2's Account (docs/watch-app.md, "Step (vi)"): read from the phone each time it's shown, in memory only.
@MainActor
final class AccountPageModelTests: XCTestCase {
    private let now = Date(timeIntervalSinceReferenceDate: 813_667_500)

    private final class Phone: PhoneAsking {
        var reply: WatchReply?
        var asked: [WatchRequest] = []
        init(_ reply: WatchReply?) { self.reply = reply }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return reply
        }
    }

    /// A personal account is named by its email: Signed in shows it once (mac-air's simulator walk-through, 19 Nov).
    func testTheAccountsNameShowsOnlyWhenItIsntTheEmail() {
        XCTAssertNil(AccountPageModel.accountName(WatchAccount(email: "owner@example.com", account: "Owner@Example.com ")))
        XCTAssertEqual(AccountPageModel.accountName(WatchAccount(email: "owner@example.com", account: "Brio Bikes")), "Brio Bikes")
        XCTAssertEqual(AccountPageModel.accountName(WatchAccount(email: nil, account: "Brio Bikes")), "Brio Bikes")
        XCTAssertNil(AccountPageModel.accountName(WatchAccount(email: "owner@example.com", account: "  ")))
    }

    func testTheAccountIsReadFromThePhoneEachTime() async {
        let account = WatchAccount(email: "owner@example.com", account: "Shop", plan: "Solo · 3 tafs")
        let phone = Phone(WatchReply(.ok, account: account))
        let model = AccountPageModel(phone: phone, clock: { self.now })
        await model.load()
        XCTAssertEqual(phone.asked, [WatchRequest(.account, made: now)])
        XCTAssertEqual(model.state, .loaded(account))
        model.leave()
        XCTAssertEqual(model.state, .reading, "nothing of it kept once the page leaves")
        await model.load()
        XCTAssertEqual(phone.asked.count, 2, "read again when shown again")
    }

    /// A phone that holds its reply until told.
    private final class HeldPhone: PhoneAsking {
        var held: CheckedContinuation<WatchReply?, Never>?
        func ask(_ request: WatchRequest) async -> WatchReply? {
            await withCheckedContinuation { held = $0 }
        }
        func answer(_ reply: WatchReply?) {
            held?.resume(returning: reply)
            held = nil
        }
    }

    /// An answer that lands after the page left is never drawn (agentgabo's read).
    func testALateAnswerIsNeverDrawn() async {
        let phone = HeldPhone()
        let model = AccountPageModel(phone: phone, clock: { self.now })
        let reading = Task { await model.load() }
        while phone.held == nil { await Task.yield() }
        model.leave()
        phone.answer(WatchReply(.ok, account: WatchAccount(email: "owner@example.com", account: "Shop", plan: nil)))
        await reading.value
        XCTAssertEqual(model.state, .reading)
    }

    func testEachWayThePhoneCantAnswer() async {
        let cases: [(WatchReply?, AccountPageModel.State)] = [
            (nil, .unreachable(.notReachable)),
            (WatchReply(.signedOut), .signedOut),
            (WatchReply(.notYet), .updatePhone),
            (WatchReply(.failed, line: "tafs couldn't read it."), .failed("tafs couldn't read it.")),
            (WatchReply(.ok), .failed(Words.Alert.tafsCouldNotReachKiff)),
        ]
        for (reply, state) in cases {
            let model = AccountPageModel(phone: Phone(reply), clock: { self.now })
            await model.load()
            XCTAssertEqual(model.state, state)
        }
    }

    /// The watch's own lines: its language in itself, and the app's version.
    func testTheWatchsOwnLines() {
        XCTAssertEqual(AccountPageModel.language(Locale(identifier: "fr_FR")), "Français")
        XCTAssertEqual(AccountPageModel.language(Locale(identifier: "de")), "Deutsch")
        XCTAssertFalse(AccountPageModel.version().isEmpty)
    }
}
