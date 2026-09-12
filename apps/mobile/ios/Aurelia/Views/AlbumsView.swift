import AureliaCore
import SwiftUI

struct AlbumsView: View {
    var isEmbedded = false
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var albums: [AlbumItem] = []
    @State private var isLoading = false
    @State private var error: String?

    private var isWide: Bool {
        horizontalSizeClass == .regular
    }

    var body: some View {
        OptionalNavigationStack(embedded: isEmbedded) {
            let minWidth: CGFloat = isWide ? 180 : 150
            let horizontalPadding: CGFloat = isWide ? AureliaSpacing.xl : AureliaSpacing.m
            let columns = [
                GridItem(.adaptive(minimum: minWidth), spacing: isWide ? 20 : 16),
            ]

            Group {
                if isLoading, albums.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let error, albums.isEmpty {
                    ContentUnavailableView("Failed to Load", systemImage: "exclamationmark.triangle", description: Text(error))
                } else if albums.isEmpty {
                    ContentUnavailableView("No Albums", systemImage: "square.stack", description: Text("Your library has no albums"))
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: isWide ? 22 : 16) {
                            ForEach(albums) { album in
                                NavigationLink(value: AlbumRoute(id: album.id, name: album.name)) {
                                    GlassCard(cornerRadius: AureliaRadius.m, padding: AureliaSpacing.s) {
                                        VStack(alignment: .leading, spacing: 6) {
                                            AlbumArtView(url: album.albumArtUrl, size: .medium, customDimension: minWidth)
                                            Text(album.name)
                                                .font(.caption)
                                                .lineLimit(1)
                                            Text(album.artist)
                                                .font(.caption2)
                                                .foregroundStyle(.secondary)
                                                .lineLimit(1)
                                        }
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, horizontalPadding)
                        .padding(.vertical, AureliaSpacing.l)
                    }
                }
            }
            .aureliaRootTabHeader("Albums")
            .navigationDestination(for: AlbumRoute.self) { route in
                AlbumDetailView(albumId: route.id, albumName: route.name)
            }
            .onAppear { loadAlbums() }
        }
        .aureliaScreen()
    }

    private func loadAlbums() {
        guard albums.isEmpty else { return }
        let sessionStore = SessionStore.shared
        guard let creds = sessionStore.getCredentials(),
              !creds.serverUrl.isEmpty else { return }

        isLoading = true

        Task.detached {
            let appDataDir = await sessionStore.getAppDataDir() ?? ""
            do {
                let songs = try loadCachedSongs(appDataDir: appDataDir)
                let songsByAlbumId = songs.compactMap { song -> (String, Song)? in
                    guard let albumId = song.albumId, !albumId.isEmpty else { return nil }
                    return (albumId, song)
                }
                let albumsMap = Dictionary(grouping: songsByAlbumId, by: { $0.0 })
                let albumItems = albumsMap.map { id, albumSongs -> AlbumItem in
                    let first = albumSongs[0].1
                    return AlbumItem(
                        id: id,
                        name: first.album ?? "Unknown Album",
                        artist: first.artists?.first ?? "Unknown Artist",
                        albumArtUrl: first.albumArtUrl,
                        songCount: albumSongs.count
                    )
                }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }

                await MainActor.run {
                    albums = albumItems
                    isLoading = false
                }
            } catch {
                await MainActor.run {
                    self.error = error.localizedDescription
                    isLoading = false
                }
            }
        }
    }
}
