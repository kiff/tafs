import SwiftUI

// The iPhone's and iPad's: the Mac app reads Cards and answers holds, and changes no one's
// access.
#if os(iOS)

/// People and groups, in Settings: the account's groups, each with its people
/// and the tafs it uses. A manager sees the group's work on those tafs; its members use
/// them. KIFF keeps all of it; the app shows what KIFF says, and each change is the
/// person's act behind Face ID.
struct PeopleGroupsView: View {
    static let title = "People & groups"
    @EnvironmentObject private var model: AppModel
    @State private var naming: GroupNaming?
    @State private var adding: KIFFClient.Group?
    @State private var deleting: KIFFClient.Group?
    @State private var loaded = false

    var body: some View {
        List {
            if let error = model.peopleError {
                Section { Text(error).scaledFont(14).foregroundColor(Theme.no) }
            }
            if let account = model.kiffAccount {
                Section {
                    Text("\(account.name) · \(model.people.count) \(model.people.count == 1 ? "person" : "people")")
                        .scaledFont(14).foregroundColor(Theme.soft)
                }
            }
            ForEach(model.groups) { group in
                Section {
                    ForEach(group.members.sorted(by: Self.managersFirst(model)), id: \.subject) { member in
                        memberRow(member, in: group)
                    }
                    if model.managesGroup(group) {
                        Button { adding = group } label: {
                            Label("Add someone to \(group.name)", systemImage: "plus").scaledFont(15)
                        }
                    }
                } header: {
                    groupHeader(group)
                }
            }
            if loaded && model.groups.isEmpty && model.peopleError == nil {
                Text("No groups yet.").scaledFont(14).foregroundColor(Theme.dim)
            }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(Self.title)
        .toolbar {
            if model.canManagePeople {
                ToolbarItem(placement: .primaryAction) {
                    // "+" alone didn't say it makes a group, and iPadOS draws a toolbar Label as its
                    // icon only (the 793 retest): the button is words.
                    Button { naming = GroupNaming(group: nil) } label: { Text("+ Group") }
                        .accessibilityLabel("New group")
                }
            }
        }
        .refreshable { await model.refreshPeople() }
        // Read again while it's open, so someone who joins shows without leaving the screen.
        .task {
            while !Task.isCancelled {
                await model.refreshPeople(); loaded = true
                try? await Task.sleep(nanoseconds: 20_000_000_000)
            }
        }
        .sheet(item: $naming) { n in GroupNameSheet(naming: n).environmentObject(model) }
        .sheet(item: $adding) { g in AddToGroupSheet(group: g).environmentObject(model) }
        .confirmationDialog("Delete \(deleting?.name ?? "this group")?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible, presenting: deleting) { group in
            Button("Delete \(group.name)", role: .destructive) {
                Task { await model.changePeople("Delete the group \(group.name)") { try await $0.deleteGroup(group.id) } }
            }
        } message: { group in
            Text("Its people lose the tafs they used through it, unless another group or a share of their own gives them those tafs. Their chats stay theirs.")
        }
    }

    /// A group's name, the tafs it uses, and its actions for whoever may take them.
    private func groupHeader(_ group: KIFFClient.Group) -> some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(group.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink).textCase(nil)
                Text(Self.uses(group, model)).scaledFont(12).foregroundColor(Theme.dim).textCase(nil)
            }
            Spacer()
            if model.canManagePeople && group.everyone != true {
                Menu {
                    Button("Rename") { naming = GroupNaming(group: group) }
                    Button("Delete", role: .destructive) { deleting = group }
                } label: {
                    Image(systemName: "ellipsis.circle").foregroundColor(Theme.soft)
                }
                .accessibilityLabel("\(group.name) actions")
            }
        }
    }

    private func memberRow(_ member: KIFFClient.GroupMember, in group: KIFFClient.Group) -> some View {
        let person = model.person(member.subject)
        return HStack(spacing: 12) {
            PersonBadge(label: person?.label ?? member.subject)
            VStack(alignment: .leading, spacing: 2) {
                Text(person?.label ?? member.subject).scaledFont(15).foregroundColor(Theme.ink).oneLineUnlessLarge()
                Text(member.isManager ? "Manager" : "Member").scaledFont(12).foregroundColor(member.isManager ? Theme.amber : Theme.dim)
            }
            Spacer()
        }
        .accessibilityElement(children: .combine)
        .swipeActions {
            if mayRemove(member, from: group) {
                Button("Remove", role: .destructive) {
                    Task { await model.changePeople("Remove \(person?.label ?? member.subject) from \(group.name)") { try await $0.removeGroupMember(group.id, subject: member.subject) } }
                }
            }
        }
        .contextMenu {
            // Naming or changing a manager is for whoever manages people (KIFF's
            // members.manage), not for the group's manager.
            if model.canManagePeople {
                let role = member.isManager ? "member" : "manager"
                Button(member.isManager ? "Make a member" : "Make the manager") {
                    Task { await model.changePeople("Make \(person?.label ?? member.subject) a \(role) of \(group.name)") { try await $0.setGroupMember(group.id, subject: member.subject, role: role) } }
                }
            }
            if mayRemove(member, from: group) {
                Button("Remove from \(group.name)", role: .destructive) {
                    Task { await model.changePeople("Remove \(person?.label ?? member.subject) from \(group.name)") { try await $0.removeGroupMember(group.id, subject: member.subject) } }
                }
            }
        }
    }

    /// A group's manager removes its members; removing a manager is for whoever manages
    /// people, as KIFF allows.
    private func mayRemove(_ member: KIFFClient.GroupMember, from group: KIFFClient.Group) -> Bool {
        model.canManagePeople || (model.managesGroup(group) && !member.isManager)
    }

    /// "Uses Refunds and Invoices", or that it uses none yet.
    static func uses(_ group: KIFFClient.Group, _ model: AppModel) -> String {
        let names = group.agents.compactMap { model.tafName(agent: $0) }
        if names.isEmpty { return group.everyone == true ? "Everyone on the account · uses no taf" : "Uses no taf yet" }
        return "Uses " + ListFormatter.localizedString(byJoining: names.sorted())
    }

    static func managersFirst(_ model: AppModel) -> (KIFFClient.GroupMember, KIFFClient.GroupMember) -> Bool {
        { a, b in
            if a.isManager != b.isManager { return a.isManager }
            return (model.person(a.subject)?.label ?? a.subject).lowercased() < (model.person(b.subject)?.label ?? b.subject).lowercased()
        }
    }
}

/// A new group's name, or a group's new one.
struct GroupNaming: Identifiable {
    var group: KIFFClient.Group?
    var id: String { group?.id ?? "new" }
}

struct GroupNameSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let naming: GroupNaming
    @State private var name = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("Support, Finance…", text: $name)
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                    .onSubmit { save() }
                Text(naming.group == nil
                     ? "Add its people next, then share a taf with it from the taf's page."
                     : "Only the name changes: its people and tafs stay.")
                    .scaledFont(13).foregroundColor(Theme.dim)
            }
            .scrollContentBackground(.hidden)
            .screenBackground()
            .navigationTitle(naming.group == nil ? "New group" : "Rename \(naming.group?.name ?? "")")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(naming.group == nil ? "Create" : "Save") { save() }
                        .disabled(trimmed.isEmpty || saving)
                }
            }
            .onAppear { name = naming.group?.name ?? "" }
        }
    }

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func save() {
        guard !trimmed.isEmpty, !saving else { return }
        saving = true
        let name = trimmed
        Task {
            let done: Bool
            if let group = naming.group {
                done = await model.changePeople("Rename \(group.name) to \(name)") { try await $0.renameGroup(group.id, to: name) }
            } else {
                done = await model.changePeople("Create the group \(name)") { try await $0.createGroup(name: name) }
            }
            saving = false
            if done { dismiss() }
        }
    }
}

/// Adds one of the account's people to a group, as a member or its manager.
struct AddToGroupSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let group: KIFFClient.Group
    @State private var asManager = false

    var body: some View {
        NavigationStack {
            List {
                // Naming a manager is for whoever manages people (KIFF's members.manage).
                if model.canManagePeople {
                    Section {
                        Toggle("As the group's manager", isOn: $asManager)
                    } footer: {
                        Text("A manager adds and removes the group's members. Managing doesn't let them change a taf's Cards.")
                    }
                }
                Section {
                    ForEach(outside) { person in
                        Button {
                            Task {
                                let role = asManager && model.canManagePeople ? "manager" : "member"
                                if await model.changePeople("Add \(person.label) to \(group.name)", { try await $0.setGroupMember(group.id, subject: person.subject, role: role) }) {
                                    dismiss()
                                }
                            }
                        } label: {
                            HStack(spacing: 12) {
                                PersonBadge(label: person.label)
                                Text(person.label).scaledFont(15).foregroundColor(Theme.ink).oneLineUnlessLarge()
                            }
                        }
                    }
                    if outside.isEmpty {
                        Text("Everyone on the account is in \(group.name). Invite people from the KIFF dashboard.")
                            .scaledFont(14).foregroundColor(Theme.dim)
                    }
                } header: { Text("The account's people") }
                if let error = model.peopleError {
                    Section { Text(error).scaledFont(14).foregroundColor(Theme.no) }
                }
            }
            .scrollContentBackground(.hidden)
            .screenBackground()
            .navigationTitle("Add to \(group.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }

    /// The account's people not in the group yet.
    private var outside: [KIFFClient.Person] {
        let inside = Set(group.members.map(\.subject))
        return model.people.filter { !inside.contains($0.subject) }
    }
}

/// A person's initial in a circle: KIFF names people by email, so it's the email's first
/// letter.
struct PersonBadge: View {
    let label: String
    var size: CGFloat = 32

    var body: some View {
        Text(String(label.prefix(1)).uppercased())
            .scaledFont(14, weight: .semibold)
            .foregroundColor(Theme.ink)
            .frame(width: size, height: size)
            .background(Circle().fill(Theme.raised))
            .accessibilityHidden(true)
    }
}
#endif
