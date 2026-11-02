import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject private var model: AppModel
    @State private var loading = false
    @State private var signingIn = false

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Mascot(size: 96)

            VStack(alignment: .leading, spacing: 12) {
                Text("They taf.\nYou kiff.")
                    .scaledFont(48, weight: .bold, design: .rounded, relativeTo: .title)
                    .foregroundColor(Theme.ink)
                Text("Hand a job to a taf. It works inside a Card you set, and asks you when it needs more.")
                    .scaledFont(16)
                    .foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(alignment: .leading, spacing: 10) {
                point("Inside the Card", "it acts, and every action has a receipt.", Theme.ok)
                point("Outside it", "it waits for you, right here.", Theme.amber)
                point("Revoked", "no new action goes through.", Theme.no)
            }

            Spacer()

            VStack(spacing: 10) {
                Button("Sign in with KIFF") { signingIn = true }
                    .buttonStyle(PrimaryButtonStyle())

                Button {
                    loading = true
                    Task {
                        await model.startSample()
                        loading = false
                    }
                } label: {
                    if loading { ProgressView().tint(Theme.ink) } else { Text("Try it with sample data") }
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
        .sheet(isPresented: $signingIn) { SignInGate().environmentObject(model) }
    }

    private func point(_ lead: String, _ rest: String, _ color: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle().fill(color).frame(width: 9, height: 9).accessibilityHidden(true)
            (Text(lead).bold().foregroundColor(Theme.ink) + Text(" " + rest).foregroundColor(Theme.soft))
                .scaledFont(15)
        }
    }
}

/// Sign-in, after "Set up approvals on this device" when this device can't approve yet: set
/// up there, or go on without approvals (kiff/tafs#423). A device that is ready goes straight
/// to sign-in.
struct SignInGate: View {
    @State private var goOn = false
    /// Read once, when sign-in opens: a device that becomes ready while the screen shows
    /// keeps the screen, which says it is ready, and Continue goes on.
    @State private var neededSetup = !ApprovalReadiness.current().isReady

    var body: some View {
        if goOn || !neededSetup {
            SignInView()
        } else {
            ReadinessReader { readiness, checkAgain in
                ApprovalSetupView(readiness: readiness, checkAgain: checkAgain) { goOn = true }
            }
        }
    }
}
