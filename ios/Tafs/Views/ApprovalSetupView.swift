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

/// An approval's button, or, while this device can't approve, the state's words and what to
/// do in its place: the person learns why before the prompt, not from a failure after it.
/// In sample mode nothing is signed for real, so the button always shows.
struct ApprovalGate<Approve: View>: View {
    @EnvironmentObject private var model: AppModel
    @ViewBuilder let button: () -> Approve

    var body: some View {
        // An approval confirms: a drive's touch on it is refused (docs/drive.md).
        if model.mode != .kiff {
            button().confirming()
        } else {
            ReadinessReader { readiness, checkAgain in
                if readiness.isReady {
                    button().confirming()
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
            // Side by side while both fit whole, else one above the other: never "Ope…".
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) { buttons }
                VStack(spacing: 8) { buttons }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var buttons: some View {
        if readiness.opensSettings {
            Button(Words.Voice.openSettings) { openTafsSettings() }
                .buttonStyle(PrimaryButtonStyle())
        }
        if readiness != .unsupported {
            Button(Words.Approval.checkAgain, action: checkAgain)
                .buttonStyle(OutlineButtonStyle())
        }
    }
}
