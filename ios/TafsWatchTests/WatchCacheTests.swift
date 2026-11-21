import XCTest
@testable import TafsWatch

/// The watch opens at once (row 44, D; as Wear OS's `WatchCacheTest`, tafs#1594): the kept read of Your tafs with the dot,
/// then the phone's; a short drop keeps the last read; and what's kept is little, protected and soon gone.
@MainActor
final class WatchCacheTests: XCTestCase {
    private var now = Date(timeIntervalSinceReferenceDate: 813_667_500)
    private var url: URL!
    private let rae = WatchTaf(id: "depl_1", name: "Rae", line: "Waiting on you: refund 140 € to Ana", halted: false, paused: true)
    private let kai = WatchTaf(id: "depl_2", name: "Kai", line: "Halted", halted: true)

    private final class Phone: PhoneAsking {
        var replies: [WatchReply?]
        var asked = 0
        init(_ replies: [WatchReply?]) { self.replies = replies }
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked += 1
            return replies.isEmpty ? nil : replies.removeFirst()
        }
    }

    override func setUp() {
        super.setUp()
        url = FileManager.default.temporaryDirectory.appendingPathComponent("watch-cache-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: url)
        super.tearDown()
    }

    private func cache(on: Bool = true, lock: WristLock = Unlocked()) -> WatchCache {
        WatchCache(url: url, on: on, lock: lock, clock: { self.now })
    }

    /// Only the id, the name, and halted or paused: never the state line, which can name what a held call wants.
    func testOnlyNamesAndHaltedOrPausedAreKept() throws {
        cache().keep([rae, kai])
        let kept = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(kept.contains("Rae"))
        XCTAssertFalse(kept.contains("refund"))
        XCTAssertFalse(kept.contains("140"))
        XCTAssertFalse(kept.contains("Halted"))
        XCTAssertEqual(cache().tafs(), [WatchTaf(id: "depl_1", name: "Rae", line: nil, halted: false, paused: true),
                                        WatchTaf(id: "depl_2", name: "Kai", line: nil, halted: true, paused: nil)])
    }

    /// The next start opens on what was kept, with the dot, then the phone's read in its place.
    func testTheNextStartOpensOnTheKeptReadThenThePhones() async {
        let first = TafsModel(phone: Phone([WatchReply(.ok, tafs: [rae])]), clock: { self.now }, lock: Unlocked(), cache: cache())
        await first.load()

        final class HeldPhone: PhoneAsking {
            var held: CheckedContinuation<WatchReply?, Never>?
            func ask(_ request: WatchRequest) async -> WatchReply? { await withCheckedContinuation { held = $0 } }
        }
        let phone = HeldPhone()
        let next = TafsModel(phone: phone, clock: { self.now }, lock: Unlocked(), cache: cache())
        let loading = Task { await next.load() }
        while phone.held == nil { await Task.yield() }
        XCTAssertEqual(next.state, .loaded([WatchTaf(id: "depl_1", name: "Rae", line: nil, halted: false, paused: true)]))
        XCTAssertTrue(next.updating)
        phone.held?.resume(returning: WatchReply(.ok, tafs: [rae, kai]))
        await loading.value
        XCTAssertEqual(next.state, .loaded([rae, kai]))
        XCTAssertFalse(next.updating)
    }

    /// A short drop keeps the last read with the dot; 30 s on, the phone is asked once more, and then it's unreachable.
    func testAShortDropKeepsTheReadAndThirtySecondsCallsItUnreachable() async {
        var waited: [TimeInterval] = []
        let phone = Phone([WatchReply(.ok, tafs: [rae]), nil, nil])
        let model = TafsModel(phone: phone, clock: { self.now }, lock: Unlocked(), cache: cache(on: false),
                              pause: { seconds in waited.append(seconds); self.now += seconds })
        await model.load()
        now += 5
        await model.load()
        XCTAssertEqual(model.state, .loaded([rae]), "5 s into a drop, the read stays")
        XCTAssertTrue(model.updating)
        while phone.asked < 3 || model.updating { await Task.yield() }
        XCTAssertEqual(waited, [ReadGrace.limit])
        XCTAssertEqual(model.state, .unreachable)
    }

    func testTheGraceEndsWithAnAnswer() {
        var grace = ReadGrace()
        XCTAssertTrue(grace.keeps(at: now))
        XCTAssertTrue(grace.keeps(at: now + 29))
        XCTAssertEqual(grace.left(at: now + 10), 20)
        XCTAssertFalse(grace.keeps(at: now + 30))
        grace.answered()
        XCTAssertTrue(grace.keeps(at: now + 31))
    }

    /// Nothing is shown before the phone answers when nothing was kept: no dot, and unreachable at once.
    func testWithNothingShownUnreachableIsAtOnce() async {
        let model = TafsModel(phone: Phone([nil]), clock: { self.now }, lock: Unlocked(), cache: cache())
        await model.load()
        XCTAssertEqual(model.state, .unreachable)
        XCTAssertFalse(model.updating)
    }

    /// Signed out on the phone, a day old, and no passcode each clear it; signing out on the watch is `clear`.
    func testSignOutADayAndNoPasscodeEachClearIt() async {
        cache().keep([rae])
        let model = TafsModel(phone: Phone([WatchReply(.signedOut)]), clock: { self.now }, lock: Unlocked(), cache: cache())
        await model.load()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))

        cache().keep([rae])
        now += WatchCache.keepFor
        XCTAssertNil(cache().tafs())
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))

        cache().keep([rae])
        cache(lock: NoPasscode()).checkLock()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))

        cache(lock: NoPasscode()).keep([rae])
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "no passcode, nothing is written")

        cache().keep([rae])
        cache().clear()
        XCTAssertNil(cache().tafs())
    }

    func testAnUnreadableFileIsNone() throws {
        try Data("not kept".utf8).write(to: url)
        XCTAssertNil(cache().tafs())
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    /// Switched off, nothing is read or written.
    func testOffKeepsNothing() {
        cache(on: false).keep([rae])
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertFalse(WatchCache.on)
    }
}
