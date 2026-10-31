import XCTest
@testable import Tafs

/// A signer with both keys, as a phone holds them (kiff/tafs#233): what each key signed,
/// and whether it asked for Face ID first.
@MainActor
final class TwoKeySigner: ApprovalSigning {
    /// Signed by the current key: after Face ID (`prompted`), or under a confirmed step.
    var current: [String] = []
    var currentPrompted: [String] = []
    /// Signed by the approval key: after Face ID (`prompted`), or under a confirmed step.
    var approval: [String] = []
    var approvalPrompted: [String] = []

    var publicKeyDER: Data { Data([0x30, 0x59, 0x01]) }
    /// The approval key's public half: another one when the phone made a new key.
    var approvalKey = Data([0x30, 0x59, 0x02])
    var approvalPublicKeyDER: Data { approvalKey }

    func sign(_ message: Data, reason: String) async throws -> String {
        currentPrompted.append(String(decoding: message, as: UTF8.self))
        return "current"
    }

    func signConfirmed(_ message: Data) throws -> String {
        current.append(String(decoding: message, as: UTF8.self))
        return "current"
    }

    /// Set when the next approval prompt closes without a recognized face or finger.
    var approvalUnrecognized = false
    func signApproval(_ message: Data, reason: String) async throws -> String {
        if approvalUnrecognized { throw ApprovalSigner.SignerError.notAuthenticated }
        approvalPrompted.append(String(decoding: message, as: UTF8.self))
        return "approval"
    }

    /// Set when faces or fingerprints were enrolled since the approval key was made: the next
    /// check makes a new one.
    var biometricsChanged = false
    func renewApprovalKeyIfBiometricsChanged() -> Bool {
        guard biometricsChanged else { return false }
        biometricsChanged = false
        approvalKey = Data([0x30, 0x59, 0x04])
        return true
    }

    func signApprovalConfirmed(_ message: Data) throws -> String {
        approval.append(String(decoding: message, as: UTF8.self))
        return "approval"
    }
}

/// The approval key (kiff/tafs#233): what grants authority is signed by a key whose own
/// access control requires Face ID, once KIFF holds it for the session; everything else
/// stays on the current key.
@MainActor
final class ApprovalKeyTests: XCTestCase {
    override func tearDown() {
        Keychain.delete(AppModel.tokenAccount)
        Keychain.delete(AppModel.tenantAccount)
        Keychain.delete(AppModel.keyedAccount)
        Keychain.delete(AppModel.approvalKeyedAccount)
        super.tearDown()
    }

    private static let heldJSON = """
    {"exceptions":[{"id":"ce_1","proposal_id":"p1","subject":"rae-k3f9","action":"refund_order","entity_id":"ord_4502",
      "status":"held","breaches":[{"mandate_id":"m1","outcome":"per_action_exceeded","quantity":"sum(amount_cents)","limit":15000}],
      "parameters":{"amount_cents":48000},"created_at":"2026-10-14T10:41:00Z","expires_at":"2099-10-14T10:51:00Z",
      "accounting_version":2}]}
    """

    /// KIFF's answer naming the key it holds.
    private static func holds(_ der: Data) -> String { #"{"fingerprint":""# + KIFFClient.fingerprint(der) + #""}"# }
    private static let ours = Data([0x30, 0x59, 0x02])

    /// Signed in with `registration` as KIFF's answer to the approval key.
    private func signedIn(_ signer: TwoKeySigner, registration: (Int, String) = (200, holds(ours)),
                          held: String = #"{"exceptions":[]}"#) async -> AppModel {
        StubProtocol.responses = [:]
        StubProtocol.thenRespond = [:]
        StubProtocol.sent = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        let session = URLSession(configuration: config)
        let model = AppModel(signer: signer,
                             kiff: KIFFClient(baseURL: URL(string: "https://api.test")!, session: session),
                             cloud: TafsCloud(baseURL: URL(string: "https://cloud.test")!, session: session))
        StubProtocol.responses["GET /v1/home"] = (200, HomeJSON.list([HomeJSON.rae]))
        StubProtocol.responses["GET /v1/me/card-exceptions?status=held"] = (200, held)
        StubProtocol.responses["GET /v1/tafs"] = (200, #"{"tafs":[{"id":"depl_1","name":"Rae","job":"refunds","kiff_agent":"rae-k3f9","status":"active"}]}"#)
        StubProtocol.responses["POST /v1/me/device/approval-key"] = registration
        await model.useSession("kiff_dev_x", tenant: "t_01")
        return model
    }

    private func sent(_ method: String, _ path: String) -> [URLRequest] {
        StubProtocol.sent.filter { $0.httpMethod == method && $0.url?.path == path }
    }

    func testASessionRegistersItsApprovalKeyOnceSignedByTheCurrentKey() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer)
        XCTAssertTrue(model.sessionHasApprovalKey)
        let register = try XCTUnwrap(sent("POST", "/v1/me/device/approval-key").first)
        let body = try JSONDecoder().decode([String: String].self, from: try XCTUnwrap(register.httpBody))
        XCTAssertEqual(body, ["public_key": Data([0x30, 0x59, 0x02]).base64EncodedString()])
        XCTAssertTrue(register.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasSuffix(",v1=current") == true,
                      "the current key vouches for it, without Face ID")
        XCTAssertTrue(signer.currentPrompted.isEmpty && signer.approvalPrompted.isEmpty, "registering asks for no Face ID")
    }

    /// Whether this test host keeps Keychain items: an unsigned test bundle on CI's
    /// Simulator can't, and the app's Keychain reads come back empty there.
    private var keychainKeeps: Bool {
        Keychain.save("x", for: "tafs.test-probe")
        defer { Keychain.delete("tafs.test-probe") }
        return Keychain.load("tafs.test-probe") == "x"
    }

    func testTheNextLaunchUsesTheRegisteredKeyWithoutRegisteringAgain() async throws {
        try XCTSkipUnless(keychainKeeps, "this test host keeps no Keychain items")
        let signer = TwoKeySigner()
        let model = await signedIn(signer)
        XCTAssertEqual(Keychain.load(AppModel.approvalKeyedAccount), KIFFClient.fingerprint(Self.ours), "it keeps which key KIFF holds")
        StubProtocol.sent = []
        await model.resumeSession("kiff_dev_x", tenant: "t_01", keyed: true)
        XCTAssertTrue(model.sessionHasApprovalKey)
        XCTAssertTrue(sent("POST", "/v1/me/device/approval-key").isEmpty)
    }

    func testASessionThatHasOneAlreadyUsesIt() async {
        // 409: the key went with the sign-in, or an earlier launch registered it.
        let model = await signedIn(TwoKeySigner(), registration: (409, #"{"error":"approval_key_exists","fingerprint":""# + KIFFClient.fingerprint(Self.ours) + #""}"#))
        XCTAssertTrue(model.sessionHasApprovalKey)
        XCTAssertFalse(model.approvalKeyLost)
    }

    func testAKIFFWithoutTheRouteKeepsTheCurrentKeyAndTriesAgainNextLaunch() async {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, registration: (404, #"{"error":"not found"}"#))
        XCTAssertFalse(model.sessionHasApprovalKey)
        XCTAssertNil(model.kiff.approvalSigner)

        StubProtocol.responses["POST /v1/me/device/approval-key"] = (200, Self.holds(Self.ours))
        await model.resumeSession("kiff_dev_x", tenant: "t_01", keyed: true)
        XCTAssertTrue(model.sessionHasApprovalKey)
    }

    func testApprovingIsSignedByTheApprovalKeyAndRefusingByTheCurrentKey() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, held: Self.heldJSON)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/approve-once"] = (200, #"{"id":"ce_1"}"#)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/reject"] = (200, #"{"id":"ce_1"}"#)
        let call = try XCTUnwrap(model.held.first)

        await model.answer(call, with: .approveOnce)
        XCTAssertEqual(signer.approvalPrompted.count, 1, model.lastError ?? "")
        XCTAssertTrue(signer.approvalPrompted[0].hasPrefix("kiff.answer.v1\nt_01\nce_1\napprove-once"), signer.approvalPrompted[0])
        XCTAssertTrue(signer.currentPrompted.isEmpty, "the current key signs no approval")
        let approve = try XCTUnwrap(sent("POST", "/v1/me/card-exceptions/ce_1/approve-once").first)
        XCTAssertTrue(String(decoding: approve.httpBody ?? Data(), as: UTF8.self).contains(#""value":"approval""#))

        // A refusal takes authority away: the current key, as before.
        let again = await signedIn(signer, held: Self.heldJSON)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/reject"] = (200, #"{"id":"ce_1"}"#)
        let refused = try XCTUnwrap(again.held.first)
        signer.approvalPrompted = []
        await again.answer(refused, with: .refuse)
        XCTAssertEqual(signer.currentPrompted.count, 1, again.lastError ?? "")
        XCTAssertTrue(signer.approvalPrompted.isEmpty)
    }

    func testWithoutTheApprovalKeyApprovingStaysOnTheCurrentKey() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, registration: (404, #"{"error":"not found"}"#), held: Self.heldJSON)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/approve-once"] = (200, #"{"id":"ce_1"}"#)
        await model.answer(try XCTUnwrap(model.held.first), with: .approveOnce)
        XCTAssertEqual(signer.currentPrompted.count, 1, model.lastError ?? "")
        XCTAssertTrue(signer.approvalPrompted.isEmpty, "KIFF doesn't hold the approval key: it couldn't check it")
    }

    func testResumingIsSignedByTheApprovalKeyAndHaltingIsNot() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer)
        let rae = try XCTUnwrap(model.tafs.first { $0.name == "Rae" }?.id)
        StubProtocol.responses["POST /v1/tafs/depl_1/halt"] = (200, #"{"halt":{"state":"halted","halted_at":"2026-10-21T23:00:00Z","runtime":true,"kiff":true,"planque":true,"account":false,"stopped":0}}"#)
        await model.halt(rae)
        XCTAssertTrue(signer.approval.isEmpty && signer.approvalPrompted.isEmpty, "a halt grants nothing")

        StubProtocol.responses["POST /v1/agents/rae-k3f9/resume"] = (200, #"{"state":"running"}"#)
        StubProtocol.responses["DELETE /v1/tafs/depl_1/halt"] = (200, #"{"halt":{"state":"running","halted_at":"2026-10-21T23:00:00Z","runtime":false,"kiff":false,"planque":false,"account":false,"stopped":0}}"#)
        let resumed = await model.resumeHalt(rae)
        XCTAssertTrue(resumed, model.lastError ?? "")
        let resume = try XCTUnwrap(sent("POST", "/v1/agents/rae-k3f9/resume").first)
        XCTAssertTrue(resume.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasSuffix(",v1=approval") == true,
                      "the same header, signed by the approval key: KIFF picks the key by route")
        XCTAssertEqual(signer.approval.count, 1)
        XCTAssertTrue(signer.approval[0].hasPrefix("kiff.request.v1\nt_01\nPOST\n/v1/agents/rae-k3f9/resume"), signer.approval[0])
    }

    /// Sharing a taf gives people its use: signed by the approval key, under one Face ID for
    /// the whole share. Taking a share away, and a group's changes, are the current key's.
    func testSharingIsSignedByTheApprovalKeyAndUnsharingIsNot() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer)
        StubProtocol.responses["POST /v1/agents/rae-k3f9/grants"] = (200, #"{"group":"grp_support"}"#)
        StubProtocol.responses["DELETE /v1/agents/rae-k3f9/grants?subject=user_bo"] = (204, "")
        StubProtocol.responses["PUT /v1/groups/grp_support/members/user_ana"] = (200, #"{"subject":"user_ana","role":"member"}"#)
        StubProtocol.responses["GET /v1/groups"] = (200, #"{"groups":[{"id":"grp_support","name":"Support","members":[{"subject":"user_ana","role":"member"}],"agents":["rae-k3f9"]}]}"#)
        StubProtocol.responses["GET /v1/tenant/members"] = (200, #"{"members":[{"subject":"user_ana","role":"approver","status":"active","email":"ana@acme.test","you":true}]}"#)
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{"rae-k3f9":{"use":true,"oversee":true,"manage":true,"groups":["grp_support"]}},"checked_at":"2026-10-26T01:00:00Z"}"#)

        let shared = await model.changePeople("Share Rae") { kiff in
            try await kiff.share(agent: "rae-k3f9", with: .init(group: "grp_support"))
            try await kiff.unshare(agent: "rae-k3f9", from: .init(subject: "user_bo"))
        }
        XCTAssertTrue(shared, model.peopleError ?? "")
        XCTAssertEqual(signer.currentPrompted.count, 1, "one Face ID for the whole share")
        let share = try XCTUnwrap(sent("POST", "/v1/agents/rae-k3f9/grants").first)
        XCTAssertTrue(share.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasSuffix(",v1=approval") == true, "sharing grants use")
        let unshare = try XCTUnwrap(sent("DELETE", "/v1/agents/rae-k3f9/grants").first)
        XCTAssertEqual(unshare.url?.query, "subject=user_bo")
        XCTAssertTrue(unshare.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasSuffix(",v1=current") == true, "unsharing takes use away")
        XCTAssertTrue(signer.current.contains { $0.hasPrefix("kiff.request.v1\nt_01\nDELETE\n/v1/agents/rae-k3f9/grants?subject=user_bo") },
                      "the query is signed with the path")

        let added = await model.changePeople("Add ana@acme.test to Support") { try await $0.setGroupMember("grp_support", subject: "user_ana", role: "member") }
        XCTAssertTrue(added, model.peopleError ?? "")
        let put = try XCTUnwrap(sent("PUT", "/v1/groups/grp_support/members/user_ana").first)
        XCTAssertTrue(put.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasSuffix(",v1=current") == true)
        // What KIFF says, read again after the change.
        XCTAssertEqual(model.groups.map(\.name), ["Support"])
        XCTAssertEqual(model.me?.label, "ana@acme.test")
        XCTAssertEqual(model.access["rae-k3f9"]?.oversee, true)
        XCTAssertEqual(model.tafName(agent: "rae-k3f9"), "Rae")
    }

    /// A change KIFF refuses says why, after the people are read again: the reread that works
    /// used to clear the reason before the owner saw it.
    func testAFailedPeopleChangeKeepsItsReason() async throws {
        let model = await signedIn(TwoKeySigner())
        StubProtocol.responses["PUT /v1/groups/grp_support/members/user_ana"] = (403, #"{"error":"a manager can't name another manager"}"#)
        StubProtocol.responses["GET /v1/groups"] = (200, #"{"groups":[{"id":"grp_support","name":"Support","members":[],"agents":[]}]}"#)
        StubProtocol.responses["GET /v1/tenant/members"] = (200, #"{"members":[{"subject":"user_ana","role":"approver","status":"active","email":"ana@acme.test","you":true}]}"#)
        StubProtocol.responses["GET /v1/me/access"] = (200, #"{"agents":{},"checked_at":"2026-10-26T01:00:00Z"}"#)

        let added = await model.changePeople("Make ana@acme.test a manager") { try await $0.setGroupMember("grp_support", subject: "user_ana", role: "manager") }
        XCTAssertFalse(added)
        XCTAssertEqual(model.groups.map(\.name), ["Support"], "read again after the failure")
        let error = try XCTUnwrap(model.peopleError, "the reason survives the reread")
        XCTAssertTrue(error.hasPrefix("Make ana@acme.test a manager didn't go through: "), error)
    }

    func testWhatGrantsAuthority() {
        XCTAssertTrue(KIFFClient.grantsAuthority("POST", "/v1/me/agent-drafts/drf_1/confirm"), "issuing a Card, or starting a trial")
        XCTAssertTrue(KIFFClient.grantsAuthority("POST", "/v1/resume"))
        XCTAssertTrue(KIFFClient.grantsAuthority("POST", "/v1/agents/rae-k3f9/resume"))
        XCTAssertFalse(KIFFClient.grantsAuthority("DELETE", "/v1/me/agent-drafts/drf_1/trial"), "ending a trial takes authority away")
        XCTAssertTrue(KIFFClient.grantsAuthority("POST", "/v1/me/agent-drafts"), "a phone's draft is confirmed as it's submitted: it issues Cards")
        XCTAssertFalse(KIFFClient.grantsAuthority("GET", "/v1/me/agent-drafts"))
        XCTAssertFalse(KIFFClient.grantsAuthority("POST", "/v1/agents/rae-k3f9/halt"))
        XCTAssertFalse(KIFFClient.grantsAuthority("POST", "/v1/me/pack-requests/pr_1/decline"))
        XCTAssertFalse(KIFFClient.grantsAuthority("POST", "/v1/me/agent-drafts//confirm"))
        XCTAssertTrue(KIFFClient.grantsAuthority("POST", "/v1/agents/rae-k3f9/grants"), "sharing a taf gives its use")
        XCTAssertFalse(KIFFClient.grantsAuthority("DELETE", "/v1/agents/rae-k3f9/grants?group=grp_1"), "unsharing takes it away")
        XCTAssertFalse(KIFFClient.grantsAuthority("POST", "/v1/groups"))
    }

    /// Groups and sharing are each the person's act behind Face ID, as KIFF asks of a phone.
    func testGroupsAndSharingAreSigned() {
        for path in ["/v1/groups", "/v1/groups/grp_1", "/v1/groups/grp_1/members/user_ana",
                     "/v1/agents/rae-k3f9/grants", "/v1/agents/rae-k3f9/grants?subject=user_bo"] {
            XCTAssertTrue(KIFFClient.signsSetup(path), path)
        }
        for path in ["/v1/groups/", "/v1/groups/grp_1/people", "/v1/agents//grants", "/v1/me/access", "/v1/tenant/members"] {
            XCTAssertFalse(KIFFClient.signsSetup(path), path)
        }
    }

    func testSignInSendsBothKeys() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer)
        StubProtocol.responses["POST /v1/auth/device/start"] = (200, #"{"device_code":"dc","user_code":"ABCD-EFGH","verification_uri":"https://app.test/auth/device","verification_uri_complete":"https://app.test/auth/device?code=ABCD-EFGH","expires_in":600,"interval":5}"#)
        _ = try await model.startSignIn()
        let body = try JSONDecoder().decode([String: String].self, from: try XCTUnwrap(sent("POST", "/v1/auth/device/start").last?.httpBody))
        XCTAssertEqual(body["public_key"], Data([0x30, 0x59, 0x01]).base64EncodedString())
        XCTAssertEqual(body["approval_public_key"], Data([0x30, 0x59, 0x02]).base64EncodedString())
    }

    func testSigningOutForgetsIt() async {
        let model = await signedIn(TwoKeySigner())
        XCTAssertTrue(model.sessionHasApprovalKey)
        await model.signOut()
        XCTAssertFalse(model.sessionHasApprovalKey)
        XCTAssertNil(model.kiff.approvalSigner)
        XCTAssertNil(Keychain.load(AppModel.approvalKeyedAccount))
    }

    /// Signing out ends the session at KIFF first: when KIFF doesn't confirm, the phone stays signed
    /// in, with its key, and says why; a session KIFF had already ended counts as ended.
    func testSigningOutWaitsForKIFF() async {
        let model = await signedIn(TwoKeySigner())
        StubProtocol.responses["POST /v1/auth/device/logout"] = (503, #"{"error":"down"}"#)
        let first = await model.signOut()
        XCTAssertFalse(first)
        XCTAssertEqual(model.mode, .kiff)
        XCTAssertTrue(model.sessionHasApprovalKey)
        XCTAssertNotNil(model.kiff.token)
        XCTAssertTrue(model.signOutError?.hasPrefix("Not signed out at KIFF yet") == true)
        XCTAssertEqual(sent("POST", "/v1/auth/device/logout").count, 1)
        StubProtocol.responses["POST /v1/auth/device/logout"] = (401, #"{"error":"session ended"}"#)
        let second = await model.signOut()
        XCTAssertTrue(second)
        XCTAssertNil(model.signOutError)
        XCTAssertFalse(model.sessionHasApprovalKey)
        XCTAssertNil(model.kiff.token)
    }

    /// The key KIFF holds isn't this phone's: approving waits for a new sign-in, and says so.
    private func assertCantApprove(_ model: AppModel, _ signer: TwoKeySigner, file: StaticString = #filePath, line: UInt = #line) async throws {
        XCTAssertTrue(model.approvalKeyLost, file: file, line: line)
        XCTAssertFalse(model.sessionHasApprovalKey, file: file, line: line)
        XCTAssertNil(model.kiff.approvalSigner, file: file, line: line)
        signer.approvalPrompted = []
        signer.currentPrompted = []
        await model.answer(try XCTUnwrap(model.held.first), with: .approveOnce)
        XCTAssertEqual(model.lastError, KIFFClient.approvalKeyLost, file: file, line: line)
        XCTAssertTrue(signer.approvalPrompted.isEmpty && signer.currentPrompted.isEmpty, "no Face ID for an answer KIFF would refuse", file: file, line: line)
        XCTAssertTrue(sent("POST", "/v1/me/card-exceptions/ce_1/approve-once").isEmpty, file: file, line: line)
    }

    func testAKeyMadeAnewAfterRegisteringCantApproveUntilSignIn() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, held: Self.heldJSON)
        XCTAssertTrue(model.sessionHasApprovalKey)

        // The passcode went off and on: the phone has a new approval key, KIFF the old one.
        signer.approvalKey = Data([0x30, 0x59, 0x03])
        StubProtocol.responses["POST /v1/me/device/approval-key"] = (409, #"{"error":"approval_key_exists","fingerprint":""# + KIFFClient.fingerprint(Self.ours) + #""}"#)
        await model.resumeSession("kiff_dev_x", tenant: "t_01", keyed: true)
        try await assertCantApprove(model, signer)
        // Refusing still works: it takes authority away, with the current key.
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/reject"] = (200, #"{"id":"ce_1"}"#)
        await model.answer(try XCTUnwrap(model.held.first), with: .refuse)
        XCTAssertEqual(sent("POST", "/v1/me/card-exceptions/ce_1/reject").count, 1, model.lastError ?? "")
    }

    func testA409WithAnotherKeyCantApproveUntilSignIn() async throws {
        let signer = TwoKeySigner()
        let other = Data([0x30, 0x59, 0x09])
        let model = await signedIn(signer, registration: (409, #"{"error":"approval_key_exists","fingerprint":""# + KIFFClient.fingerprint(other) + #""}"#),
                                   held: Self.heldJSON)
        try await assertCantApprove(model, signer)
        XCTAssertNil(Keychain.load(AppModel.approvalKeyedAccount))
    }

    func testKIFFAskingForTheApprovalKeySaysSignInAgain() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, held: Self.heldJSON)
        StubProtocol.responses["POST /v1/me/card-exceptions/ce_1/approve-once"] = (403, #"{"error":"approval_key_required"}"#)
        await model.answer(try XCTUnwrap(model.held.first), with: .approveOnce)
        XCTAssertEqual(model.lastError, KIFFClient.approvalKeyLost)
        XCTAssertTrue(model.approvalKeyLost)
        XCTAssertNil(model.kiff.approvalSigner)

        // A new sign-in starts over: KIFF holds this phone's key again.
        let again = await signedIn(signer)
        XCTAssertTrue(again.sessionHasApprovalKey)
        XCTAssertFalse(again.approvalKeyLost)
    }

    func testNewBiometricsAreSaidAtRefreshNotAfterATap() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, held: Self.heldJSON)
        XCTAssertTrue(model.sessionHasApprovalKey)

        // A face or finger was enrolled: the next refresh finds it, with no prompt.
        signer.biometricsChanged = true
        await model.refresh()
        try await assertCantApprove(model, signer)
        XCTAssertNil(Keychain.load(AppModel.approvalKeyedAccount))
    }

    func testAnUnrecognizedPromptSaysTryAgainWhileTheKeyIsGood() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, held: Self.heldJSON)
        signer.approvalUnrecognized = true
        await model.answer(try XCTUnwrap(model.held.first), with: .approveOnce)
        XCTAssertEqual(model.lastError, KIFFClient.notRecognized)
        XCTAssertFalse(model.approvalKeyLost)
        XCTAssertTrue(sent("POST", "/v1/me/card-exceptions/ce_1/approve-once").isEmpty)
    }

    func testTheApprovalKeyTakesTodaysBiometricsOnly() {
        // Never the passcode, and a new enrollment ends the key.
        XCTAssertEqual(ApprovalSigner.approvalKeyFlags, [.privateKeyUsage, .biometryCurrentSet])
    }

    func testOnlyTwoKnownDomainsThatDifferEndTheKey() {
        let a = Data([1]), b = Data([2])
        XCTAssertTrue(ApprovalSigner.biometricsChanged(made: a, now: b))
        XCTAssertFalse(ApprovalSigner.biometricsChanged(made: a, now: a))
        // A lockout after a few misses, or a face not available, reads no domain: the key stays.
        XCTAssertFalse(ApprovalSigner.biometricsChanged(made: a, now: nil))
        XCTAssertFalse(ApprovalSigner.biometricsChanged(made: nil, now: b))
    }

    func testTheFingerprintIsKIFFs() {
        let fp = KIFFClient.fingerprint(Data([0x30, 0x59, 0x02]))
        XCTAssertEqual(fp.count, 19)
        XCTAssertEqual(fp.split(separator: " ").count, 4)
        XCTAssertTrue(KIFFClient.sameKey(fp.uppercased(), Data([0x30, 0x59, 0x02])))
        XCTAssertFalse(KIFFClient.sameKey(fp, Data([0x30, 0x59, 0x03])))
        XCTAssertFalse(KIFFClient.sameKey(nil, Data([0x30, 0x59, 0x02])))
    }

    func testSignersWithOneKeySignEverythingWithIt() async throws {
        let signer = StubSigner()
        XCTAssertTrue(signer.approvalPublicKeyDER.isEmpty)
        let signed = try await signer.signApproval(Data("x".utf8), reason: "test")
        XCTAssertEqual(signed, "stub-signature")
    }

    /// A call Coach's own agent holds is titled Coach: no taf in the list is Coach (phone check 4
    /// read "A taf").
    func testACoachHoldIsTitledCoach() async throws {
        let signer = TwoKeySigner()
        let coachHeld = Self.heldJSON.replacingOccurrences(of: #""subject":"rae-k3f9""#, with: #""subject":"setup""#)
        let model = await signedIn(signer, held: coachHeld)
        let call = try XCTUnwrap(model.held.first)
        XCTAssertNil(model.taf(call.tafID))
        XCTAssertTrue(model.isCoach(call.tafID))
        XCTAssertEqual(model.holderName(call.tafID), "Coach")
        let rae = await signedIn(signer, held: Self.heldJSON)
        let raeCall = try XCTUnwrap(rae.held.first)
        XCTAssertFalse(rae.isCoach(raeCall.tafID))
        XCTAssertEqual(rae.holderName(raeCall.tafID), "Rae")
    }

    /// A start of a re-link as KIFF answers it, polled at once.
    private static let relinkStart = #"{"device_code":"rl","user_code":"WXYZ-BCDF","verification_uri":"https://app.test/auth/device","verification_uri_complete":"https://app.test/auth/device?code=WXYZ-BCDF","expires_in":600,"interval":0}"#

    /// A phone whose faces or fingerprints changed approves again through the browser, with
    /// no sign-out (kiff/tafs#233, case 2): the new key proves itself under Face ID, the
    /// device key signs the request, and the session, its token and its list stay.
    func testARelinkLetsThePhoneApproveAgainWithoutSigningOut() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, held: Self.heldJSON)
        model.signInSlice = 1_000_000
        model.signInMinimumInterval = 0
        signer.biometricsChanged = true
        await model.refresh()
        XCTAssertTrue(model.approvalKeyLost)
        let newKey = signer.approvalKey

        StubProtocol.responses["POST /v1/me/device/relink"] = (200, Self.relinkStart)
        StubProtocol.responses["POST /v1/auth/device/token"] = (400, #"{"error":"authorization_pending"}"#)
        StubProtocol.thenRespond["POST /v1/auth/device/token"] =
            ["POST /v1/auth/device/token": (200, #"{"status":"relinked","fingerprint":""# + KIFFClient.fingerprint(newKey) + #""}"#)]
        signer.approvalPrompted = []
        let url = await model.startRelink()
        XCTAssertEqual(url?.absoluteString, "https://app.test/auth/device?code=WXYZ-BCDF")

        // The new key signed KIFF's message, after Face ID; the device key signed the request.
        let proof = try XCTUnwrap(signer.approvalPrompted.first)
        XCTAssertTrue(proof.hasPrefix("kiff.approval-key-relink.v1\nt_01\n"), proof)
        let request = try XCTUnwrap(sent("POST", "/v1/me/device/relink").first)
        let body = try JSONDecoder().decode([String: String].self, from: try XCTUnwrap(request.httpBody))
        XCTAssertEqual(body["approval_public_key"], newKey.base64EncodedString())
        XCTAssertEqual(body["signature"], "approval")
        XCTAssertTrue(request.value(forHTTPHeaderField: "KIFF-Device-Signature")?.hasSuffix(",v1=current") == true)

        for _ in 0..<200 where model.approvalKeyLost { try await Task.sleep(nanoseconds: 5_000_000) }
        XCTAssertFalse(model.approvalKeyLost)
        XCTAssertTrue(model.sessionHasApprovalKey)
        XCTAssertNil(model.relink)
        XCTAssertEqual(model.kiff.token, "kiff_dev_x", "the session goes on")
        XCTAssertTrue(sent("POST", "/v1/auth/device/logout").isEmpty, "no sign-out")
    }

    func testARelinkThatKIFFDoesntHaveSaysToSignInAgain() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, registration: (409, Self.holds(Data([0x30, 0x59, 0x09]))))
        XCTAssertTrue(model.approvalKeyLost)
        StubProtocol.responses["POST /v1/me/device/relink"] = (404, #"{"error":"not found"}"#)
        let url = await model.startRelink()
        XCTAssertNil(url)
        XCTAssertNil(model.relink)
        XCTAssertTrue(model.lastError?.contains("sign in again from Settings") == true, model.lastError ?? "")
        XCTAssertTrue(model.approvalKeyLost)
    }

    func testARelinkWhosePromptIsntRecognizedAsksNothingOfKIFF() async throws {
        let signer = TwoKeySigner()
        let model = await signedIn(signer, registration: (409, Self.holds(Data([0x30, 0x59, 0x09]))))
        signer.approvalUnrecognized = true
        let url = await model.startRelink()
        XCTAssertNil(url)
        XCTAssertEqual(model.lastError, KIFFClient.notRecognized)
        XCTAssertTrue(sent("POST", "/v1/me/device/relink").isEmpty)
    }

    func testTheRelinkMessageIsKIFFs() {
        let message = String(decoding: KIFFClient.relinkMessage(tenant: "t_01", deviceKey: Data([1]), newKey: Data([2]), signedAt: "2026-10-30T22:00:00Z"), as: UTF8.self)
        XCTAssertEqual(message, "kiff.approval-key-relink.v1\nt_01\n" +
                       "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a\n" +
                       "dbc1b4c900ffe48d575b5da5c638040125f65db0fe3e24494b76ea986457d986\n2026-10-30T22:00:00Z")
    }
}
