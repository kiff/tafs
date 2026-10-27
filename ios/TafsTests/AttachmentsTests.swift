import XCTest
import ImageIO
import UIKit
import UniformTypeIdentifiers
@testable import Tafs

/// Files a person attaches to a message (kiff/tafs#209): the tray while they go up, and
/// what happens to them when the message goes, or doesn't.
@MainActor
final class AttachmentsTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        super.tearDown()
    }

    /// A model signed in with KIFF whose one taf is Rae, run by tafs cloud as depl_1, with
    /// tafs cloud ready to take a file and storage to take its bytes.
    private func kiffModel() async throws -> (AppModel, UUID) {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: StubSigner(),
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, #"{"exceptions":[]}"#)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        await model.useSession("kiff_dev_x")
        let rae = try XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.id, "Rae is named by tafs cloud")
        Self.acceptFiles()
        return (model, rae)
    }

    /// tafs cloud declares each file as att_1, and storage takes its bytes.
    private static func acceptFiles() {
        StubProtocol.responses["POST /v1/tafs/depl_1/attachments"] = (200, """
        {"attachment":{"id":"att_1","kind":"text","media_type":"text/plain","name":"policy.txt","size":5},
         "upload":{"url":"https://storage.test/put/att_1","method":"PUT","headers":{"Content-Type":"text/plain"}}}
        """)
        StubProtocol.responses["PUT /put/att_1"] = (200, "")
    }

    private static func file(_ name: String = "policy.txt") -> PreparedFile {
        PreparedFile(name: name, mediaType: "text/plain", data: Data("hello".utf8), thumbnail: nil)
    }

    /// A file picked goes up: declared to tafs cloud, then its bytes straight to storage with
    /// no KIFF session, and the tray shows it ready.
    func testAFilePickedGoesUpAndIsReady() async throws {
        let (model, id) = try await kiffModel()
        let key = id.uuidString
        XCTAssertEqual(model.filesTarget(key), "depl_1")
        await model.attach(Self.file(), to: key)

        let tray = model.files(key)
        XCTAssertEqual(tray.count, 1)
        XCTAssertEqual(tray.first?.ref?.id, "att_1")
        XCTAssertFalse(model.filesUploading(key))
        let declared = try XCTUnwrap(StubProtocol.sent.first { $0.httpMethod == "POST" && $0.url?.path == "/v1/tafs/depl_1/attachments" })
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: declared.httpBody ?? Data()) as? [String: Any])
        XCTAssertEqual(body["media_type"] as? String, "text/plain")
        XCTAssertEqual(body["size"] as? Int, 5)
        XCTAssertEqual(body["sha256"] as? String, FilePrep.sha256(Data("hello".utf8)))
        let put = try XCTUnwrap(StubProtocol.sent.first { $0.httpMethod == "PUT" })
        XCTAssertEqual(put.url?.host, "storage.test")
        XCTAssertNil(put.value(forHTTPHeaderField: "Authorization"), "no KIFF session goes to storage")
    }

    /// A file whose upload fails stays in the tray saying why, and isn't taken with the message.
    func testAFileThatDidntGoUpStaysInTheTrayAndIsLeftOut() async throws {
        let (model, id) = try await kiffModel()
        let key = id.uuidString
        await model.attach(Self.file(), to: key)
        StubProtocol.responses["PUT /put/att_1"] = (403, "")
        await model.attach(Self.file("second.txt"), to: key)

        guard case .failed(let why)? = model.files(key).last?.state else { return XCTFail("the second file failed") }
        XCTAssertTrue(why.contains("didn't upload"), why)
        let taken = model.takeFiles(key)
        XCTAssertEqual(taken.map(\.name), ["policy.txt"])
        XCTAssertEqual(model.files(key).map(\.name), ["second.txt"], "the failed one stays, saying why")
    }

    /// A message carries at most five files: a sixth is refused in the tray, never uploaded.
    func testASixthFileIsRefused() async throws {
        let (model, id) = try await kiffModel()
        let key = id.uuidString
        for i in 1...FilePrep.perMessage { await model.attach(Self.file("f\(i).txt"), to: key) }
        let declared = StubProtocol.sent.filter { $0.httpMethod == "POST" }.count
        await model.attach(Self.file("f6.txt"), to: key)
        XCTAssertEqual(model.files(key).count, FilePrep.perMessage)
        XCTAssertEqual(model.fileNotices[key], "A message carries at most 5 files.")
        XCTAssertEqual(StubProtocol.sent.filter { $0.httpMethod == "POST" }.count, declared, "the sixth never went up")
    }

    /// A file over its type's limit, or of a type tafs can't take, is refused before upload,
    /// in words the person reads.
    func testFilesOverTheLimitOrOfAnotherKindAreRefusedFirst() {
        XCTAssertThrowsError(try FilePrep.check(name: "big.pdf", size: 26 << 20, mediaType: "application/pdf")) {
            XCTAssertEqual($0.localizedDescription, "big.pdf is 26.0 MB; the most is 25.0 MB.")
        }
        XCTAssertNoThrow(try FilePrep.check(name: "ok.pdf", size: 25 << 20, mediaType: "application/pdf"))
        XCTAssertThrowsError(try FilePrep.file(name: "song.mp3", data: Data([0]), type: .mp3)) {
            XCTAssertTrue($0.localizedDescription.hasPrefix("song.mp3 can't be attached."), $0.localizedDescription)
        }
    }

    /// Sent with its files, a message names them to tafs cloud and the tray is cleared.
    func testAMessageGoesWithItsFiles() async throws {
        let (model, id) = try await kiffModel()
        let key = id.uuidString
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (200, #"{"session":"sesn_9","new_session":false}"#)
        StubProtocol.responses["GET /v1/tafs/depl_1/conversation"] = (200, #"{"messages":[]}"#)
        await model.attach(Self.file(), to: key)

        let files = model.takeFiles(key)
        XCTAssertTrue(model.files(key).isEmpty, "the tray is cleared as the message goes")
        let sent = await model.submitChat("What does it say about refunds?", to: id, files: files.compactMap(\.ref))
        XCTAssertTrue(sent)
        let post = try XCTUnwrap(StubProtocol.sent.first { $0.httpMethod == "POST" && $0.url?.path == "/v1/tafs/depl_1/messages" })
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: post.httpBody ?? Data()) as? [String: Any])
        XCTAssertEqual(body["attachments"] as? [String], ["att_1"])
        XCTAssertEqual(body["text"] as? String, "What does it say about refunds?")
    }

    /// A message with files that doesn't go is left with the composer, files and all: not
    /// kept in the thread as not sent, and the files go back ahead of any picked since.
    func testAMessageWithFilesThatDoesntGoGivesThemBack() async throws {
        let (model, id) = try await kiffModel()
        let key = id.uuidString
        StubProtocol.responses["POST /v1/tafs/depl_1/messages"] = (500, #"{"error":"down"}"#)
        await model.attach(Self.file(), to: key)

        let files = model.takeFiles(key)
        await model.attach(Self.file("later.txt"), to: key)
        let sent = await model.submitChat("Read this", to: id, files: files.compactMap(\.ref))
        XCTAssertFalse(sent)
        // What the chat view does when Send isn't taken: the files go back to the tray.
        model.putBackFiles(files, key)

        XCTAssertEqual(model.files(key).map(\.name), ["policy.txt", "later.txt"])
        XCTAssertTrue(model.chatState(id).unsent.isEmpty, "not kept as not sent: the composer has it")
        XCTAssertFalse(model.chats[id]?.contains { $0.session == "pending" } ?? false)
        XCTAssertNotNil(model.chatErrors[id], "a line under the thread says why")
    }

    /// Send waits while a file is going up, words or not: a message never goes without a file
    /// just attached (the review on #287). Ready files wait while the taf works; words alone don't.
    func testSendWaitsWhileAFileIsGoingUp() async throws {
        let (model, id) = try await kiffModel()
        let key = id.uuidString
        XCTAssertFalse(model.holdsSend(key, thinking: false), "nothing attached")
        XCTAssertFalse(model.holdsSend(key, thinking: true), "words alone go to the run at work")

        StubProtocol.holdOnce = "PUT /put/att_1"
        let upload = Task { await model.attach(Self.file(), to: key) }
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async { StubProtocol.arrived.wait(); done.resume() }
        }
        XCTAssertTrue(model.filesUploading(key))
        XCTAssertTrue(model.holdsSend(key, thinking: false), "text and a file going up: Send waits")
        XCTAssertTrue(model.takeFiles(key).isEmpty, "nothing ready to take")
        XCTAssertEqual(model.files(key).count, 1, "the file going up stays in the tray")

        StubProtocol.release.signal()
        await upload.value
        XCTAssertFalse(model.holdsSend(key, thinking: false), "all up: Send goes")
        XCTAssertTrue(model.holdsSend(key, thinking: true), "ready files wait for the taf to be done")
    }

    /// An image from Files goes as a photo does: a JPEG drawn again, with no metadata (location
    /// included) and white where it was transparent.
    func testAnImageFromFilesIsSentAsAPhoto() throws {
        // A 32×32 PNG, its lower half transparent, with a GPS location in its metadata.
        let image = UIGraphicsImageRenderer(size: CGSize(width: 32, height: 32), format: {
            let f = UIGraphicsImageRendererFormat.default(); f.scale = 1; f.opaque = false; return f
        }()).image { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 32, height: 16))
        }
        let png = NSMutableData()
        let dest = try XCTUnwrap(CGImageDestinationCreateWithData(png, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(dest, try XCTUnwrap(image.cgImage),
                                   [kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 59.43, kCGImagePropertyGPSLongitude: 24.75]] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        let source = CGImageSourceCreateWithData(png as CFData, nil)!
        let before = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        XCTAssertNotNil(before?[kCGImagePropertyGPSDictionary], "the PNG carries a location")

        let sent = try FilePrep.file(name: "map.png", data: png as Data, type: .png)
        XCTAssertEqual(sent.mediaType, "image/jpeg")
        XCTAssertEqual(sent.name, "map.jpg")
        let out = try XCTUnwrap(CGImageSourceCreateWithData(sent.data as CFData, nil))
        let after = CGImageSourceCopyPropertiesAtIndex(out, 0, nil) as? [CFString: Any]
        XCTAssertNil(after?[kCGImagePropertyGPSDictionary], "no location goes")
        XCTAssertNil(after?[kCGImagePropertyExifDictionary].flatMap { ($0 as? [CFString: Any])?[kCGImagePropertyExifDateTimeOriginal] })
        // The transparent half is white, not black: its bottom-left pixel (Core Graphics counts up
        // from the bottom), a JPEG block away from the red.
        let decoded = try XCTUnwrap(UIImage(data: sent.data)?.cgImage)
        var pixel = [UInt8](repeating: 0, count: 4)
        let ctx = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(decoded, in: CGRect(x: 0, y: 0, width: 32, height: 32))
        XCTAssertGreaterThan(Int(pixel[0]) + Int(pixel[1]) + Int(pixel[2]), 600, "white where it was transparent: \(pixel)")
    }

    /// A file opened full size is a copy on the phone: it goes on sign-out.
    func testOpenedFilesGoOnSignOut() async throws {
        let (model, _) = try await kiffModel()
        let dir = AppModel.openedFiles.appendingPathComponent("att_x", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("hi".utf8).write(to: dir.appendingPathComponent("policy.txt"))
        await model.signOut()
        XCTAssertFalse(FileManager.default.fileExists(atPath: AppModel.openedFiles.path))
    }

    /// Where nothing can be attached, nothing is: a sample taf has no tafs cloud to take files.
    func testASampleTafTakesNoFiles() async throws {
        let model = AppModel(signer: StubSigner())
        await model.startSample()
        let id = try XCTUnwrap(model.tafs.first?.id)
        XCTAssertNil(model.filesTarget(id.uuidString))
        await model.attach(Self.file(), to: id.uuidString)
        XCTAssertTrue(model.files(id.uuidString).isEmpty)
    }

    /// Signing out leaves nothing of a tray behind.
    func testSigningOutClearsTheTray() async throws {
        let (model, id) = try await kiffModel()
        let key = id.uuidString
        await model.attach(Self.file(), to: key)
        model.refuseFile(FileProblem(message: "nope"), in: key)
        await model.signOut()
        XCTAssertTrue(model.files(key).isEmpty)
        XCTAssertNil(model.fileNotices[key])
    }
}
