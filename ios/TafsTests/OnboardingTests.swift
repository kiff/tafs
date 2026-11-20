import XCTest
@testable import Tafs

/// The three cards after signing in (docs/onboarding.md).
final class OnboardingTests: XCTestCase {
    /// Next goes card by card; the last card's button ends them, and nothing goes past the last card.
    func testNextGoesCardByCardThenGetStartedEnds() {
        var steps = OnboardingSteps()
        XCTAssertEqual(steps.page, .tafs)
        XCTAssertFalse(steps.isLast)
        steps.advance()
        XCTAssertEqual(steps.page, .card)
        steps.advance()
        XCTAssertEqual(steps.page, .control)
        XCTAssertTrue(steps.isLast, "the last card's button is Get started")
        XCTAssertFalse(steps.finished)
        steps.advance()
        XCTAssertTrue(steps.finished)
        XCTAssertEqual(steps.page, .control, "Get started ends on the last card, never past it")
    }

    /// Skip ends the cards from any card.
    func testSkipEndsFromAnyCard() {
        for start in OnboardingPage.allCases {
            var steps = OnboardingSteps(start: start)
            steps.skip()
            XCTAssertTrue(steps.finished, "\(start)")
        }
    }

    /// Reopened from How tafs works, the cards open on the one tapped.
    func testHowItWorksOpensOnTheCardTapped() {
        XCTAssertEqual(OnboardingSteps(start: .card).page, .card)
        XCTAssertEqual(OnboardingSteps(start: .control).page, .control)
    }

    /// The last card names each device's own way to approve: a Mac with Touch ID or on the phone, never Face ID.
    func testTheLastCardNamesThisDevicesWayToApprove() {
        XCTAssertEqual(OnboardingPage.control.lead(isMac: false), Words.Onboarding.controlLead)
        XCTAssertEqual(OnboardingPage.control.lead(isMac: true), Words.Onboarding.controlLeadMac)
        XCTAssertNotEqual(Words.Onboarding.controlLead, Words.Onboarding.controlLeadMac)
        XCTAssertEqual(OnboardingPage.tafs.lead(isMac: true), OnboardingPage.tafs.lead(isMac: false), "the other cards read the same everywhere")
    }

    /// The cards follow the celebration once on this device, and only with the new onboarding on.
    func testTheCardsShowOnceAndOnlyWhenOn() {
        XCTAssertTrue(Onboarding.showsCards(on: true, seen: false))
        XCTAssertFalse(Onboarding.showsCards(on: true, seen: true), "seen here: Home at once")
        XCTAssertFalse(Onboarding.showsCards(on: false, seen: false), "off: the celebration goes straight to Home, as it ships")
        XCTAssertFalse(Onboarding.showsCards(seen: false), "ships off")
    }

    /// The pictures draw the sample's own tafs, the ones that never move money, and its Card, 28 left of 40.
    func testThePicturesDrawTheSamplesOwnTafsAndCard() {
        XCTAssertEqual(OnboardingPicture.sampleTafs.map(\.name),
                       [TafTemplates.support.name, TafTemplates.parcels.name, TafTemplates.leads.name])
        let card = OnboardingPicture.sampleCard
        XCTAssertEqual(card.calls?.limit, 40)
        XCTAssertEqual(card.calls?.used, 12)
        XCTAssertEqual(card.title, Words.Sample.Card.refundsStripe)
    }
}
