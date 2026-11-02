import XCTest
@testable import Tafs

/// Export my chats (kiff/tafs#424): the person's own export from tafs cloud, as two files.
@MainActor
final class ExportChatsTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    private let body = #"{"exported_at":"2026-11-01T18:00:00Z","account":"acme","person":"u_ana","tafs":[{"id":"depl_1","name":"Rae","chats":[{"session":"sesn_9","kind":"chat","messages":[{"from":"taf","text":"Refunded."}]}],"notes":[]}],"readable":"Your chats in tafs\n\nRae\n- Refunded."}"#

    /// tafs.json is the answer without readable; tafs.txt is readable.
    func testTheExportSplitsIntoItsTwoFiles() throws {
        let (json, text) = try TafsCloud.splitExport(Data(body.utf8))
        XCTAssertEqual(text, "Your chats in tafs\n\nRae\n- Refunded.")
        let kept = try XCTUnwrap(JSONSerialization.jsonObject(with: json) as? [String: Any])
        XCTAssertNil(kept["readable"], "the text isn't in the JSON")
        XCTAssertEqual(kept["person"] as? String, "u_ana")
        XCTAssertEqual((kept["tafs"] as? [[String: Any]])?.first?["name"] as? String, "Rae")
    }

    func testSignedInItAsksTafsCloudAndSavesBothFiles() async throws {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: StubSigner(), kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        await model.useSession("kiff_dev_x")
        XCTAssertEqual(model.mode, .kiff)

        StubProtocol.responses["GET /v1/me/chats/export"] = (200, body)
        let result = await model.exportChats()
        let files = try XCTUnwrap(result.files, result.why ?? "")
        XCTAssertEqual(files.map(\.lastPathComponent), ["tafs-chats.json", "tafs-chats.txt"])
        XCTAssertEqual(try String(contentsOf: files[1], encoding: .utf8), "Your chats in tafs\n\nRae\n- Refunded.")
        let get = try XCTUnwrap(StubProtocol.sent.first { $0.url?.path == "/v1/me/chats/export" })
        XCTAssertEqual(get.value(forHTTPHeaderField: "Authorization"), "Bearer kiff_dev_x")

        StubProtocol.responses["GET /v1/me/chats/export"] = (502, #"{"error":"agents down"}"#)
        let failed = await model.exportChats()
        XCTAssertNil(failed.files)
        XCTAssertEqual(failed.why, "Your chats weren't exported. Check your connection and try again.")

        // Signing out takes the last export's files with it: nothing of the chats stays on the phone.
        StubProtocol.responses["POST /v1/auth/device/logout"] = (200, "{}")
        XCTAssertTrue(FileManager.default.fileExists(atPath: files[0].path))
        let signedOut = await model.signOut()
        XCTAssertTrue(signedOut)
        XCTAssertFalse(FileManager.default.fileExists(atPath: files[0].path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: AppModel.exportedFiles.path))
    }
}
