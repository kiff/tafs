import XCTest
@testable import Tafs

/// The four cards after signing in (docs/onboarding.md, first-run v2).
final class OnboardingTests: XCTestCase {
    /// Next goes card by card, in the v2 order: tafs, Coach, the Card, credits. The last card's button ends them, and
    /// nothing goes past the last card.
    func testNextGoesCardByCardThenGetStartedEnds() {
        var steps = OnboardingSteps()
        XCTAssertEqual(OnboardingPage.allCases, [.tafs, .coach, .card, .credits])
        XCTAssertEqual(steps.page, .tafs)
        for page in [OnboardingPage.coach, .card, .credits] {
            XCTAssertFalse(steps.isLast)
            steps.advance()
            XCTAssertEqual(steps.page, page)
        }
        XCTAssertTrue(steps.isLast, "the last card's button is Get started")
        XCTAssertFalse(steps.finished)
        steps.advance()
        XCTAssertTrue(steps.finished)
        XCTAssertEqual(steps.page, .credits, "Get started ends on the last card, never past it")
    }

    /// A swipe left goes on and a swipe right goes back, never past either end; only the last card's button, or Skip,
    /// ends the cards.
    func testSwipesMoveBetweenCardsButNeverEndThem() {
        var steps = OnboardingSteps()
        steps.back()
        XCTAssertEqual(steps.page, .tafs, "nothing before the first card")
        steps.swipeForward()
        XCTAssertEqual(steps.page, .coach)
        steps.back()
        XCTAssertEqual(steps.page, .tafs)
        var last = OnboardingSteps(start: .credits)
        last.swipeForward()
        XCTAssertEqual(last.page, .credits)
        XCTAssertFalse(last.finished, "a swipe never ends the cards: Get started does")
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
        for page in OnboardingPage.allCases {
            XCTAssertEqual(OnboardingSteps(start: page).page, page)
        }
    }

    /// Each card says its shared lines: the Coach card today's Home lead, the credits card what credits buy, and the Card
    /// card ends with this device's own way to approve (a Mac with Touch ID or on the phone, never Face ID), each line
    /// its own paragraph.
    func testEachCardSaysItsLinesAndTheCardCardNamesThisDevicesWayToApprove() {
        XCTAssertEqual(OnboardingPage.tafs.lines(isMac: false), [Words.Onboarding.tafsLead])
        XCTAssertEqual(OnboardingPage.coach.lines(isMac: false), [Words.Onboarding.homeLead])
        XCTAssertEqual(OnboardingPage.credits.lines(isMac: false), [Words.Billing.creditsBuy])
        XCTAssertEqual(OnboardingPage.card.lines(isMac: false), [Words.Onboarding.cardLead, Words.Onboarding.controlLead])
        XCTAssertEqual(OnboardingPage.card.lines(isMac: true), [Words.Onboarding.cardLead, Words.Onboarding.controlLeadMac])
        XCTAssertNotEqual(Words.Onboarding.controlLead, Words.Onboarding.controlLeadMac)
        for page in OnboardingPage.allCases where page != .card {
            XCTAssertEqual(page.lines(isMac: true), page.lines(isMac: false), "\(page) reads the same everywhere")
        }
        XCTAssertEqual(OnboardingPage.coach.title, Words.Onboarding.coachTitle)
        XCTAssertEqual(OnboardingPage.credits.title, Words.Onboarding.creditsTitle)
    }

    /// A first taf to try is the first recipe tafs cloud lists with a proof, in its words; none proved, or the
    /// suggestions off, and there's none. The app never writes a job of its own.
    func testTheFirstTafToTryIsTheFirstProvedRecipe() {
        typealias R = TafsCloud.Recipes.Recipe
        let unproved = R(id: "a", title: "Late parcels", line: "Chases late parcels.", say: "Chase late parcels")
        let proved = R(id: "b", title: "Acknowledge every ticket", line: "Acknowledges each new ticket.", say: "Acknowledge tickets",
                       proved: "Ran on 25 Nov")
        let alsoProved = R(id: "c", title: "Lead follow-ups", line: "Follows up leads.", say: "Follow up leads", proved: "Ran on 26 Nov")
        XCTAssertEqual(TafsCloud.Recipes(recipes: [unproved, proved, alsoProved]).firstToTry?.id, "b", "the first proved, in tafs cloud's order")
        XCTAssertNil(TafsCloud.Recipes(recipes: [unproved]).firstToTry, "none proved: no card")
        XCTAssertNil(TafsCloud.Recipes(recipes: [proved], shown: false).firstToTry, "suggestions off: no card")
        let noWords = R(id: "d", title: "Acknowledge every ticket", line: "Acknowledges each new ticket.", say: " ", proved: "Ran")
        XCTAssertNil(TafsCloud.Recipes(recipes: [noWords]).firstToTry, "nothing for Coach's box: no card")
    }

    /// The cards follow the celebration once on this device, and only with the new onboarding on.
    func testTheCardsShowOnceAndOnlyWhenOn() {
        XCTAssertTrue(Onboarding.showsCards(on: true, seen: false))
        XCTAssertFalse(Onboarding.showsCards(on: true, seen: true), "seen here: Home at once")
        XCTAssertFalse(Onboarding.showsCards(on: false, seen: false), "off: the celebration goes straight to Home, as it ships")
        XCTAssertTrue(Onboarding.showsCards(seen: false), "ships on, in every build (K5)")
    }

    /// The first Home shows for anyone who may use Coach with the new onboarding on, whatever tafs cloud says there;
    /// never with it off, and never for someone who may not use Coach.
    func testTheFirstHomeShowsWithTheSwitchForWhoeverMayUseCoach() {
        XCTAssertTrue(Onboarding.showsFirstHome(on: true, mayUseCoach: true))
        XCTAssertFalse(Onboarding.showsFirstHome(on: true, mayUseCoach: false), "a viewer reads where shared tafs show, as today")
        XCTAssertFalse(Onboarding.showsFirstHome(on: false, mayUseCoach: true), "off: Home as it ships")
        XCTAssertTrue(Onboarding.showsFirstHome(mayUseCoach: true), "ships on, in every build (K5)")
    }

    /// tafs cloud's own empty line comes first when it sends one; the app's words only when it doesn't.
    func testTheFirstHomeSaysTafsCloudsLineFirst() {
        let cloud = Onboarding.firstHomeLines(cloud: "Nothing set up yet. Ask Coach.")
        XCTAssertEqual(cloud.title, "Nothing set up yet. Ask Coach.")
        XCTAssertNil(cloud.lead, "the server's words alone, no app line under them")
        for none in [nil, ""] as [String?] {
            let own = Onboarding.firstHomeLines(cloud: none)
            XCTAssertEqual(own.title, Words.Home.noTafs)
            XCTAssertEqual(own.lead, Words.Onboarding.homeLead)
        }
    }

    /// How tafs works titles its four legal rows as their pages, never with the tick line's link words (written to sit
    /// inside its sentence), and each opens its own page.
    func testTheLegalRowsUseStandaloneTitles() {
        let rows = HowTafsWorksView.legalRows
        XCTAssertEqual(rows.map(\.url), [Legal.kiffPrivacy, Legal.privacy, Legal.kiffTerms, Legal.terms])
        XCTAssertEqual(rows.map(\.title), [Words.Onboarding.rowKiffPrivacy, Words.Onboarding.rowTafsPrivacy,
                                           Words.Onboarding.rowKiffTerms, Words.Onboarding.rowTafsTerms])
        XCTAssertEqual(Set(rows.map(\.title)).count, 4, "four pages, four titles")
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

    /// The welcome's age line reads the person's country: India, Indonesia and Brazil say 18;
    /// anywhere else, or no region known, reads the line every country reads (16, or the country's age if higher).
    func testTheAgeLineIsTheCountrys() {
        XCTAssertEqual(MinimumAge.standard, 16)
        for region in ["IN", "ID", "BR", "in"] {
            XCTAssertEqual(MinimumAge.of(region), 18, region)
            XCTAssertEqual(Onboarding.ageLine(region: region), Words.Onboarding.ageN(age: "18"), region)
        }
        for region in ["FR", "KR", "US", nil] as [String?] {
            XCTAssertEqual(MinimumAge.of(region), 16, region ?? "none")
            XCTAssertEqual(Onboarding.ageLine(region: region), Words.Onboarding.age, region ?? "none")
        }
    }

    /// Today's sign-in screen tells a country that asks for more than 16 its own age, before the switch is on; every
    /// other country sees the screen as it was.
    func testTodaysSignInSaysARaisedAgeOnly() {
        XCTAssertEqual(Onboarding.raisedAgeLine(region: "IN"), Words.Onboarding.ageN(age: "18"))
        XCTAssertEqual(Onboarding.raisedAgeLine(region: "BR"), Words.Onboarding.ageN(age: "18"))
        XCTAssertNil(Onboarding.raisedAgeLine(region: "FR"))
        XCTAssertNil(Onboarding.raisedAgeLine(region: nil))
    }

    /// The iPad's wide layout opens on the Overview, so with no taf it is the first Home, as the phone's Home is.
    func testTheOverviewIsTheFirstHomeWithNoTaf() {
        func shows(kiff: Bool = true, loaded: Bool = true, unread: Bool = false, noTafs: Bool = true, coach: Bool = true, recipes: Bool = false) -> Bool {
            OverviewView.showsFirstHome(kiff: kiff, loaded: loaded, unread: unread, noTafs: noTafs, mayUseCoach: coach, listsRecipes: recipes)
        }
        XCTAssertTrue(shows())
        XCTAssertFalse(shows(kiff: false), "sample mode keeps the table")
        XCTAssertFalse(shows(loaded: false), "not read yet: no first Home flashing before the list")
        XCTAssertFalse(shows(unread: true), "a list that couldn't be read says so; it is not an empty account")
        XCTAssertFalse(shows(noTafs: false), "with a taf, the table")
        XCTAssertFalse(shows(coach: false), "someone who may not use Coach reads where shared tafs show")
        XCTAssertFalse(shows(recipes: true), "where tafs cloud lists recipes, Home is as it ships: the table, not the first Home")
    }
}
