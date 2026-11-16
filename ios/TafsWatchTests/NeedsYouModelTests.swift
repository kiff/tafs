import XCTest
@testable import TafsWatch

/// Needs you on the wrist (docs/watch-app.md): read from the phone, kept in memory only, and
/// never "nothing is waiting" when the phone didn't say so.
@MainActor
final class NeedsYouModelTests: XCTestCase {
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

    private var page: WatchNeedsYou {
        WatchNeedsYou(count: 3, calls: [.init(id: "ce_1", taf: "Rae", what: "Refund order 4502, €480.00",
                                              reason: "Over €150.00 each", answerBy: "10:51")], at: now)
    }

    func testItAsksForNeedsYouMadeNow() async {
        let phone = Phone(WatchReply(.ok, needsYou: page))
        let model = NeedsYouModel(phone: phone, clock: { self.now })
        XCTAssertEqual(model.state, .loading)
        await model.load()
        XCTAssertEqual(phone.asked, [WatchRequest(.needsYou, made: now)])
        XCTAssertEqual(model.state, .loaded(page))
    }

    func testThePhoneOutOfReachIsSaidAsSuch() async {
        let model = NeedsYouModel(phone: Phone(nil), clock: { self.now })
        await model.load()
        XCTAssertEqual(model.state, .unreachable)
    }

    func testSignedOutShowsNothingOfTheAccount() async {
        let phone = Phone(WatchReply(.ok, needsYou: page))
        let model = NeedsYouModel(phone: phone, clock: { self.now })
        await model.load()
        phone.reply = WatchReply(.signedOut)
        await model.load()
        XCTAssertEqual(model.state, .signedOut, "what was read goes with the sign-out")
    }

    func testAFailureIsThePhonesWords() async {
        let model = NeedsYouModel(phone: Phone(WatchReply(.failed, line: "tafs couldn't reach KIFF.")), clock: { self.now })
        await model.load()
        XCTAssertEqual(model.state, .failed("tafs couldn't reach KIFF."))
    }

    func testWhatWasReadGoesWhenTheAppLeavesTheScreen() async {
        let model = NeedsYouModel(phone: Phone(WatchReply(.ok, needsYou: page)), clock: { self.now })
        await model.load()
        model.leave()
        XCTAssertEqual(model.state, .loading)
    }

    func testTheWatchNeverAsksToApprove() {
        // The contract has no approval to ask for: only these six kinds decode on either side.
        let kinds = ["needs_you", "tafs", "refuse", "halt", "halt_all", "coach"]
        for kind in kinds {
            XCTAssertNotNil(WatchRequest.Kind(rawValue: kind), kind)
        }
        for kind in ["approve", "approve_once", "extra_room", "install"] {
            XCTAssertNil(WatchRequest.Kind(rawValue: kind), kind)
        }
    }
}
