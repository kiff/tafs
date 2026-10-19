import SwiftUI

/// Starting a taf on KIFF: pick its job → choose the connected tools it may use and their
/// limits → confirm with Face ID. The app then adds the agent, issues its Cards, gives it
/// its gateway key, and tafs cloud runs it on its job.
struct StartTafFlow: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var step = 1
    @State private var job: TafTemplate?
    @State private var name = ""
    @State private var limits: [ToolLimit] = []
    @State private var loading = false
    @State private var loadError: String?
    @State private var reached: AppModel.StartStep?
    @State private var failure: AppModel.StartFailure?
    @State private var started: AppModel.StartResult?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Eyebrow(text: "New taf · \(step) of 3")
                    switch step {
                    case 1: jobStep
                    case 2: toolsStep
                    default: startStep
                    }
                }
                .padding(20)
            }
            .screenBackground()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(started == nil ? "Close" : "Done") { dismiss() } }
                if step > 1 && reached == nil {
                    ToolbarItem(placement: .navigationBarLeading) { Button("Back") { step -= 1 } }
                }
            }
        }
    }

    // MARK: 1. The job

    private var jobStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("What's its job?").font(Theme.display(38)).foregroundColor(Theme.ink)
            Text("Each job is a few critical actions it does for you, inside a Card you set.")
                .font(.system(size: 15)).foregroundColor(Theme.soft)
            ForEach(TafTemplates.all) { t in
                JobRow(template: t, selected: job?.key == t.key) {
                    job = t
                    if name.isEmpty || TafTemplates.all.contains(where: { $0.name == name }) { name = t.name }
                }
            }
            TextField("Its name", text: $name)
                .font(.system(size: 17))
                .padding(12)
                .background(Theme.panel)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .accessibilityLabel("The taf's name")
            Button("Choose its tools") {
                step = 2
                Task { await loadTools() }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(job == nil || name.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    // MARK: 2. Tools and limits

    private var toolsStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("What may \(name) use?").font(Theme.display(34)).foregroundColor(Theme.ink)
            Text("Your tools connected to KIFF. Each one you turn on gets its own Card with these limits; anything over them waits for you.")
                .font(.system(size: 14)).foregroundColor(Theme.soft)
            if loading {
                ProgressView().tint(Theme.amber).frame(maxWidth: .infinity)
            } else if let loadError {
                Text(loadError).font(.system(size: 14)).foregroundColor(Theme.no)
                Button("Try again") { Task { await loadTools() } }.buttonStyle(OutlineButtonStyle())
            } else if limits.isEmpty {
                Text("You have no tools connected to KIFF yet. Connect the ones this job needs, then come back.")
                    .font(.system(size: 14)).foregroundColor(Theme.soft)
                Link(destination: model.kiff.webURL.appendingPathComponent("tools")) {
                    Label("Connect tools in KIFF", systemImage: "arrow.up.right")
                }
                .buttonStyle(OutlineButtonStyle())
            } else {
                ForEach($limits) { $limit in ToolLimitEditor(limit: $limit) }
                if let problem = planProblem {
                    Text(problem).font(.system(size: 13)).foregroundColor(Theme.no)
                }
                Button("Review") { step = 3 }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(planProblem != nil)
            }
        }
    }

    private var planProblem: String? {
        do { _ = try TafPlan.cards(agentID: "check", limits: limits); return nil } catch { return error.localizedDescription }
    }

    private func loadTools() async {
        guard let job else { return }
        loading = true
        loadError = nil
        defer { loading = false }
        do {
            limits = TafPlan.suggested(job: job.key, tools: try await model.connectedTools())
        } catch {
            loadError = "Your tools couldn't be loaded from KIFF: \(error.localizedDescription)"
        }
    }

    // MARK: 3. Start

    private var startStep: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(started == nil ? "Start \(name)" : "\(name) is running").font(Theme.display(32)).foregroundColor(Theme.ink)
            ForEach(limits.filter(\.on)) { limit in
                HStack(alignment: .firstTextBaseline) {
                    Text(limit.tool.name).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                    Spacer()
                    Text(summary(limit)).font(Theme.mono(13)).foregroundColor(Theme.soft).multilineTextAlignment(.trailing)
                }
                .padding(14).background(Theme.panel).clipShape(RoundedRectangle(cornerRadius: 14))
            }
            Text("Runs \(job?.name.lowercased() ?? "its job") on its schedule. Over a limit, it waits for you here and on your lock screen.")
                .font(.system(size: 14)).foregroundColor(Theme.soft)

            if reached != nil {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(AppModel.StartStep.allCases, id: \.self) { s in progressRow(s) }
                }
            }
            if let failure {
                Text(failure.message).font(.system(size: 14)).foregroundColor(Theme.no)
            }
            if let started {
                Text(started.session == nil
                     ? "\(name) is set up and runs on its schedule. Its first run didn't start; you can run it now from its page."
                     : "Its first run has started. Its report shows on its page when the run ends.")
                    .font(.system(size: 14)).foregroundColor(Theme.ok)
                Button("Done") { dismiss() }.buttonStyle(PrimaryButtonStyle())
            } else {
                Button {
                    Task { await start() }
                } label: {
                    Label(failure == nil ? "Start with \(Biometry.name)" : "Try again with \(Biometry.name)", systemImage: Biometry.symbol)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(reached != nil && failure == nil)
            }
        }
    }

    private func progressRow(_ s: AppModel.StartStep) -> some View {
        let state: (String, Color) = {
            guard let reached else { return ("circle", Theme.dim) }
            if started != nil || s < reached { return ("checkmark.circle.fill", Theme.ok) }
            if s == reached { return failure == nil ? ("circle.dotted", Theme.amber) : ("xmark.circle.fill", Theme.no) }
            return ("circle", Theme.dim)
        }()
        return Label(s.label, systemImage: state.0)
            .font(.system(size: 14))
            .foregroundColor(state.1)
    }

    private func start() async {
        guard let job else { return }
        failure = nil
        started = nil
        do {
            started = try await model.startOnKIFF(name: name, job: job.key, limits: limits) { reached = $0 }
        } catch let f as AppModel.StartFailure {
            failure = f
            if f.step == .confirm { reached = nil }
        } catch {
            failure = AppModel.StartFailure(step: reached ?? .confirm, message: error.localizedDescription)
        }
    }

    private func summary(_ limit: ToolLimit) -> String {
        let unit = limit.tool.amountUnit.map { " \($0)" } ?? ""
        var parts: [String] = []
        if !limit.perCall.isEmpty { parts.append("≤ \(limit.perCall)\(unit) each") }
        if !limit.perDay.isEmpty { parts.append("\(limit.perDay)\(unit) a day") }
        if !limit.callsPerDay.isEmpty { parts.append("\(limit.callsPerDay) calls a day") }
        return parts.joined(separator: "\n")
    }
}

/// One of the jobs a taf can do, as a card to pick.
struct JobRow: View {
    let template: TafTemplate
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? AnyShapeStyle(Theme.brand) : AnyShapeStyle(Theme.dim))
                VStack(alignment: .leading, spacing: 4) {
                    Text(template.name).font(.system(size: 16, weight: .semibold)).foregroundColor(Theme.ink)
                    Text(template.job).font(.system(size: 13)).foregroundColor(Theme.soft).lineLimit(3)
                }
                Spacer(minLength: 0)
            }
            .padding(14)
            .background(Theme.panel)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(selected ? Theme.pink.opacity(0.6) : Theme.line))
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// One connected tool: on or off, and its limits in the tool's own unit.
struct ToolLimitEditor: View {
    @Binding var limit: ToolLimit

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle(isOn: $limit.on) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(limit.tool.name).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                    if let d = limit.tool.description, !d.isEmpty {
                        Text(d).font(.system(size: 12)).foregroundColor(Theme.dim).lineLimit(2)
                    }
                }
            }
            .tint(Theme.amber)
            if limit.on {
                if limit.tool.movesAmount {
                    field("Up to, each", $limit.perCall, unit: limit.tool.amountUnit)
                    field("Up to, a day", $limit.perDay, unit: limit.tool.amountUnit)
                }
                field("Calls a day", $limit.callsPerDay, unit: nil)
            }
        }
        .padding(14)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(limit.on ? Theme.amber.opacity(0.45) : Theme.line))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private func field(_ label: String, _ text: Binding<String>, unit: String?) -> some View {
        HStack {
            Text(label).font(.system(size: 14)).foregroundColor(Theme.soft)
            Spacer()
            TextField("no limit", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .font(Theme.mono(15))
                .foregroundColor(Theme.ink)
                .frame(width: 110)
                .padding(.vertical, 8).padding(.horizontal, 10)
                .background(Theme.background)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .accessibilityLabel(label)
            if let unit { Text(unit).font(Theme.mono(12)).foregroundColor(Theme.dim).frame(width: 34, alignment: .leading) }
        }
    }
}
