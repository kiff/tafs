import SwiftUI

/// The three cards on how tafs works (docs/onboarding.md): after signing in, before the first Home, and again from How
/// tafs works. Each draws the app's own components with the sample's words, so the person knows them a minute later
/// and reads them in their language. Off with `Onboarding.on`.
enum OnboardingPage: Int, CaseIterable, Identifiable {
    case tafs, card, control

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .tafs: return Words.Onboarding.tafsTitle
        case .card: return Words.Onboarding.cardTitle
        case .control: return Words.Onboarding.controlTitle
        }
    }

    /// The last card names this device's own way to approve: a Mac approves with Touch ID or on the phone, since a Mac
    /// without a Secure Enclave can't approve; an iPhone or iPad with Face ID or Touch ID.
    func lead(isMac: Bool = ThisDevice.isMac) -> String {
        switch self {
        case .tafs: return Words.Onboarding.tafsLead
        case .card: return Words.Onboarding.cardLead
        case .control: return isMac ? Words.Onboarding.controlLeadMac : Words.Onboarding.controlLead
        }
    }

    var next: OnboardingPage? { OnboardingPage(rawValue: rawValue + 1) }
}

/// Where the person is in the cards: Next goes on, the last card's button ends them, and Skip ends them from any card.
struct OnboardingSteps: Equatable {
    private(set) var page: OnboardingPage
    private(set) var finished = false

    init(start: OnboardingPage = .tafs) { page = start }

    var isLast: Bool { page.next == nil }

    /// Next, or Get started on the last card.
    mutating func advance() {
        if let next = page.next { page = next } else { finished = true }
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
                Text(steps.page.lead())
                    .scaledFont(16)
                    .foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
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
                withAnimation(.easeInOut(duration: 0.2)) { steps.advance() }
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
        .onChange(of: steps.finished) { if $0 { done() } }
    }
}

/// Each card's picture, drawn by the app's own components with the sample's words.
struct OnboardingPicture: View {
    let page: OnboardingPage

    var body: some View {
        switch page {
        case .tafs: tafs
        case .card: card
        case .control: control
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

    /// A taf's Card as its Card tab draws it: the sample's Support replies Card, 28 left of 40 today, issued by you.
    private var card: some View {
        TafCardFace(card: Self.sampleCard, issuedBy: Words.Common.you, color: Theme.tafColor(0))
            .frame(maxWidth: TafCardFace.width)
    }

    /// Needs you's card: the sample's Deploys asks, nothing is sent until you approve, and the three answers.
    private var control: some View {
        Panel {
            Eyebrow(text: Words.Sample.Deploys.name)
            Text(Words.Sample.Deploys.deployApi).scaledFont(16, weight: .semibold).foregroundColor(Theme.ink)
            Text(Words.Sample.Deploys.deployApiWhy).scaledFont(14).foregroundColor(Theme.soft)
            Text(Words.NeedsYou.nothingSent).scaledFont(13).foregroundColor(Theme.dim)
            Button(Self.approveLabel) {}.buttonStyle(PrimaryButtonStyle())
            Button(Words.Sample.Deploys.nextStep) {}.buttonStyle(OutlineButtonStyle())
            Button(Words.NeedsYou.refuse) {}.buttonStyle(OutlineButtonStyle(color: Theme.no))
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

    /// The Card the screenshots show (`Screenshots.cards`): Support replies, 12 of 40 replies used today.
    static var sampleCard: TafCard {
        TafCard(id: "onboarding-support", title: Words.Sample.Card.refundsStripe, rules: TafTemplates.support.rules,
                calls: CardTotal(limit: 40, used: 12, window: .day))
    }
}

/// How tafs works, any time (Account): the three cards again, each opening where it's tapped, and the privacy and
/// terms pages, KIFF's and tafs's.
struct HowTafsWorksView: View {
    @State private var opened: OnboardingPage?

    var body: some View {
        List {
            Section {
                ForEach(OnboardingPage.allCases) { page in
                    Button { opened = page } label: {
                        HStack {
                            Text(page.title).foregroundColor(Theme.ink)
                            Spacer()
                            Image(systemName: "chevron.right").scaledFont(13, weight: .semibold).foregroundColor(Theme.dim)
                        }
                    }
                    .accessibilityIdentifier("howItWorks.\(page.rawValue)")
                }
            }
            Section {
                Link(Legal.label(Words.Onboarding.kiffPrivacy), destination: Legal.kiffPrivacy)
                Link(Legal.label(Words.Onboarding.tafsPrivacy), destination: Legal.privacy)
                Link(Legal.label(Words.Onboarding.kiffTerms), destination: Legal.kiffTerms)
                Link(Legal.label(Words.Onboarding.tafsTerms), destination: Legal.terms)
            }
        }
        .navigationTitle(Words.Onboarding.howItWorks)
        .sheet(item: $opened) { page in
            OnboardingCards(start: page) { opened = nil }
        }
    }
}
