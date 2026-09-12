import AureliaCore
import SwiftUI

struct ArtistsView: View {
    var isEmbedded = false
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var artists: [(id: String, name: String, artUrl: String?, songCount: Int)] = []
    @State private var isLoading = false
    @State private var error: String?

    private var isWide: Bool {
        horizontalSizeClass == .regular
    }

    var body: some View {
        OptionalNavigationStack(embedded: isEmbedded) {
            let columns = [
                GridItem(.adaptive(minimum: isWide ? 200 : 160), spacing: AureliaSpacing.m),
            ]

            Group {
                if isLoading, artists.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let error, artists.isEmpty {
                    ContentUnavailableView("Failed to Load", systemImage: "exclamationmark.triangle", description: Text(error))
                } else if artists.isEmpty {
                    ContentUnavailableView("No Artists", systemImage: "music.mic", description: Text("Your library has no artists"))
                } else {
                    if isWide {
                        ScrollView {
                            LazyVGrid(columns: columns, spacing: AureliaSpacing.m) {
                                ForEach(artists, id: \.id) { artist in
                                    NavigationLink(value: ArtistRoute(id: artist.id, name: artist.name)) {
                                        GlassCard(cornerRadius: AureliaRadius.m, padding: AureliaSpacing.m, showsShadow: false) {
                                            VStack(alignment: .leading, spacing: 10) {
                                                HStack(spacing: 12) {
                                                    CachedImageView(
                                                        url: artist.artUrl.flatMap { URL(string: $0) },
                                                        contentMode: .fill,
                                                        targetSize: CGSize(width: 56, height: 56)
                                                    )
                                                    .frame(width: 56, height: 56)
                                                    .clipShape(Circle())

                                                    VStack(alignment: .leading, spacing: 4) {
                                                        Text(artist.name)
                                                            .font(.body)
                                                            .lineLimit(1)
                                                        Text("\(artist.songCount) songs")
                                                            .font(.caption)
                                                            .foregroundStyle(.secondary)
                                                    }
                                                    Spacer()
                                                }
                                            }
                                        }
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, AureliaSpacing.xl)
                            .padding(.vertical, AureliaSpacing.l)
                        }
                    } else {
                        List(artists, id: \.id) { artist in
                            GlassCard(cornerRadius: AureliaRadius.m, padding: AureliaSpacing.s, showsShadow: false) {
                                NavigationLink(value: ArtistRoute(id: artist.id, name: artist.name)) {
                                    HStack(spacing: 12) {
                                        CachedImageView(
                                            url: artist.artUrl.flatMap { URL(string: $0) },
                                            contentMode: .fill,
                                            targetSize: CGSize(width: 48, height: 48)
                                        )
                                        .frame(width: 48, height: 48)
                                        .clipShape(Circle())

                                        VStack(alignment: .leading) {
                                            Text(artist.name)
                                                .font(.body)
                                            Text("\(artist.songCount) songs")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                        Spacer()
                                    }
                                }
                            }
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets(top: 6, leading: AureliaSpacing.m, bottom: 6, trailing: AureliaSpacing.m))
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                    }
                }
            }
            .aureliaRootTabHeader("Artists")
            .navigationDestination(for: ArtistRoute.self) { route in
                ArtistDetailView(artistId: route.id, artistName: route.name)
            }
            .onAppear { loadArtists() }
        }
        .aureliaScreen()
    }

    private func loadArtists() {
        guard artists.isEmpty else { return }
        let sessionStore = SessionStore.shared

        isLoading = true

        Task.detached {
            let appDataDir = await sessionStore.getAppDataDir() ?? ""
            do {
                let songs = try loadCachedSongs(appDataDir: appDataDir)
                var artistMap: [String: (name: String, artUrl: String?, count: Int)] = [:]
                for song in songs {
                    var seen = Set<String>()
                    for (artistId, name) in zip(song.artistIds ?? [], song.artists ?? []) {
                        guard !artistId.isEmpty, !name.isEmpty, seen.insert(artistId).inserted else { continue }
                        if var existing = artistMap[artistId] {
                            existing.count += 1
                            artistMap[artistId] = existing
                        } else {
                            artistMap[artistId] = (name: name, artUrl: song.albumArtUrl, count: 1)
                        }
                    }
                }
                let sorted = artistMap.map { (id: $0.key, name: $0.value.name, artUrl: $0.value.artUrl, songCount: $0.value.count) }
                    .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }

                await MainActor.run {
                    artists = sorted
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
