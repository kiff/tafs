import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject private var model: AppModel
    @State private var loading = false
    @State private var signingIn = false
    /// The one tick for the terms (`Onboarding`): Continue waits on it.
    @State private var tick = ConsentTick()
    /// KIFF's sign-in is open in the system's sheet, or its session is being made.
    @State private var signingInApp = false
    /// Why the last sign-in didn't work; nil when the sheet was closed, or nothing went wrong.
    @State private var problem: String?
    /// KIFF said the terms changed since the tick: the box is unticked, and this says why.
    @State private var termsChanged = false
    /// iPad and Mac: "Sign in with your phone", the pairing QR, beside KIFF's sheet.
    @State private var pairing = false
    /// The terms version in force, read as the tick is shown: the one sent with the sign-in is the one the person saw.
    @State private var termsVersion: String?

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
                    if let problem {
                        Text(problem).scaledFont(14).foregroundColor(Theme.no)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Button { signInInApp() } label: {
                        if signingInApp { ProgressView().tint(Theme.ink) } else { Text(Words.Common.continue) }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!tick.canContinue || signingInApp)
                    .accessibilityIdentifier("onboarding.continue")
                    if SignInView.offersPhone() {
                        Button(Words.SignIn.withPhone) { pairing = true }
                            .buttonStyle(OutlineButtonStyle())
                            .disabled(!tick.canContinue || signingInApp)
                            .accessibilityIdentifier("onboarding.withPhone")
                    }
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
        .task { if Onboarding.on { termsVersion = try? await model.appTermsVersion() } }
        .sheet(isPresented: $pairing) {
            NavigationStack {
                PairSignInView {
                    pairing = false
                    model.justConnected = true
                }
                .environmentObject(model)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(Words.Common.cancel) { pairing = false } } }
            }
        }
    }

    /// Continue: KIFF's sign-in in the system's sheet (`AppSignIn`), then the celebration and the three cards.
    /// Closing the sheet leaves the welcome as it was; terms that changed since the tick untick the box.
    private func signInInApp() {
        problem = nil
        termsChanged = false
        signingInApp = true
        Task {
            defer { signingInApp = false }
            do {
                try await model.signInInApp(termsVersion: termsVersion, sheet: VendorSignIn(callbackScheme: AppSignIn.callbackScheme))
                model.justConnected = true
            } catch AppSignIn.Failure.closed {
            } catch KIFFClient.KIFFError.termsOutdated {
                tick = ConsentTick()
                termsChanged = true
                // The new version, for the new tick.
                termsVersion = try? await model.appTermsVersion()
            } catch is CancellationError {
            } catch {
                problem = error.localizedDescription
            }
        }
    }

    /// One tick for the terms, with both Terms linked in it; privacy as links below it, never ticked; and the age line.
    /// The box is its own control, so tapping a link opens its page and never ticks the box.
    private var consent: some View {
        VStack(alignment: .leading, spacing: 10) {
            if termsChanged {
                Text(Words.Onboarding.termsChanged).scaledFont(14, weight: .semibold).foregroundColor(Theme.amberText)
                    .fixedSize(horizontal: false, vertical: true)
            }
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
