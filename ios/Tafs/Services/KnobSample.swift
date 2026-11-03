import Foundation

extension Screenshots {
    /// Synthetic dial for simulator screenshots and navigation tests. It never calls a service.
    static var knob: TafsCloud.Knob {
        var knob = TafsCloud.Knob(version: 7,
            does: .init(label: "Refund", line: "Checks the charge, refunds duplicates and keeps a log."),
            wakes: (1...12).map { .init(id: "event_\($0)", kind: "app_event", label: "Service \($0)", words: "An event from Service \($0)", state: "On", grant: "sample_\($0)") },
            results: (1...12).map { .init(id: "result_\($0)", place: "action", label: "Service \($0)", words: "Logs the result in Service \($0)", state: "On", grant: "sample_\($0)") },
            rim: .init(done: 12, working: 1, retrying: 1, needsYou: 0, line: "12 done today · 1 working · 1 retrying"))
        knob.wakes[0] = .init(id: "door_sample", kind: "door", label: "Email",
                              words: "Email to refunds@tafs.example", state: "Open", tap: "Tap to close", door: "door_sample", ok: "door", look: "knock")
        knob.wakes.append(.init(id: "add_door", kind: "add_door", label: "Add a door", words: "Ask Coach to add a door",
                                state: "Add", tap: "Tap to ask Coach", ok: "coach"))
        knob.why = "Refund double charges"
        knob.what = [
            .init(id: "check", label: "Check", words: "Looks up the charge", state: "Check", step: 1),
            .init(id: "refund", label: "Refund", words: "Refunds the duplicate within its Card", state: "Refund", step: 2),
            .init(id: "log", label: "Log", words: "Keeps a log", state: "Log", step: 3),
        ]
        knob.connections = (1...12).map { .init(grant: "sample_\($0)", name: "Service \($0)", wakes: $0 == 1 ? 0 : 1, results: 1, icon: nil) }
        knob.canPause = !ProcessInfo.processInfo.arguments.contains("-knob-no-pause")
        if ProcessInfo.processInfo.arguments.contains("-knob-long-labels") {
            knob.why = "Refund duplicate charges after checking the original payment and keeping a record"
            if let index = knob.wakes.firstIndex(where: { $0.id == "event_12" }) {
                knob.wakes[index].label = "A very long connection event name that must remain readable with VoiceOver"
                knob.wakes[index].state = "Waiting for the next event from the connected business application"
            }
        }
        if ProcessInfo.processInfo.arguments.contains("-knob-event-approval"),
           let index = knob.wakes.firstIndex(where: { $0.id == "event_12" }) {
            knob.wakes[index].label = "Support inbox"
            knob.wakes[index].ok = "approve"
            knob.wakes[index].on = false
            knob.wakes[index].state = "Needs your approval"
            knob.wakes[index].approval = .init(state: "needed", grant: "sample_12", event: "NEW_TICKET",
                config: ["queue": .string("Support queue"), "include_archived": .bool(false)], subscription: nil)
        }
        return knob
    }
}
