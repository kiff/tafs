import CoreImage.CIFilterBuiltins
import SwiftUI

/// Signing in from another phone (KIFF's pairing contract §5.8): this device shows the pairing QR and its
/// check code, the person's other phone scans and approves it with its approval key, and this device
/// claims the session. Nothing here approves anything: the other phone does, behind its own Face ID.
struct PairSignInView: View {
    @EnvironmentObject private var model: AppModel
    /// Signed in: the sign-in closes.
    let done: () -> Void
    @State private var start: AppModel.PairingStart?
    @State private var problem: String?
    @State private var renewed = false
    @State private var task: Task<Void, Never>?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(Words.SignIn.pairStart)
                    .scaledFont(28, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                if let start {
                    Text(Words.SignIn.pairHow(account: Words.Settings.title, devices: Words.Settings.Devices.title, add: Words.Settings.Devices.pair))
                        .scaledFont(15).foregroundColor(Theme.soft).fixedSize(horizontal: false, vertical: true)
                    if let image = Self.qrImage(start.offer.qr) {
                        Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                            .padding(16)
                            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white))
                            .frame(maxWidth: 320)
                            .frame(maxWidth: .infinity)
                            .accessibilityHidden(true)
                            .accessibilityIdentifier("pair.qr")
                    }
                    Panel(stroke: Theme.amber.opacity(0.4)) {
                        Eyebrow(text: Words.SignIn.yourCode)
                        Text(start.offer.checkCode)
                            .scaledFont(34, weight: .semibold, design: .monospaced)
                            .foregroundColor(Theme.ink)
                            .accessibilityIdentifier("pair.code")
                        Text(Words.SignIn.pairCheck(device: "\(ThisDevice.kind)", code: start.offer.checkCode))
                            .scaledFont(13).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
                    }
                    if renewed { Text(Words.SignIn.pairNewCode).scaledFont(13).foregroundColor(Theme.dim) }
                    HStack(spacing: 10) {
                        ProgressView().tint(Theme.amber)
                        Text(Words.SignIn.pairWaiting).scaledFont(14).foregroundColor(Theme.soft)
                    }
                } else if problem == nil {
                    ProgressView().tint(Theme.amber)
                }
                if let problem {
                    Text(problem).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                    Button(Words.Common.tryAgain) { begin() }.buttonStyle(OutlineButtonStyle())
                }
            }
            .padding(24)
        }
        .screenBackground()
        .onAppear(perform: begin)
        .onDisappear { task?.cancel() }
    }

    /// Shows a code and waits; an expired one is replaced by a new one, which signs its own proof.
    private func begin() {
        problem = nil
        renewed = false
        task?.cancel()
        task = Task {
            while !Task.isCancelled {
                do {
                    let next = try await model.startPairingSignIn()
                    start = next
                    try await model.finishPairingSignIn(next)
                    done()
                    return
                } catch KIFFClient.KIFFError.expired {
                    renewed = true
                } catch is CancellationError {
                    return
                } catch let cannot as AppModel.CannotPair {
                    start = nil
                    problem = cannot.errorDescription
                    return
                } catch {
                    let shown = start != nil
                    start = nil
                    problem = shown ? Words.SignIn.couldNotFinish(why: "\(error.localizedDescription)")
                        : Words.SignIn.couldNotStart(why: "\(error.localizedDescription)")
                    return
                }
            }
        }
    }

    /// The QR, error correction M (§5.1), drawn sharp at any size.
    static func qrImage(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}
