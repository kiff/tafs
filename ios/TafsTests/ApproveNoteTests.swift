import XCTest
@testable import Tafs

/// Approve's note under an app event on Starts when says the device it's on (`setup.approve_note_device`):
/// a Mac reads "this Mac", never "this phone".
@MainActor
final class ApproveNoteTests: XCTestCase {
    func testTheNoteNamesThisDevice() {
        XCTAssertEqual(StartsWhenView.approveNote, Words.Setup.approveNoteDevice(device: ThisDevice.kind))
        XCTAssertTrue(StartsWhenView.approveNote.contains(ThisDevice.kind), StartsWhenView.approveNote)
    }

    func testOnAMacItSaysMacNotPhone() {
        let mac = Words.Setup.approveNoteDevice(device: "Mac")
        XCTAssertTrue(mac.contains("Mac"), mac)
        XCTAssertFalse(mac.localizedCaseInsensitiveContains("phone"), mac)
        XCTAssertTrue(Words.Setup.approveNoteDevice(device: "iPad").contains("iPad"))
    }
}
