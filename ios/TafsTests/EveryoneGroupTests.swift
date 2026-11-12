import XCTest
@testable import Tafs

/// KIFF's default group, the one it marks `everyone`: shown in the app's language, shared with in a
/// sentence of its own, and fixed, so no one is added to it or removed from it (as Android 1.8.1).
@MainActor
final class EveryoneGroupTests: XCTestCase {
    private let everyone = KIFFClient.Group(id: "grp_all", name: "Everyone", everyone: true)
    private let support = KIFFClient.Group(id: "grp_support", name: "Support")
    private let ana = KIFFClient.GroupMember(subject: "user_ana", role: "member")

    func testTheEveryoneGroupIsShownInTheAppsLanguage() {
        XCTAssertEqual(everyone.shownName, Words.People.everyoneGroup)
        XCTAssertEqual(support.shownName, "Support")
        // A group someone named "Everyone" by hand is theirs, as named.
        XCTAssertEqual(KIFFClient.Group(id: "grp_x", name: "Tout le monde").shownName, "Tout le monde")
    }

    func testSharedWithEveryoneIsItsOwnSentence() {
        let groups = [everyone, support]
        XCTAssertEqual(ShareLine.sharedWith([.init(group: "grp_all")], groups: groups) { $0 }, Words.People.sharedWithEveryone)
        XCTAssertEqual(ShareLine.sharedWith([.init(group: "grp_all")], groups: groups) { $0 }, "Shared with everyone")
        XCTAssertEqual(ShareLine.sharedWith([.init(group: "grp_support")], groups: groups) { $0 }, "Shared with Support")
        // Alongside someone else, the group is a name in the list.
        let both = ShareLine.sharedWith([.init(group: "grp_all"), .init(subject: "ana@acme.test")], groups: groups) { $0 }
        XCTAssertTrue(both.contains(Words.People.everyoneGroup), both)
        XCTAssertTrue(both.contains("ana@acme.test"), both)
        XCTAssertEqual(ShareLine.sharedWith([], groups: groups) { $0 }, Words.People.notShared)
    }

    /// The person reading the line is never named in it: a taf only they use is "Not shared",
    /// and one they share reads with the others alone.
    func testTheSharedLineNeverNamesThePersonReadingIt() {
        let groups: [KIFFClient.Group] = []
        let you: (String) -> Bool = { $0 == "usr_me" }
        XCTAssertEqual(ShareLine.sharedWith([.init(subject: "usr_me")], groups: groups, you: you) { $0 }, Words.People.notShared)
        XCTAssertEqual(ShareLine.sharedWith([.init(subject: "usr_me"), .init(subject: "ana@acme.test")], groups: groups, you: you) { $0 },
                       Words.People.sharedWithPrefix + "ana@acme.test")
        XCTAssertEqual(ShareLine.sharedWith([.init(subject: "ana@acme.test")], groups: groups, you: you) { $0 },
                       Words.People.sharedWithPrefix + "ana@acme.test", "someone else is still named")
        XCTAssertEqual(ShareLine.sharedWith([.init(subject: "usr_me")], groups: groups) { $0 }, Words.People.sharedWithPrefix + "usr_me",
                       "with nobody known as the reader, every subject is named")
        XCTAssertEqual(ShareLine.sharedWith([], groups: groups, you: you) { $0 }, Words.People.notShared)
    }

    func testNoOneIsRemovedFromTheEveryoneGroup() {
        XCTAssertFalse(PeopleGroupsView.mayRemove(ana, from: everyone, canManagePeople: true, managesGroup: true))
        XCTAssertTrue(PeopleGroupsView.mayRemove(ana, from: support, canManagePeople: true, managesGroup: false))
        XCTAssertTrue(PeopleGroupsView.mayRemove(ana, from: support, canManagePeople: false, managesGroup: true))
        let manager = KIFFClient.GroupMember(subject: "user_bo", role: "manager")
        XCTAssertFalse(PeopleGroupsView.mayRemove(manager, from: support, canManagePeople: false, managesGroup: true))
    }
}
