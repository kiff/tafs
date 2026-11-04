import Foundation

/// A stalled system authentication must release Resume without accepting a later answer.
/// Unstructured tasks let the deadline return even if a signing provider ignores cancellation.
@MainActor
final class ResumeConfirmation {
    enum Failure: Error { case timedOut }
    private var continuation: CheckedContinuation<Void, Error>?
    private var signing: Task<Void, Never>?
    private var deadline: Task<Void, Never>?

    static func confirm(signer: ApprovalSigning, message: Data, reason: String,
                        wait: @escaping @MainActor () async throws -> Void) async throws {
        let pending = ResumeConfirmation()
        try await withTaskCancellationHandler {
            try Task.checkCancellation()
            try await withCheckedThrowingContinuation { continuation in
                pending.continuation = continuation
                pending.signing = Task {
                    do {
                        _ = try await signer.sign(message, reason: reason, grants: true)
                        pending.finish(.success(()))
                    } catch { pending.finish(.failure(error)) }
                }
                pending.deadline = Task {
                    do {
                        try await wait()
                        guard pending.continuation != nil else { return }
                        signer.cancelConfirmation()
                        pending.finish(.failure(Failure.timedOut))
                    } catch is CancellationError {
                    } catch {
                        guard pending.continuation != nil else { return }
                        signer.cancelConfirmation()
                        pending.finish(.failure(error))
                    }
                }
            }
        } onCancel: {
            Task { @MainActor in
                guard pending.continuation != nil else { return }
                signer.cancelConfirmation()
                pending.finish(.failure(CancellationError()))
            }
        }
    }

    private func finish(_ result: Result<Void, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        signing?.cancel(); deadline?.cancel()
        signing = nil; deadline = nil
        continuation.resume(with: result)
    }
}
