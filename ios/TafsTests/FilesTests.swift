import XCTest
@testable import Tafs

/// Files (docs/files.md): the account's delivered files, grouped by day, taf and run in tafs cloud's facts, the days left
/// counted to tafs cloud's date, and Delete now gone from the list only once the store deleted it.
@MainActor
final class FilesTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-11-17T10:00:00Z")!

    private func file(_ id: String, taf: String = "depl_1", run: String = "r1", hoursAgo: Double, name: String? = nil,
                      goesInDays: Double = 30) -> DeliveredFile {
        let at = now.addingTimeInterval(-hoursAgo * 3_600)
        return DeliveredFile(id: id, name: name ?? "\(id).csv", type: "CSV", bytes: 1_000, tafID: taf, tafName: taf, runID: run,
                             runAt: at, chat: "s_" + run, goesAt: now.addingTimeInterval(goesInDays * 86_400))
    }

    private final class Store: FilesStore {
        var listed: [DeliveredFile]
        var fails = false
        var deleted: [String] = []
        init(_ listed: [DeliveredFile]) { self.listed = listed }
        func files() async throws -> [DeliveredFile] {
            if fails { throw URLError(.notConnectedToInternet) }
            return listed
        }
        func contents(of id: String) async throws -> Data { Data() }
        func delete(_ id: String) async throws {
            if fails { throw URLError(.notConnectedToInternet) }
            deleted.append(id)
        }
    }

    /// Off until tafs cloud's store lands, and kept 30 days (the owner's pick, 1a).
    func testFilesIsOffAndKeptThirtyDays() {
        XCTAssertFalse(FilesFeature.on)
        XCTAssertEqual(FilesFeature.keptDays, 30)
    }

    /// By day, newest first, in the account's zone: 23:30 UTC is already the next day in Tokyo.
    func testFilesByDayNewestFirstInTheAccountsZone() throws {
        let files = [file("a", hoursAgo: 30), file("b", hoursAgo: 1), file("c", hoursAgo: 2)]
        let utc = FilesGrouping.byDay(files, zone: try XCTUnwrap(TimeZone(identifier: "UTC")))
        XCTAssertEqual(utc.map(\.id), ["2026-11-17", "2026-11-16"])
        XCTAssertEqual(utc.first?.files.map(\.id), ["b", "c"])
        let late = [file("d", hoursAgo: -13.5)]  // 23:30 UTC on the 17th
        XCTAssertEqual(FilesGrouping.byDay(late, zone: try XCTUnwrap(TimeZone(identifier: "Asia/Tokyo"))).map(\.id), ["2026-11-18"])
    }

    /// By taf and by run: the newest run first, each run's files together, in a stable order.
    func testFilesByTafAndByRun() {
        let files = [file("a", taf: "rae", run: "r1", hoursAgo: 30), file("b", taf: "ivo", run: "r2", hoursAgo: 2, name: "z.csv"),
                     file("c", taf: "ivo", run: "r2", hoursAgo: 2, name: "a.csv"), file("d", taf: "rae", run: "r3", hoursAgo: 1)]
        XCTAssertEqual(FilesGrouping.byTaf(files).map(\.id), ["rae", "ivo"])
        XCTAssertEqual(FilesGrouping.byTaf(files).first?.files.map(\.id), ["d", "a"])
        XCTAssertEqual(FilesGrouping.byRun(files).map(\.id), ["r3", "r2", "r1"])
        XCTAssertEqual(FilesGrouping.byRun(files)[1].files.map(\.id), ["c", "b"], "one run's files by name")
    }

    /// The days left are counted to tafs cloud's date, never below none; the week's count is what goes within 7 days.
    func testDaysLeftAreCountedToTafsCloudsDate() {
        XCTAssertEqual(FilesGrouping.daysLeft(file("a", hoursAgo: 0, goesInDays: 29.9), now: now), 29)
        XCTAssertEqual(FilesGrouping.daysLeft(file("b", hoursAgo: 0, goesInDays: 0.5), now: now), 0, "today")
        XCTAssertEqual(FilesGrouping.daysLeft(file("c", hoursAgo: 0, goesInDays: -1), now: now), 0, "past: none, never negative")
        let files = [file("a", hoursAgo: 0, goesInDays: 4), file("b", hoursAgo: 0, goesInDays: 7.5), file("c", hoursAgo: 0, goesInDays: 20)]
        XCTAssertEqual(FilesGrouping.goingThisWeek(files, now: now), 2)
    }

    /// Read, by taf and by run; a failed read says so and keeps nothing it had.
    func testTheFilesAreReadAndAFailedReadKeepsNothing() async {
        let store = Store([file("a", taf: "rae", run: "r1", hoursAgo: 3), file("b", taf: "ivo", run: "r2", hoursAgo: 1)])
        let model = FilesModel(store: store)
        await model.load()
        XCTAssertEqual(model.state, .loaded([file("b", taf: "ivo", run: "r2", hoursAgo: 1), file("a", taf: "rae", run: "r1", hoursAgo: 3)]))
        XCTAssertEqual(model.files(taf: "rae").map(\.id), ["a"])
        XCTAssertEqual(model.files(run: "r2").map(\.id), ["b"])
        XCTAssertEqual(model.files(chat: "s_r1").map(\.id), ["a"], "a chat's chips: the files its runs delivered")
        XCTAssertEqual(model.files(chat: "s_none"), [])
        store.fails = true
        await model.load()
        XCTAssertEqual(model.state, .failed)
        XCTAssertEqual(model.files(), [])
    }

    /// Delete now leaves the list only once the store has deleted it; one that couldn't be stays.
    func testDeleteNowLeavesTheListOnlyOnceDeleted() async {
        let store = Store([file("a", hoursAgo: 3), file("b", hoursAgo: 1)])
        let model = FilesModel(store: store)
        await model.load()
        let gone = await model.delete("a")
        XCTAssertTrue(gone)
        XCTAssertEqual(store.deleted, ["a"])
        XCTAssertEqual(model.files().map(\.id), ["b"])
        store.fails = true
        let kept = await model.delete("b")
        XCTAssertFalse(kept)
        XCTAssertEqual(model.files().map(\.id), ["b"], "it couldn't be deleted, so it stays")
    }

    /// tafs cloud's list (`contracts/files-list.json`, tafs#1412) as the app reads it: newest first, the taf's name kept as
    /// delivered, the chat only where there's one, times to the millisecond; the type from the file's extension.
    func testTafsCloudsListReadsAsTheScreensDrawIt() throws {
        let json = #"""
        {"files":[{"id":"fil_00000000000000000000000000000002","taf":"taf-0bde5000000000000000","run":"run-2","name":"notes.md","media_type":"text/markdown","size":8,"created":"2026-11-19T08:00:00.000Z","expires":"2026-12-19T08:00:00.000Z","taf_name":"Order rows"},
                  {"id":"fil_00000000000000000000000000000001","taf":"taf-0bde5000000000000000","run":"run-1","chat":"chat-1","name":"week.csv","media_type":"text/csv","size":22,"created":"2026-11-19T08:00:00.000Z","expires":"2026-12-19T08:00:00.000Z","taf_name":"Order rows"}]}
        """#
        let page = try KIFFClient.decoder.decode(TafsCloud.FilesPage.self, from: Data(json.utf8))
        XCTAssertNil(page.next)
        let files = page.files.map(DeliveredFile.init)
        XCTAssertEqual(files.map(\.id), ["fil_00000000000000000000000000000002", "fil_00000000000000000000000000000001"])
        XCTAssertEqual(files.map(\.type), ["MD", "CSV"])
        XCTAssertEqual(files.map(\.tafName), ["Order rows", "Order rows"])
        XCTAssertEqual(files.map(\.chat), [nil, "chat-1"])
        XCTAssertEqual(files[1].bytes, 22)
        XCTAssertEqual(files[1].goesAt.timeIntervalSince(files[1].runAt), 30 * 86_400, accuracy: 1, "kept 30 days, as tafs cloud says")
        // A name with no extension takes its media type's.
        let bare = TafsCloud.DeliveredFileRead(id: "f", taf: "t", run: "r", name: "report", mediaType: "application/pdf", size: 1,
                                               created: files[0].runAt, expires: files[0].goesAt, tafName: nil)
        XCTAssertEqual(DeliveredFile(bare).type, "PDF")
        XCTAssertEqual(DeliveredFile(bare).tafName, "", "no name from tafs cloud: none drawn")
    }

    /// One file and its download (`contracts/files-file.json`, `files-download.json`) read as tafs cloud writes them.
    func testOneFileAndItsDownloadRead() throws {
        let download = #"{"id":"fil_00000000000000000000000000000001","taf":"taf-0bde5000000000000000","run":"run-1","chat":"chat-1","name":"week.csv","media_type":"text/csv","size":22,"created":"2026-11-19T08:00:00.000Z","expires":"2026-12-19T08:00:00.000Z","url":"https://s3.test/delivered/fil_00000000000000000000000000000001?signed","url_expires":"2026-11-19T08:00:00.000Z","taf_name":"Order rows"}"#
        let read = try KIFFClient.decoder.decode(TafsCloud.FileDownload.self, from: Data(download.utf8))
        XCTAssertEqual(read.url.host, "s3.test")
    }

    /// The cloud store reads every page, fetches a file's bytes through its minute-long URL, and deletes through tafs cloud.
    func testTheCloudStoreReadsEveryPageAndTheBytesThroughTheURL() async throws {
        StubProtocol.responses = [:]
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
        func record(_ id: String) -> String {
            #"{"id":"\#(id)","taf":"t1","run":"r1","name":"\#(id).csv","media_type":"text/csv","size":3,"created":"2026-11-19T08:00:00.000Z","expires":"2026-12-19T08:00:00.000Z","taf_name":"Rae"}"#
        }
        StubProtocol.responses["GET /v1/files"] = (200, #"{"files":[\#(record("fil_a"))],"next":"fil_a"}"#)
        StubProtocol.responses["GET /v1/files?after=fil_a"] = (200, #"{"files":[\#(record("fil_b"))]}"#)
        StubProtocol.responses["GET /v1/files/fil_a/download"] = (200, #"{"url":"https://s3.test/fil_a?signed"}"#)
        StubProtocol.responses["DELETE /v1/files/fil_a"] = (200, #"{"deleted":"fil_a"}"#)
        final class Fetched: @unchecked Sendable { var urls: [URL] = [] }
        let fetched = Fetched()
        let store = CloudFilesStore(cloud: cloud, fetch: { url in fetched.urls.append(url); return Data("a,b".utf8) })
        let files = try await store.files()
        XCTAssertEqual(files.map(\.id), ["fil_a", "fil_b"], "both pages")
        let bytes = try await store.contents(of: "fil_a")
        XCTAssertEqual(bytes, Data("a,b".utf8))
        XCTAssertEqual(fetched.urls.map(\.absoluteString), ["https://s3.test/fil_a?signed"])
        try await store.delete("fil_a")
    }

    /// The stub moves no money and keeps every file 30 days from its run.
    func testTheStubKeepsEachFileThirtyDaysFromItsRun() async throws {
        let files = try await StubFilesStore(now: now).files()
        XCTAssertFalse(files.isEmpty)
        for f in files {
            XCTAssertEqual(f.goesAt.timeIntervalSince(f.runAt), 30 * 86_400, accuracy: 1, f.name)
        }
    }
}
