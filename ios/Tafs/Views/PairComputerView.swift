import AVFoundation
import SwiftUI

/// Account → Devices → Add a computer (KIFF's pairing contract §5): the camera reads the computer's QR, the
/// phone says what pairing gives and asks whether the computer shows the same check code, and only then
/// does the approval key sign the pairing, behind Face ID naming the computer. Nothing is signed from
/// the scan itself.
struct PairComputerView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var request: PairingRequest?
    @State private var problem: String?
    @State private var outcome: String?
    @State private var sending = false

    var body: some View {
        NavigationStack {
            ScrollView {
                content
                    .padding(20)
                    .frame(maxWidth: 560, alignment: .leading)
                    .frame(maxWidth: .infinity)
            }
            .screenBackground()
            .navigationTitle(Words.Settings.Devices.pair)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(outcome == nil ? Words.Common.cancel : Words.Common.done) { dismiss() }
                }
            }
        }
    }

    @ViewBuilder private var content: some View {
        if let outcome {
            Text(outcome).scaledFont(17, weight: .semibold).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("pair.outcome")
        } else if let request {
            confirm(request)
        } else {
            VStack(alignment: .leading, spacing: 14) {
                QRScanner(found: read) { why in problem = Words.Settings.Devices.pairScannerFailed(why: why) }
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .accessibilityIdentifier("pair.scanner")
                Text(Words.Settings.Devices.pairOnlyNow).scaledFont(14).foregroundColor(Theme.dim)
                if let problem { Text(problem).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true) }
            }
        }
    }

    /// What pairing gives, said before the check code, then the code itself and the one yes.
    private func confirm(_ request: PairingRequest) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(Words.Settings.Devices.pairNamed(device: request.name))
                .scaledFont(22, weight: .bold, design: .rounded, relativeTo: .title2).foregroundColor(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(Self.lines(request), id: \.self) { line in
                Text(line).scaledFont(15).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            }
            Text(Self.check(request))
                .scaledFont(17, weight: .semibold).foregroundColor(Theme.ink).fixedSize(horizontal: false, vertical: true)
            Text(request.checkCode)
                .scaledFont(34, weight: .bold, design: .monospaced, relativeTo: .largeTitle).tracking(4)
                .foregroundColor(Theme.ink)
                .accessibilityIdentifier("pair.code")
            if let problem { Text(problem).scaledFont(14).foregroundColor(Theme.no).fixedSize(horizontal: false, vertical: true) }
            Button { Task { await pair(request) } } label: { Text(Words.Settings.Devices.pairYes) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(sending)
                .confirming()
                .accessibilityIdentifier("pair.yes")
        }
    }

    private func read(_ text: String) {
        switch PairingRequest.read(text) {
        case .success(let read):
            request = read
            problem = nil
        case .failure(let why):
            problem = Self.words(why)
        }
    }

    private func pair(_ request: PairingRequest) async {
        sending = true
        defer { sending = false }
        switch await model.pairComputer(request) {
        case .paired(let words): outcome = words
        case .failed(let why): problem = why
        case .cancelled: break
        }
    }

    /// The question before the fingerprint: does the computer, or the new phone or tablet, show this code?
    static func check(_ request: PairingRequest) -> String {
        request.isPhone ? Words.Settings.Devices.pairCheckPhone(code: request.checkCode) : Words.Settings.Devices.pairCheck(code: request.checkCode)
    }

    /// A code the phone won't pair, in the person's words.
    static func words(_ problem: PairingRequest.Problem) -> String {
        switch problem {
        case .notAPairingCode: return Words.Settings.Devices.pairNotAPairing
        case .expired: return Words.Settings.Devices.pairExpired
        case .badKeys, .badField: return Words.Settings.Devices.pairInvalid
        }
    }

    /// What saying yes gives, by the computer's kind; a software-keyed one says it never approves alone;
    /// and only a computer in use now.
    static func lines(_ request: PairingRequest) -> [String] {
        // A phone or tablet (§5.8): what it gets, whatever its biometric, and only one in hand now.
        if request.isPhone {
            return [Words.Settings.Devices.pairGivesPhone(device: request.name), Words.Settings.Devices.pairOnlyNowPhone]
        }
        var lines: [String] = []
        switch request.kind {
        case "hello": lines.append(Words.Settings.Devices.pairGivesHello(device: request.name))
        case "enclave": lines.append(Words.Settings.Devices.pairGivesEnclave(device: request.name))
        case "passkey": lines.append(Words.Settings.Devices.pairGivesPasskey(device: request.name))
        default: break
        }
        if request.softwareKey { lines.append(Words.Settings.Devices.pairSoftwareKey) }
        lines.append(Words.Settings.Devices.pairOnlyNow)
        return lines
    }
}

/// The camera reading QR codes: each new one is handed on once, so a code the phone won't pair can be
/// followed by another without opening the camera again. It stops when it's closed.
struct QRScanner: UIViewControllerRepresentable {
    let found: (String) -> Void
    let failed: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerController {
        let controller = QRScannerController()
        controller.found = found
        controller.failed = failed
        return controller
    }

    func updateUIViewController(_ controller: QRScannerController, context: Context) {}
}

final class QRScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var found: ((String) -> Void)?
    var failed: ((String) -> Void)?
    private let capture = CaptureBox()
    private var preview: AVCaptureVideoPreviewLayer?
    /// The last code read, so the same one held in front of the camera isn't read again and again.
    private var last: String?

    /// The session, started and stopped off the main thread.
    private final class CaptureBox: @unchecked Sendable {
        let session = AVCaptureSession()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        Task { @MainActor in
            guard await AVCaptureDevice.requestAccess(for: .video) else {
                failed?(AVError(.applicationIsNotAuthorizedToUseDevice).localizedDescription)
                return
            }
            start()
        }
    }

    private func start() {
        let session = capture.session
        do {
            guard let camera = AVCaptureDevice.default(for: .video) else { throw AVError(.deviceNotConnected) }
            let input = try AVCaptureDeviceInput(device: camera)
            let output = AVCaptureMetadataOutput()
            guard session.canAddInput(input), session.canAddOutput(output) else { throw AVError(.deviceNotConnected) }
            session.addInput(input)
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
        } catch {
            failed?(error.localizedDescription)
            return
        }
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer
        let box = capture
        DispatchQueue.global(qos: .userInitiated).async { box.session.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        let box = capture
        DispatchQueue.global(qos: .userInitiated).async { box.session.stopRunning() }
    }

    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let text = metadataObjects.lazy.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
        Task { @MainActor in self.deliver(text) }
    }

    private func deliver(_ text: String) {
        guard text != last else { return }
        last = text
        found?(text)
    }
}
