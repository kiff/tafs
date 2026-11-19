import XCTest
@testable import Tafs

/// A taf's doors in the app (docs/doors.md): what tafs cloud sends reads as the app shows it,
/// the dial lands on the door the owner turned to, and the owner's answers reach tafs cloud.
@MainActor
final class DoorsTests: XCTestCase {
    private static let doorsJSON = #"""
    {"doors":[{"id":"email","app":"email","kind":"email","label":"Email","open":true,"address":"refunds@tafs.example",
      "proof":"proven","how":"The email must pass the sender check","keys":[{"id":"ana@acme.example","auto":true}],
      "today":2,"limit":20,"resting":false,"others_today":10,"others_limit":10}],
     "apps":[{"id":"email","label":"Email","kind":"email","proof":"proven","how":"x"},
             {"id":"whatsapp","label":"WhatsApp","kind":"whatsapp","proof":"proven","how":"x"}],
     "knocks":[{"id":"k-1","door":"email","app":"email","from":"ana@northwind.example","name":"Ana Lima","proof":"pass",
       "subject":"Order 4471","text":"Refund order 4471.","attachments":0,"at":"2026-10-24T09:00:00.000Z","state":"open"},
      {"id":"k-2","door":"email","app":"email","from":"","proof":"none","text":"","attachments":0,
       "at":"2026-10-24T09:05:00.000Z","state":"open","rest":"others","dropped":3}]}
    """#

    private func stubbedCloud() -> TafsCloud {
        StubProtocol.responses = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return TafsCloud(baseURL: URL(string: "https://cloud.test")!, token: "kiff_dev_x", session: URLSession(configuration: config))
    }

    private func body(_ method: String, _ path: String) -> [String: String]? {
        guard let r = StubProtocol.sent.last(where: { $0.httpMethod == method && $0.url?.path == path }), let data = r.httpBody else { return nil }
        return try? JSONDecoder().decode([String: String].self, from: data)
    }

    func testTheDoorsListReadsAsTafsCloudSendsIt() throws {
        let r = try KIFFClient.decoder.decode(TafsCloud.Doors.self, from: Data(Self.doorsJSON.utf8))
        XCTAssertEqual(r.doors.first?.address, "refunds@tafs.example")
        XCTAssertEqual(r.doors.first?.keys.first?.auto, true)
        XCTAssertTrue(r.doors.first?.holdsKeys ?? false)
        XCTAssertEqual(r.knocks.first?.who, "Ana Lima (ana@northwind.example)")
        // The two caps, and the line that counts what was dropped past one.
        XCTAssertEqual(r.doors.first?.othersToday, 10)
        XCTAssertEqual(r.doors.first?.othersLimit, 10)
        XCTAssertFalse(r.knocks[0].isLine)
        XCTAssertEqual(r.knocks[1].rest, "others")
        XCTAssertEqual(r.knocks[1].dropped, 3)
    }

    /// An `api` door ("From another application": a company's own software handing a taf work) reads with today's fields and its own: counts, the
    /// last request, and each key's last four and dates, never the key. One door this app can't read (here, one with no
    /// `label`) is left out, and never hides the others.
    func testAnAPIDoorReadsAndOneUnreadableDoorHidesNoOther() throws {
        let sent = #"""
        {"doors":[
          {"id":"door_ord7","app":"api","kind":"api","label":"Order system","open":true,"address":"/v1/doors/api/door_ord7/requests",
           "proof":"proven","how":"Your application's key","today":37,"limit":100,"resting":false,
           "counts":{"today":37,"waiting":2},"last_request":{"status":"done","at":"2026-11-19T09:42:15Z"},
           "keys":[{"id":"key_1","name":"ERP production","last4":"iXrW","made_at":"2026-11-19T08:00:00Z","last_used_at":"2026-11-19T09:42:15Z"}]},
          {"id":"door_new","kind":"api","keys":[{"key_id":"k"}]},
          {"id":"email","app":"email","kind":"email","label":"Email","open":true,"address":"refunds@tafs.example",
           "proof":"proven","how":"x","keys":[],"today":0,"limit":20,"resting":false}],
         "apps":[],"knocks":[]}
        """#
        let r = try KIFFClient.decoder.decode(TafsCloud.Doors.self, from: Data(sent.utf8))
        XCTAssertEqual(r.doors.map(\.id), ["door_ord7", "email"], "the unreadable door is left out, and email still shows")
        let api = try XCTUnwrap(r.doors.first)
        XCTAssertEqual(api.counts, .init(today: 37, waiting: 2))
        XCTAssertEqual(api.lastRequest, .init(status: "done", at: "2026-11-19T09:42:15Z"))
        XCTAssertEqual(api.lastRequest?.shown, "done", "until tafs cloud sends its words, its status")
        let worded = try KIFFClient.decoder.decode(TafsCloud.Door.LastRequest.self,
                                                   from: Data(#"{"status":"done","at":"2026-11-19T09:42:15Z","words":"Erledigt"}"#.utf8))
        XCTAssertEqual(worded.shown, "Erledigt", "tafs cloud's word, in the reader's language")
        XCTAssertEqual(api.keys.first?.last4, "iXrW")
        XCTAssertEqual(api.keys.first?.lastUsedAt, "2026-11-19T09:42:15Z")
        XCTAssertNil(r.doors[1].counts, "an email door has none")
    }

    // MARK: A door "From another application" (`api`)

    /// New key, Revoke and opening again are each signed over the exact method, path and body sent, with the words the
    /// prompt shows; the key is kept only until the key screen is left. Without a signer, nothing is sent.
    func testAnAPIDoorsKeysAreMadeRevokedAndReopenedOnlySigned() async throws {
        let cloud = stubbedCloud()
        var signed: [(String, String, String, String)] = []
        let model = DoorsModel(cloud: cloud, tafID: "taf_r", sign: { method, path, body, reason in
            signed.append((method, path, String(decoding: body, as: UTF8.self), reason))
            return "t=1,v1=sig"
        })
        let door = TafsCloud.Door(id: "door_ord7", app: "api", kind: "api", label: "Order system", open: false, address: "/v1/doors/api/door_ord7/requests",
                                  proof: "proven", how: "Your application's key", keys: [.init(id: "key_1", name: "ERP production", last4: "iXrW")],
                                  today: 0, limit: 100, resting: false)
        StubProtocol.responses["POST /v1/tafs/taf_r/doors/door_ord7/keys"] = (200, #"{"id":"key_2","key":"tafs_api_abc","last4":"_abc","made_at":"2026-11-19T18:00:00Z"}"#)
        StubProtocol.responses["DELETE /v1/tafs/taf_r/doors/door_ord7/keys/key_1"] = (200, #"{"door":{"id":"door_ord7"}}"#)
        StubProtocol.responses["PATCH /v1/tafs/taf_r/doors/door_ord7"] = (200, #"{"door":{"id":"door_ord7","app":"api","kind":"api","label":"Order system","open":true,"address":"/v1/doors/api/door_ord7/requests","proof":"proven","how":"x","keys":[],"today":0,"limit":100,"resting":false}}"#)
        StubProtocol.responses["GET /v1/tafs/taf_r/doors"] = (200, #"{"doors":[],"apps":[],"knocks":[]}"#)

        let key = await model.newKey(door, name: "ERP staging", reason: "Make a new key")
        XCTAssertEqual(key?.key, "tafs_api_abc")
        XCTAssertEqual(model.issued?.last4, "_abc", "shown once, on the key screen")
        model.keyShown()
        XCTAssertNil(model.issued, "never shown again")
        let made = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "POST" && $0.url?.path == "/v1/tafs/taf_r/doors/door_ord7/keys" })
        XCTAssertEqual(made.value(forHTTPHeaderField: "KIFF-Device-Signature"), "t=1,v1=sig")
        XCTAssertEqual(String(decoding: made.httpBody ?? Data(), as: UTF8.self), #"{"name":"ERP staging"}"#)

        await model.revoke(door, door.keys[0], reason: "Revoke")
        let revoked = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "DELETE" })
        XCTAssertEqual(revoked.value(forHTTPHeaderField: "KIFF-Device-Signature"), "t=1,v1=sig")

        await model.reopen(door, reason: "Open again")
        let opened = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "PATCH" })
        XCTAssertEqual(String(decoding: opened.httpBody ?? Data(), as: UTF8.self), #"{"open":true}"#)
        XCTAssertEqual(opened.value(forHTTPHeaderField: "KIFF-Device-Signature"), "t=1,v1=sig")

        // Each was signed over exactly what was sent.
        XCTAssertEqual(signed.map { $0.0 }, ["POST", "DELETE", "PATCH"])
        XCTAssertEqual(signed.map { $0.1 }, ["/v1/tafs/taf_r/doors/door_ord7/keys", "/v1/tafs/taf_r/doors/door_ord7/keys/key_1",
                                         "/v1/tafs/taf_r/doors/door_ord7"])
        XCTAssertEqual(signed.map { $0.2 }, [#"{"name":"ERP staging"}"#, "", #"{"open":true}"#])
        XCTAssertEqual(signed.map { $0.3 }, ["Make a new key", "Revoke", "Open again"])

        // No signer (a model made without one): nothing is sent.
        let unsigned = DoorsModel(cloud: cloud, tafID: "taf_r")
        let before = StubProtocol.sent.count
        let none = await unsigned.newKey(door, name: "x", reason: "x")
        await unsigned.revoke(door, door.keys[0], reason: "x")
        await unsigned.reopen(door, reason: "x")
        XCTAssertNil(none)
        XCTAssertEqual(StubProtocol.sent.count, before)
    }

    /// An `api` door is removed only signed, over the DELETE and its empty body; never through the unsigned remove.
    func testAnAPIDoorIsRemovedOnlySigned() async throws {
        let cloud = stubbedCloud()
        var signed: [(String, String, String)] = []
        let model = DoorsModel(cloud: cloud, tafID: "taf_r", sign: { method, path, body, _ in
            signed.append((method, path, String(decoding: body, as: UTF8.self)))
            return "t=1,v1=sig"
        })
        let door = TafsCloud.Door(id: "door_ord7", app: "api", kind: "api", label: "Order system", open: true, address: "", proof: "proven",
                                  how: "", keys: [], today: 0, limit: 100, resting: false)
        StubProtocol.responses["GET /v1/tafs/taf_r/doors"] = (200, #"{"doors":[{"id":"door_ord7","app":"api","kind":"api","label":"Order system","open":true,"address":"","proof":"proven","how":"","keys":[],"today":0,"limit":100,"resting":false}],"apps":[],"knocks":[]}"#)
        StubProtocol.responses["DELETE /v1/tafs/taf_r/doors/door_ord7"] = (200, #"{"removed":"door_ord7"}"#)
        await model.load()
        XCTAssertEqual(model.doors.map(\.id), ["door_ord7"])
        StubProtocol.sent = []

        await model.remove(door)
        XCTAssertTrue(StubProtocol.sent.isEmpty, "the unsigned remove sends nothing for an api door")
        XCTAssertEqual(model.doors.map(\.id), ["door_ord7"])

        await DoorsModel(cloud: cloud, tafID: "taf_r").removeAPI(door, reason: "Remove")
        XCTAssertTrue(StubProtocol.sent.isEmpty, "no signer: nothing is sent")

        await model.removeAPI(door, reason: "Remove")
        let removed = try XCTUnwrap(StubProtocol.sent.last { $0.httpMethod == "DELETE" })
        XCTAssertEqual(removed.url?.path, "/v1/tafs/taf_r/doors/door_ord7")
        XCTAssertEqual(removed.value(forHTTPHeaderField: "KIFF-Device-Signature"), "t=1,v1=sig")
        XCTAssertEqual(signed.count, 1)
        XCTAssertEqual(signed.first?.0, "DELETE")
        XCTAssertEqual(signed.first?.1, "/v1/tafs/taf_r/doors/door_ord7")
        XCTAssertEqual(signed.first?.2, "", "signed over the empty body sent")
        XCTAssertTrue(model.doors.isEmpty)
    }

    /// A refused signature (Face ID cancelled) sends nothing and keeps no key.
    func testACancelledSignatureSendsNothing() async {
        let cloud = stubbedCloud()
        let model = DoorsModel(cloud: cloud, tafID: "taf_r", sign: { _, _, _, _ in throw CancellationError() })
        let door = TafsCloud.Door(id: "door_ord7", app: "api", kind: "api", label: "Order system", open: true, address: "", proof: "proven",
                                  how: "", keys: [], today: 0, limit: 100, resting: false)
        let key = await model.newKey(door, name: "x", reason: "x")
        XCTAssertNil(key)
        XCTAssertNil(model.issued)
        XCTAssertTrue(StubProtocol.sent.isEmpty)
    }

    func testHomeCarriesTafsCloudsNeedsYouCount() throws {
        let sent = #"{"tafs":[],"others":[],"removing":[],"needs_you":{"count":3,"words":"3 need you."}}"#
        XCTAssertEqual(try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(sent.utf8)).needsYou, TafsCloud.NeedsYou(count: 3, words: "3 need you."))
        XCTAssertNil(try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(HomeJSON.empty.utf8)).needsYou)
    }

    func testDoorsShowOnlyWhereTafsCloudOffersThem() throws {
        let offered = #"{"tafs":[],"others":[],"removing":[],"doors_offered":["email"]}"#
        XCTAssertEqual(try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(offered.utf8)).doorsOffered, ["email"])
        XCTAssertNil(try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(HomeJSON.empty.utf8)).doorsOffered)
    }

    func testADoorsMessageAndItsAnswerAreMarked() throws {
        let json = #"""
        [{"at":"2026-10-24T09:00:00.000Z","from":"owner","kind":"message","text":"Refund 4471","session":"s1",
          "door":{"app":"email","from":"Ana <ana@acme.example>","subject":"Order 4471"}},
         {"at":"2026-10-24T09:01:00.000Z","from":"taf","kind":"message","text":"Done.","session":"s1","door":{"app":"email","reply":"sent"}},
         {"at":"2026-10-24T09:02:00.000Z","from":"taf","kind":"message","text":"Odd","session":"s1","door":42}]
        """#
        let messages = try KIFFClient.decoder.decode([TafsCloud.ChatMessage].self, from: Data(json.utf8))
        XCTAssertEqual(messages[0].door?.subject, "Order 4471")
        XCTAssertEqual(messages[1].door?.reply, "sent")
        // A mark the app can't read leaves a plain message, never a broken thread.
        XCTAssertNil(messages[2].door)
        XCTAssertEqual(messages[2].text, "Odd")
    }

    func testTheHomeListsDoorsLightTheAvatar() throws {
        let entry = HomeJSON.rae.dropLast() + #","doors":[{"app":"email","open":true,"resting":false,"knocks":1},{"app":"whatsapp"}]}"#
        let home = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(HomeJSON.list([String(entry)]).utf8))
        let taf = KIFFMapping.taf(try XCTUnwrap(home.tafs.first), id: UUID(), owner: "you")
        XCTAssertEqual(taf.doors, [DoorLight(app: "email", open: true, knocks: 1), DoorLight(app: "whatsapp", open: false)])
        // A taf from before doors still reads.
        let plain = try KIFFClient.decoder.decode(TafsCloud.Home.self, from: Data(HomeJSON.list([HomeJSON.rae]).utf8))
        XCTAssertNil(plain.tafs.first?.doors)
    }

    func testTheDialLandsOnTheDoorAtTheTop() {
        XCTAssertEqual(DialMath.stop(rotation: 0, stops: 4), 0)
        XCTAssertEqual(DialMath.stop(rotation: 90, stops: 4), 1)
        XCTAssertEqual(DialMath.stop(rotation: 130, stops: 4), 1)
        XCTAssertEqual(DialMath.stop(rotation: -90, stops: 4), 3)
        XCTAssertEqual(DialMath.stop(rotation: 361, stops: 4), 0)
        XCTAssertEqual(DialMath.angle(index: 1, stops: 4, rotation: 90), -90)
        XCTAssertEqual(DialMath.delta(from: 170, to: -170), 20)
        XCTAssertEqual(DialMath.shortest(from: 0, to: 3, stops: 4), -1)
        XCTAssertEqual(DialMath.shortest(from: 5, to: 2, stops: 4), 1)
    }

    func testTheOwnersAnswersReachTafsCloud() async throws {
        let cloud = stubbedCloud()
        StubProtocol.responses["GET /v1/tafs/taf-1/doors"] = (200, Self.doorsJSON)
        StubProtocol.responses["POST /v1/tafs/taf-1/doors/knocks/k-1"] = (200, #"{"knock":"k-1","state":"done"}"#)
        let model = DoorsModel(cloud: cloud, tafID: "taf-1")
        await model.load()
        XCTAssertEqual(model.openKnocks.count, 2)
        // One email door per taf: email isn't offered again.
        XCTAssertEqual(model.addable.map(\.id), ["whatsapp"])

        await model.answer(model.openKnocks[0], "do", say: "Refund 4471 only.")
        XCTAssertEqual(body("POST", "/v1/tafs/taf-1/doors/knocks/k-1"), ["action": "do", "say": "Refund 4471 only."])
        XCTAssertEqual(model.openKnocks.map(\.id), ["k-2"])
        XCTAssertNil(model.error)
    }

    func testWhatsAppsSignupHandsItsCodeToTafsCloud() async throws {
        let cloud = stubbedCloud()
        let vendor = StubVendor()
        vendor.callback = URL(string: "tafs://doors/whatsapp?taf=taf-1&code=c0de&phone_number_id=109&waba_id=209")
        StubProtocol.responses["POST /v1/tafs/taf-1/doors"] = (200, #"{"door":{"id":"whatsapp","app":"whatsapp","kind":"whatsapp","label":"WhatsApp","open":true,"address":"+34 600 000 000","proof":"proven","how":"x","keys":[],"today":0,"limit":20,"resting":false}}"#)
        let model = DoorsModel(cloud: cloud, tafID: "taf-1", signIn: vendor)
        let door = await model.add(TafsCloud.DoorApp(id: "whatsapp", label: "WhatsApp", kind: "whatsapp", proof: "proven", how: ""))
        XCTAssertEqual(door?.address, "+34 600 000 000")
        XCTAssertEqual(vendor.opened.first?.path, "/doors/whatsapp/connect")
        XCTAssertEqual(body("POST", "/v1/tafs/taf-1/doors"), ["app": "whatsapp", "code": "c0de", "phone_number_id": "109", "waba_id": "209"])
    }

    func testClosingASignupAddsNothingAndSaysNothing() async {
        let cloud = stubbedCloud()
        let vendor = StubVendor()
        vendor.cancel = true
        let model = DoorsModel(cloud: cloud, tafID: "taf-1", signIn: vendor)
        let door = await model.add(TafsCloud.DoorApp(id: "whatsapp", label: "WhatsApp", kind: "whatsapp", proof: "proven", how: ""))
        XCTAssertNil(door)
        XCTAssertNil(model.error)
        XCTAssertTrue(model.doors.isEmpty)
    }

    /// Each door's words are tafs cloud's, shown as they are; an older tafs cloud sends none.
    func testADoorsWordsReadAsTafsCloudSendsThem() throws {
        let json = #"{"id":"email","app":"email","kind":"email","label":"Email","open":true,"address":"refunds@tafs.example","proof":"proven","how":"x","keys":[],"today":0,"limit":20,"resting":false,"words":{"state":"Open: messages from you and paired senders to refunds@tafs.example start work.","tap":"Tap to close the email door.","settings":"Paired senders and today's limits are below."}}"#
        let door = try KIFFClient.decoder.decode(TafsCloud.Door.self, from: Data(json.utf8))
        XCTAssertEqual(door.words?.tap, "Tap to close the email door.")
        XCTAssertEqual(door.words?.settings, "Paired senders and today's limits are below.")
        let older = try KIFFClient.decoder.decode(TafsCloud.Doors.self, from: Data(Self.doorsJSON.utf8))
        XCTAssertNil(older.doors.first?.words)
    }

    /// The chat is told the open knocks each time they change, so it places them at their times.
    func testTheChatIsToldTheOpenKnocks() async throws {
        let cloud = stubbedCloud()
        StubProtocol.responses["GET /v1/tafs/taf-1/doors"] = (200, Self.doorsJSON)
        StubProtocol.responses["POST /v1/tafs/taf-1/doors/knocks/k-1"] = (200, #"{"knock":"k-1","state":"done"}"#)
        let model = DoorsModel(cloud: cloud, tafID: "taf-1")
        var told: [[String]] = []
        model.onKnocks = { told.append($0.map(\.id)) }
        await model.load()
        await model.answer(model.openKnocks[0], "ignore")
        XCTAssertEqual(told, [["k-1", "k-2"], ["k-2"]])
    }
}
