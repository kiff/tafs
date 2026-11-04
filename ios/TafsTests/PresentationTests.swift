import XCTest
@testable import Tafs

@MainActor
final class PresentationTests: XCTestCase {
    func testConditionalActivityKeepsItsValidatorAndOpaqueCursor() async throws {
        StubProtocol.responses = [:]; StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let cloud = TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "synthetic-session", session: URLSession(configuration: config))
        StubProtocol.responses["GET /v1/tafs/taf-example/activity?filter=held"] = (200, #"{"entries":[],"groups":[],"filters":[{"id":"held","title":"Held"}],"filter":"held","revision":"r","next_cursor":"opaque+/="}"#)
        let (page, _) = try await cloud.activityPage("taf-example", filter: "held", cursor: nil, etag: nil, selection: "")
        XCTAssertEqual(page?.next_cursor, "opaque+/=")
        StubProtocol.responses["GET /v1/tafs/taf-example/activity?filter=held"] = (304, "")
        let (same, tag) = try await cloud.activityPage("taf-example", filter: "held", cursor: nil, etag: "\"view-1\"", selection: "")
        XCTAssertNil(same); XCTAssertEqual(tag, "\"view-1\"")
        XCTAssertEqual(StubProtocol.sent.last?.value(forHTTPHeaderField: "If-None-Match"), tag)
        XCTAssertEqual(StubProtocol.sent.last?.cachePolicy, .reloadIgnoringLocalCacheData, "the app owns cached pages and must see the wire 304")
        StubProtocol.responses["GET /v1/tafs/taf-example/activity?filter=held&cursor=opaque%2B%2F%3D"] = (304, "")
        _ = try await cloud.activityPage("taf-example", filter: "held", cursor: page?.next_cursor, etag: nil, selection: "")
        XCTAssertTrue(StubProtocol.sent.last?.url?.absoluteString.hasSuffix("cursor=opaque%2B%2F%3D") == true)
    }
}
