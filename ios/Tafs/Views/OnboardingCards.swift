import SwiftUI

/// The four cards on how tafs works (docs/onboarding.md, first-run v2): after signing in, before the first Home, and
/// again from How tafs works. Each draws the app's own components with the sample's words, so the person knows them a
/// minute later and reads them in their language and theme. Off with `Onboarding.on`.
enum OnboardingPage: Int, CaseIterable, Identifiable {
    case tafs, coach, card, credits

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .tafs: return Words.Onboarding.tafsTitle
        case .coach: return Words.Onboarding.coachTitle
        case .card: return Words.Onboarding.cardTitle
        case .credits: return Words.Onboarding.creditsTitle
        }
    }

    /// The card's lines, each its own paragraph (no language joins two sentences with a space it may not use). The Card
    /// card ends with this device's own way to approve: a Mac approves with Touch ID or on the phone, since a Mac without
    /// a Secure Enclave can't approve; an iPhone or iPad with Face ID or Touch ID.
    func lines(isMac: Bool = ThisDevice.isMac) -> [String] {
        switch self {
        case .tafs: return [Words.Onboarding.tafsLead]
        case .coach: return [Words.Onboarding.homeLead]
        case .card: return [Words.Onboarding.cardLead, isMac ? Words.Onboarding.controlLeadMac : Words.Onboarding.controlLead]
        case .credits: return [Words.Billing.creditsBuy]
        }
    }

    var next: OnboardingPage? { OnboardingPage(rawValue: rawValue + 1) }
    var previous: OnboardingPage? { OnboardingPage(rawValue: rawValue - 1) }
}

/// Where the person is in the cards: Next (or a swipe left) goes on, a swipe right goes back, the last card's button
/// ends them, and Skip ends them from any card.
struct OnboardingSteps: Equatable {
    private(set) var page: OnboardingPage
    private(set) var finished = false

    init(start: OnboardingPage = .tafs) { page = start }

    var isLast: Bool { page.next == nil }

    /// Next, or Get started on the last card.
    mutating func advance() {
        if let next = page.next { page = next } else { finished = true }
    }

    /// A swipe left: the next card, never past the last (only its button ends the cards).
    mutating func swipeForward() {
        if let next = page.next { page = next }
    }

    /// A swipe right: the card before, never before the first.
    mutating func back() {
        if let previous = page.previous { page = previous }
    }

    mutating func skip() { finished = true }
}

struct OnboardingCards: View {
    @State private var steps: OnboardingSteps
    /// After signing in: Skip and Get started go to Home. Reopened from How tafs works: they close the cards.
    let done: () -> Void

    init(start: OnboardingPage = .tafs, done: @escaping () -> Void) {
        _steps = State(initialValue: OnboardingSteps(start: start))
        self.done = done
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            HStack {
                Spacer()
                Button(Words.Onboarding.skip) { steps.skip() }
                    .scaledFont(15, weight: .semibold)
                    .foregroundColor(Theme.soft)
                    .accessibilityIdentifier("onboarding.skip")
            }

            OnboardingPicture(page: steps.page)
                .frame(maxWidth: .infinity)
                .frame(maxHeight: .infinity)
                // A picture of the app, not a control: what it says is in the title and the lead.
                .allowsHitTesting(false)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 12) {
                Text(steps.page.title)
                    .scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title)
                    .foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                ForEach(Array(steps.page.lines().enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .scaledFont(16)
                        .foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            HStack(spacing: 8) {
                ForEach(OnboardingPage.allCases) { page in
                    Circle()
                        .fill(page == steps.page ? Theme.amber : Theme.dim.opacity(0.4))
                        .frame(width: 8, height: 8)
                }
            }
            .frame(maxWidth: .infinity)
            .accessibilityHidden(true)

            Button(steps.isLast ? Words.Onboarding.getStarted : Words.Common.next) {
                move { steps.advance() }
            }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityIdentifier("onboarding.next")
        }
        .padding(.horizontal, 24)
        .padding(.top, 16)
        .padding(.bottom, 24)
        // On an iPad or a Mac window, a phone's width, centred, as the welcome reads.
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .screenBackground()
        // A swipe left goes on, a swipe right goes back, as the buttons do (the spec's phones swipe as well).
        .contentShape(Rectangle())
        .gesture(DragGesture(minimumDistance: 30).onEnded { drag in
            guard abs(drag.translation.width) > abs(drag.translation.height) else { return }
            let forward = drag.translation.width < 0
            move {
                if forward { steps.swipeForward() } else { steps.back() }
            }
        })
        .onChange(of: steps.finished) { if $0 { done() } }
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// A step to another card: with reduced motion, no slide.
    private func move(_ step: () -> Void) {
        if reduceMotion { step() } else { withAnimation(.easeInOut(duration: 0.2)) { step() } }
    }
}

/// Each card's picture, drawn by the app's own components with the sample's words.
struct OnboardingPicture: View {
    let page: OnboardingPage

    var body: some View {
        switch page {
        case .tafs: tafs
        case .coach: coach
        case .card:
            VStack(spacing: 14) {
                card
                held
            }
        case .credits: credits
        }
    }

    /// Home's taf rows: the sample's tafs, each with its avatar, name and job, as Home lists them.
    private var tafs: some View {
        VStack(spacing: 10) {
            ForEach(Array(Self.sampleTafs.enumerated()), id: \.offset) { _, taf in
                HStack(spacing: 12) {
                    TafAvatar(taf: taf)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(taf.name).scaledFont(15, weight: .semibold).foregroundColor(Theme.ink)
                        Text(taf.job).scaledFont(13).foregroundColor(Theme.soft).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .padding(14)
                .background(Theme.raised)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
        }
        .frame(maxWidth: 420)
    }

    /// A taf's Card as its Card tab draws it: the sample's "Support replies · Zendesk" Card (`sample.card.refunds_stripe`,
    /// a key older than its words), 28 replies left of 40 today, issued by you.
    private var card: some View {
        TafCardFace(card: Self.sampleCard, issuedBy: Words.Common.you, color: Theme.tafColor(0))
            .frame(maxWidth: TafCardFace.width)
    }

    /// Coach's first exchange: the sample's ask in, Coach's answer back, as Coach's chat draws them.
    private var coach: some View {
        VStack(spacing: 10) {
            HStack {
                Spacer(minLength: 40)
                Text(Words.Sample.Coach.ask)
                    .scaledFont(15).foregroundColor(Theme.onFill)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Theme.ink)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            HStack {
                VStack(alignment: .leading, spacing: 6) {
                    Eyebrow(text: Words.Sample.Coach.planLine)
                    Text(Words.Sample.Coach.connectFirst).scaledFont(15).foregroundColor(Theme.ink)
                }
                .padding(14)
                .background(Theme.raised)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                Spacer(minLength: 40)
            }
        }
        .frame(maxWidth: 420)
    }

    /// Needs you's card, short: the sample's Deploys asks, nothing is sent until you approve, and the answer.
    private var held: some View {
        Panel {
            Eyebrow(text: Words.Sample.Deploys.name)
            Text(Words.Sample.Deploys.deployApi).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
            Text(Words.NeedsYou.nothingSent).scaledFont(13).foregroundColor(Theme.dim)
            // As Needs you marks it: a driven touch is refused, though the picture takes no taps at all.
            Button(Self.approveLabel) {}.buttonStyle(PrimaryButtonStyle()).confirming()
        }
        .frame(maxWidth: 420)
    }

    /// Plan & usage's credits: the sample's Solo, its credits left this period, and its meter.
    private var credits: some View {
        Panel {
            Text(Words.Sample.Credits.balanceWords).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
            Meter(fraction: 260.0 / 500.0)
            Text(Words.Sample.Credits.note).scaledFont(13).foregroundColor(Theme.dim)
        }
        .frame(maxWidth: 420)
    }

    /// As Needs you says it on this device: with Face ID or Touch ID where it has them, plainly on a Mac.
    static var approveLabel: String {
        if ThisDevice.isMac { return Words.NeedsYou.approveOnce }
        return Words.NeedsYou.approveOnceWith(biometry: Biometry.kind ?? Words.Approval.faceIdTouchId)
    }

    /// The sample's own tafs, in the order Home shows them: the ones that never move money.
    static var sampleTafs: [Taf] {
        [TafTemplates.support, TafTemplates.parcels, TafTemplates.leads].enumerated().map { i, t in
            Taf(name: t.name, job: t.job, tools: t.tools, colorIndex: i, card: Card(rules: t.rules, issuedBy: Words.Common.you))
        }
    }

    /// The Card the screenshots show (`Screenshots.cards`): "Support replies · Zendesk" with Support replies' own rules,
    /// 12 of 40 replies used today, so its title and its rules are one Card.
    static var sampleCard: TafCard {
        TafCard(id: "onboarding-support", title: Words.Sample.Card.refundsStripe, rules: TafTemplates.support.rules,
                calls: CardTotal(limit: 40, used: 12, window: .day))
    }
}

/// How tafs works, any time (Account): the four cards again, each opening where it's tapped, and the privacy and
/// terms pages, KIFF's and tafs's.
struct HowTafsWorksView: View {
    @EnvironmentObject private var model: AppModel
    @State private var opened: OnboardingPage?

    /// The four legal pages as rows, each titled as its page is: standalone titles (`onboarding.row_*`), never the
    /// tick line's link words, which are written to sit inside its sentence ("the tafs Terms", German's dative).
    struct LegalRow: Hashable {
        let title: String
        let url: URL
    }

    static var legalRows: [LegalRow] {
        [LegalRow(title: Words.Onboarding.rowKiffPrivacy, url: Legal.kiffPrivacy),
         LegalRow(title: Words.Onboarding.rowTafsPrivacy, url: Legal.privacy),
         LegalRow(title: Words.Onboarding.rowKiffTerms, url: Legal.kiffTerms),
         LegalRow(title: Words.Onboarding.rowTafsTerms, url: Legal.terms)]
    }

    var body: some View {
        List {
            Section {
                ForEach(OnboardingPage.allCases) { page in
                    Button { opened = page } label: {
                        HStack {
                            Text(page.title).foregroundColor(Theme.ink)
                            Spacer()
                            Image(systemName: "chevron.forward").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
                        }
                    }
                    .accessibilityIdentifier("howItWorks.\(page.rawValue)")
                }
            }
            // Chrome, only where planque offers it; the emailed code, only where KIFF can send it: the app says nothing about
            // what the person can't use (as Account says nothing about Chrome until planque offers it).
            if model.chromes != nil {
                Section {
                    Text(Words.Help.chromeLead(needsYou: Words.NeedsYou.title)).scaledFont(15).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(Words.Help.chromeManage(account: Words.Settings.title, chrome: Words.Chrome.title)).scaledFont(14).foregroundColor(Theme.dim)
                        .fixedSize(horizontal: false, vertical: true)
                } header: {
                    Text(Words.Help.chromeTitle)
                }
                .accessibilityIdentifier("howItWorks.chrome")
            }
            if model.codeSignIn?.available == true {
                Section {
                    Text(Words.Help.codeLead(account: Words.Settings.title, signIn: Words.Settings.SignInCode.title)).scaledFont(15).foregroundColor(Theme.soft)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(Words.Help.codeSafe).scaledFont(14).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                } header: {
                    Text(Words.Help.codeTitle)
                }
                .accessibilityIdentifier("howItWorks.code")
            }
            Section {
                ForEach(Self.legalRows, id: \.url) { row in
                    Link(Legal.label(row.title), destination: row.url)
                }
            }
        }
        .task { await model.loadCodeSignIn(); await model.refreshChromes() }
        .navigationTitle(Words.Onboarding.howItWorks)
        .sheet(item: $opened) { page in
            OnboardingCards(start: page) { opened = nil }
        }
    }
}
