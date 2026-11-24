import XCTest
@testable import TafsWatch

/// v2's Inbox kept on the watch (docs/watch-app.md, "Step (iv)"), as Wear OS's: each held call and each answer from Coach a
/// line, newest first, until dismissed; nothing kept with no passcode; nothing left after sign-out.
@MainActor
final class InboxModelTests: XCTestCase {
    private var now = Date(timeIntervalSinceReferenceDate: 813_667_500)

    /// The Inbox in a list, as the watch's defaults keep it.
    private final class Kept: InboxStore {
        var kept: [InboxLine] = []
        var gone: Set<String> = []
        /// As a protected file while the watch is locked: it reads as nothing, and nothing is written over it.
        var locked = false
        func lines() -> [InboxLine] { locked ? [] : kept }
        func write(_ lines: [InboxLine]) { if !locked { kept = lines } }
        func dismissed() -> Set<String> { locked ? [] : gone }
        func writeDismissed(_ ids: Set<String>) { if !locked { gone = ids } }
        func clear() { kept = []; gone = [] }
    }

    /// A passcode that can be taken away mid-test.
    private final class Passcode: WristLock {
        var on = true
        var isSecure: Bool { on }
    }

    private func call(_ id: String, summary: String? = "Refund order 4502", amount: String = "€150.00") -> WatchNeedsYou.Call {
        WatchNeedsYou.Call(id: id, taf: "Rae", what: [summary, amount].compactMap { $0 }.joined(separator: ", "), summary: summary,
                           reason: "Over the limit", answerBy: "10:51")
    }

    private func heard(_ calls: [WatchNeedsYou.Call]) -> NeedsYouModel.State {
        .loaded(WatchNeedsYou(count: calls.count, calls: calls, at: now))
    }

    private func model(_ store: Kept, lock: WristLock = Unlocked()) -> InboxModel {
        InboxModel(store: store, lock: lock, clock: { self.now })
    }

    /// Each held call is one line, unread, newest first; read again, it's the same line with the phone's words now.
    func testEachHeldCallIsOneLineNewestFirst() {
        let store = Kept()
        let inbox = model(store)
        inbox.heard(heard([call("h_1")]))
        now += 60
        inbox.heard(heard([call("h_1", summary: "Refund order 4503"), call("h_2")]))
        XCTAssertEqual(inbox.lines.map(\.id), ["held:h_2", "held:h_1"])
        XCTAssertEqual(inbox.lines.last?.line, "Refund order 4503")
        XCTAssertEqual(inbox.unread, 2)
        XCTAssertEqual(store.kept, inbox.lines, "kept on the watch")
        XCTAssertEqual(model(store).lines, inbox.lines, "and there when the app opens again")
    }

    /// A held call's line is what it asks, never its amount, in the Inbox and in its file (as Wear OS); a
    /// phone from before `summary` gives Needs you's title. The amount shows only while Needs you holds the call.
    func testNoAmountIsKeptOnTheWatch() {
        let store = Kept()
        let inbox = model(store)
        inbox.heard(heard([call("h_1", amount: "€150.00"), call("h_2", summary: nil, amount: "€90.00"), call("h_3", summary: " ")]))
        XCTAssertEqual(Set(inbox.lines.map(\.line)), ["Refund order 4502", Words.NeedsYou.title])
        for line in store.kept {
            XCTAssertFalse(line.line.contains("€"), line.line)
            XCTAssertFalse((line.title ?? "").contains("€"))
        }
        XCTAssertEqual(InboxLineScreen.held(inbox.lines.last!, in: heard([call("h_1")]))?.what, "Refund order 4502, €150.00",
                       "opened while Needs you holds it: the amount, from memory")
    }

    /// A held call no longer listed stays until it's dismissed: opened, it says it was answered or ended.
    func testALineStaysUntilDismissed() {
        let inbox = model(Kept())
        inbox.heard(heard([call("h_1")]))
        inbox.heard(heard([]))
        XCTAssertEqual(inbox.lines.map(\.id), ["held:h_1"])
        XCTAssertNil(InboxLineScreen.held(inbox.lines[0], in: heard([])), "Already answered, or it ended.")
        XCTAssertEqual(InboxLineScreen.held(inbox.lines[0], in: heard([call("h_1")]))?.id, "h_1")
    }

    /// Opened is read; Dismiss and Clear read take lines away, and a read while the phone still lists them doesn't bring them
    /// back. An id taken away goes once the phone no longer lists it.
    func testDismissedStaysGoneWhileThePhoneListsIt() {
        let store = Kept()
        let inbox = model(store)
        inbox.heard(heard([call("h_1"), call("h_2"), call("h_3")]))
        inbox.markRead("held:h_2")
        XCTAssertEqual(inbox.unread, 2)
        inbox.dismiss("held:h_1")
        inbox.clearRead()
        XCTAssertEqual(inbox.lines.map(\.id), ["held:h_3"])
        inbox.heard(heard([call("h_1"), call("h_2"), call("h_3")]))
        XCTAssertEqual(inbox.lines.map(\.id), ["held:h_3"], "a read never brings them back")
        XCTAssertEqual(store.gone, ["held:h_1", "held:h_2"])
        inbox.heard(heard([call("h_3")]))
        XCTAssertEqual(store.gone, [], "dropped once the phone no longer lists them")
    }

    /// Coach's answer to words from this watch is a line, read already: the person saw it come.
    func testCoachsAnswerIsALineAlreadyRead() {
        let inbox = model(Kept())
        inbox.answered(WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false), message: "m_1")
        inbox.answered(nil, message: "m_2")
        XCTAssertEqual(inbox.lines.map(\.id), ["answer:m_2", "answer:m_1"])
        XCTAssertEqual(inbox.lines.map(\.line), [Words.Watch.moreOnPhone, "Rae is paused."])
        XCTAssertEqual(inbox.unread, 0)
        XCTAssertEqual(InboxLineScreen.title(inbox.lines[0]), Words.Coach.title)
    }

    /// Coach's reply is kept with no amount in it, as a held call's line is, so no amount is kept on the watch at all.
    func testCoachsReplyIsKeptWithNoAmount() {
        let store = Kept()
        let inbox = model(store)
        inbox.answered(WatchCoachAnswer(text: "Two calls wait: a refund of €84 for order A-1039, and 150 zł for Ivo.", more: false,
                                        approveOnPhone: false), message: "m_1")
        inbox.answered(WatchCoachAnswer(text: "€84", more: false, approveOnPhone: false), message: "m_2")
        XCTAssertEqual(inbox.lines.map(\.line), [Words.Watch.moreOnPhone, "Two calls wait: a refund of for order A-1039, and for Ivo."])
        for line in store.kept { XCTAssertFalse(line.line.contains("€") || line.line.contains("zł"), line.line) }
    }

    /// An answer taken away keeps no id: it's put once, by its message's id, so nothing would bring it back.
    func testADismissedAnswerKeepsNoID() {
        let store = Kept()
        let inbox = model(store)
        inbox.answered(WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false), message: "m_1")
        inbox.answered(nil, message: "m_2")
        inbox.dismiss("answer:m_1")
        inbox.clearRead()
        XCTAssertEqual(inbox.lines, [])
        XCTAssertEqual(store.gone, [])
    }

    /// With no passcode nothing is kept or added, and the lines go when the app is opened or put away; the ids taken away
    /// stay, so a dismissed line doesn't come back unread once a passcode is set again.
    func testNoPasscodeKeepsNothing() {
        let store = Kept()
        let passcode = Passcode()
        let inbox = model(store, lock: passcode)
        inbox.heard(heard([call("h_1"), call("h_2")]))
        inbox.dismiss("held:h_1")
        passcode.on = false
        inbox.checkLock()
        XCTAssertFalse(inbox.kept)
        XCTAssertEqual(inbox.lines, [])
        XCTAssertEqual(store.kept, [])
        XCTAssertEqual(store.gone, ["held:h_1"])
        inbox.heard(heard([call("h_1"), call("h_2")]))
        XCTAssertEqual(store.kept, [], "nothing added")
        passcode.on = true
        inbox.checkLock()
        inbox.heard(heard([call("h_1"), call("h_2")]))
        XCTAssertEqual(inbox.lines.map(\.id), ["held:h_2"])

        let opened = Kept()
        opened.kept = [InboxLine(id: "held:h_9", kind: .held, title: "Rae", line: "Refund", at: now)]
        XCTAssertEqual(model(opened, lock: NoPasscode()).lines, [])
        XCTAssertEqual(opened.kept, [], "opened with no passcode: cleared")
    }

    /// A model made while the file can't be read starts empty; once the watch is unlocked it takes in what the file kept, so
    /// its next write leaves nothing out.
    func testAModelMadeWhileLockedTakesInTheFileOnceItCan() {
        let store = Kept()
        store.kept = [InboxLine(id: "held:h_1", kind: .held, title: "Rae", line: "Refund order 4502", at: now)]
        store.gone = ["held:h_0"]
        store.locked = true
        let inbox = model(store)
        XCTAssertEqual(inbox.lines, [], "locked: nothing read")
        now += 60
        inbox.heard(heard([call("h_0"), call("h_2")]))
        XCTAssertEqual(store.kept.map(\.id), ["held:h_1"], "nothing written over while locked")
        store.locked = false
        inbox.checkLock()
        XCTAssertEqual(inbox.lines.map(\.id), ["held:h_2", "held:h_1"], "the file's line taken in, the dismissed one still gone")
        XCTAssertEqual(store.kept.map(\.id), ["held:h_2", "held:h_1"])
    }

    /// Signed out on the phone: nothing of the Inbox stays, the ids taken away included.
    func testSignedOutLeavesNothing() {
        let store = Kept()
        let inbox = model(store)
        inbox.heard(heard([call("h_1"), call("h_2")]))
        inbox.dismiss("held:h_1")
        inbox.heard(.signedOut)
        XCTAssertEqual(inbox.lines, [])
        XCTAssertEqual(store.kept, [])
        XCTAssertEqual(store.gone, [])
    }

    /// A file there that can't be read now (as while the watch is locked) is never written over, so neither half is lost.
    /// A folder in the file's place stands for it: it's there, and reading it fails.
    func testAFileThatCantBeReadIsNeverWrittenOver() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("InboxModelTests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("tafs.inbox.json")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        let store = FileInboxStore(url: url)
        XCTAssertEqual(store.lines(), [])
        store.write([InboxLine(id: "held:h_1", kind: .held, title: "Rae", line: "Refund", at: now)])
        store.writeDismissed(["held:h_0"])
        var folderStill: ObjCBool = false
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path, isDirectory: &folderStill))
        XCTAssertTrue(folderStill.boolValue, "nothing written over what couldn't be read")
    }

    /// Both halves are written together: the lines and the ids taken away each survive the other's write.
    func testEachHalfSurvivesTheOthersWrite() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("InboxModelTests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("tafs.inbox.json")
        let line = InboxLine(id: "held:h_1", kind: .held, title: "Rae", line: "Refund", at: now)
        let store = FileInboxStore(url: url)
        store.write([line])
        store.writeDismissed(["held:h_0"])
        store.write([line])
        let opened = FileInboxStore(url: url)
        XCTAssertEqual(opened.lines(), [line])
        XCTAssertEqual(opened.dismissed(), ["held:h_0"])
    }

    /// The file store keeps the lines and the ids taken away in one protected file, and clearing removes it.
    func testTheFileStoreKeepsAndClears() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("InboxModelTests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("tafs.inbox.json")
        let store = FileInboxStore(url: url)
        XCTAssertEqual(store.lines(), [], "nothing before anything's kept")
        let line = InboxLine(id: "held:h_1", kind: .held, title: nil, line: "Refund order 4502", at: now, read: true)
        store.write([line])
        store.writeDismissed(["held:h_0"])
        XCTAssertEqual(FileInboxStore(url: url).lines(), [line])
        XCTAssertEqual(FileInboxStore(url: url).dismissed(), ["held:h_0"])
        store.clear()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "cleared: no file left on the watch")
        XCTAssertEqual(store.lines(), [])
        XCTAssertEqual(store.dismissed(), [])
        store.write([])
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "nothing kept: no file written")
    }

    // MARK: In step across the person's watches (row 21)

    /// The phone, answering the marks as told and keeping what it was asked to mark.
    private final class MarksPhone: PhoneAsking {
        var marks: WatchReply? = WatchReply(.ok, marks: WatchInboxMarks())
        var asked: [WatchRequest] = []
        func ask(_ request: WatchRequest) async -> WatchReply? {
            asked.append(request)
            return request.kind == .inboxMarks ? marks : WatchReply(.ok, marks: WatchInboxMarks())
        }
        var sent: [WatchInboxMarks] { asked.filter { $0.kind == .inboxMark }.compactMap(\.marks) }
    }

    private func model(_ store: Kept, phone: MarksPhone, lock: WristLock = Unlocked()) -> InboxModel {
        InboxModel(store: store, lock: lock, clock: { self.now }, phone: phone)
    }

    /// Lines dismissed on another watch go, and a held call's stays away while the phone lists it; lines read there are read.
    func testMarksFromTheOtherWatchesAreTakenIn() async {
        let store = Kept()
        let phone = MarksPhone()
        let inbox = model(store, phone: phone)
        inbox.heard(heard([call("h_1"), call("h_2"), call("h_3")]))
        inbox.answered(WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false), message: "m-1")
        phone.marks = WatchReply(.ok, marks: WatchInboxMarks(read: ["h_2"], dismissed: ["h_1", "m-1", "h_9"]))
        await inbox.sync()
        XCTAssertEqual(Set(inbox.lines.map(\.id)), ["held:h_2", "held:h_3"])
        XCTAssertEqual(inbox.lines.first { $0.id == "held:h_2" }?.read, true)
        XCTAssertEqual(inbox.lines.first { $0.id == "held:h_3" }?.read, false)
        XCTAssertEqual(store.kept, inbox.lines, "kept as taken in")
        // Read again while the phone still lists h_1: it stays away; h_9, which the phone doesn't list, a call to come.
        inbox.heard(heard([call("h_1"), call("h_2"), call("h_3")]))
        XCTAssertFalse(inbox.lines.contains { $0.id == "held:h_1" })
        XCTAssertEqual(store.gone, ["held:h_1"], "ids the phone doesn't list go, so the list never grows")
        XCTAssertTrue(phone.sent.isEmpty, "taking marks in sends none")
    }

    /// Open, Dismiss and Clear read send their ids, by a held call's own id or the message an answer is to; never words.
    func testWhatThisWatchDoesIsMarked() async {
        let phone = MarksPhone()
        let inbox = model(Kept(), phone: phone)
        inbox.heard(heard([call("h_1"), call("h_2")]))
        inbox.answered(WatchCoachAnswer(text: "Rae is paused.", more: false, approveOnPhone: false), message: "m-1")
        inbox.markRead("held:h_1")
        inbox.markRead("held:h_1")
        inbox.markRead("answer:m-1")
        inbox.dismiss("held:h_2")
        inbox.clearRead()
        await inbox.marking?.value
        XCTAssertEqual(phone.sent, [WatchInboxMarks(read: ["h_1"]), WatchInboxMarks(dismissed: ["h_2"]),
                                    WatchInboxMarks(dismissed: ["m-1", "h_1"])],
                       "once each, in order; Coach's answer comes in read, so opening it marks nothing")
        XCTAssertTrue(inbox.lines.isEmpty)
    }

    /// Clear read of more lines than tafs cloud takes at once goes in chunks of 100.
    func testClearReadGoesInChunks() async {
        let phone = MarksPhone()
        let inbox = model(Kept(), phone: phone)
        inbox.heard(heard((0..<150).map { call("h_\($0)") }))
        let marks = WatchInboxMarks(read: (0..<150).map { "h_\($0)" })
        inbox.take(marks)
        inbox.clearRead()
        await inbox.marking?.value
        XCTAssertEqual(phone.sent.map(\.dismissed.count), [100, 50])
        XCTAssertTrue(phone.sent.allSatisfy(\.isComplete))
    }

    /// No passcode: nothing is kept, so nothing is read or marked. A phone from before the marks, or none, leaves the lines.
    func testNoPasscodeOrAnOlderPhoneChangesNothing() async {
        let phone = MarksPhone()
        let open = model(Kept(), phone: phone, lock: NoPasscode())
        await open.sync()
        XCTAssertTrue(phone.asked.isEmpty)

        let store = Kept()
        let inbox = model(store, phone: phone)
        inbox.heard(heard([call("h_1")]))
        for answer in [WatchReply(.notYet), WatchReply(.refused), WatchReply(.failed, line: "down"), nil] {
            phone.marks = answer
            await inbox.sync()
        }
        XCTAssertEqual(inbox.lines.map(\.id), ["held:h_1"])
        XCTAssertEqual(inbox.lines.first?.read, false)
    }
}
