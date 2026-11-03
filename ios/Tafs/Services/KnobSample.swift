import Foundation

extension Screenshots {
    /// Synthetic dial for simulator screenshots and navigation tests. It never calls a service.
    static var knob: TafsCloud.Knob {
        var knob = TafsCloud.Knob(version: 7,
            does: .init(label: "Refund", line: "Checks the charge, refunds duplicates and keeps a log."),
            wakes: (1...12).map { .init(id: "event_\($0)", kind: "app_event", label: "Service \($0)", words: "An event from Service \($0)", state: "On", grant: "sample_\($0)") },
            results: (1...12).map { .init(id: "result_\($0)", place: "action", label: "Service \($0)", words: "Logs the result in Service \($0)", state: "On", grant: "sample_\($0)") },
            rim: .init(done: 12, working: 1, retrying: 1, needsYou: 0, line: "12 done today · 1 working · 1 retrying"))
        knob.why = "Refund double charges"
        knob.what = [
            .init(id: "check", label: "Check", words: "Looks up the charge", state: "Check", step: 1),
            .init(id: "refund", label: "Refund", words: "Refunds the duplicate within its Card", state: "Refund", step: 2),
            .init(id: "log", label: "Log", words: "Keeps a log", state: "Log", step: 3),
        ]
        knob.connections = (1...12).map { .init(grant: "sample_\($0)", name: "Service \($0)", wakes: 1, results: 1, icon: nil) }
        return knob
    }
}
