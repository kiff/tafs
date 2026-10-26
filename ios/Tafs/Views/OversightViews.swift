import SwiftUI

// The iPhone's and iPad's: the Mac app reads Cards and answers holds.
#if os(iOS)

/// The notice on a taf's chat wherever someone else can read it: an account admin can read
/// any member's work, and a manager their group's. Tapping it opens who read their work.
struct ReadNotice: View {
    @EnvironmentObject private var model: AppModel
    let tafID: UUID
    @State private var showing = false

    var body: some View {
        if model.mode == .kiff, let agent = model.kiffAgent(for: tafID),
           let text = Self.text(access: model.access[agent], agent: agent, groups: model.groups, people: model.people.count, me: model.me?.subject) {
            Button { showing = true } label: {
                HStack(spacing: 8) {
                    Image(systemName: "eye").scaledFont(12)
                    Text(text).scaledFont(12).multilineTextAlignment(.leading)
                }
                .foregroundColor(Theme.dim)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Shows who read your work")
            .sheet(isPresented: $showing) {
                NavigationStack {
                    WhoReadMyWorkView()
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showing = false } } }
                }
                .environmentObject(model)
            }
        }
    }

    /// What the notice says, or nil where no one else can read the chat: an account of one
    /// person, or a taf this person doesn't use. "Your manager" only when a group of theirs
    /// the taf is shared with has a manager other than them; account admins always can.
    static func text(access: KIFFClient.Access?, agent: String, groups: [KIFFClient.Group], people: Int, me: String?) -> String? {
        guard people > 1, access?.use == true else { return nil }
        let managed = groups.contains { g in
            g.agents.contains(agent) && g.members.contains { $0.subject == me }
                && g.members.contains { $0.isManager && $0.subject != me }
        }
        return managed
            ? "Your manager and account admins can read this chat. You can see each time they do."
            : "Account admins can read this chat. You can see each time they do."
    }
}

/// Who read this person's work, when and why, as KIFF recorded each read.
struct WhoReadMyWorkView: View {
    static let title = "Who read my work"
    @EnvironmentObject private var model: AppModel
    @State private var reads: [KIFFClient.Read]?
    @State private var error: String?

    var body: some View {
        List {
            if let error {
                Text(error).scaledFont(14).foregroundColor(Theme.no)
            } else if let reads, reads.isEmpty {
                Text("No one has read your work.").scaledFont(14).foregroundColor(Theme.dim)
            }
            ForEach(reads ?? []) { read in
                VStack(alignment: .leading, spacing: 4) {
                    Text(Self.line(read, model)).scaledFont(15).foregroundColor(Theme.ink)
                    Text("\(read.purposeWords) · \(read.at.zonedShortTime)").scaledFont(12).foregroundColor(Theme.soft)
                    if let note = read.note, !note.isEmpty {
                        Text("“\(note)”").scaledFont(13).foregroundColor(Theme.dim)
                    }
                }
                .padding(.vertical, 2)
                .accessibilityElement(children: .combine)
            }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(Self.title)
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        do {
            if model.people.isEmpty { await model.refreshPeople() }
            reads = try await model.kiff.myReads()
            error = nil
        } catch {
            self.error = "Who read your work couldn't be read: \(error.localizedDescription)"
        }
    }

    /// "ada@acme.com opened one of your chats with Refunds", or read their list.
    static func line(_ read: KIFFClient.Read, _ model: AppModel) -> String {
        let who = model.person(read.reader)?.label ?? read.reader
        let taf = model.tafName(agent: read.agent) ?? read.agent
        return read.session == nil || read.session?.isEmpty == true
            ? "\(who) saw the list of your chats with \(taf)"
            : "\(who) opened one of your chats with \(taf)"
    }
}

/// The people whose work with a taf this person oversees: an admin, everyone who uses it;
/// a manager, their own group's. KIFF decides each read; the app only lists who to ask about.
struct PeoplesWorkView: View {
    @EnvironmentObject private var model: AppModel
    let taf: Taf
    let agent: String
    @State private var shares: [KIFFClient.Share]?
    @State private var asking: KIFFClient.Person?
    @State private var opened: OpenedWork?

    var body: some View {
        List {
            Section {
                ForEach(people) { person in
                    Button { asking = person } label: {
                        HStack(spacing: 12) {
                            PersonBadge(label: person.label)
                            Text(person.label).scaledFont(15).foregroundColor(Theme.ink).oneLineUnlessLarge()
                            Spacer()
                            Image(systemName: "chevron.right").scaledFont(12).foregroundColor(Theme.dim)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                if shares != nil && people.isEmpty {
                    Text("No one else's work with \(taf.name) is yours to read.").scaledFont(14).foregroundColor(Theme.dim)
                }
            } footer: {
                Text("Before you read, you say why. KIFF records each read, and the person sees it.")
            }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle("\(taf.name): people's work")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if model.groups.isEmpty && model.people.isEmpty { await model.refreshPeople() }
            shares = (try? await model.kiff.shares(agent: agent)) ?? []
        }
        .sheet(item: $asking) { person in
            ReadPurposeSheet(personLabel: person.label, what: "the list of their chats with \(taf.name)") { purpose, note in
                let grant = try await model.kiff.requestRead(agent: agent, owner: person.subject, purpose: purpose, note: note)
                let chats = try await model.cloud.peopleChats(cloudID, person: person.subject, grant: grant.grant)
                opened = OpenedWork(person: person, purpose: purpose, note: note, chats: chats)
            }
        }
        .navigationDestination(isPresented: Binding(get: { opened != nil }, set: { if !$0 { opened = nil } })) {
            if let work = opened { PersonChatsView(taf: taf, agent: agent, cloudID: cloudID, work: work) }
        }
    }

    private var cloudID: String { model.cloudTaf(for: taf.id)?.id ?? "" }

    /// Whose work this person may ask KIFF to read: an admin, everyone the taf is shared
    /// with; a manager, the members of their groups the taf is shared with. Never their own.
    private var people: [KIFFClient.Person] {
        guard let shares else { return [] }
        let me = model.me?.subject
        let admin = model.canManagePeople
        var subjects = Set<String>()
        for share in shares {
            if let subject = share.subject, admin { subjects.insert(subject) }
            if let id = share.group, let group = model.groups.first(where: { $0.id == id }),
               admin || group.members.contains(where: { $0.subject == me && $0.isManager }) {
                group.members.forEach { subjects.insert($0.subject) }
            }
        }
        if let me { subjects.remove(me) }
        return model.people.filter { subjects.contains($0.subject) }
    }
}

/// One person's work, opened for a stated purpose.
struct OpenedWork: Hashable, Identifiable {
    let person: KIFFClient.Person
    let purpose: KIFFClient.ReadPurpose
    let note: String?
    let chats: [TafsCloud.Chat]
    var id: String { person.subject }

    static func == (a: OpenedWork, b: OpenedWork) -> Bool { a.person == b.person && a.chats == b.chats }
    func hash(into h: inout Hasher) { h.combine(person.subject) }
}

/// Why this person reads someone's work, asked before KIFF is: one of three purposes, and a
/// note if they like. `read` asks KIFF and fetches; its error shows here.
struct ReadPurposeSheet: View {
    @Environment(\.dismiss) private var dismiss
    let personLabel: String
    let what: String
    let read: (KIFFClient.ReadPurpose, String?) async throws -> Void
    @State private var purpose: KIFFClient.ReadPurpose?
    @State private var note = ""
    @State private var reading = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(KIFFClient.ReadPurpose.allCases) { p in
                        Button {
                            purpose = p
                        } label: {
                            HStack {
                                Text(p.words).scaledFont(15).foregroundColor(Theme.ink)
                                Spacer()
                                if purpose == p { Image(systemName: "checkmark").foregroundColor(Theme.amber) }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(purpose == p ? .isSelected : [])
                    }
                } header: {
                    Text("Why are you reading \(personLabel)'s work?")
                } footer: {
                    Text("KIFF records that you read \(what), and why. \(personLabel) sees it.")
                }
                Section("A note (optional)") {
                    TextField("What you're looking for", text: $note, axis: .vertical)
                        .lineLimit(1...3)
                        .onChange(of: note) { note = String($0.prefix(280)) }
                }
                if let error {
                    Section { Text(error).scaledFont(14).foregroundColor(Theme.no) }
                }
            }
            .scrollContentBackground(.hidden)
            .screenBackground()
            .navigationTitle("Read \(personLabel)'s work")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Read") { go() }.disabled(purpose == nil || reading)
                }
            }
        }
    }

    private func go() {
        guard let purpose, !reading else { return }
        reading = true
        error = nil
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            do {
                try await read(purpose, trimmed.isEmpty ? nil : trimmed)
                dismiss()
            } catch {
                self.error = Self.refusal(error)
            }
            reading = false
        }
    }

    static func refusal(_ error: Error) -> String {
        let text = error.localizedDescription
        return text.contains("no_access") || text.contains("403")
            ? "KIFF says this work isn't yours to read: an admin reads anyone's, a manager only their group's."
            : "It couldn't be read: \(text)"
    }
}

/// One person's chats with a taf, by their times: opening one asks KIFF again, for that
/// chat, with the same purpose. The list's own grant may lapse (10 minutes) while it's
/// open; nothing here needs it again, since each chat opened asks KIFF for its own.
struct PersonChatsView: View {
    @EnvironmentObject private var model: AppModel
    let taf: Taf
    let agent: String
    let cloudID: String
    let work: OpenedWork
    @State private var reading: String?
    @State private var opened: OpenedChat?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                RecordedBanner(personLabel: work.person.label)
            }
            .listRowBackground(Color.clear)
            if let error {
                Section { Text(error).scaledFont(14).foregroundColor(Theme.no) }
            }
            Section {
                ForEach(work.chats) { chat in
                    Button { open(chat) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Chat started \(chat.started.zonedShortTime)").scaledFont(15).foregroundColor(Theme.ink).oneLineUnlessLarge()
                                Text("Last message \(chat.updated.zonedShortTime)").scaledFont(12).foregroundColor(Theme.dim)
                            }
                            Spacer()
                            if reading == chat.session { ProgressView() }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(reading != nil)
                }
                if work.chats.isEmpty {
                    Text("\(work.person.label) has no chats with \(taf.name).").scaledFont(14).foregroundColor(Theme.dim)
                }
            } header: { Text("\(work.person.label)'s chats") }
        }
        .scrollContentBackground(.hidden)
        .screenBackground()
        .navigationTitle(work.person.label)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: Binding(get: { opened != nil }, set: { if !$0 { opened = nil } })) {
            if let chat = opened { PersonChatView(taf: taf, personLabel: work.person.label, chat: chat) }
        }
    }

    private func open(_ chat: TafsCloud.Chat) {
        reading = chat.session
        error = nil
        Task {
            do {
                let grant = try await model.kiff.requestRead(agent: agent, owner: work.person.subject, session: chat.session,
                                                             purpose: work.purpose, note: work.note)
                let messages = try await model.cloud.personConversation(cloudID, person: work.person.subject, session: chat.session, grant: grant.grant)
                opened = OpenedChat(chat: chat, messages: messages)
            } catch {
                self.error = ReadPurposeSheet.refusal(error)
            }
            reading = nil
        }
    }
}

struct OpenedChat: Hashable, Identifiable {
    let chat: TafsCloud.Chat
    let messages: [TafsCloud.ChatMessage]
    var id: String { chat.session }
    func hash(into h: inout Hasher) { h.combine(chat.session) }
}

/// Another person's chat, read only: no composer, and the banner says the read is recorded.
struct PersonChatView: View {
    let taf: Taf
    let personLabel: String
    let chat: OpenedChat

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                RecordedBanner(personLabel: personLabel)
                ForEach(chat.messages) { message in
                    ChatBubble(message: message, taf: taf)
                }
            }
            .padding(16)
        }
        .screenBackground()
        .navigationTitle(personLabel)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// "You're viewing Luis's chat. Your view is recorded, and Luis can see that you looked."
struct RecordedBanner: View {
    let personLabel: String

    var body: some View {
        Label("You're viewing \(personLabel)'s work. Your view is recorded, and \(personLabel) can see that you looked. You can't write in it.",
              systemImage: "eye")
            .scaledFont(13)
            .foregroundColor(Theme.ink)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(Theme.raised))
    }
}

#endif
