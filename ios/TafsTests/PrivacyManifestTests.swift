import XCTest
@testable import Tafs

/// The App Store's privacy manifest ships in the app (kiff/tafs#185): no tracking, and the
/// reason for each required-reason API it uses.
final class PrivacyManifestTests: XCTestCase {
    func testTheAppShipsItsPrivacyManifest() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"), "PrivacyInfo.xcprivacy is in the app's resources")
        let manifest = try XCTUnwrap(PropertyListSerialization.propertyList(from: Data(contentsOf: url), format: nil) as? [String: Any])
        XCTAssertEqual(manifest["NSPrivacyTracking"] as? Bool, false)
        let apis = try XCTUnwrap(manifest["NSPrivacyAccessedAPITypes"] as? [[String: Any]])
        let defaults = apis.first { $0["NSPrivacyAccessedAPIType"] as? String == "NSPrivacyAccessedAPICategoryUserDefaults" }
        XCTAssertEqual(defaults?["NSPrivacyAccessedAPITypeReasons"] as? [String], ["CA92.1"])
        // Photos a person attaches to a message (kiff/tafs#209): theirs, for the app's use only.
        let collected = try XCTUnwrap(manifest["NSPrivacyCollectedDataTypes"] as? [[String: Any]])
        let photos = try XCTUnwrap(collected.first { $0["NSPrivacyCollectedDataType"] as? String == "NSPrivacyCollectedDataTypePhotosorVideos" })
        XCTAssertEqual(photos["NSPrivacyCollectedDataTypeLinked"] as? Bool, true)
        XCTAssertEqual(photos["NSPrivacyCollectedDataTypeTracking"] as? Bool, false)
        XCTAssertEqual(photos["NSPrivacyCollectedDataTypePurposes"] as? [String], ["NSPrivacyCollectedDataTypePurposeAppFunctionality"])
    }
}
