import AureliaCore
import SwiftUI

struct SettingsView: View {
    @Environment(AppViewModel.self) private var appViewModel
    @Environment(AudioPlayerController.self) private var playerController
    @State private var viewModel = SettingsViewModel()
    @State private var showLogoutConfirmation = false
    @State private var visualizerEnabled: Bool = SessionStore.shared.visualizerEnabled
    @State private var visualizerStyle: VisualizerStyle = .init(rawValue: SessionStore.shared.visualizerStyle) ?? .bars
    @State private var profiles: [SessionProfile] = []
    @State private var activeProfileId: String?
    @State private var showAddProfileSheet = false
    @AppStorage("animatedArtworkEnabled") private var animatedArtworkEnabled = true

    var body: some View {
        NavigationStack {
            settingsContent
                .aureliaRootTabHeader("Settings")
                .confirmationDialog("Sign Out", isPresented: $showLogoutConfirmation) {
                    Button("Sign Out", role: .destructive) {
                        SessionStore.shared.clear()
                        appViewModel.logout()
                    }
                } message: {
                    Text("Are you sure you want to sign out?")
                }
        }
        .aureliaScreen()
        .onAppear {
            visualizerEnabled = SessionStore.shared.visualizerEnabled
            visualizerStyle = VisualizerStyle(rawValue: SessionStore.shared.visualizerStyle) ?? .bars
            playerController.refreshVisualizerSettings()
            profiles = SessionStore.shared.getProfiles()
            activeProfileId = SessionStore.shared.getActiveProfileId()
        }
        .sheet(isPresented: $showAddProfileSheet) {
            AddProfileSheet {
                profiles = SessionStore.shared.getProfiles()
                activeProfileId = SessionStore.shared.getActiveProfileId()
            }
        }
    }

    private var settingsSections: some View {
        Group {
            Section("Library Sync") {
                HStack {
                    Text("Last Synced")
                    Spacer()
                    Text(TimeFormatter.formatRelativeTime(viewModel.lastSyncTime))
                        .foregroundStyle(.secondary)
                }

                Button {
                    viewModel.syncLibrary()
                } label: {
                    HStack {
                        Text("Sync Now")
                        Spacer()
                        if viewModel.isSyncing {
                            ProgressView()
                        } else if let success = viewModel.syncSuccess {
                            Image(systemName: success ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundStyle(success ? .green : .red)
                        }
                    }
                }
                .disabled(viewModel.isSyncing)
            }

            Section("Cache") {
                Button(role: .destructive) {
                    viewModel.clearLocalCache()
                } label: {
                    HStack {
                        Text("Clear Local Cache")
                        Spacer()
                        if viewModel.isClearing {
                            ProgressView()
                        } else if let success = viewModel.clearSuccess {
                            Image(systemName: success ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundStyle(success ? .green : .red)
                        }
                    }
                }
                .disabled(viewModel.isClearing)
            }

            Section {
                Toggle("Animated artwork", isOn: $animatedArtworkEnabled)
            } header: { Text("Now Playing") } footer: {
                Text("Play album artwork loops when available. Motion pauses with playback and respects Reduce Motion.")
            }

            Section("Visualizer") {
                Toggle("Enable Visualizer", isOn: $visualizerEnabled)
                    .onChange(of: visualizerEnabled) { _, newValue in
                        playerController.setVisualizerEnabled(newValue)
                    }

                Picker("Style", selection: $visualizerStyle) {
                    ForEach(VisualizerStyle.allCases, id: \.self) { style in
                        Text(style.title).tag(style)
                    }
                }
                .disabled(!visualizerEnabled)
                .onChange(of: visualizerStyle) { _, newValue in
                    playerController.setVisualizerStyle(newValue)
                }
            }

            Section("Account") {
                if let serverUrl = SessionStore.shared.serverUrl {
                    HStack {
                        Text("Server")
                        Spacer()
                        Text(serverUrl)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }

                Button("Sign Out", role: .destructive) {
                    showLogoutConfirmation = true
                }
            }

            Section {
                Button {
                    showAddProfileSheet = true
                } label: {
                    Label("Add Profile", systemImage: "plus")
                }

                if profiles.isEmpty {
                    Text("No saved profiles")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(profiles) { profile in
                        profileRow(profile)
                    }
                }
            } header: {
                Text("Profiles")
            }

            Section("About") {
                HStack {
                    Text("Version")
                    Spacer()
                    Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0")
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder
    private var settingsContent: some View {
        List {
            settingsSections
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .listRowBackground(Rectangle().fill(.ultraThinMaterial))
    }

    @ViewBuilder
    private func profileRow(_ profile: SessionProfile) -> some View {
        let isActive = profile.id == activeProfileId

        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(profile.username.isEmpty ? profile.userId : profile.username)
                    .font(.headline)
                Spacer()
                if isActive {
                    Text("Active")
                        .font(.caption)
                        .foregroundStyle(.tint)
                }
            }

            Text(profile.serverUrl)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)

            HStack(spacing: 12) {
                if !isActive {
                    Button("Switch") {
                        switchToProfile(profile.id)
                    }
                    .buttonStyle(.bordered)
                }

                Button("Remove", role: .destructive) {
                    removeProfile(profile)
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 4)
    }

    private func switchToProfile(_ profileId: String) {
        playerController.stop()
        guard appViewModel.switchProfile(profileId) else { return }
        profiles = SessionStore.shared.getProfiles()
        activeProfileId = SessionStore.shared.getActiveProfileId()
    }

    private func removeProfile(_ profile: SessionProfile) {
        let wasActive = profile.id == activeProfileId
        guard SessionStore.shared.removeProfile(profile.id) else { return }

        profiles = SessionStore.shared.getProfiles()
        activeProfileId = SessionStore.shared.getActiveProfileId()

        if profiles.isEmpty {
            appViewModel.logout()
            return
        }

        if wasActive {
            playerController.stop()
            appViewModel.refreshAfterSessionChange()
        }
    }


}
