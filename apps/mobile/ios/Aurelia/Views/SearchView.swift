import AureliaCore
import SwiftUI

struct SearchView: View {
    @Environment(AudioPlayerController.self) private var playerController
    @State private var viewModel = SearchViewModel()
    @State private var filter: SearchFilter = .all

    private var visibleResults: [LibrarySearchHit] {
        viewModel.results.filter { filter.includes($0.kind) }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Search results", selection: $filter) {
                    ForEach(SearchFilter.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.bottom, 8)

                Group {
                    if viewModel.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        ContentUnavailableView(
                            "Search your library", systemImage: "magnifyingglass",
                            description: Text("Find songs, albums, and artists."))
                    } else if viewModel.isSearching || (viewModel.isLoading && viewModel.songsById.isEmpty) {
                        ProgressView("Searching your library")
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if let error = viewModel.error, viewModel.songsById.isEmpty {
                        ContentUnavailableView {
                            Label("Couldn't load your library", systemImage: "exclamationmark.triangle")
                        } description: {
                            Text(error)
                        } actions: {
                            Button("Retry") { viewModel.load() }
                        }
                    } else if visibleResults.isEmpty {
                        ContentUnavailableView.search(text: viewModel.query)
                    } else {
                        List {
                            if filter == .all, let top = visibleResults.first {
                                Section("Top result") { resultRow(top) }
                            }
                            ForEach(SearchFilter.categories) { category in
                                let hits = visibleResults.filter { category.includes($0.kind) }
                                if !hits.isEmpty {
                                    Section(category.rawValue) {
                                        ForEach(hits, id: \.self) { resultRow($0) }
                                    }
                                }
                            }
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                        .scrollDismissesKeyboard(.interactively)
                    }
                }
            }
            .aureliaRootTabHeader("Search")
            .searchable(text: $viewModel.query, prompt: "Songs, albums, artists")
            .navigationDestination(for: AlbumRoute.self) { AlbumDetailView(albumId: $0.id, albumName: $0.name) }
            .navigationDestination(for: ArtistRoute.self) { ArtistDetailView(artistId: $0.id, artistName: $0.name) }
            .onAppear { viewModel.load() }
        }
        .aureliaScreen()
    }

    @ViewBuilder
    private func resultRow(_ hit: LibrarySearchHit) -> some View {
        Group {
            switch hit.kind {
            case .song:
                if let song = viewModel.songsById[hit.id] {
                    SongRow(song: song, isPlaying: song.id == playerController.snapshot.currentSongId) {
                        let songs = viewModel.results.filter { $0.kind == .song }.compactMap {
                            viewModel.songsById[$0.id]
                        }
                        guard let session = SessionStore.shared.snapshot(),
                            let index = songs.firstIndex(where: { $0.id == song.id })
                        else { return }
                        playerController.setQueue(
                            songs, serverUrl: session.credentials.serverUrl, token: session.credentials.token,
                            startIndex: index)
                    }
                }
            case .album:
                NavigationLink(value: AlbumRoute(id: hit.id, name: hit.name)) { entityRow(hit, isArtist: false) }
            case .artist:
                NavigationLink(value: ArtistRoute(id: hit.id, name: hit.name)) { entityRow(hit, isArtist: true) }
            }
        }
        .listRowBackground(Color.clear)
    }

    private func entityRow(_ hit: LibrarySearchHit, isArtist: Bool) -> some View {
        HStack(spacing: 12) {
            AlbumArtView(url: hit.artworkUrl, size: .small)
                .clipShape(RoundedRectangle(cornerRadius: isArtist ? 28 : 8))
            VStack(alignment: .leading, spacing: 4) {
                Text(hit.name).lineLimit(1)
                Text(isArtist ? "Artist · \(hit.songCount) songs" : hit.artistNames.joined(separator: ", "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.vertical, 6)
    }
}

private enum SearchFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case songs = "Songs"
    case albums = "Albums"
    case artists = "Artists"
    var id: Self { self }
    static let categories: [Self] = [.songs, .albums, .artists]
    func includes(_ kind: LibrarySearchKind) -> Bool {
        switch self {
        case .all: true
        case .songs: kind == .song
        case .albums: kind == .album
        case .artists: kind == .artist
        }
    }
}
