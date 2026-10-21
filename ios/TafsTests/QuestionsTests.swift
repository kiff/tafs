import XCTest
@testable import Tafs

/// Several questions at once (kiff/tafs#138, item 57): the card is the agent's, its answers
/// one message, read back in the owner's words.
@MainActor
final class QuestionsTests: XCTestCase {
    static let card = #"""
    {"type":"questions","id":"setup-basics","text":"Three things before I connect Stripe:",
     "questions":[
       {"id":"mode","text":"Test mode or real money?","options":[{"value":"test","label":"Test mode","description":"Recommended to start"},{"value":"live","label":"Real money"}]},
       {"id":"tools","text":"What may it do?","multi":true,"options":[{"value":"refund","label":"Refund"},{"value":"read","label":"Look up payments"}]},
       {"id":"limit","text":"How much a day before it asks you?","note":"Each refund over 50 € already waits for you.","options":[{"value":"10000","label":"100 €"},{"value":"25000","label":"250 €"}]}]}
    """#

    func testTheCardIsTheAgents() throws {
        let c = try JSONDecoder().decode(SetupComponent.self, from: Data(Self.card.utf8))
        XCTAssertEqual(c.type, .questions)
        XCTAssertEqual(c.questions?.map(\.id), ["mode", "tools", "limit"])
        XCTAssertEqual(c.questions?.first?.options.first?.description, "Recommended to start")
        XCTAssertEqual(c.questions?[1].multi, true)
        XCTAssertEqual(c.questions?[2].note, "Each refund over 50 € already waits for you.")
        XCTAssertNil(try JSONDecoder().decode(SetupComponent.self, from: Data(#"{"type":"confirm","id":"c1","text":"Ok?","options":[]}"#.utf8)).questions)
    }

    func testTheAnswersAreOneMessageReadBackInTheOwnersWords() throws {
        let c = try JSONDecoder().decode(SetupComponent.self, from: Data(Self.card.utf8))
        let text = QuestionAnswers.encode(["mode": .choice("test"), "tools": .choices(["refund", "read"]), "limit": .own("300 € on weekdays")])
        XCTAssertEqual(text, #"{"limit":{"own":"300 € on weekdays"},"mode":{"value":"test"},"tools":{"values":["refund","read"]}}"#)
        XCTAssertEqual(QuestionAnswers.decode(text)?["limit"], .own("300 € on weekdays"))
        XCTAssertEqual(QuestionAnswers.words(text, questions: c.questions ?? []),
                       "Test mode · Refund, Look up payments · In your words: “300 € on weekdays”", "in the card's order")
        XCTAssertNil(QuestionAnswers.words("not json", questions: c.questions ?? []))

        let answer = try XCTUnwrap(SetupThread.Answer("[questions setup-basics] " + text))
        XCTAssertEqual(answer.type, "questions")
        XCTAssertEqual(answer.id, "setup-basics")
        XCTAssertEqual(answer.text, text)
    }

    /// A multi question's answer is its picks or the owner's words, never words that
    /// silently drop the picks on screen.
    func testAMultiQuestionIsItsPicksOrTheOwnersWords() throws {
        let c = try JSONDecoder().decode(SetupComponent.self, from: Data(Self.card.utf8))
        let tools = try XCTUnwrap(c.questions?.first { $0.multi == true })
        let (first, second) = (tools.options[0].value, tools.options[1].value)
        var s = QuestionSelection()
        s.pick(tools, first)
        s.pick(tools, second)
        XCTAssertEqual(s.answer(tools), .choices([first, second]))

        s.toggleOwn(tools)
        XCTAssertEqual(s.picked[tools.id], [], "opening Something else… clears the picks")
        XCTAssertNil(s.answer(tools), "no words yet: no answer")
        s.own[tools.id] = "only refunds under 50 €"
        XCTAssertEqual(s.answer(tools), .own("only refunds under 50 €"))

        s.pick(tools, first)
        XCTAssertFalse(s.ownOpen.contains(tools.id), "picking a choice closes the own field")
        XCTAssertEqual(s.answer(tools), .choices([first]))
    }
}

/// KIFF hears the phone's languages, so its own words reach the owner in theirs (kiff/tafs#75).
@MainActor
final class AcceptLanguageTests: XCTestCase {
    func testThePhonesLanguagesInOrderWithEnglishLast() {
        XCTAssertEqual(KIFFClient.acceptLanguage(["es-CO", "fr-FR"]), "es-CO, fr-FR;q=0.9, en;q=0.8")
        XCTAssertEqual(KIFFClient.acceptLanguage(["en-US"]), "en-US")
        XCTAssertEqual(KIFFClient.acceptLanguage([]), "en")
    }
}
