import XCTest
@testable import TafsWatch

/// Your tafs on the wrist, with Halt and Halt all through the phone (docs/watch-app.md).
@MainActor
final class TafsModelTests: XCTestCase {
    private let now = Date(timeIntervalSinceReferenceDate: 813_667_500)
    private let rae = WatchTaf(id: "depl_1", name: "Rae", line: "Paused", halted: false)

    private final class Phone: PhoneAsking {
        var replies: [WatchReply?]
        var asked: [WatchRequest] = []
        init(_ replies: [WatchReply?]) { self.replies = replies }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return replies.isEmpty ? nil : replies.removeFirst()
        }
    }

    func testTheTafsAreReadFromThePhone() async {
        let phone = Phone([WatchReply(.ok, tafs: [rae])])
        let model = TafsModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.load()
        XCTAssertEqual(phone.asked, [WatchRequest(.tafs, made: now)])
        XCTAssertEqual(model.state, .loaded([rae]))
    }

    func testHaltNamesTheTafThenReadsTheListAgain() async {
        let halted = WatchTaf(id: "depl_1", name: "Rae", line: "Halted", halted: true)
        let phone = Phone([WatchReply(.ok, line: "Halted. Resuming starts only new work."), WatchReply(.ok, tafs: [halted])])
        let model = TafsModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.halt("depl_1")
        XCTAssertEqual(phone.asked, [WatchRequest(.halt, made: now, taf: "depl_1"), WatchRequest(.tafs, made: now)])
        XCTAssertEqual(model.halts["depl_1"], .done("Halted. Resuming starts only new work."))
        XCTAssertEqual(model.state, .loaded([halted]))
    }

    func testHaltAllNamesNoTaf() async {
        let phone = Phone([WatchReply(.failed, line: "tafs couldn't halt your tafs. Open tafs to halt them.")])
        let model = TafsModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.halt(nil)
        XCTAssertEqual(phone.asked, [WatchRequest(.haltAll, made: now)])
        XCTAssertEqual(model.halts[TafsModel.all], .failed("tafs couldn't halt your tafs. Open tafs to halt them."))
    }

    func testSignedOutDropsTheList() async {
        let phone = Phone([WatchReply(.ok, tafs: [rae]), WatchReply(.signedOut)])
        let model = TafsModel(phone: phone, clock: { self.now }, lock: Unlocked())
        await model.load()
        await model.halt("depl_1")
        XCTAssertEqual(model.state, .signedOut)
        XCTAssertTrue(model.halts.isEmpty)
    }

    func testWhatWasReadGoesWhenTheAppLeavesTheScreen() async {
        let model = TafsModel(phone: Phone([WatchReply(.ok, tafs: [rae])]), clock: { self.now }, lock: Unlocked())
        await model.load()
        model.leave()
        XCTAssertEqual(model.state, .loading)
    }

    /// With no passcode, neither Halt nor Halt all asks anything of the phone.
    func testWithNoPasscodeHaltAndHaltAllSendNothing() async {
        let phone = Phone([WatchReply(.ok, line: "Halted.")])
        let model = TafsModel(phone: phone, clock: { self.now }, lock: NoPasscode())
        await model.halt("depl_1")
        await model.halt(nil)
        XCTAssertTrue(phone.asked.isEmpty, "nothing reaches the phone")
        XCTAssertEqual(model.halts["depl_1"], .failed(Words.Watch.needsLock))
        XCTAssertEqual(model.halts.count, 2, "Halt all says so too")
        XCTAssertTrue(model.halts.values.allSatisfy { $0 == .failed(Words.Watch.needsLock) })
    }
}
