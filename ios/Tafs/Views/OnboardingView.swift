import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject private var model: AppModel
    @State private var loading = false
    @State private var signingIn = false
    /// The one tick for the terms (`Onboarding`): Continue waits on it.
    @State private var tick = ConsentTick()

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            // Board 1, first run (docs/ios-app.md › Illustrations): a card opening. It shrinks on a short
            // phone rather than push the buttons off the screen.
            Illustration.firstRun.frame(maxWidth: .infinity)

            VStack(alignment: .leading, spacing: 12) {
                Text(Words.SignIn.Welcome.title)
                    .scaledFont(48, weight: .bold, design: .rounded, relativeTo: .title)
                    .foregroundColor(Theme.ink)
                Text(Words.SignIn.Welcome.lead)
                    .scaledFont(16)
                    .foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(alignment: .leading, spacing: 10) {
                point(Words.SignIn.Welcome.insideLead, Words.SignIn.Welcome.insideRest, Theme.ok)
                point(Words.SignIn.Welcome.outsideLead, Words.SignIn.Welcome.outsideRest, Theme.amber)
                point(Words.SignIn.Welcome.revokedLead, Words.SignIn.Welcome.revokedRest, Theme.no)
            }

            Spacer()

            if Onboarding.on { consent }

            VStack(spacing: 10) {
                if Onboarding.on {
                    Button(Words.Common.continue) { signingIn = true }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(!tick.canContinue)
                        .accessibilityIdentifier("onboarding.continue")
                } else {
                    Button(Words.SignIn.withKiff) { signingIn = true }
                        .buttonStyle(PrimaryButtonStyle())
                }

                Button {
                    loading = true
                    Task {
                        await model.startSample()
                        loading = false
                    }
                } label: {
                    if loading { ProgressView().tint(Theme.ink) } else { Text(Words.Sample.tryIt) }
                }
                .buttonStyle(OutlineButtonStyle())
                .disabled(loading)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 48)
        .padding(.bottom, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .screenBackground()
        // Sign-in never waits on Face ID (docs/approvals.md): approvals are set up at Coach's Start.
        .sheet(isPresented: $signingIn) { SignInView().environmentObject(model) }
    }

    /// One tick for the terms, with both Terms linked in it; privacy as links below it, never ticked; and the age line.
    /// The box is its own control, so tapping a link opens its page and never ticks the box.
    private var consent: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Button { tick.toggle() } label: {
                    Image(systemName: tick.agreed ? "checkmark.square.fill" : "square")
                        .scaledFont(20)
                        .foregroundColor(tick.agreed ? Theme.amber : Theme.dim)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Words.Onboarding.consentTick(kiffTerms: Words.Onboarding.kiffTerms,
                                                                 tafsTerms: Words.Onboarding.tafsTerms))
                .accessibilityAddTraits(tick.agreed ? .isSelected : [])
                .accessibilityIdentifier("onboarding.tick")
                Text(Legal.linked(Words.Onboarding.consentTick(kiffTerms: "\u{1}", tafsTerms: "\u{2}"),
                                  [(Words.Onboarding.kiffTerms, Legal.kiffTerms), (Words.Onboarding.tafsTerms, Legal.terms)]))
                    .scaledFont(14).foregroundColor(Theme.ink).tint(Theme.amber)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(Legal.linked(Words.Onboarding.privacyLinks(kiffPrivacy: "\u{1}", tafsPrivacy: "\u{2}"),
                              [(Words.Onboarding.kiffPrivacy, Legal.kiffPrivacy), (Words.Onboarding.tafsPrivacy, Legal.privacy)]))
                .scaledFont(13).foregroundColor(Theme.soft).tint(Theme.amber)
                .fixedSize(horizontal: false, vertical: true)
            Text(Words.Onboarding.age)
                .scaledFont(12).foregroundColor(Theme.dim)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func point(_ lead: String, _ rest: String, _ color: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle().fill(color).frame(width: 9, height: 9).accessibilityHidden(true)
            (Text(lead).bold().foregroundColor(Theme.ink) + Text(" " + rest).foregroundColor(Theme.soft))
                .scaledFont(15)
        }
    }
}
