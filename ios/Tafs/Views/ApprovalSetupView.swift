import SwiftUI
import UIKit

/// This device's approval readiness, the model's one value, read again when the view appears
/// and whenever the app comes back to it: the person may have just set up Face ID in Settings
/// (kiff/tafs#423). Check again reads it again for every view at once.
struct ReadinessReader<Content: View>: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @ViewBuilder let content: (ApprovalReadiness, _ checkAgain: @escaping () -> Void) -> Content

    var body: some View {
        content(model.approvalReadiness) { model.checkApprovalReadiness() }
            .onAppear { model.checkApprovalReadiness() }
            .onChange(of: scenePhase) { phase in
                if phase == .active { model.checkApprovalReadiness() }
            }
    }
}

/// Opens tafs' own settings: iOS opens no other page for an app, so the words give the path.
@MainActor func openTafsSettings() {
    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
}

/// "Set up approvals on this device": before sign-in when the device can't approve yet, and
/// from Coach's start before the first taf. Signing in, reviewing, refusing and halting all
/// work without approvals, so the person may go on without.
struct ApprovalSetupView: View {
    let readiness: ApprovalReadiness
    let checkAgain: () -> Void
    /// Go on without approvals; nil where there's nothing to go on to.
    var continueWithout: (() -> Void)?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Image(systemName: Biometry.symbol)
                    .font(.system(size: 40)).foregroundColor(Theme.amber)
                    .accessibilityHidden(true)
                Text(ApprovalReadiness.title)
                    .scaledFont(28, weight: .bold, design: .rounded, relativeTo: .title)
                    .foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text("A taf asks before it does anything outside its Card, and Coach asks before it connects a tool or issues a Card. You approve on this device, and that needs Face ID or Touch ID.")
                    .scaledFont(16).foregroundColor(Theme.soft)
                    .fixedSize(horizontal: false, vertical: true)
                Text(readiness.words(device: ThisDevice.kind, kind: Biometry.kind))
                    .scaledFont(16, weight: .semibold).foregroundColor(readiness.isReady ? Theme.ok : Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("approvalReadiness")
                if readiness != .unsupported {
                    Text(ApprovalReadiness.newBiometricsNote)
                        .scaledFont(13, relativeTo: .footnote).foregroundColor(Theme.dim)
                        .fixedSize(horizontal: false, vertical: true)
                }
                VStack(spacing: 10) {
                    if readiness.opensSettings {
                        Button("Open Settings") { openTafsSettings() }
                            .buttonStyle(PrimaryButtonStyle())
                    }
                    if !readiness.isReady && readiness != .unsupported {
                        Button("Check again", action: checkAgain)
                            .buttonStyle(OutlineButtonStyle())
                    }
                    if let continueWithout {
                        if readiness.isReady {
                            Button("Continue", action: continueWithout)
                                .buttonStyle(PrimaryButtonStyle())
                        } else {
                            Button("Continue without approvals", action: continueWithout)
                                .buttonStyle(OutlineButtonStyle())
                            Text("Without approvals you can still sign in, review, refuse and halt.")
                                .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
                .padding(.top, 8)
            }
            .padding(24)
            .frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .screenBackground()
    }
}

/// An approval's button, or, while this device can't approve, the state's words and what to
/// do in its place: the person learns why before the prompt, not from a failure after it.
/// In sample mode nothing is signed for real, so the button always shows.
struct ApprovalGate<Approve: View>: View {
    @EnvironmentObject private var model: AppModel
    @ViewBuilder let button: () -> Approve

    var body: some View {
        if model.mode != .kiff {
            button()
        } else {
            ReadinessReader { readiness, checkAgain in
                if readiness.isReady {
                    button()
                } else {
                    ApprovalNotReady(readiness: readiness, checkAgain: checkAgain)
                }
            }
        }
    }
}

/// The same words, for an approval whose button stays where it is (a toolbar's, a
/// confirmation's), disabled with the model's `approvalBlocked`, which reads the same value:
/// Check again here enables the button too. Nothing while the device can approve.
struct ApprovalNotice: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if model.mode == .kiff {
            ReadinessReader { readiness, checkAgain in
                if !readiness.isReady {
                    ApprovalNotReady(readiness: readiness, checkAgain: checkAgain)
                }
            }
        }
    }
}

/// Why this device can't approve, and what to do: Open Settings where tafs' settings help,
/// Check again unless the device can never approve.
struct ApprovalNotReady: View {
    let readiness: ApprovalReadiness
    let checkAgain: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(readiness.short(kind: Biometry.kind))
                .scaledFont(14).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("approvalNotReady")
            if readiness == .noneEnrolled || readiness == .noPasscode {
                Text(ApprovalReadiness.newBiometricsNote)
                    .scaledFont(12, relativeTo: .caption).foregroundColor(Theme.dim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 10) {
                if readiness.opensSettings {
                    Button("Open Settings") { openTafsSettings() }
                        .buttonStyle(PrimaryButtonStyle())
                }
                if readiness != .unsupported {
                    Button("Check again", action: checkAgain)
                        .buttonStyle(OutlineButtonStyle())
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
