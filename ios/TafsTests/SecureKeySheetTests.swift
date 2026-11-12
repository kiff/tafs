import XCTest
@testable import Tafs

/// The key's sheet (docs/secure-connection.md): tafs cloud's words for it by the grant, the key
/// pasted and never typed, only its last 4 characters drawn, and the legal line's links.
@MainActor
final class SecureKeySheetTests: XCTestCase {
    private static let answer = #"""
    { "vendor": { "name": "Desk", "logo": "/v1/logos/0123456789abcdef0123456789abcdef", "host": "api.desk.example" },
      "title": "Secure connection to Desk",
      "lines": [
        { "kind": "reads_only", "text": "Read only: it can't pay, send or change anything in Desk" },
        { "kind": "sealed", "text": "Your key is sealed: it's never shown again, and the taf never sees it" },
        { "kind": "disconnect", "text": "You can disconnect it any time, in Connect apps" } ],
      "paste": { "button": "Paste the key", "what": "Paste the API key you created in Desk",
                 "pasted": "Key …{last4}", "cleared": "Clipboard cleared" },
      "details": { "title": "Details", "lines": [
        { "label": "Specification", "value": "https://desk.example/openapi.json" },
        { "label": "Sent only to", "value": "api.desk.example" } ] },
      "card_title": "Its Card",
      "legal": { "text": "By connecting, you agree to the {terms} and the {privacy}.",
                 "terms": { "words": "Terms", "url": "https://kiff.dev/terms" },
                 "privacy": { "words": "Privacy Policy", "url": "https://kiff.dev/privacy" } },
      "button": "Connect",
      "receipt": { "text": "{vendor} connected · key …{last4}", "disconnect": "Disconnect" } }
    """#

    private func cloud() -> TafsCloud {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
    }

    /// The sheet's words are asked for by the grant, and read as the contract writes them.
    func testTheSheetsWordsAreReadByTheGrant() async throws {
        let cloud = cloud()
        StubProtocol.responses["GET /v1/connect/grants/g_2/sheet"] = (200, Self.answer)
        let sheet = try await cloud.connectionSheet(grant: "g_2", proof: "t=1,v1=sig,d=dev_phone01")
        XCTAssertEqual(StubProtocol.sent.last?.value(forHTTPHeaderField: "Planque-Device-Signature"), "t=1,v1=sig,d=dev_phone01",
                       "read with this phone's signed grants read")
        XCTAssertEqual(sheet.vendor.name, "Desk")
        XCTAssertEqual(sheet.vendor.host, "api.desk.example")
        XCTAssertEqual(sheet.title, "Secure connection to Desk")
        XCTAssertEqual(sheet.lines.map(\.kind), ["reads_only", "sealed", "disconnect"])
        XCTAssertEqual(sheet.paste.what, "Paste the API key you created in Desk")
        XCTAssertEqual(sheet.details?.lines.map(\.label), ["Specification", "Sent only to"])
        XCTAssertEqual(sheet.button, "Connect")
        XCTAssertEqual(sheet.receipt?.disconnect, "Disconnect")
        XCTAssertEqual(StubProtocol.sent.last?.url?.path, "/v1/connect/grants/g_2/sheet")
    }

    /// A sheet with only what it must have still reads: no lines, details, legal line or receipt.
    func testASheetWithoutItsOptionalPartsStillReads() throws {
        let json = #"{"vendor":{"name":"Desk"},"title":"Secure connection to Desk","paste":{"button":"Paste","what":"Paste it","pasted":"Key …{last4}","cleared":"Cleared"},"button":"Connect"}"#
        let sheet = try KIFFClient.decoder.decode(TafsCloud.ConnectionSheet.self, from: Data(json.utf8))
        XCTAssertTrue(sheet.lines.isEmpty)
        XCTAssertNil(sheet.details)
        XCTAssertNil(sheet.legal)
        XCTAssertNil(sheet.receipt)
        XCTAssertNil(sheet.vendor.logo)
        XCTAssertNil(sheet.paste.next, "a tafs cloud from before the line: nothing is drawn after a paste")
    }

    /// After a paste, the next tap is in sight: tafs cloud's line for it under the key, and
    /// Connect right under the key (and the Card it signs with it) and the one legal line, above Details.
    func testConnectSitsRightUnderThePastedKeyWithTafsCloudsLineForIt() throws {
        let json = #"{"vendor":{"name":"Desk"},"title":"Secure connection to Desk","paste":{"button":"Paste","what":"Paste it","pasted":"Key …{last4}","cleared":"Cleared","next":"Key pasted. Tap Connect"},"button":"Connect"}"#
        let sheet = try KIFFClient.decoder.decode(TafsCloud.ConnectionSheet.self, from: Data(json.utf8))
        XCTAssertEqual(sheet.paste.next, "Key pasted. Tap Connect")

        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let file = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        let from = try XCTUnwrap(file.range(of: "private func keySheet("))
        let to = try XCTUnwrap(file.range(of: "private func heading("))
        let body = file[from.lowerBound..<to.lowerBound]
        let paste = try XCTUnwrap(body.range(of: "next: sheet.paste.next)"))
        let card = try XCTUnwrap(body.range(of: "connect.sheet.card"))
        let connect = try XCTUnwrap(body.range(of: "connect.sheet.connect"))
        let details = try XCTUnwrap(body.range(of: "DisclosureGroup(isExpanded: $detailsOpen)"))
        let legal = try XCTUnwrap(body.range(of: "connect.sheet.legal"))
        XCTAssertTrue(paste.lowerBound < card.lowerBound && card.lowerBound < legal.lowerBound, "the key, then the Card it signs with it")
        XCTAssertTrue(legal.lowerBound < connect.lowerBound, "what the tap agrees to is in sight before the tap")
        XCTAssertTrue(connect.lowerBound < details.lowerBound, "Details comes after Connect")
        XCTAssertTrue(file.contains("@State private var detailsOpen = false"), "Details starts collapsed")
    }

    /// An answer that isn't the sheet (no paste words) is not drawn as one.
    func testAnAnswerWithoutThePasteWordsIsNotASheet() {
        let json = #"{"vendor":{"name":"Desk"},"title":"Secure connection to Desk","button":"Connect"}"#
        XCTAssertThrowsError(try KIFFClient.decoder.decode(TafsCloud.ConnectionSheet.self, from: Data(json.utf8)))
    }

    private func grant(tools: String) throws -> PlanqueClient.Grant {
        let json = #"{"id":"g_2","status":"pending","text":"","digest":"d","vendor":"Desk","request":{"server":"https://api.desk.example","agent":"a","tools":\#(tools),"kind":"api_key"},"events":[]}"#
        return try KIFFClient.decoder.decode(PlanqueClient.Grant.self, from: Data(json.utf8))
    }

    /// "Read only" is believed only when the grant this phone read says so: at least one tool,
    /// every one a read. A sheet that says it of any other grant is refused. A sheet that says
    /// the connection may change things claims nothing to check.
    func testReadOnlyIsCheckedAgainstTheGrantThePhoneRead() throws {
        let readsOnly = try KIFFClient.decoder.decode(TafsCloud.ConnectionSheet.self, from: Data(Self.answer.utf8))
        XCTAssertTrue(SecureKey.saysTrue(readsOnly, of: try grant(tools: #"[{"tool":"list","read_only":true},{"tool":"get","read_only":true}]"#)))
        XCTAssertFalse(SecureKey.saysTrue(readsOnly, of: try grant(tools: #"[{"tool":"list","read_only":true},{"tool":"pay","read_only":false}]"#)), "one tool writes")
        XCTAssertFalse(SecureKey.saysTrue(readsOnly, of: try grant(tools: #"[{"tool":"list","read_only":true},{"tool":"pay"}]"#)), "one tool carries no flag")
        XCTAssertFalse(SecureKey.saysTrue(readsOnly, of: try grant(tools: "[]")), "no tool at all")
        let mayChange = try KIFFClient.decoder.decode(TafsCloud.ConnectionSheet.self, from: Data(Self.answer.replacingOccurrences(of: "reads_only", with: "may_change").utf8))
        XCTAssertTrue(SecureKey.saysTrue(mayChange, of: try grant(tools: #"[{"tool":"pay","read_only":false}]"#)))
        XCTAssertTrue(SecureKey.saysTrue(mayChange, of: try grant(tools: #"[{"tool":"list","read_only":true}]"#)), "it claims less than the grant allows")
    }

    /// A sheet whose "read only" isn't the grant's draws nothing to paste or sign.
    func testASheetThatDoesntMatchItsGrantHasNothingToSign() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let file = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        let refused = try XCTUnwrap(file.range(of: "if !SecureKey.saysTrue(sheet, of: grant) {"))
        let drawn = try XCTUnwrap(file.range(of: "keySheet(grant, sheet)", range: refused.upperBound..<file.endIndex))
        let between = String(file[refused.upperBound..<drawn.lowerBound])
        XCTAssertTrue(between.contains("Words.Connect.requestDoesNotMatchWhat"))
        XCTAssertFalse(between.contains("paste("), "nothing to paste")
        XCTAssertFalse(between.contains("approve("), "nothing to sign")
    }

    /// An approval card whose connection is already connected carries tafs cloud's mark: the
    /// card draws that line, and never the connection's text again.
    func testAnApprovalWhoseConnectionIsDoneShowsItsLine() throws {
        func component(_ connection: String) throws -> SetupComponent {
            try KIFFClient.decoder.decode(SetupComponent.self, from: Data(#"{"type":"approval","id":"card","grant":"g_2","draft":"dr_1"\#(connection)}"#.utf8))
        }
        XCTAssertEqual(try component(#","connection":{"done":true,"words":"Desk is connected (read only)"}"#).connection?.doneWords, "Desk is connected (read only)")
        XCTAssertNil(try component(#","connection":{"done":false,"words":"Desk is connected (read only)"}"#).connection?.doneWords, "not done")
        XCTAssertNil(try component(#","connection":{"done":true,"words":" "}"#).connection?.doneWords, "no words")
        XCTAssertNil(try component(#","connection":{"words":"Desk is connected"}"#).connection?.doneWords, "it doesn't say done")
        XCTAssertNil(try component("").connection, "a card from before the mark")
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let chat = try String(contentsOf: views.appendingPathComponent("SetupChatView.swift"), encoding: .utf8)
        let mark = try XCTUnwrap(chat.range(of: "if let done = component.connection?.doneWords {"))
        let text = try XCTUnwrap(chat.range(of: "Text(grant.text)", range: mark.upperBound..<chat.endIndex))
        XCTAssertTrue(chat[mark.upperBound..<text.lowerBound].contains("} else {"), "the connection's text is drawn only when the mark isn't there")
    }

    /// The first line's kind picks an icon only. One this app doesn't know still draws its words.
    func testALinesKindPicksItsIconOnly() throws {
        XCTAssertNotEqual(SecureKey.icon("reads_only"), SecureKey.icon("may_change"), "reading and changing never look the same")
        XCTAssertFalse(SecureKey.icon("something_new").isEmpty)
    }

    /// The key from a paste: the first text, without the blanks a copy adds; nothing, nothing.
    func testTheKeyIsTakenFromThePasteTrimmed() {
        XCTAssertEqual(SecureKey.taken(from: ["  desk_live_secret_123\n"]), "desk_live_secret_123")
        XCTAssertEqual(SecureKey.taken(from: ["desk_live_secret_123", "other"]), "desk_live_secret_123")
        XCTAssertNil(SecureKey.taken(from: []))
        XCTAssertNil(SecureKey.taken(from: [" \n"]))
    }

    /// Only the last 4 characters are ever drawn, and none of a key too short for them to hide the rest.
    func testOnlyTheLast4AreDrawn() {
        XCTAssertEqual(SecureKey.last4(of: "desk_live_secret_ab12"), "ab12")
        XCTAssertEqual(SecureKey.last4(of: "123456789012"), "9012")
        XCTAssertEqual(SecureKey.last4(of: "12345678901"), "", "11 characters: 4 would show too much of it")
        XCTAssertEqual(SecureKey.last4(of: "ab12"), "")
        XCTAssertEqual(SecureKey.filled("Key …{last4}", last4: "ab12", vendor: "Desk"), "Key …ab12")
        XCTAssertEqual(SecureKey.filled("{vendor} connected · key …{last4}", last4: "ab12", vendor: "Desk"), "Desk connected · key …ab12")
        XCTAssertEqual(SecureKey.filled("Desk connected · key …{last4}", last4: "ab12", vendor: "Desk"), "Desk connected · key …ab12", "tafs cloud may fill the vendor itself")
    }

    /// The legal line's two marks become links, in the line's own order; a link that isn't
    /// https is words alone; a mark with no link leaves nothing behind.
    func testTheLegalLinesLinks() throws {
        let sheet = try KIFFClient.decoder.decode(TafsCloud.ConnectionSheet.self, from: Data(Self.answer.utf8))
        let line = SecureKey.legal(try XCTUnwrap(sheet.legal))
        XCTAssertEqual(String(line.characters), "By connecting, you agree to the Terms and the Privacy Policy.")
        let links = line.runs.compactMap(\.link)
        XCTAssertEqual(links.map(\.absoluteString), ["https://kiff.dev/terms", "https://kiff.dev/privacy"])

        typealias Legal = TafsCloud.ConnectionSheet.Legal
        let swapped = SecureKey.legal(Legal(text: "{privacy} / {terms}", terms: .init(words: "Terms", url: "https://kiff.dev/de/terms"), privacy: .init(words: "Privacy", url: "https://kiff.dev/de/privacy")))
        XCTAssertEqual(String(swapped.characters), "Privacy / Terms")
        XCTAssertEqual(swapped.runs.compactMap(\.link).map(\.absoluteString), ["https://kiff.dev/de/privacy", "https://kiff.dev/de/terms"])

        let plain = SecureKey.legal(Legal(text: "See the {terms}.", terms: .init(words: "Terms", url: "http://kiff.dev/terms"), privacy: nil))
        XCTAssertEqual(String(plain.characters), "See the Terms.")
        XCTAssertTrue(plain.runs.compactMap(\.link).isEmpty, "not https: no link")

        let none = SecureKey.legal(Legal(text: "See the {terms} and {privacy}.", terms: nil, privacy: nil))
        XCTAssertEqual(String(none.characters), "See the  and .")
    }

    /// The logo is one of tafs cloud's own, by its path or its full address here; no other address is loaded.
    func testTheLogoIsTafsCloudsOwn() {
        let cloud = cloud()
        let path = "/v1/logos/0123456789abcdef0123456789abcdef"
        XCTAssertEqual(cloud.sheetLogoURL(path)?.absoluteString, "https://cloud.test" + path)
        XCTAssertEqual(cloud.sheetLogoURL("https://cloud.test" + path)?.absoluteString, "https://cloud.test" + path)
        XCTAssertNil(cloud.sheetLogoURL("https://elsewhere.example" + path), "another host")
        XCTAssertNil(cloud.sheetLogoURL("http://cloud.test" + path), "not https")
        XCTAssertNil(cloud.sheetLogoURL("https://cloud.test/v1/other/0123456789abcdef0123456789abcdef"), "not a logo")
        XCTAssertNil(cloud.sheetLogoURL(nil))
    }

    /// The key is never typed: the sheet's file has no text field of any kind, and pastes with
    /// Apple's own button. The pasted key is forgotten once it's given, before the receipt shows.
    func testTheKeyIsPastedNeverTyped() throws {
        let views = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Tafs/Views")
        let file = try String(contentsOf: views.appendingPathComponent("ConnectAppsView.swift"), encoding: .utf8)
        let sheet = String(file[try XCTUnwrap(file.range(of: "struct ConnectApprovalSheet")).lowerBound...])
        for field in ["DriveField(", "SecureField(", "TextField(", "TextEditor("] {
            XCTAssertFalse(sheet.contains(field), "a typing field on the key's sheet: \(field)")
        }
        XCTAssertTrue(sheet.contains("PasteButton(payloadType: String.self)"))
        XCTAssertFalse(sheet.contains("UIPasteboard.general.string"), "the clipboard is read only by the paste button")
        let given = try XCTUnwrap(sheet.range(of: "if await model.giveKey("))
        let after = sheet[given.upperBound...]
        let forgot = try XCTUnwrap(after.range(of: "key = \"\""))
        let shown = try XCTUnwrap(after.range(of: "given = true"))
        XCTAssertTrue(forgot.lowerBound < shown.lowerBound, "the key is forgotten before the receipt shows")
    }
}
