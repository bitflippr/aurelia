import AureliaCore
import SwiftUI
import UIKit



struct AddProfileSheet: View {
    @Environment(\.dismiss) private var dismiss

    @State private var serverUrl = ""
    @State private var username = ""
    @State private var password = ""

    @State private var isSubmitting = false
    @State private var error: String?

    let onProfileAdded: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("Server URL", text: $serverUrl)
                        .textContentType(.URL)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)


                }

                Section("Credentials") {
                    TextField("Username", text: $username)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)

                    SecureField("Password", text: $password)
                        .textContentType(.password)
                }

                if let error {
                    Section {
                        Text(error)
                            .font(.callout)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Add Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        addProfile()
                    } label: {
                        if isSubmitting {
                            ProgressView()
                        } else {
                            Text("Add")
                        }
                    }
                    .disabled(isSubmitting)
                }
            }
        }
    }





    private func addProfile() {
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
        let username = self.username
        let password = self.password

        let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? "aurelia-ios-\(UUID().uuidString)"

        Task.detached {
            [serverUrl = normalizedServerUrl, username, password, deviceId] in
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
                    let previousProfileId = sessionStore.getActiveProfileId()
                    try sessionStore.save(
                        serverUrl: serverUrl,
                        userId: response.userId,
                        token: response.token,
                        username: username,
                        provider: resolvedProvider
                    )
                    if let previousProfileId, !previousProfileId.isEmpty {
                        _ = sessionStore.switchProfile(previousProfileId)
                    }

                    onProfileAdded()
                    isSubmitting = false
                    dismiss()
                }
            } catch {
                await MainActor.run {
                    isSubmitting = false
                    self.error = error.localizedDescription
                }
            }
        }
    }
}
