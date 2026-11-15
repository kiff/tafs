import XCTest
@testable import Tafs

/// What KIFF is told about the device: its name, platform and kind (docs/mac-app.md).
final class ThisDeviceTests: XCTestCase {
    func testAnIPhoneAndAnIPadAreNamedByTheirModel() {
        XCTAssertEqual(ThisDevice.kind(model: "iPhone", mac: false), "iPhone")
        XCTAssertEqual(ThisDevice.kind(model: "iPad", mac: false), "iPad")
        XCTAssertEqual(ThisDevice.kind(model: "iPod touch", mac: false), "iPhone")
    }

    /// The Mac build runs the iPad's layout and reports an iPad's model: it's still a Mac.
    func testTheMacBuildIsAMacWhateverTheModelSays() {
        XCTAssertEqual(ThisDevice.kind(model: "iPad", mac: true), "Mac")
        XCTAssertEqual(ThisDevice.kind(model: "iPhone", mac: true), "Mac")
    }

    func testThePlatformKIFFRecords() {
        XCTAssertEqual(ThisDevice.platform(kind: "iPhone"), "ios")
        XCTAssertEqual(ThisDevice.platform(kind: "iPad"), "ipados")
        XCTAssertEqual(ThisDevice.platform(kind: "Mac"), "macos")
    }

    /// KIFF's kinds name phones and tablets, so a computer sends "other".
    func testTheKindKIFFRecords() {
        XCTAssertEqual(ThisDevice.kiffKind(kind: "iPhone"), "iphone")
        XCTAssertEqual(ThisDevice.kiffKind(kind: "iPad"), "ipad")
        XCTAssertEqual(ThisDevice.kiffKind(kind: "Mac"), "other")
    }

    /// The build this test runs in agrees with itself: a Mac build says "macos", and an
    /// iPhone or iPad build never does.
    @MainActor
    func testThisBuild() {
        XCTAssertEqual(ThisDevice.platform == "macos", ThisDevice.isMac)
        XCTAssertEqual(ThisDevice.kind == "Mac", ThisDevice.isMac)
    }
}
