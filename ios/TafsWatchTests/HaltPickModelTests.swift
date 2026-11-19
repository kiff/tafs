import XCTest
@testable import TafsWatch

/// v2's Choose which to halt (docs/watch-app.md, "Step (v)"): only tafs not halted are offered; all picked is one Halt all;
/// one or a few are one Halt each, in order, each to the end; nothing before Halt now, and nothing with no passcode.
@MainActor
final class HaltPickModelTests: XCTestCase {
    private let now = Date(timeIntervalSinceReferenceDate: 813_667_500)
    private let rae = WatchTaf(id: "depl_1", name: "Rae", line: "Ready", halted: false)
    private let ivo = WatchTaf(id: "depl_2", name: "Ivo", line: "Ready", halted: false)
    private let lea = WatchTaf(id: "depl_3", name: "Lea", line: "Halted", halted: true)

    /// The phone: Your tafs as listed, and each Halt's answer by the taf it names (Halt all under "").
    private final class Phone: PhoneAsking {
        var listed: [WatchTaf]
        var halts: [String: WatchReply?]
        var asked: [WatchRequest] = []
        init(listed: [WatchTaf], halts: [String: WatchReply?] = [:]) {
            self.listed = listed
            self.halts = halts
        }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            switch request.kind {
            case .tafs: return WatchReply(.ok, tafs: listed)
            case .halt, .haltAll: return halts[request.taf ?? ""] ?? WatchReply(.ok, line: "Halted.")
            default: return nil
            }
        }
    }

    private func pick(_ phone: Phone, lock: WristLock = Unlocked()) async -> HaltPickModel {
        let tafs = TafsModel(phone: phone, clock: { self.now }, lock: lock)
        await tafs.load()
        phone.asked = []
        return HaltPickModel(tafs: tafs)
    }

    /// A taf halted already isn't offered; with none picked, Halt now sends nothing.
    func testOnlyTafsNotHaltedAreOfferedAndNothingGoesUnpicked() async {
        let phone = Phone(listed: [rae, lea, ivo])
        let model = await pick(phone)
        XCTAssertEqual(model.offered, [rae, ivo])
        model.toggle("depl_3")
        XCTAssertEqual(model.picked, [], "a halted taf can't be picked")
        await model.haltNow()
        XCTAssertTrue(phone.asked.isEmpty)
        XCTAssertEqual(model.outcome, .choosing)
    }

    /// Every taf offered picked is "Halt all your tafs?" and one Halt all.
    func testAllPickedIsOneHaltAll() async {
        let phone = Phone(listed: [rae, ivo, lea], halts: ["": WatchReply(.ok, line: "Your tafs are halted.")])
        let model = await pick(phone)
        model.toggleAll()
        XCTAssertTrue(model.allPicked)
        XCTAssertEqual(model.question, Words.Watch.haltAllQ)
        XCTAssertEqual(model.explain, Words.Halt.Explain.runningAll)
        await model.haltNow()
        XCTAssertEqual(phone.asked.filter { $0.kind != .tafs }.map(\.kind), [.haltAll])
        XCTAssertEqual(model.outcome, .done([.init(name: Words.Watch.allYourTafs, answer: .done("Your tafs are halted."))]))
        model.toggleAll()
        XCTAssertEqual(model.picked, [], "All your tafs again: none")
    }

    /// One or a few are named, and each gets its own Halt, in Your tafs' order; one that fails never keeps the next from
    /// halting, and each shows what came of it by name.
    func testAFewAreHaltedOneByOneEachToTheEnd() async {
        let three = WatchTaf(id: "depl_4", name: "Ola", line: "Ready", halted: false)
        let phone = Phone(listed: [rae, ivo, three], halts: ["depl_1": .some(nil), "depl_4": WatchReply(.ok, line: "Ola is halted.")])
        let model = await pick(phone)
        model.toggle("depl_4")
        XCTAssertEqual(model.question, "Ola")
        XCTAssertEqual(model.explain, Words.Halt.Explain.runningOne)
        model.toggle("depl_1")
        XCTAssertFalse(model.allPicked)
        XCTAssertEqual(model.question, "Rae, Ola")
        XCTAssertEqual(model.explain, Words.Halt.haltingStopsNoRunStarts(who: "Rae, Ola"))
        await model.haltNow()
        XCTAssertEqual(phone.asked.filter { $0.kind == .halt }.map(\.taf), ["depl_1", "depl_4"])
        XCTAssertEqual(model.outcome, .done([
            .init(name: "Rae", answer: .failed(Words.Watch.phoneNotReachable)),
            .init(name: "Ola", answer: .done("Ola is halted.")),
        ]))
    }

    /// With no passcode, nothing is sent.
    func testWithNoPasscodeNothingIsSent() async {
        let phone = Phone(listed: [rae, ivo])
        let model = await pick(phone, lock: NoPasscode())
        model.toggle("depl_1")
        await model.haltNow()
        XCTAssertTrue(phone.asked.isEmpty)
        XCTAssertEqual(model.outcome, .notSent(Words.Watch.needsLock))
    }
}
