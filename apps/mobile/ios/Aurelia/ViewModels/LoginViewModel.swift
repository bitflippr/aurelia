import AureliaCore
import Foundation
import Observation
import UIKit

@Observable
final class LoginViewModel: @unchecked Sendable {
    var serverUrl = ""
    var username = ""
    var password = ""
    var isSubmitting = false
    var error: String?

    // Set after successful login
    var token: String?
    var userId: String?

    private let sessionStore = SessionStore.shared

    func submit() {
        guard !serverUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !username.trimmingCharacters(in: .whitespaces).isEmpty,
              !password.isEmpty
        else {
            error = "All fields are required"
            return
        }

        guard let normalizedServerUrl = ServerURLNormalizer.normalizeForServer(raw: serverUrl),
              ServerURLNormalizer.isValidServerURL(normalizedServerUrl)
        else {
            error = "Enter a valid server URL"
            return
        }

        isSubmitting = true
        error = nil
        serverUrl = normalizedServerUrl

        let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? "aurelia-ios-\(UUID().uuidString)"

        Task.detached {
            [serverUrl = normalizedServerUrl, username = self.username, password = self.password, deviceId] in
            do {
                let resolvedProvider: BackendProvider = .jellyfin

                let response = try await authenticate(
                    request: AuthRequest(
                        provider: resolvedProvider,
                        serverUrl: serverUrl,
                        username: username,
                        password: password,
                        deviceId: deviceId
                    )
                )
                try await MainActor.run {
                    let sessionStore = SessionStore.shared
                    try sessionStore.save(
                        serverUrl: serverUrl,
                        userId: response.userId,
                        token: response.token,
                        username: username,
                        provider: resolvedProvider
                    )
                }

                await MainActor.run { [self] in
                    isSubmitting = false
                    token = response.token
                    userId = response.userId
                }
            } catch {
                await MainActor.run { [self] in
                    isSubmitting = false
                    self.error = error.localizedDescription
                }
            }
        }
    }
}
