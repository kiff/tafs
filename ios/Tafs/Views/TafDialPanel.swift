import SwiftUI

/// Owns the remote's requests and confirmation, apart from the dial's drawing.
struct TafDialPanel: View {
    @EnvironmentObject private var model: AppModel
    let taf: Taf
    let knob: TafsCloud.Knob
    @Binding var face: TafKnobView.Face
    @ObservedObject var doors: DoorsModel
    let manages: Bool
    let held: [HeldCall]
    var press: (TafsCloud.Knob.Item) async -> Void
    var approve: (HeldCall) -> Void
    @State private var busy = false
    @State private var reading: TafsCloud.Version?
    @State private var requests = false
    @State private var changedStops: Set<String> = []

    var body: some View {
        VStack(spacing: 14) {
            if let draft = knob.draft {
                Text("DRAFT · V\(draft.version) · NOT LIVE").scaledFont(10, weight: .semibold, design: .monospaced)
                    .foregroundColor(Theme.amberText).frame(maxWidth: .infinity, alignment: .leading)
            }
            TafKnobView(knob: knob, face: $face, held: held, press: { _, item in
                guard manages, !busy else { return }
                busy = true
                Task {
                    defer { busy = false }
                    await press(item)
                    await doors.load()
                }
            }, doors: doors.doors, knocks: doors.openKnocks,
                        paused: model.cloudTaf(for: taf.id)?.paused ?? false,
                        manages: manages, busy: busy,
                        askCoach: { model.openCoach() }, pause: pause,
                        adjust: adjust, canAdjust: { item, direction in
                            knob.acceptsChoices(waitingFor: model.knobChanges[taf.id]?.version) && item.size?.choice(moving: direction) != nil
                        }, approve: approve)
            if let draft = knob.draft, manages {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(draft.words.enumerated()), id: \.offset) { _, line in
                        Text(line).scaledFont(13).foregroundColor(Theme.soft)
                    }
                    Button(busy ? "Confirming…" : liveLabel) { confirm(draft.version) }
                        .buttonStyle(PrimaryButtonStyle()).disabled(busy)
                        .accessibilityIdentifier("knob.makeLive")
                }
            }
            Button { requests = true } label: {
                HStack(spacing: 4) {
                    Text(knob.rim.line ?? "\(knob.rim.done) done today").scaledFont(12)
                    Image(systemName: "chevron.right").font(.caption2)
                }.foregroundColor(Theme.dim).frame(minHeight: 44)
            }
            .accessibilityIdentifier("knob.requests")
            if let error = model.lastError {
                Text(error).scaledFont(13).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                Button("Refresh dial") { Task { await model.loadKnob(taf.id) } }.disabled(busy)
            }
        }
        .task { showHeld() }
        .onChange(of: knob.draft?.version) { version in if version == nil { changedStops = [] } }
        .onChange(of: held.map(\.id)) { _ in showHeld() }
        .sheet(item: $reading) { version in
            VersionCardSheet(version: version, tafID: taf.id).environmentObject(model)
        }
        .sheet(isPresented: $requests) { KnobRequestsSheet(tafID: taf.id).environmentObject(model) }
    }

    private var liveLabel: String {
        let changes = changedStops.count
        return changes == 0 ? "Make it live with \(Biometry.name)"
            : "Make \(changes) change\(changes == 1 ? "" : "s") live with \(Biometry.name)"
    }

    private func showHeld() {
        if let call = held.first { face = .held(call.id) }
        else if case .held = face { face = .overview }
    }

    private func adjust(_ item: TafsCloud.Knob.Item, _ direction: Int) {
        guard manages, !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            if await model.adjustKnob(taf.id, item: item, direction: direction) { changedStops.insert(item.id) }
        }
    }

    private func pause() {
        guard manages, !busy, let cloud = model.cloudTaf(for: taf.id) else { return }
        busy = true
        Task {
            defer { busy = false }
            await model.setPaused(taf.id, !cloud.paused)
            await model.loadKnob(taf.id)
        }
    }

    private func confirm(_ n: Int) {
        guard manages, !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            await model.loadVersions(taf.id)
            await model.loadKnob(taf.id)
            guard model.knobs[taf.id]?.draft?.version == n,
                  let version = model.versions[taf.id]?.first(where: { $0.n == n }),
                  let change = await model.prepareSwitch(version, of: taf.id) else { return }
            if change.draft == nil { _ = await model.useVersion(version, of: taf.id) }
            else { reading = version }
            await model.loadKnob(taf.id)
        }
    }
}

struct KnobRequestsSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let tafID: UUID
    @State private var path: [String] = []
    @State private var openedLatest = false
    var body: some View {
        NavigationStack(path: $path) {
            List {
                if let error = model.workErrors[tafID] { Text(error).foregroundColor(Theme.no) }
                ForEach(model.works[tafID]?.requests ?? []) { request in
                    NavigationLink(value: request.id) {
                        Text(request.line).scaledFont(14).foregroundColor(Theme.ink)
                    }
                }
                if let work = model.works[tafID], work.requests.isEmpty {
                    Text("No requests yet.").foregroundColor(Theme.dim)
                }
            }
            .navigationTitle("Its work").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .navigationDestination(for: String.self) { run in KnobRequestDetail(tafID: tafID, run: run) }
            .task(id: tafID) {
                await model.loadWork(tafID)
                if !openedLatest, let latest = model.works[tafID]?.requests.first {
                    openedLatest = true
                    path = [latest.id]
                }
            }
        }
    }
}

struct KnobRequestDetail: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    let run: String
    @State private var detail: TafsCloud.RequestDetail?
    @State private var error: String?
    @State private var sending = false
    var body: some View {
        List {
            if let detail {
                Section(detail.from) { Text(detail.line) }
                Section {
                    ForEach(Array(detail.steps.enumerated()), id: \.offset) { _, step in
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: step.state == "done" ? "checkmark.circle.fill" : step.state == "next" ? "clock" : "exclamationmark.circle")
                                .foregroundColor(step.state == "done" ? Theme.ok : Theme.amberText)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(step.label).scaledFont(15, weight: .semibold)
                                if let line = step.line { Text(line).scaledFont(13).foregroundColor(Theme.dim) }
                            }
                        }
                    }
                }
                ForEach(Array(detail.deliveries.enumerated()), id: \.offset) { _, delivery in
                    if delivery.sendAgain, model.manages(tafID) {
                        Button(sending ? "Sending again…" : "Try the reply now") {
                            sending = true
                            Task {
                                defer { sending = false }
                                await model.sendAgain(tafID, run: run)
                                await load()
                            }
                        }.disabled(sending)
                    }
                }
                Button("Ask Coach") { model.openCoach() }
            } else if error == nil { ProgressView() }
            if let error {
                Text(error).foregroundColor(Theme.no)
                Button("Try again") { Task { await load() } }
            }
        }
        .navigationTitle("Request").navigationBarTitleDisplayMode(.inline)
        .task(id: run) { await load() }
    }

    private func load() async {
        guard let cloud = model.cloudTaf(for: tafID) else { return }
        do {
            let read = try await model.requestDetail(tafID, run: run)
            guard !Task.isCancelled, model.cloudTaf(for: tafID)?.id == cloud.id else { return }
            detail = read; error = nil
        } catch {
            guard !Task.isCancelled else { return }
            self.error = error.localizedDescription
        }
    }
}
