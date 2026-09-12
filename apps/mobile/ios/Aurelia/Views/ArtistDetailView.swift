import AureliaCore
import SwiftUI

struct ArtistDetailView: View {
    let artistId: String
    let artistName: String
    @Environment(AudioPlayerController.self) private var playerController
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var artist: Artist?
    @State private var songs: [Song] = []
    @State private var albums: [AlbumItem] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var showBiography = false
    @State private var showAllSongs = false
    @State private var didLoad = false
    @State private var headerBehindToolbar = true

    private var headerHeight: CGFloat { typeSize.isAccessibilitySize ? 400 : 330 }

    var body: some View {
        GeometryReader { geometry in
            let wide = horizontalSizeClass == .regular && geometry.size.width >= 700 && !typeSize.isAccessibilitySize
            Group {
                if isLoading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let error {
                    ContentUnavailableView(
                        "Failed to Load", systemImage: "exclamationmark.triangle", description: Text(error))
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 24) {
                            hero(width: geometry.size.width, wide: wide)
                            VStack(alignment: .leading, spacing: 24) {
                                AureliaGlassGroup {
                                    HStack(spacing: 12) {
                                        Button {
                                            playAllSongs(startIndex: 0)
                                        } label: {
                                            Label("Play", systemImage: "play.fill").frame(minWidth: 86)
                                        }.aureliaGlassButton(prominent: true)
                                        Button {
                                            playAllSongs(shuffled: true)
                                        } label: {
                                            Label("Shuffle", systemImage: "shuffle").frame(minWidth: 86)
                                        }.aureliaGlassButton()
                                    }.controlSize(.large).disabled(songs.isEmpty)
                                }
                                if let overview = artist?.overview, !overview.isEmpty {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(overview).font(.subheadline).foregroundStyle(.secondary)
                                            .lineLimit(showBiography ? nil : 2)
                                        Button(showBiography ? "Show less" : "More") {
                                            withAnimation { showBiography.toggle() }
                                        }
                                        .font(.subheadline)
                                    }.frame(maxWidth: 700, alignment: .leading)
                                }
                                if wide {
                                    HStack(alignment: .top, spacing: 28) {
                                        albumSection(width: (geometry.size.width - 68) * 0.48)
                                        songSection.frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                } else {
                                    albumSection(width: geometry.size.width - 40)
                                    songSection
                                }
                                if songs.isEmpty, albums.isEmpty {
                                    Text("No songs available for this artist yet.").foregroundStyle(.secondary)
                                }
                            }.padding(.horizontal, 20)
                        }.padding(.bottom, 24)
                    }
                    .ignoresSafeArea(.container, edges: .top)
                    .artistHeaderScrollEdge(hidden: headerBehindToolbar)
                    .onScrollGeometryChange(for: Bool.self) { scroll in
                        scroll.contentOffset.y + scroll.contentInsets.top < (wide ? 300 : headerHeight)
                            - geometry.safeAreaInsets.top
                    } action: { _, visible in
                        headerBehindToolbar = visible
                    }
                }
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(headerBehindToolbar ? .hidden : .automatic, for: .navigationBar)
        .toolbarColorScheme(headerBehindToolbar && !isLoading && error == nil ? .dark : nil, for: .navigationBar)
        .navigationDestination(for: AlbumRoute.self) { AlbumDetailView(albumId: $0.id, albumName: $0.name) }
        .onAppear {
            guard !didLoad else { return }
            didLoad = true
            loadArtist()
        }
        .aureliaScreen()
    }

    private func hero(width: CGFloat, wide: Bool) -> some View {
        let height: CGFloat = typeSize.isAccessibilitySize ? 400 : (wide ? 300 : 330)
        return ZStack(alignment: .bottomLeading) {
            Color(red: 0.08, green: 0.07, blue: 0.12)
            if let imageUrl = artist?.imageUrl {
                CachedImageView(
                    url: URL(string: imageUrl), contentMode: .fill,
                    targetSize: CGSize(width: min(width, 1000), height: height)
                )
                .frame(width: width, height: height).clipped()
            } else {
                Image(systemName: "music.mic").font(.system(size: 110, weight: .ultraLight))
                    .foregroundStyle(Color.accentColor.opacity(0.5))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            LinearGradient(colors: [.black.opacity(0.45), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 120)
                .frame(maxHeight: .infinity, alignment: .top)
            LinearGradient(
                colors: [.clear, .black.opacity(0.35), .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 8) {
                Text(artist?.name ?? artistName)
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .foregroundStyle(.white).accessibilityAddTraits(.isHeader)
                Text(
                    "\(albums.count) albums · \(songs.count) songs · \(TimeFormatter.formatDuration(songs.reduce(Int64(0)) { $0 + Int64(($1.duration ?? 0) * 1000) }))"
                )
                .font(.subheadline).foregroundStyle(.white.opacity(0.8))
            }.padding(20)
        }
        .frame(width: width, height: height)
        .clipped()
    }

    @ViewBuilder private func albumSection(width: CGFloat) -> some View {
        if !albums.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                Text("Albums").font(.system(.title2, design: .rounded, weight: .bold)).accessibilityAddTraits(.isHeader)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 14) {
                        ForEach(albums) { album in
                            NavigationLink(value: AlbumRoute(id: album.id, name: album.name)) {
                                VStack(alignment: .leading, spacing: 6) {
                                    AlbumArtView(url: album.albumArtUrl, size: .medium, customDimension: 148)
                                    Text(album.name).font(.subheadline.weight(.semibold)).lineLimit(2)
                                    Text("\(album.songCount) songs").font(.caption).foregroundStyle(.secondary)
                                }.frame(width: 148, alignment: .leading)
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }.frame(width: width, alignment: .leading)
        }
    }

    @ViewBuilder private var songSection: some View {
        if !songs.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Songs").font(.system(.title2, design: .rounded, weight: .bold)).accessibilityAddTraits(
                        .isHeader)
                    Spacer()
                    if songs.count > 5 { Button(showAllSongs ? "Show less" : "See all") { showAllSongs.toggle() } }
                }
                ForEach(Array((showAllSongs ? songs : Array(songs.prefix(5))).enumerated()), id: \.element.id) {
                    index, song in
                    SongRow(song: song, isPlaying: song.id == playerController.snapshot.currentSongId) {
                        playAllSongs(startIndex: index)
                    }
                    Divider()
                }
            }
        }
    }

    private func loadArtist() {
        let sessionStore = SessionStore.shared
        guard let creds = sessionStore.getCredentials() else {
            error = "Missing session data"
            isLoading = false
            return
        }

        Task.detached {
            let appDataDir = await sessionStore.getAppDataDir() ?? ""

            if let cachedArtist = try? getCachedArtist(appDataDir: appDataDir, artistId: artistId) {
                await MainActor.run {
                    artist = cachedArtist
                    isLoading = false
                }
            }

            // Load songs for this artist from cache first
            if let allSongs = try? loadCachedSongs(appDataDir: appDataDir) {
                let artistSongs = Self.songsForArtist(
                    from: allSongs,
                    artistId: artistId,
                    artistName: artistName
                )
                let albumItems = Self.albumItems(from: artistSongs, fallbackArtistName: artistName)

                await MainActor.run {
                    songs = artistSongs
                    albums = albumItems
                    isLoading = false
                }
            }

            // Fetch freshest artist metadata in background
            do {
                let fetched = try await fetchArtist(
                    serverUrl: creds.serverUrl,
                    token: creds.token,
                    userId: creds.userId,
                    artistId: artistId,
                    appDataDir: appDataDir
                )
                await MainActor.run {
                    artist = fetched
                    isLoading = false
                    error = nil
                }
            } catch {
                if await !AuthInterceptor.shared.handlePotentialAuthError(error) {
                    await MainActor.run {
                        isLoading = false
                        if songs.isEmpty, artist == nil {
                            self.error = error.localizedDescription
                        }
                    }
                }
            }
        }
    }

    private nonisolated static func songsForArtist(from allSongs: [Song], artistId: String, artistName: String)
        -> [Song]
    {
        let byId = allSongs.filter { song in
            guard let ids = song.artistIds else { return false }
            return ids.contains(where: { splitValues($0).contains(artistId) })
        }
        if !byId.isEmpty {
            return sortSongs(byId)
        }

        let normalizedArtistName = artistName.trimmingCharacters(in: .whitespacesAndNewlines)
        if normalizedArtistName.isEmpty {
            return []
        }

        let byName = allSongs.filter { song in
            (song.artists ?? []).contains(where: { rawValue in
                splitValues(rawValue).contains(where: {
                    $0.localizedCaseInsensitiveCompare(normalizedArtistName) == .orderedSame
                })
            })
        }
        return sortSongs(byName)
    }

    private nonisolated static func albumItems(from songs: [Song], fallbackArtistName: String) -> [AlbumItem] {
        let songsByAlbumId = songs.compactMap { song -> (String, Song)? in
            guard let albumId = song.albumId, !albumId.isEmpty else { return nil }
            return (albumId, song)
        }
        let grouped = Dictionary(grouping: songsByAlbumId, by: { $0.0 })

        return grouped.map { albumId, albumSongs in
            let first = albumSongs[0].1
            return AlbumItem(
                id: albumId,
                name: first.album ?? "Unknown Album",
                artist: first.artists?.first ?? fallbackArtistName,
                albumArtUrl: first.albumArtUrl,
                songCount: albumSongs.count
            )
        }
        .sorted { lhs, rhs in
            lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    private nonisolated static func splitValues(_ rawValue: String) -> [String] {
        rawValue
            .split(whereSeparator: { $0 == "\u{001F}" || $0 == "|" || $0 == ";" })
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private nonisolated static func sortSongs(_ songs: [Song]) -> [Song] {
        songs.sorted { lhs, rhs in
            let lhsAlbum = lhs.album ?? ""
            let rhsAlbum = rhs.album ?? ""
            if lhsAlbum != rhsAlbum {
                return lhsAlbum.localizedCaseInsensitiveCompare(rhsAlbum) == .orderedAscending
            }

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

    private func playAllSongs(startIndex: Int = 0, shuffled: Bool = false) {
        guard let serverUrl = SessionStore.shared.serverUrl,
            let token = SessionStore.shared.token,
            !songs.isEmpty
        else { return }
        let queue = shuffled ? songs.shuffled() : Array(songs)
        playerController.setQueue(queue, serverUrl: serverUrl, token: token, startIndex: shuffled ? 0 : startIndex)
    }
}

extension View {
    @ViewBuilder
    fileprivate func artistHeaderScrollEdge(hidden: Bool) -> some View {
        if #available(iOS 26, *) {
            scrollEdgeEffectHidden(hidden, for: .top)
        } else {
            self
        }
    }
}
