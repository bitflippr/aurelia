import AureliaCore
import SwiftUI

struct AlbumDetailView: View {
    let albumId: String
    let albumName: String

    @Environment(AudioPlayerController.self) private var playerController
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var album: Album?
    @State private var songs: [Song] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var didLoad = false
    @State private var showPlaylists = false

    private var artworkUrl: String? { album?.albumArtUrl ?? songs.first?.albumArtUrl }

    var body: some View {
        GeometryReader { geometry in
            let wide = horizontalSizeClass == .regular && geometry.size.width >= 700 && !typeSize.isAccessibilitySize
            ZStack(alignment: .top) {
                AureliaBackground()
                AlbumDetailBackdrop(artworkUrl: artworkUrl)
                    .ignoresSafeArea(.container, edges: .top)
                    .albumBackgroundExtension()
                Group {
                    if isLoading {
                        ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if let error {
                        ContentUnavailableView(
                            "Failed to Load", systemImage: "exclamationmark.triangle", description: Text(error))
                    } else {
                        ScrollView {
                            if wide {
                                HStack(alignment: .top, spacing: 36) {
                                    albumSummary(artDimension: 260).frame(width: min(360, geometry.size.width * 0.38))
                                    albumSongs.frame(maxWidth: .infinity)
                                }
                                .padding(.horizontal, 28)
                                .padding(.vertical, 24)
                            } else {
                                VStack(alignment: .leading, spacing: 28) {
                                    albumSummary(artDimension: min(208, geometry.size.width - 40))
                                    albumSongs
                                }
                                .padding(.horizontal, 20)
                                .padding(.top, 12)
                                .padding(.bottom, 24)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Album")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .navigationDestination(for: ArtistRoute.self) { ArtistDetailView(artistId: $0.id, artistName: $0.name) }
        .sheet(isPresented: $showPlaylists) { AddSongsToPlaylistView(songs: songs) }
        .onAppear {
            guard !didLoad else { return }
            didLoad = true
            loadAlbum()
        }
    }

    private func albumSummary(artDimension: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            AlbumArtView(url: artworkUrl, size: .large, customDimension: artDimension)
                .clipShape(RoundedRectangle(cornerRadius: 24))
                .shadow(color: .black.opacity(0.2), radius: 16, y: 10)
                .frame(maxWidth: .infinity)

            VStack(alignment: .leading, spacing: 8) {
                Text(album?.name ?? albumName)
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .accessibilityAddTraits(.isHeader)
                artistLink
                Text(
                    "Album · \(songs.count) songs · \(TimeFormatter.formatDuration(Int64(songs.reduce(0.0) { $0 + ($1.duration ?? 0) } * 1000)))"
                )
                .font(.subheadline).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            AureliaGlassGroup {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 10) { playbackActions }
                    VStack(alignment: .leading, spacing: 10) { playbackActions }
                }
                .controlSize(.large)
            }
            .disabled(songs.isEmpty)
        }
    }

    @ViewBuilder private var artistLink: some View {
        let name = album?.artist ?? songs.first?.artists?.joined(separator: ", ") ?? ""
        let artists = songs.first.map { SongNavigation.artists(for: $0) } ?? []
        if artists.count == 1, let artist = artists.first {
            NavigationLink(value: artist) {
                Label(name, systemImage: "chevron.right")
                    .labelStyle(ArtistLinkLabelStyle())
                    .frame(minHeight: 44)
            }.buttonStyle(.plain)
        } else if !artists.isEmpty {
            Menu {
                ForEach(artists, id: \.id) { artist in
                    NavigationLink(value: artist) { Text(artist.name) }
                }
            } label: {
                Label(name, systemImage: "chevron.right")
                    .labelStyle(ArtistLinkLabelStyle())
                    .frame(minHeight: 44)
            }
        } else if !name.isEmpty {
            Text(name).font(.headline).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var playbackActions: some View {
        Button {
            playSongs(startIndex: 0)
        } label: {
            Label("Play", systemImage: "play.fill").frame(minWidth: 72)
        }.aureliaGlassButton(prominent: true)
        Button {
            shuffleSongs()
        } label: {
            Label("Shuffle", systemImage: "shuffle").frame(minWidth: 72)
        }.aureliaGlassButton()
        Menu {
            Button("Add to Queue", systemImage: "text.badge.plus") { addAlbumToQueue() }
            Button("Add to Playlist", systemImage: "music.note.list") { showPlaylists = true }
        } label: {
            Image(systemName: "ellipsis").frame(minWidth: 16, minHeight: 20)
        }
        .aureliaGlassButton()
        .accessibilityLabel("Album actions")
    }

    private var albumSongs: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Tracks").font(.system(.title2, design: .rounded, weight: .bold)).accessibilityAddTraits(.isHeader)
            if songs.isEmpty {
                Text("No songs available for this album yet.")
                    .font(.subheadline).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 24)
            } else {
                LazyVStack(spacing: 0) {
                    let groupedSongs = groupSongsByDisc(songs)
                    ForEach(groupedSongs) { group in
                        if group.showDiscHeader {
                            Text("Disc \(group.discNumber)")
                                .font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 12)
                        }
                        ForEach(Array(group.songs.enumerated()), id: \.element.id) { index, song in
                            let globalIndex = songs.firstIndex(where: { $0.id == song.id }) ?? 0
                            SongRow(
                                song: song, isPlaying: song.id == playerController.snapshot.currentSongId,
                                showTrackNumber: true
                            ) {
                                playSongs(startIndex: globalIndex)
                            }
                            if index != group.songs.count - 1 { Divider() }
                        }
                    }
                }
            }
        }
    }

    private func addAlbumToQueue() {
        guard let session = SessionStore.shared.snapshot() else { return }
        for song in songs {
            playerController.addToQueue(
                song, serverUrl: session.credentials.serverUrl, token: session.credentials.token)
        }
    }

    private func groupSongsByDisc(_ songs: [Song]) -> [DiscGroup] {
        let sorted = Self.sortSongs(songs)
        let grouped = Dictionary(grouping: sorted) { Int($0.discNumber ?? 1) }
        let discs = grouped.keys.sorted()
        let showDiscHeader = discs.count > 1

        return discs.compactMap { disc in
            guard let discSongs = grouped[disc] else { return nil }
            return DiscGroup(discNumber: disc, songs: discSongs, showDiscHeader: showDiscHeader)
        }
    }

    private nonisolated static func sortSongs(_ songs: [Song]) -> [Song] {
        songs.sorted { lhs, rhs in
            let lhsDisc = Int(lhs.discNumber ?? 1)
            let rhsDisc = Int(rhs.discNumber ?? 1)
            if lhsDisc != rhsDisc {
                return lhsDisc < rhsDisc
            }

            let lhsTrack = Int(lhs.trackNumber ?? Int32.max)
            let rhsTrack = Int(rhs.trackNumber ?? Int32.max)
            if lhsTrack != rhsTrack {
                return lhsTrack < rhsTrack
            }

            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    private func loadAlbum() {
        let sessionStore = SessionStore.shared
        guard let creds = sessionStore.getCredentials() else {
            error = "Missing session data"
            isLoading = false
            return
        }

        Task.detached {
            let appDataDir = await sessionStore.getAppDataDir() ?? ""

            if let cachedSongs = try? loadCachedSongs(appDataDir: appDataDir) {
                let albumSongs = Self.sortSongs(cachedSongs.filter { $0.albumId == albumId })
                if !albumSongs.isEmpty {
                    await MainActor.run {
                        songs = albumSongs
                        isLoading = false
                    }
                }
            }

            if let cached = try? getCachedAlbum(appDataDir: appDataDir, albumId: albumId) {
                await MainActor.run {
                    album = cached
                    let embeddedSongs = Self.sortSongs(cached.songs ?? [])
                    if !embeddedSongs.isEmpty {
                        songs = embeddedSongs
                    }
                    isLoading = false
                }
            }

            do {
                let fetched = try await fetchAlbum(
                    serverUrl: creds.serverUrl,
                    token: creds.token,
                    userId: creds.userId,
                    albumId: albumId,
                    appDataDir: appDataDir
                )
                await MainActor.run {
                    album = fetched
                    let embeddedSongs = Self.sortSongs(fetched.songs ?? [])
                    if !embeddedSongs.isEmpty {
                        songs = embeddedSongs
                    }
                    isLoading = false
                    error = nil
                }
            } catch {
                if await !AuthInterceptor.shared.handlePotentialAuthError(error) {
                    await MainActor.run {
                        if songs.isEmpty, album == nil {
                            self.error = error.localizedDescription
                        }
                        isLoading = false
                    }
                }
            }
        }
    }

    private func playSongs(startIndex: Int) {
        guard let serverUrl = SessionStore.shared.serverUrl,
            let token = SessionStore.shared.token,
            !songs.isEmpty
        else { return }
        playerController.setQueue(songs, serverUrl: serverUrl, token: token, startIndex: startIndex)
    }

    private func shuffleSongs() {
        guard let serverUrl = SessionStore.shared.serverUrl,
            let token = SessionStore.shared.token,
            !songs.isEmpty
        else { return }
        playerController.setQueue(songs.shuffled(), serverUrl: serverUrl, token: token)
    }
}

struct DiscGroup: Identifiable {
    let id = UUID()
    let discNumber: Int
    let songs: [Song]
    let showDiscHeader: Bool
}

private struct ArtistLinkLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.title.font(.headline)
            configuration.icon.font(.caption.weight(.semibold)).foregroundStyle(.secondary)
        }
    }
}

private struct AlbumDetailBackdrop: View {
    let artworkUrl: String?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let artworkUrl, let url = URL(string: artworkUrl) {
                    CachedImageView(url: url, contentMode: .fill, targetSize: CGSize(width: 160, height: 160))
                        .frame(width: geometry.size.width, height: 640)
                        .clipped()
                        .blur(radius: 40)
                        .scaleEffect(1.15)
                        .opacity(scheme == .dark ? 0.38 : 0.2)
                }
                LinearGradient(
                    colors: [.clear, AureliaPalette.background(for: scheme)], startPoint: .top, endPoint: .bottom)
            }
            .frame(width: geometry.size.width, height: 640)
            .clipped()
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

extension View {
    @ViewBuilder
    fileprivate func albumBackgroundExtension() -> some View {
        if #available(iOS 26, *) {
            backgroundExtensionEffect()
        } else {
            self
        }
    }
}
