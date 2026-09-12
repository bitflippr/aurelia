import SwiftUI

struct AccountMenuButton: View {
    var expanded = false
    @Environment(AppViewModel.self) private var appViewModel
    @Environment(AudioPlayerController.self) private var player
    @State private var showSettings = false
    @State private var showAddProfile = false
    @State private var profiles: [SessionProfile] = []
    @State private var activeId: String?

    private var name: String {
        profiles.first(where: { $0.id == activeId })?.username ?? "Account"
    }

    var body: some View {
        Menu {
            Button("Settings", systemImage: "gearshape") { showSettings = true }
            Section("Profiles") {
                ForEach(profiles) { profile in
                    Button {
                        guard profile.id != activeId else { return }
                        player.stop()
                        _ = appViewModel.switchProfile(profile.id)
                        refresh()
                    } label: {
                        Label(profile.label, systemImage: profile.id == activeId ? "checkmark" : "person")
                    }
                }
                Button("Add Profile", systemImage: "plus") { showAddProfile = true }
            }
            Button("Log Out", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                player.stop()
                appViewModel.logout()
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "person.crop.circle.fill").font(.title2)
                if expanded {
                    Text(name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Spacer()
                    Image(systemName: "gearshape").font(.body)
                }
            }
            .frame(minWidth: 28, minHeight: 32)
        }
        .accessibilityLabel("Account and settings")
        .sheet(isPresented: $showSettings) {
            SettingsView()
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showSettings = false } } }
        }
        .sheet(isPresented: $showAddProfile) { AddProfileSheet { refresh() } }
        .onAppear { refresh() }
        .onChange(of: appViewModel.sessionVersion) { _, _ in refresh() }
    }

    private func refresh() {
        profiles = SessionStore.shared.getProfiles()
        activeId = SessionStore.shared.getActiveProfileId()
    }
}
