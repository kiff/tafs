import SwiftUI

/// Owns the remote's requests and confirmation, apart from the dial's drawing.
struct TafDialPanel<Details: View>: View {
    @EnvironmentObject private var model: AppModel
    let taf: Taf
    let knob: TafsCloud.Knob
    @Binding var face: TafKnobView.Face
    @ObservedObject var doors: DoorsModel
    let manages: Bool
    let held: [HeldCall]
    var press: (TafsCloud.Knob.Item) async -> Void
    var approve: (HeldCall) -> Void
    let details: Details
    @State private var filter: KnobNavigation.Filter = .all
    @State private var busy = false
    @State private var reading: TafsCloud.Version?
    @State private var requests = false
    @State private var eventToApprove: TafsCloud.Knob.Item?

    var body: some View {
        ScrollView {
            content.padding(.horizontal, 16).padding(.vertical, 12)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 8) {
                if let error = model.lastError {
                    Text(error).scaledFont(13).foregroundColor(Theme.no)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("knob.error")
                }
                TafKnobRemote(knob: knob, face: $face, filter: filter,
                              paused: model.cloudTaf(for: taf.id)?.paused ?? false,
                              manages: manages, busy: busy, pause: pause, adjust: adjust,
                              canAdjust: { item, direction in
                                  knob.acceptsChoices(waitingFor: model.knobChanges[taf.id]?.version) && item.size?.choice(moving: direction) != nil
                              })
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(Theme.background)
        }
        .task { showHeld() }
        .onChange(of: held.map(\.id)) { _ in showHeld() }
        .sheet(item: $reading) { version in
            VersionCardSheet(version: version, tafID: taf.id).environmentObject(model)
        }
        .sheet(isPresented: $requests) { KnobRequestsSheet(tafID: taf.id).environmentObject(model) }
        .sheet(item: $eventToApprove) { item in
            AppEventApprovalSheet(item: item, approve: { await model.approveKnobEvent(taf.id, item: item) })
                .environmentObject(model)
        }
    }

    private var content: some View {
        VStack(spacing: 14) {
            if let pending = knob.pending {
                Text(Words.Knob.notLive(version: "\(pending.version)")).scaledFont(10, weight: .semibold, design: .monospaced)
                    .foregroundColor(Theme.amberText).frame(maxWidth: .infinity, alignment: .leading)
            }
            TafKnobView(knob: knob, face: $face, held: held, press: { _, item in
                guard manages, !busy else { return }
                if item.ok == "approve" { eventToApprove = item; return }
                busy = true
                Task {
                    defer { busy = false }
                    await press(item)
                    await doors.load()
                }
            }, doors: doors.doors, manages: manages, busy: busy,
                        askCoach: { model.openCoach() }, approve: approve, filter: $filter)
            if let pending = knob.pending, manages {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array((knob.draft?.words ?? []).enumerated()), id: \.offset) { _, line in
                        Text(line).scaledFont(13).foregroundColor(Theme.soft)
                    }
                    Button(busy ? "Confirming…" : liveLabel) { confirm(pending.version) }
                        .buttonStyle(PrimaryButtonStyle()).disabled(busy)
                        .accessibilityIdentifier("knob.makeLive")
                }
            }
            Button { requests = true } label: {
                HStack(spacing: 4) {
                    Text(knob.rim.line ?? Words.Knob.doneToday(done: "\(knob.rim.done)")).scaledFont(12)
                    Image(systemName: "chevron.right").font(.caption2)
                }.foregroundColor(Theme.dim).frame(minHeight: 44)
            }
            .accessibilityIdentifier("knob.requests")
            details
        }
    }

    private var liveLabel: String {
        let changes = knob.pending?.changes ?? 0
        return "Make \(changes) change\(changes == 1 ? "" : "s") live with \(Biometry.name)"
    }

    private func showHeld() {
        if case .held(let id) = face, !held.contains(where: { $0.id == id && $0.approvableOnce }) { face = .overview }
        if face == .overview, let call = held.first(where: \.approvableOnce) { face = .held(call.id) }
    }

    private func adjust(_ item: TafsCloud.Knob.Item, _ direction: Int) {
        guard manages, !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            _ = await model.adjustKnob(taf.id, item: item, direction: direction)
        }
    }

    private func pause() {
        guard manages, !busy, knob.canPause, let cloud = model.cloudTaf(for: taf.id) else { return }
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
            guard model.knobs[taf.id]?.pending?.version == n,
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
                    Text(Words.Knob.noRequests).foregroundColor(Theme.dim)
                }
            }
            .navigationTitle(Words.Taf.itsWork).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(Words.Common.done) { dismiss() } } }
            .navigationDestination(for: String.self) { run in KnobRequestDetail(tafID: tafID, run: run) }
            .task(id: tafID) {
                await model.loadWork(tafID)
                if !openedLatest, let latest = model.works[tafID]?.focusedRequest {
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
                        Button(sending ? Words.Work.sendingAgain : Words.Work.Request.tryReplyNow) {
                            sending = true
                            Task {
                                defer { sending = false }
                                await model.sendAgain(tafID, run: run)
                                await load()
                            }
                        }.disabled(sending)
                    }
                }
                Button(Words.Coach.ask) { model.openCoach() }
            } else if error == nil { ProgressView() }
            if let error {
                Text(error).foregroundColor(Theme.no)
                Button(Words.Common.tryAgain) { Task { await load() } }
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
