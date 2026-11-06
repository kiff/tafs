import Foundation

extension TafsCloud {
    struct CardUnitException: Decodable, Equatable, Identifiable {
        let id: String; let kind: String; let taf: String; let name: String; let title: String; let words: String
        let history: String; let action_title: String; let coach_text: String
        let dismiss_title: String; let can_approve_once: Bool
    }
    struct AlertChoice: Decodable, Identifiable { let id: String; let title: String; let words: String; let enabled: Bool }
    struct AlertTaf: Decodable, Identifiable { let id: String; let name: String }
    struct PhoneAlerts: Decodable {
        struct Answering: Decodable { let title: String; let tafs: [AlertTaf] }
        let enabled: Bool; let sound: Bool; let kinds: [AlertChoice]; let answering: Answering
    }
    struct TimeZoneChoice: Decodable, Identifiable { let id: String; let title: String }
    struct ActivityChoice: Decodable, Identifiable { let id: String; let title: String }
    struct ActivityEntry: Decodable, Identifiable {
        let id: String; let at: Date; let kind: String; let title: String; let detail: String; let time: String
        let amount_text: String?; let call: KIFFClient.ActivityCall
        /// What the owner can do about a call that wasn't done, as tafs cloud says it (kiff/tafs#476, gap 10).
        var next: NextStep? = nil
    }
    /// The owner's next step for a call that wasn't done: `kind` is `reconnect` (the connection
    /// whose tools include `tool`), `ask_coach` (Coach on the taf, `message` written in) or
    /// `try_later`; `words` are its button's, or its line's for a kind the app doesn't know.
    struct NextStep: Decodable, Equatable {
        let kind: String; let words: String
        var tool: String? = nil; var taf: String? = nil; var message: String? = nil
    }
    struct ActivityGroup: Decodable, Identifiable { let id: String; let title: String; let entries: [ActivityEntry] }
    struct ActivityPage: Decodable {
        let entries: [ActivityEntry]; let groups: [ActivityGroup]; let filters: [ActivityChoice]
        let filter: String; let revision: String; let next_cursor: String?; var end_words: String? = nil
        var card_unit_exceptions: [CardUnitException]? = nil
        var card_unit_exceptions_notice: String? = nil
    }
    struct RunsPage: Decodable {
        let requests: [TafWork.Request]; let next_cursor: String?; let title: String; let more_title: String; var end_words: String? = nil
    }
    struct ConnectedAction: Decodable, Identifiable {
        var id: String { name }
        let name: String; let title: String; let limits: [String]; let steps: [Int]; let step_words: String
    }
    struct ConnectedApp: Decodable, Identifiable {
        struct Stats: Decodable { let calls: Int; let done: Int; let held: Int; let words: String; let complete: Bool; let activity_query: String }
        let id: String; let name: String; let status: String; let status_words: String; let reconnect: Bool; let action_count: Int; let action_words: String
        let actions: [ConnectedAction]; let steps: [Int]; let workflow_steps: [WorkflowStep]; let running: Bool; let stats: Stats
        /// With no saved workflow, the one line under Workflow (kiff/tafs#469 item 2).
        var workflow_words: String? = nil
    }
    struct WorkflowStep: Decodable, Identifiable {
        var id: Int { number }
        let number: Int; let label: String; let words: String; let app: String?; let grant: String?; let running: Bool
    }
    struct SetupView: Decodable {
        struct Workflow: Decodable { let title: String; let summary: String; let steps: [WorkflowStep]; let running_step: Int? }
        struct Memory: Decodable { let count: Int; let title: String; let latest: String? }
        struct Versions: Decodable { let title: String; let summary: String; let live: Int?; let earlier: Int }
        let connections: [ConnectedApp]; let workflow: Workflow; let memory: Memory; let versions: Versions; let connections_error: String?
        var card_unit_exceptions: [CardUnitException]? = nil
    }
    private func query(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? value
    }
    func activityPage(_ id: String, filter: String, cursor: String?, etag: String?, selection: String) async throws -> (ActivityPage?, String?) {
        let path = "/v1/tafs/\(escaped(id))/activity?filter=\(query(filter))" + (selection.isEmpty ? "" : "&\(selection)") + (cursor.map { "&cursor=\(query($0))" } ?? "")
        let (data, tag) = try await representation(path, etag: etag)
        return (try data.map { try KIFFClient.decoder.decode(ActivityPage.self, from: $0) }, tag)
    }
    func runsPage(_ id: String, cursor: String?, etag: String?, limit: Int) async throws -> (RunsPage?, String?) {
        let path = "/v1/tafs/\(escaped(id))/work/runs?limit=\(limit)" + (cursor.map { "&cursor=\(query($0))" } ?? "")
        let (data, tag) = try await representation(path, etag: etag)
        return (try data.map { try KIFFClient.decoder.decode(RunsPage.self, from: $0) }, tag)
    }
    func setupView(_ id: String, proof: String?) async throws -> SetupView {
        try await send("GET", "/v1/tafs/\(escaped(id))/setup", headers: proof.map { ["Planque-Device-Signature": $0] } ?? [:])
    }
    func unregisterPhone(_ token: String, environment: String) async throws {
        struct Body: Encodable { let device_token: String; let environment: String }
        struct Result: Decodable { let removed: Bool }
        let _: Result = try await send("DELETE", "/v1/push", body: Body(device_token: token, environment: environment))
    }
    func phoneAlerts(_ token: String) async throws -> PhoneAlerts {
        try await send("GET", "/v1/push", headers: ["Tafs-Device-Token": token])
    }
    func timeZones(_ search: String) async throws -> [TimeZoneChoice] {
        struct Response: Decodable { let zones: [TimeZoneChoice] }
        let response: Response = try await send("GET", "/v1/time-zones?q=\(query(search))")
        return response.zones
    }
    func syncTimeZone() async throws {
        struct Result: Decodable { let time_zone: String; let updated: Int }
        let _: Result = try await send("POST", "/v1/account/time-zone")
    }
}

/// Cached representations are scoped to the account and taf; the server owns pagination.
struct SourcePages<Page> {
    var pages: [Page] = []
    var etag: String?
    var loading = false
    var moreWhenReady = false
    var error: String?
}
