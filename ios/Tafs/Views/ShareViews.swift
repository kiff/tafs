import SwiftUI


/// Who a taf is shared with, on its page, and Share for whoever may change it (KIFF's
/// manage). Nothing for a taf KIFF doesn't run.
struct ShareLine: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @State private var shares: [KIFFClient.Share]?
    @State private var sharing = false

    var body: some View {
        if model.mode == .kiff, let agent = model.kiffAgent(for: tafID), let taf = model.taf(tafID) {
            // The system's share mark, large enough to find, with who uses the taf beside it
            // (the 870 test: the small "Share" link went unseen).
            HStack(spacing: 10) {
                if model.access[agent]?.manage == true {
                    Button { sharing = true } label: {
                        Label(Words.People.Share.action, systemImage: "square.and.arrow.up")
                            .scaledFont(15, weight: .semibold)
                            .padding(.horizontal, 12).padding(.vertical, 7)
                            .background(Theme.panel)
                            .overlay(Capsule().stroke(Theme.line))
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.drivablePlain)
                    .foregroundColor(Theme.ink)
                    .accessibilityIdentifier("share-taf")
                }
                Text(Self.sharedWith(shares, model)).scaledFont(14).foregroundColor(Theme.soft).oneLineUnlessLarge()
            }
            .padding(.top, 2)
            .task(id: model.groups) { shares = try? await model.kiff.shares(agent: agent) }
            .task { if model.groups.isEmpty && model.people.isEmpty { await model.refreshPeople() } }
            .sheet(isPresented: $sharing, onDismiss: { Task { shares = try? await model.kiff.shares(agent: agent) } }) {
                ShareTafSheet(taf: taf, agent: agent).environmentObject(model)
            }
        }
    }

    /// "Shared with Support and ana@acme.com", or not shared yet.
    static func sharedWith(_ shares: [KIFFClient.Share]?, _ model: AppModel) -> String {
        guard let shares else { return Words.People.sharing }
        let names = shares.compactMap { s in
            s.group.flatMap { g in model.groups.first { $0.id == g }?.name } ?? s.subject.map { model.person($0)?.label ?? $0 }
        }
        return names.isEmpty ? Words.People.notShared : Words.People.sharedWithPrefix + Words.list(names)
    }
}

/// Share a taf, in two steps (the signed-off design): who uses it, then what that means,
/// then one Face ID for all of it.
struct ShareTafSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let taf: Taf
    let agent: String
    @State private var step = 1
    @State private var had: Set<KIFFClient.Share> = []
    @State private var chosen: Set<KIFFClient.Share> = []
    @State private var loaded = false
    @State private var saving = false

    var body: some View {
        NavigationStack {
            List {
                if step == 1 { whoUses } else { whatItMeans }
                if let error = model.peopleError {
                    Section { Text(error).scaledFont(14).foregroundColor(Theme.no) }
                }
            }
            .scrollContentBackground(.hidden)
            .screenBackground()
            .navigationTitle(Words.People.Share.title(taf: "\(taf.name)"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if step == 1 { Button(Words.Common.cancel) { dismiss() } } else { Button(Words.Common.back) { step = 1 } }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if step == 1 {
                        Button(Words.Common.next) { step = 2 }.disabled(!loaded || chosen == had)
                    } else {
                        Button(Words.People.Share.action) { save() }.disabled(saving)
                    }
                }
            }
            .task {
                if model.groups.isEmpty && model.people.isEmpty { await model.refreshPeople() }
                if let now = try? await model.kiff.shares(agent: agent) { had = Set(now); chosen = had }
                loaded = true
            }
        }
    }

    // Step 1 of 2: who uses it.
    @ViewBuilder private var whoUses: some View {
        Section {
            Text(Words.People.Share.step1).scaledFont(11, weight: .bold).foregroundColor(Theme.dim)
            Text(Words.People.Share.whoUses(taf: "\(taf.name)")).scaledFont(20, weight: .bold).foregroundColor(Theme.ink)
        }
        .listRowBackground(Color.clear)
        if !model.groups.isEmpty {
            Section {
                ForEach(model.groups) { group in
                    pick(.init(group: group.id)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(group.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                            Text(Self.people(of: group, model)).scaledFont(12).foregroundColor(Theme.dim).oneLineUnlessLarge()
                        }
                    }
                }
            } header: { Text(Words.People.groups) }
        }
        Section {
            ForEach(model.people) { person in
                pick(.init(subject: person.subject)) {
                    HStack(spacing: 12) {
                        PersonBadge(label: person.label, size: 28)
                        Text(person.label).scaledFont(15).foregroundColor(Theme.ink).oneLineUnlessLarge()
                    }
                }
            }
        } header: { Text(Words.People.onePerson) } footer: { Text(Words.People.Share.chooseSomeone) }
    }

    // Step 2 of 2: what sharing means for them.
    @ViewBuilder private var whatItMeans: some View {
        Section {
            Text(Words.People.Share.step2).scaledFont(11, weight: .bold).foregroundColor(Theme.dim)
            Text(chosen.isEmpty ? Words.People.Share.notShared(taf: "\(taf.name)") : Words.People.Share.whoGets(taf: "\(taf.name)")).scaledFont(20, weight: .bold).foregroundColor(Theme.ink)
        }
        .listRowBackground(Color.clear)
        Section {
            ForEach(Self.consequences(taf: taf, had: had, chosen: chosen, model: model), id: \.self) { line in
                Label(line, systemImage: "checkmark.circle").scaledFont(14).foregroundColor(Theme.ink)
            }
        }
    }

    private func pick<Label: View>(_ share: KIFFClient.Share, @ViewBuilder label: () -> Label) -> some View {
        Button {
            if chosen.contains(share) { chosen.remove(share) } else { chosen.insert(share) }
        } label: {
            HStack {
                label()
                Spacer()
                Image(systemName: chosen.contains(share) ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(chosen.contains(share) ? Theme.amber : Theme.dim)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.drivablePlain)
        .accessibilityAddTraits(chosen.contains(share) ? .isSelected : [])
    }

    private func save() {
        guard !saving else { return }
        saving = true
        let add = chosen.subtracting(had), remove = had.subtracting(chosen), agent = agent
        Task {
            let done = await model.changePeople(Words.People.Share.title(taf: "\(taf.name)")) { kiff in
                for s in add { try await kiff.share(agent: agent, with: s) }
                for s in remove { try await kiff.unshare(agent: agent, from: s) }
            }
            saving = false
            if done { dismiss() }
        }
    }

    /// "Maria (manager), Ana, Luis".
    static func people(of group: KIFFClient.Group, _ model: AppModel) -> String {
        let names = group.members.sorted(by: PeopleGroupsView.managersFirst(model)).map { m in
            let label = model.person(m.subject)?.label ?? m.subject
            return m.isManager ? Words.People.nameManager(name: "\(label)") : label
        }
        return names.isEmpty ? Words.People.noOneYet : names.joined(separator: ", ")
    }

    /// What the change means, as it holds today: each person's own chats and notes, which no
    /// one else sees in the app, and who stops using it.
    static func consequences(taf: Taf, had: Set<KIFFClient.Share>, chosen: Set<KIFFClient.Share>, model: AppModel) -> [String] {
        var lines: [String] = []
        if !chosen.isEmpty { lines.append(Words.People.ownChats) }
        for share in chosen.sorted(by: { ($0.group ?? $0.subject ?? "") < ($1.group ?? $1.subject ?? "") }) {
            if let id = share.group, let group = model.groups.first(where: { $0.id == id }) {
                lines.append(Words.People.groupUses(group: "\(group.name)", taf: "\(taf.name)", people: "\(people(of: group, model))"))
            } else if let subject = share.subject {
                lines.append(Words.People.personUses(person: "\(model.person(subject)?.label ?? subject)", taf: "\(taf.name)"))
            }
        }
        for share in had.subtracting(chosen) {
            let name = share.group.flatMap { g in model.groups.first { $0.id == g }?.name } ?? share.subject.map { model.person($0)?.label ?? $0 } ?? ""
            lines.append(Words.People.stopsUsing(who: "\(name)", taf: "\(taf.name)"))
        }
        return lines
    }
}

/// Today across the account's tafs (the signed-off Overview): what needs them, and a line per taf with who uses it, what it does now, its Card and its last result.
/// Each line opens the taf.
struct OverviewView: View {
    @EnvironmentObject private var model: AppModel
    /// Opens a taf, or a held call on its taf's page, beside the sidebar.
    let open: (HomeSelection) -> Void
    @State private var shares: [String: [KIFFClient.Share]] = [:]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(Words.Home.Overview.title).scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                        Text(Words.Home.Overview.subtitle).scaledFont(14).foregroundColor(Theme.dim)
                    }
                    Spacer()
                    // The tafs' scheduled work to come (kiff/tafs#534).
                    Button { open(.calendar) } label: {
                        Image(systemName: "calendar").scaledFont(20, weight: .semibold).foregroundColor(Theme.ink)
                            .frame(width: 44, height: 44)
                            .background(Circle().fill(Theme.raised))
                    }
                    .buttonStyle(.drivablePlain)
                    .accessibilityLabel(Words.Calendar.heading)
                    .accessibilityIdentifier("overview.calendar")
                }
                if !model.held.isEmpty { needsYou }
                tafsTable
                Text(Words.Home.Overview.eachLineOpens).scaledFont(12).foregroundColor(Theme.dim)
            }
            .padding(24)
            .frame(maxWidth: 980, alignment: .leading)
        }
        .screenBackground()
        .task {
            await model.refreshPeople()
            for taf in model.listedTafs {
                if model.manages(taf.id), let agent = model.kiffAgent(for: taf.id) { shares[agent] = try? await model.kiff.shares(agent: agent) }
                if let cloud = model.cloudTaf(for: taf.id), model.reports[cloud.id] == nil { await model.loadReport(cloud) }
            }
        }
    }

    private var needsYou: some View {
        VStack(alignment: .leading, spacing: 8) {
            Eyebrow(text: Words.NeedsYou.title, color: Theme.amber)
            ForEach(model.held) { call in
                if let taf = model.taf(call.tafID) {
                    Button { open(.held(call.id, taf: taf.id)) } label: {
                        HStack(spacing: 10) {
                            Circle().fill(Theme.amber).frame(width: 8, height: 8)
                            Text("\(taf.name) · \(call.proposed.summary)").scaledFont(14, weight: .semibold).foregroundColor(Theme.ink).oneLineUnlessLarge()
                            Spacer()
                            Text(Words.NeedsYou.answerBy(time: "\(call.expiresAt.zonedShortTime)")).scaledFont(12).foregroundColor(Theme.soft)
                        }
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Theme.raised))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.drivablePlain)
                }
            }
        }
    }

    /// One grid, so each heading sits over its column (the 870 test: the headings drifted
    /// apart from the rows, and only one showed over its column).
    private var tafsTable: some View {
        Grid(alignment: .topLeading, horizontalSpacing: 16, verticalSpacing: 12) {
            GridRow {
                ForEach([Words.Home.Overview.columnTaf, Words.Home.Overview.columnNow, Words.Home.Overview.columnCard, Words.Home.Overview.columnLastResult], id: \.self) { title in
                    Text(title).scaledFont(11, weight: .bold).foregroundColor(Theme.dim)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            ForEach(model.listedTafs) { taf in
                Divider().gridCellUnsizedAxes(.horizontal)
                row(taf)
            }
            if model.listedTafs.isEmpty {
                Text(Words.Home.noTafs).scaledFont(14).foregroundColor(Theme.dim).gridCellColumns(4)
            }
        }
    }

    private func row(_ taf: Taf) -> some View {
        GridRow {
            Button { open(.taf(taf.id)) } label: {
                HStack(spacing: 10) {
                    TafAvatar(taf: taf, size: 30)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(taf.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink).oneLineUnlessLarge()
                        let who = usedBy(taf)
                        if !who.isEmpty { Text(who).scaledFont(12).foregroundColor(Theme.dim).oneLineUnlessLarge() }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.drivablePlain)
            .accessibilityLabel([taf.name, taf.now, Self.cardLine(taf), lastResult(taf)].compactMap { $0 }.joined(separator: ", "))
            .accessibilityHint(Words.People.opensTaf)
            Group {
                Text(taf.now).scaledFont(13).foregroundColor(Theme.ink)
                Text(Self.cardLine(taf) ?? "–").scaledFont(13).foregroundColor(Theme.ink)
                Text(lastResult(taf)).scaledFont(13).foregroundColor(Theme.soft).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { open(.taf(taf.id)) }
            .accessibilityHidden(true)
        }
    }

    /// "Support · 3 people", or who else it's shared with: only for whoever manages the taf,
    /// as on its page. Someone who uses it doesn't see who else does (the 870 test).
    private func usedBy(_ taf: Taf) -> String {
        guard model.manages(taf.id), let agent = model.kiffAgent(for: taf.id), let shares = shares[agent] else { return "" }
        if shares.isEmpty { return Words.People.notShared }
        var people = Set<String>()
        var names: [String] = []
        for s in shares {
            if let g = s.group, let group = model.groups.first(where: { $0.id == g }) {
                names.append(group.name); group.members.forEach { people.insert($0.subject) }
            } else if let subject = s.subject {
                names.append(model.person(subject)?.label ?? subject); people.insert(subject)
            }
        }
        return names.joined(separator: ", ") + " · " + Words.People.count(count: people.count)
    }

    /// "300 US$ left today of 300 US$", from the Card in front, as its page says it: its money,
    /// else its calls.
    static func cardLine(_ taf: Taf) -> String? {
        guard let card = taf.shownCards.first else { return nil }
        if let hero = card.hero { return "\(hero.figure) \(hero.words)" }
        if let t = card.total {
            guard let used = t.used else { return "\(t.limit.formatted) \(t.window.label)" }
            return "\(used.formatted) of \(t.limit.formatted) \(t.window.current)"
        }
        if let c = card.calls {
            guard let used = c.used else { return Words.Card.callsWindow(count: c.limit, window: c.window.label) }
            return Words.Card.callsUsedOf(count: c.limit, used: "\(used)", window: c.window.current)
        }
        return nil
    }

    /// The first line of its last run's report, else when it last ran.
    private func lastResult(_ taf: Taf) -> String {
        guard let cloud = model.cloudTaf(for: taf.id) else { return "–" }
        if let report = model.reports[cloud.id] {
            let first = report.text.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.first { !$0.isEmpty }
            if let first { return first.replacingOccurrences(of: "**", with: "").replacingOccurrences(of: "#", with: "").trimmingCharacters(in: .whitespaces) }
            if let error = report.error { return error }
        }
        return cloud.lastRun.map { Words.People.lastRan(time: "\($0.shortTime)") } ?? Words.Overview.nothingYet
    }
}

