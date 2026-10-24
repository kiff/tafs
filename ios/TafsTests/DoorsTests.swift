import XCTest
@testable import Tafs

/// A taf's doors in the app (docs/doors.md): what tafs cloud sends reads as the app shows it,
/// the dial lands on the door the owner turned to, and the owner's answers reach tafs cloud.
@MainActor
final class DoorsTests: XCTestCase {
    private static let doorsJSON = #"""
    {"doors":[{"id":"email","app":"email","kind":"email","label":"Email","open":true,"address":"refunds@tafs.example",
      "proof":"proven","how":"The email must pass the sender check","keys":[{"id":"ana@acme.example","auto":true}],
      "today":2,"limit":20,"resting":false}],
     "apps":[{"id":"email","label":"Email","kind":"email","proof":"proven","how":"x"},
             {"id":"whatsapp","label":"WhatsApp","kind":"whatsapp","proof":"proven","how":"x"},
             {"id":"form","label":"Web form","kind":"connector","proof":"none","how":"Anyone can type any name"}],
     "knocks":[{"id":"k-1","door":"email","app":"email","from":"ana@northwind.example","name":"Ana Lima","proof":"pass",
       "subject":"Order 4471","text":"Refund order 4471.","attachments":0,"at":"2026-10-24T09:00:00.000Z","state":"open"}]}
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
        XCTAssertEqual(model.openKnocks.count, 1)
        // One email door per taf: email isn't offered again.
        XCTAssertEqual(model.addable.map(\.id), ["whatsapp", "form"])

        await model.answer(model.openKnocks[0], "do", say: "Refund 4471 only.")
        XCTAssertEqual(body("POST", "/v1/tafs/taf-1/doors/knocks/k-1"), ["action": "do", "say": "Refund 4471 only."])
        XCTAssertTrue(model.openKnocks.isEmpty)
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

    func testSampleModeHasDoorsWithoutTafsCloud() {
        let model = DoorsModel(cloud: nil, tafID: nil)
        XCTAssertEqual(model.doors.map(\.app), ["email"])
        XCTAssertEqual(model.openKnocks.count, 1)
    }
}
