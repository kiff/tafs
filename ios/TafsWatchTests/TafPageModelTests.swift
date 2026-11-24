import XCTest
@testable import TafsWatch

/// v2's taf page (docs/watch-app.md, "Step (v)"): read from the phone by the taf's id, drawn only for that taf and only while
/// open; Halt this taf only from a watch with a passcode, then the page read again.
@MainActor
final class TafPageModelTests: XCTestCase {
    private let now = Date(timeIntervalSinceReferenceDate: 813_667_500)
    private let rae = WatchTaf(id: "depl_1", name: "Rae", line: "Ready", halted: false)

    private final class Phone: PhoneAsking {
        var replies: [WatchReply?]
        var asked: [WatchRequest] = []
        init(_ replies: WatchReply?...) { self.replies = replies }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return replies.isEmpty ? nil : replies.removeFirst()
        }
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

    private func page(_ id: String = "depl_1", name: String = "Rae", halted: Bool = false) -> WatchTafPage {
        WatchTafPage(id: id, name: name, state: halted ? "Halted" : "Ready", halted: halted, job: "Answers refund questions",
                     cards: [WatchTafPage.Card(title: "Refunds", figure: "€50", words: "each, at most", rules: ["Only paid orders"])],
                     waiting: 2)
    }

    func testThePageIsReadByTheTafsID() async {
        let phone = Phone(WatchReply(.ok, tafPage: page()))
        let model = TafPageModel(taf: rae, phone: phone, clock: { self.now }, lock: Unlocked())
        XCTAssertEqual(model.state, .asking)
        XCTAssertEqual(model.name, "Rae", "Your tafs' name before the read")
        await model.load()
        XCTAssertEqual(phone.asked, [WatchRequest(.taf, made: now, taf: "depl_1")])
        XCTAssertEqual(model.state, .loaded(page()))
    }

    /// Another taf's page is never drawn on this one.
    func testAnotherTafsPageIsNeverDrawn() async {
        let model = TafPageModel(taf: rae, phone: Phone(WatchReply(.ok, tafPage: page("depl_2", name: "Ivo"))), clock: { self.now },
                                 lock: Unlocked())
        await model.load()
        XCTAssertEqual(model.state, .failed(Words.Watch.phoneCouldNotReachCloud))
        XCTAssertEqual(model.name, "Rae")
    }

    /// An answer that lands after the page closed is never drawn.
    func testALateAnswerForAClosedPageIsNeverDrawn() async {
        let phone = HeldPhone()
        let model = TafPageModel(taf: rae, phone: phone, clock: { self.now }, lock: Unlocked())
        let reading = Task { await model.load() }
        while phone.held == nil { await Task.yield() }
        model.close()
        phone.answer(WatchReply(.ok, tafPage: page()))
        await reading.value
        XCTAssertEqual(model.state, .asking)
    }

    /// Before the phone can read it, the page says why in v1's words.
    func testEachWayThePhoneCantAnswer() async {
        let cases: [(WatchReply?, TafPageModel.State)] = [
            (nil, .unreachable(.notReachable)),
            (WatchReply(.signedOut), .signedOut),
            (WatchReply(.notYet), .updatePhone),
            (WatchReply(.failed, line: "tafs couldn't read it."), .failed("tafs couldn't read it.")),
            (WatchReply(.stale), .unreachable(.notReachable)),
        ]
        for (reply, state) in cases {
            let model = TafPageModel(taf: rae, phone: Phone(reply), clock: { self.now }, lock: Unlocked())
            await model.load()
            XCTAssertEqual(model.state, state)
        }
    }

    /// Halt this taf sends v1's Halt for it, then reads the page again; the phone's word for the result shows.
    func testHaltThisTafHaltsItAndReadsThePageAgain() async {
        let phone = Phone(WatchReply(.ok, tafPage: page()), WatchReply(.ok, line: "Rae is halted."),
                          WatchReply(.ok, tafPage: page(halted: true)))
        let model = TafPageModel(taf: rae, phone: phone, clock: { self.now }, lock: Unlocked())
        await model.load()
        await model.haltThis()
        XCTAssertEqual(phone.asked.map(\.kind), [.taf, .halt, .taf])
        XCTAssertEqual(phone.asked[1].taf, "depl_1")
        XCTAssertEqual(model.halt, .done("Rae is halted."))
        XCTAssertEqual(model.state, .loaded(page(halted: true)))
    }

    /// With no passcode, Halt sends nothing.
    func testWithNoPasscodeHaltSendsNothing() async {
        let phone = Phone()
        let model = TafPageModel(taf: rae, phone: phone, clock: { self.now }, lock: NoPasscode())
        await model.haltThis()
        XCTAssertTrue(phone.asked.isEmpty)
        XCTAssertEqual(model.halt, .failed(Words.Watch.needsLock))
    }

    /// Your tafs opens each page through its own phone.
    func testYourTafsOpensThePage() async {
        let phone = Phone(WatchReply(.ok, tafPage: page()))
        let tafs = TafsModel(phone: phone, clock: { self.now }, lock: Unlocked())
        let model = tafs.page(rae)
        await model.load()
        XCTAssertEqual(model.taf, rae)
        XCTAssertEqual(phone.asked.map(\.kind), [.taf])
    }
}
