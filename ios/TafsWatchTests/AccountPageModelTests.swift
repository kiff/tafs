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

    func testEachWayThePhoneCantAnswer() async {
        let cases: [(WatchReply?, AccountPageModel.State)] = [
            (nil, .unreachable),
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
