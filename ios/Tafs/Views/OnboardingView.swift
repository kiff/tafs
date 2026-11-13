import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject private var model: AppModel
    @State private var loading = false
    @State private var signingIn = false

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Mascot(size: 96)

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

            VStack(spacing: 10) {
                Button(Words.SignIn.withKiff) { signingIn = true }
                    .buttonStyle(PrimaryButtonStyle())

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

    private func point(_ lead: String, _ rest: String, _ color: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle().fill(color).frame(width: 9, height: 9).accessibilityHidden(true)
            (Text(lead).bold().foregroundColor(Theme.ink) + Text(" " + rest).foregroundColor(Theme.soft))
                .scaledFont(15)
        }
    }
}
