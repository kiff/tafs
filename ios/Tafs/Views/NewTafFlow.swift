import SwiftUI

/// Say the job → the taf proposes its tools and Card → issue the Card with Face ID.
struct NewTafFlow: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var step = 1
    @State private var job = ""
    @State private var draft = TafDraft(name: "", job: "", tools: [], rules: [])
    @State private var issuing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Eyebrow(text: "New taf · \(step) of 3")
                    switch step {
                    case 1: jobStep
                    case 2: draftStep
                    default: issueStep
                    }
                }
                .padding(20)
            }
            .screenBackground()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                if step > 1 {
                    ToolbarItem(placement: .navigationBarLeading) { Button("Back") { step -= 1 } }
                }
            }
        }
    }

    // MARK: 1. The job

    private var jobStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("What's the job?").font(Theme.display(38)).foregroundColor(Theme.ink)
            Text("Say it the way you'd brief a new hire. Include what it must never do.")
                .font(.system(size: 15)).foregroundColor(Theme.soft)
            TextEditor(text: $job)
                .font(.system(size: 17))
                .scrollContentBackground(.hidden)
                .padding(12)
                .frame(minHeight: 160)
                .background(Theme.panel)
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.line))
                .clipShape(RoundedRectangle(cornerRadius: 18))
                .accessibilityLabel("The job")
            Text("Or start from one of these").font(.system(size: 13)).foregroundColor(Theme.dim)
            ForEach(TafTemplates.all) { t in
                JobRow(template: t, selected: job == t.job) { job = t.job }
            }
            Button("Draft the taf") {
                draft = model.draft(job: job)
                step = 2
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(job.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    // MARK: 2. The draft

    private var draftStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            TextField("Name", text: $draft.name)
                .font(Theme.display(34))
                .foregroundColor(Theme.ink)
            Text("Drafted from your words. Change anything.").font(.system(size: 13)).foregroundColor(Theme.dim)

            Eyebrow(text: "Tools it needs")
            if draft.tools.isEmpty {
                Text("No tools matched this job yet. Pick a starting job to see a full draft.")
                    .font(.system(size: 14)).foregroundColor(Theme.soft)
            }
            ForEach(draft.tools, id: \.self) { tool in
                HStack {
                    Text(tool).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                    Spacer()
                    Text("through KIFF").font(.system(size: 12)).foregroundColor(Theme.dim)
                }
                .padding(14).background(Theme.panel).clipShape(RoundedRectangle(cornerRadius: 14))
            }

            Eyebrow(text: "Its Card")
            ForEach($draft.rules) { $rule in RuleEditor(rule: $rule) }

            Button("Review its Card") { step = 3 }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(draft.name.isEmpty || draft.rules.isEmpty)
        }
    }

    // MARK: 3. Issue

    private var issueStep: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Give \(draft.name) its Card").font(Theme.display(32)).foregroundColor(Theme.ink)
            CardFace(name: draft.name, rules: draft.rules, issuedBy: model.ownerName,
                     expires: Calendar.current.date(byAdding: .month, value: 3, to: Date()) ?? Date(),
                     color: Theme.tafColor(model.tafs.count))
            // What Face ID signs, in full: every action and its limits, not only the face's main one.
            CardLines(rules: draft.rules)
            VStack(alignment: .leading, spacing: 10) {
                rule("Inside the Card,", "it acts and you get a receipt.", Theme.ok)
                rule("Outside it,", "it waits for you, here and on your lock screen.", Theme.amber)
                rule("Revoked,", "no new action goes through.", Theme.no)
            }
            Button {
                issuing = true
                Task {
                    let started = await model.start(draft)
                    issuing = false
                    if started != nil { dismiss() }
                }
            } label: {
                Label("Issue with \(Biometry.name)", systemImage: Biometry.symbol)
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(issuing)
        }
    }

    private func rule(_ lead: String, _ rest: String, _ color: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle().fill(color).frame(width: 9, height: 9).accessibilityHidden(true)
            (Text(lead).bold().foregroundColor(Theme.ink) + Text(" " + rest).foregroundColor(Theme.soft))
                .font(.system(size: 14))
        }
    }
}

/// One Card line, with its amounts editable in dollars.
struct RuleEditor: View {
    @Binding var rule: CardRule

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(rule.label).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.ink)
                Spacer()
                Text(rule.never ? "Refused, never asked" : (rule.outside == .hold ? "Outside: held for you" : "Outside: refused"))
                    .font(.system(size: 12)).foregroundColor(rule.never ? Theme.no : Theme.dim)
            }
            if !rule.never {
                if rule.perAction != nil { amountField("Up to, each", $rule.perAction) }
                if rule.perWindow != nil { amountField("Up to, \(rule.window.label)", $rule.perWindow) }
                if let count = rule.countPerWindow {
                    Stepper(value: Binding(get: { count }, set: { rule.countPerWindow = $0 }), in: 1...10_000) {
                        Text("\(count) \(rule.window.label)").font(Theme.mono(14)).foregroundColor(Theme.ink)
                    }
                }
            }
        }
        .padding(14)
        .background(Theme.panel)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.line))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private func amountField(_ label: String, _ money: Binding<Money?>) -> some View {
        HStack {
            Text(label).font(.system(size: 14)).foregroundColor(Theme.soft)
            Spacer()
            TextField("0", text: Binding(
                get: { money.wrappedValue.map { String($0.minor / 100) } ?? "" },
                set: { if let dollars = Int64($0.filter(\.isNumber)) { money.wrappedValue = Money(minor: dollars * 100, currency: "USD", scale: 2) } }
            ))
            .keyboardType(.numberPad)
            .multilineTextAlignment(.trailing)
            .font(Theme.mono(15))
            .foregroundColor(Theme.ink)
            .frame(width: 110)
            .padding(.vertical, 8).padding(.horizontal, 10)
            .background(Theme.background)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .accessibilityLabel(label)
        }
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
