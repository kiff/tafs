import CoreImage.CIFilterBuiltins
import SwiftUI

/// Signing in from the person's phone (KIFF's pairing contract §5.8), the sign-in's right half on an iPad or a
/// Mac: this device shows the pairing QR at once, with no prompt (its claim is signed with the device key), then
/// its check code. The phone scans and approves it with its approval key, and this device claims the session.
/// Nothing here approves anything: the phone does, behind its own Face ID, after comparing the code.
struct PairSignInView: View {
    @EnvironmentObject private var model: AppModel
    /// Inside the sign-in's two halves: no scroll or background of its own.
    var embedded = false
    /// Signed in: the sign-in closes.
    let done: () -> Void
    /// The terms changed since the welcome's tick (`Onboarding`): the pairing stops, and the welcome asks for the tick
    /// again. Nil where no tick was shown, and nothing is sent that could be outdated.
    var termsChanged: (() -> Void)? = nil
    @State private var start: AppModel.PairingStart?
    @State private var problem: String?
    @State private var renewed = false
    @State private var task: Task<Void, Never>?

    var body: some View {
        if embedded {
            content
        } else {
            ScrollView { content }.screenBackground()
        }
    }

    private var content: some View {
        VStack(spacing: 22) {
            if let start, let image = Self.qrImage(start.offer.qr) {
                Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                    .padding(16)
                    .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color.white))
                    .shadow(color: .black.opacity(0.14), radius: 18, y: 10)
                    .frame(maxWidth: 240)
                    .accessibilityHidden(true)
                    .accessibilityIdentifier("pair.qr")
            } else if problem == nil {
                ProgressView().tint(Theme.amber).frame(height: 240)
            }
            Text(Words.SignIn.withPhone)
                .scaledFont(30, weight: .bold, design: .rounded, relativeTo: .title).foregroundColor(Theme.ink)
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            Label(Words.SignIn.scanWithTafs, systemImage: "viewfinder")
                .scaledFont(17).foregroundColor(Theme.soft)
            if let start {
                VStack(alignment: .leading, spacing: 14) {
                    Text(Words.SignIn.pairHow(account: Words.Settings.title, devices: Words.Settings.Devices.title, add: Words.Settings.Devices.pair))
                        .scaledFont(14).foregroundColor(Theme.dim).fixedSize(horizontal: false, vertical: true)
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
                }
                .frame(maxWidth: 420)
            }
            if let problem {
                Text(problem).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true)
                Button(Words.Common.tryAgain) { begin() }.buttonStyle(OutlineButtonStyle())
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity)
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
                } catch KIFFClient.KIFFError.termsOutdated {
                    start = nil
                    termsChanged?()
                    return
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
