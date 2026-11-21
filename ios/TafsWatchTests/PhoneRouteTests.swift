import WatchConnectivity
import XCTest
@testable import TafsWatch

/// Fewer false "phone not reachable" (row 44, D; Wear OS's `PhoneRouteTest`, tafs#1588): a phone that's there is
/// reached, a read goes once more, a write never does, and each failure says which.
@MainActor
final class PhoneRouteTests: XCTestCase {
    /// A link whose answers are scripted: reachable as listed, look after look, and each send's outcome in turn.
    @MainActor
    private final class Link {
        var activated = true
        var installed = true
        var reachable: [Bool]
        var sends: [Result<WatchReply, PhoneFailure>]
        var sent: [WatchRequest.Kind] = []
        var paused: [TimeInterval] = []

        init(reachable: [Bool] = [true], sends: [Result<WatchReply, PhoneFailure>] = []) {
            self.reachable = reachable
            self.sends = sends
        }

        var route: PhoneRoute {
            PhoneRoute(
                activated: { self.activated },
                installed: { self.installed },
                reachable: { self.reachable.count > 1 ? self.reachable.removeFirst() : (self.reachable.first ?? false) },
                send: { data, _ in
                    self.sent.append(WatchMessage.decode(WatchRequest.self, from: data)!.kind)
                    return self.sends.removeFirst().map { WatchMessage.encode($0) }
                },
                pause: { self.paused.append($0) })
        }
    }

    private let ok = WatchReply(.ok)

    func testAReachablePhoneIsAskedOnce() async {
        let link = Link(sends: [.success(ok)])
        let reached = await link.route.reach(WatchRequest(.needsYou, made: Date()))
        XCTAssertEqual(reached, .reply(ok))
        XCTAssertEqual(link.sent, [.needsYou])
        XCTAssertEqual(link.paused, [])
    }

    /// Just after the app starts the link can say "not reachable" for a moment: one more look, half a second on.
    func testANotReachableLinkIsLookedAtOnceMore() async {
        let link = Link(reachable: [false, true], sends: [.success(ok)])
        let reached = await link.route.reach(WatchRequest(.tafs, made: Date()))
        XCTAssertEqual(reached, .reply(ok))
        XCTAssertEqual(link.paused, [PhoneRoute.secondLook])
    }

    func testNotReachableTwiceIsNotReachableWithNothingSent() async {
        let link = Link(reachable: [false, false])
        let reached = await link.route.reach(WatchRequest(.halt, made: Date(), taf: "t_1"))
        XCTAssertEqual(reached, .failed(.notReachable))
        XCTAssertEqual(link.sent, [])
    }

    /// A read whose send failed goes once more, and the second reply is the answer.
    func testAReadWhoseSendFailedGoesOnceMore() async {
        let link = Link(sends: [.failure(.link), .success(ok)])
        let reached = await link.route.reach(WatchRequest(.account, made: Date()))
        XCTAssertEqual(reached, .reply(ok))
        XCTAssertEqual(link.sent, [.account, .account])
    }

    func testAReadGoesOnceMoreOnlyOnce() async {
        let link = Link(sends: [.failure(.notReachable), .failure(.link)])
        let reached = await link.route.reach(WatchRequest(.needsYou, made: Date()))
        XCTAssertEqual(reached, .failed(.link))
        XCTAssertEqual(link.sent.count, 2)
    }

    /// A write's failed send may still have arrived: it's never sent twice, whatever it is.
    func testAWriteIsNeverSentTwice() async {
        for request in [WatchRequest(.refuse, made: Date(), hold: "h_1"), WatchRequest(.halt, made: Date(), taf: "t_1"), WatchRequest(.haltAll, made: Date()),
                        WatchRequest(.coach, made: Date(), text: "hi", message: "m"), WatchRequest(.coachSend, made: Date(), text: "hi", message: "m")] {
            let link = Link(sends: [.failure(.link), .success(ok)])
            let reached = await link.route.reach(request)
            XCTAssertEqual(reached, .failed(.link), "\(request.kind)")
            XCTAssertEqual(link.sent, [request.kind])
        }
    }

    /// The limit used up waiting isn't a failed send: no second try, and it says the phone didn't answer.
    func testNoAnswerInTimeIsNotSentAgain() async {
        let link = Link(sends: [.failure(.noAnswer)])
        let reached = await link.route.reach(WatchRequest(.tafs, made: Date()))
        XCTAssertEqual(reached, .failed(.noAnswer))
        XCTAssertEqual(link.sent.count, 1)
    }

    func testNoTafsOnThePhoneSaysSoWithNothingSent() async {
        let link = Link()
        link.installed = false
        let reached = await link.route.reach(WatchRequest(.needsYou, made: Date()))
        XCTAssertEqual(reached, .failed(.noApp))
        XCTAssertEqual(link.sent, [])
    }

    /// A reply that can't be read is the link's failure; the phone did answer, so it isn't sent again.
    func testAReplyThatCantBeReadIsTheLinks() async {
        let link = Link()
        var route = link.route
        var sends = 0
        route.send = { _, _ in sends += 1; return .success(Data("not a reply".utf8)) }
        let reached = await route.reach(WatchRequest(.needsYou, made: Date()))
        XCTAssertEqual(reached, .failed(.link))
        XCTAssertEqual(sends, 1)
    }

    /// Each failure has its own words, and only "not in reach" keeps the old line.
    func testEachFailureSaysWhich() {
        XCTAssertEqual(PhoneFailure.notReachable.line, Words.Watch.phoneNotReachable)
        let lines = [PhoneFailure.notReachable, .noAnswer, .noApp, .link].map(\.line)
        XCTAssertEqual(Set(lines).count, 4)
    }

    /// WatchConnectivity's own errors, worded.
    func testWatchConnectivitysErrorsAreWorded() {
        XCTAssertEqual(PhoneLink.failure(WCError(.notReachable)), .notReachable)
        XCTAssertEqual(PhoneLink.failure(WCError(.companionAppNotInstalled)), .noApp)
        XCTAssertEqual(PhoneLink.failure(WCError(.messageReplyTimedOut)), .noAnswer)
        XCTAssertEqual(PhoneLink.failure(WCError(.deliveryFailed)), .link)
        XCTAssertEqual(PhoneLink.failure(URLError(.badURL)), .link)
    }

    /// The models show why: Needs you with no tafs on the phone says so, not "not in reach".
    func testNeedsYouShowsWhy() async {
        final class Phone: PhoneAsking {
            func ask(_ request: WatchRequest) async -> WatchReply? { nil }
            func reach(_ request: WatchRequest) async -> PhoneReach { .failed(.noApp) }
        }
        let model = NeedsYouModel(phone: Phone())
        await model.load()
        XCTAssertEqual(model.state, .unreachable(.noApp))
    }

    func testARefuseThatGotNoAnswerSaysSo() {
        XCTAssertEqual(WatchAnswer.from(PhoneReach.failed(.noAnswer), notDone: "Not refused"),
                       .failed("Not refused\n" + Words.Watch.phoneNoAnswer))
        XCTAssertEqual(WatchAnswer.from(nil as WatchReply?, notDone: nil), .failed(Words.Watch.phoneNotReachable))
    }
}
