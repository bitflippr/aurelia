import AureliaCore
import Foundation
import os

@MainActor
final class AuthInterceptor {
    static let shared = AuthInterceptor()
    private let logger = Logger(subsystem: "com.aurelia.app", category: "AuthInterceptor")
    private var logoutCallback: (() -> Void)?
    private init() {}

    func setLogoutCallback(_ callback: @escaping () -> Void) { logoutCallback = callback }
    func clearLogoutCallback() { logoutCallback = nil }

    func isUnauthorizedError(_ error: Error) -> Bool {
        guard let error = error as? AppError, case let .Http(status, _) = error else { return false }
        return status == 401
    }

    @discardableResult
    func handlePotentialAuthError(_ error: Error) -> Bool {
        guard isUnauthorizedError(error) else { return false }
        logger.warning("Server rejected credentials; ending the session")
        logoutCallback?()
        return true
    }
}
