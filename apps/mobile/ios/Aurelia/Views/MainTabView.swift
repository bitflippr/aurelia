import AureliaCore
import SwiftUI

struct MainTabView: View {
    @Binding var selectedTab: MainDestination
    @Binding var playerPresentationProgress: CGFloat
    var onMiniPlayerTap: () -> Void
    var onMiniPlayerLyricsTap: () -> Void
    var onMiniPlayerQueueTap: () -> Void
    @Environment(AppViewModel.self) private var appViewModel
    @Environment(AudioPlayerController.self) private var player
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var playlists: [Playlist] = []
    @State private var selectedPlaylist: PlaylistRoute?
    @State private var showSettings = false
    @State private var sidebarTrailingEdge: CGFloat = 260

    var body: some View {
        let playerVisible = showsPlayer
        let playbackOpacity = playerOpacity
        GeometryReader { geometry in
            let wide = AureliaLayout.usesSidebar(
                width: geometry.size.width, accessibilitySize: typeSize.isAccessibilitySize)
            let sidebarEdge = columnVisibility == .detailOnly ? 0 : sidebarTrailingEdge
            let dockFits = (geometry.size.width - 768) / 2 >= sidebarEdge + 16
            let playerLeading = dockFits ? max(16, (geometry.size.width - 768) / 2) : sidebarEdge + 16
            let playerWidth = dockFits ? 768 : max(0, geometry.size.width - playerLeading - 16)
            Group {
                if wide {
                    NavigationSplitView(columnVisibility: $columnVisibility) {
                        sidebar
                            .onGeometryChange(for: CGFloat.self) {
                                $0.frame(in: .named("playerContainer")).maxX
                            } action: {
                                sidebarTrailingEdge = $0
                            }
                            .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 260)
                    } detail: {
                        detail
                            .environment(\.aureliaSidebarVisible, columnVisibility != .detailOnly)
                    }
                    .navigationSplitViewStyle(.balanced)
                    .safeAreaInset(edge: .bottom, spacing: 0) {
                        if playerVisible {
                            MiniPlayerView(
                                onTap: onMiniPlayerTap, onLyricsTap: onMiniPlayerLyricsTap,
                                onQueueTap: onMiniPlayerQueueTap,
                                maximumWidth: playerWidth,
                                usesCompactLayout: playerWidth < 700
                            )
                            .frame(width: playerWidth)
                            .padding(.leading, playerLeading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.bottom, 10)
                            .opacity(playbackOpacity)
                        }
                    }
                } else {
                    compactTabs
                        .compactPlayerAccessory(isEnabled: playerVisible) {
                            MiniPlayerView(
                                onTap: onMiniPlayerTap, onLyricsTap: onMiniPlayerLyricsTap,
                                onQueueTap: onMiniPlayerQueueTap,
                                usesSystemAccessory: usesNativePlayerAccessory
                            )
                            .opacity(playbackOpacity)
                        }
                        .environment(\.horizontalSizeClass, .compact)
                }
            }
            .coordinateSpace(name: "playerContainer")
            .onChange(of: selectedTab) { _, destination in
                if !wide, [.albums, .artists, .favorites, .playlists].contains(destination) {
                    selectedTab = .songs
                    selectedPlaylist = nil
                }
            }
            .onChange(of: wide) { _, newValue in
                if !newValue, ![MainDestination.home, .search, .songs].contains(selectedTab) {
                    selectedTab = .songs
                    selectedPlaylist = nil
                }
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("Done") { showSettings = false } }
                }
        }
        .onChange(of: selectedTab) { _, destination in
            if destination == .settings {
                showSettings = true
                selectedTab = .home
            }
            if destination != .playlists { selectedPlaylist = nil }
        }
        .task(id: appViewModel.sessionVersion) {
            playlists = []
            selectedPlaylist = nil
            guard let session = SessionStore.shared.snapshot() else { return }
            let fetched = try? await getPlaylists(
                serverUrl: session.credentials.serverUrl, token: session.credentials.token,
                userId: session.credentials.userId)
            guard !Task.isCancelled, SessionStore.shared.getAppDataDir() == session.appDataDir else {
                return
            }
            playlists = fetched ?? []
        }
    }

    private var showsPlayer: Bool {
        player.snapshot.currentSongId != nil && playerPresentationProgress < 0.999
    }
    private var usesNativePlayerAccessory: Bool {
        if #available(iOS 26, *) { return true }
        return false
    }
    private var playerOpacity: Double { Double(max(0, 1 - playerPresentationProgress * 1.4)) }

    private var sidebar: some View {
        List(
            selection: Binding<MainDestination?>(
                get: { selectedPlaylist == nil ? selectedTab : nil },
                set: { destination in
                    guard let destination else { return }
                    selectedPlaylist = nil
                    selectedTab = destination
                }
            )
        ) {
            sidebarLink(.home)
            sidebarLink(.search)
            Section("Library") {
                sidebarLink(.songs, title: "Songs", icon: "music.note")
                sidebarLink(.albums)
                sidebarLink(.artists)
                sidebarLink(.favorites)
                sidebarLink(.playlists)
            }
            if !playlists.isEmpty {
                Section("Your playlists") {
                    ForEach(playlists, id: \.id) { playlist in
                        Button {
                            selectedTab = .playlists
                            selectedPlaylist = PlaylistRoute(id: playlist.id, name: playlist.name)
                        } label: {
                            Label(playlist.name, systemImage: "music.note.list")
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                        }
                        .listRowBackground(
                            selectedPlaylist?.id == playlist.id ? Color.accentColor.opacity(0.16) : Color.clear)
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .navigationTitle("Aurelia")
        .safeAreaInset(edge: .bottom) {
            AccountMenuButton(expanded: true)
                .padding(14)
                .aureliaGlass(in: RoundedRectangle(cornerRadius: 20))
                .padding(12)
                .padding(.bottom, showsPlayer ? 80 : 0)
        }
    }

    private func sidebarLink(
        _ destination: MainDestination, title: String? = nil, icon: String? = nil
    ) -> some View {
        NavigationLink(value: destination) {
            Label(title ?? destination.title, systemImage: icon ?? destination.systemImage)
        }
    }

    @ViewBuilder
    private var detail: some View {
        if selectedTab == .playlists, let selectedPlaylist {
            NavigationStack {
                PlaylistDetailView(playlistId: selectedPlaylist.id, playlistName: selectedPlaylist.name)
            }
            .id(selectedPlaylist.id)
        } else {
            selectedTab.destinationView()
                .id(selectedTab)
        }
    }

    private var compactTabs: some View {
        TabView(selection: $selectedTab) {
            Tab("Home", systemImage: "house", value: MainDestination.home) { HomeView() }
            Tab("Search", systemImage: "magnifyingglass", value: MainDestination.search) { SearchView() }
            Tab("Library", systemImage: "square.stack", value: MainDestination.songs) { LibraryView() }
        }
    }
}

extension View {
    @ViewBuilder
    fileprivate func compactPlayerAccessory<Accessory: View>(
        isEnabled: Bool, @ViewBuilder content: () -> Accessory
    )
        -> some View
    {
        if #available(iOS 26.1, *) {
            tabViewBottomAccessory(isEnabled: isEnabled, content: content)
        } else if #available(iOS 26, *) {
            // The original iOS 26 API does not have the isEnabled overload.
            tabViewBottomAccessory { if isEnabled { content() } }
        } else {
            safeAreaInset(edge: .bottom, spacing: 0) {
                if isEnabled { content().padding(.horizontal, 12).padding(.bottom, 6) }
            }
        }
    }
}
