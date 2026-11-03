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

    /// The Refunds sample's Setup screens (`-screen starts`, `-screen results`): a schedule
    /// with its sizes, an app event to approve, a door, a follow-up, and where results go.
    static var setupKnob: TafsCloud.Knob {
        let setting = TafsCloud.Knob.Setting(wakes: [], busy: "skip", results: [.string("door_reply"), .string("app")])
        func choice(_ label: String) -> TafsCloud.Knob.Item.Size.Choice { .init(label: label, setting: setting) }
        var schedule = TafsCloud.Knob.Item(id: "schedule_1", kind: "schedule", label: "Every 4 hours",
                                           words: "Every 4 hours, 9:00–17:00")
        schedule.size = .init(label: "Every 4 hours", index: 2, choices: ["Every hour", "Every 2 hours", "Every 4 hours", "Every 8 hours"].map(choice))
        var event = TafsCloud.Knob.Item(id: "event_1", kind: "app_event", label: "Stripe · new dispute",
                                        words: "A dispute opened on a charge over €50", on: false, ok: "approve")
        event.approval = .init(state: "needed", grant: "sample_1", event: "DISPUTE_CREATED",
                               config: ["amount_over": .number(50)], subscription: nil)
        var listening = TafsCloud.Knob.Item(id: "event_2", kind: "app_event", label: "Gmail · new email",
                                            words: "An email to refunds@ with \"refund\" in its subject")
        listening.approval = .init(state: "approved", grant: "sample_2", event: "NEW_EMAIL", config: [:], subscription: "sub_1")
        listening.trigger = .object(["kind": .string("app_event"), "grant": .string("sample_2")])
        var followUp = TafsCloud.Knob.Item(id: "fu_1", kind: "follow_up", label: "Follow-up",
                                           words: "Check dispute #4825 again", expires: "2026-11-04T10:00:00Z")
        followUp.followUp = "fu-1"
        func place(_ id: String, _ place: String, _ label: String, on: Bool) -> TafsCloud.Knob.Item {
            var item = TafsCloud.Knob.Item(id: id, place: place, label: label, words: "", on: on)
            item.size = .init(label: on ? "On" : "Off", index: on ? 0 : 1, choices: [choice("On"), choice("Off")])
            return item
        }
        var notion = place("result_notion", "action", "Notion · Support log", on: true)
        notion.words = "A line for each refund, in the Support log page"
        var knob = TafsCloud.Knob(version: 7,
            does: .init(label: "Refund", line: "Checks the charge, refunds duplicates and keeps a log."),
            wakes: [schedule, event, listening,
                    .init(id: "door_sample", kind: "door", label: "Email", words: "Email to refunds@tafs.example", state: "Open", door: "door_sample"),
                    followUp],
            results: [place("result_reply", "door_reply", "Reply to the sender", on: true),
                      place("result_app", "app", "Alert our phones", on: true),
                      place("result_email", "owner_email", "Email me", on: false),
                      notion],
            rim: .init(done: 12, working: 1, line: "12 done today · 1 working"))
        knob.setting = setting
        return knob
    }
}
