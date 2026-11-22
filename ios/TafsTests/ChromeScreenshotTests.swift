import CryptoKit
import XCTest
@testable import Tafs

/// The picture kept before a Chrome step's act (docs/chrome.md, "Screenshots of a run's acts"): what the app accepts of
/// planque's answer, and nothing else.
final class ChromeScreenshotTests: XCTestCase {
    private func webp(_ size: Int = 64) -> Data {
        var d = Data("RIFF".utf8)
        d.append(contentsOf: [UInt8(truncatingIfNeeded: size), 0, 0, 0])
        d.append(Data("WEBP".utf8))
        d.append(Data(repeating: 7, count: size - 4))
        return d
    }

    private func answer(_ image: Data, sha: String? = nil, at: String = "2026-11-22T17:20:00Z", image text: String? = nil) -> Data {
        let hash = sha ?? SHA256.hash(data: image).map { String(format: "%02x", $0) }.joined()
        let body: [String: String] = ["step": "st_1", "run": "r1", "grant": "g1", "taken_at": at, "sha256": hash,
                                      "image": text ?? ChromePairing.base64URL(image)]
        return try! JSONSerialization.data(withJSONObject: body)
    }

    func testAPictureWhoseHashHoldsIsRead() throws {
        let image = webp()
        let shot = try XCTUnwrap(PlanqueClient.ChromeScreenshot.read(answer(image)))
        XCTAssertEqual(shot.step, "st_1")
        XCTAssertEqual(shot.image, image)
        XCTAssertEqual(shot.takenAt, ISO8601DateFormatter().date(from: "2026-11-22T17:20:00Z"))
    }

    func testAnythingElseIsNotShown() {
        let image = webp()
        XCTAssertNil(PlanqueClient.ChromeScreenshot.read(answer(image, sha: String(repeating: "0", count: 64))), "a hash that isn't the bytes'")
        XCTAssertNil(PlanqueClient.ChromeScreenshot.read(answer(Data("<html>".utf8) + Data(repeating: 1, count: 40))), "not WebP")
        XCTAssertNil(PlanqueClient.ChromeScreenshot.read(answer(webp(PlanqueClient.ChromeScreenshot.largest + 1))), "over 120 KB")
        XCTAssertNil(PlanqueClient.ChromeScreenshot.read(answer(image, image: "not base64url!")), "not base64url")
        XCTAssertNil(PlanqueClient.ChromeScreenshot.read(answer(image, at: "yesterday")), "no time")
        XCTAssertNil(PlanqueClient.ChromeScreenshot.read(Data("{}".utf8)), "not the shape")
    }

    func testTheHashMayBeInCapitals() {
        let image = webp()
        let upper = SHA256.hash(data: image).map { String(format: "%02X", $0) }.joined()
        XCTAssertNotNil(PlanqueClient.ChromeScreenshot.read(answer(image, sha: upper)))
    }
}
