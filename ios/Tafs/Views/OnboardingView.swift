import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject private var model: AppModel
    @State private var loading = false
    @State private var signingIn = false

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("t")
                .font(Theme.display(30))
                .foregroundColor(Theme.amberInk)
                .frame(width: 56, height: 56)
                .background(Theme.amber)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 12) {
                Text("They taf.\nYou kiff.")
                    .font(Theme.display(48))
                    .foregroundColor(Theme.ink)
                Text("Hand a job to a taf. It works inside a Card you set, and asks you when it needs more.")
                    .font(.system(size: 16))
                    .foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(alignment: .leading, spacing: 10) {
                point("Inside the Card", "it acts, and every action has a receipt.", Theme.ok)
                point("Outside it", "it waits for you, right here.", Theme.amber)
                point("Revoked", "it stops at once.", Theme.no)
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
        .sheet(isPresented: $signingIn) { SignInView() }
    }

    private func point(_ lead: String, _ rest: String, _ color: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle().fill(color).frame(width: 9, height: 9).accessibilityHidden(true)
            (Text(lead).bold().foregroundColor(Theme.ink) + Text(" " + rest).foregroundColor(Theme.soft))
                .font(.system(size: 15))
        }
    }
}
