import AureliaCore
import SwiftUI

struct LibraryView: View {
    var initialFavoritesOnly = false
    var showsCategories = true
    @Environment(AudioPlayerController.self) private var playerController
    @State private var viewModel = LibraryViewModel()
    @State private var initialized = false
    private var songs: [Song] { viewModel.visibleSongs }

    var body: some View {
        NavigationStack {
            List {
                if showsCategories { Section {
                    NavigationLink(value: LibrarySection.albums) {
                        Label("Albums", systemImage: "square.stack")
                    }
                    NavigationLink(value: LibrarySection.artists) {
                        Label("Artists", systemImage: "music.mic")
                    }
                    NavigationLink(value: LibrarySection.playlists) {
                        Label("Playlists", systemImage: "music.note.list")
                    }
                } }
                Section {
                    Toggle(isOn: $viewModel.favoritesOnly) { Label("Favorites", systemImage: "heart") }
                    HStack {
                        Button {
                            play(songs.shuffled())
                        } label: {
                            Label("Shuffle", systemImage: "shuffle")
                        }
                        .disabled(songs.isEmpty)
                        Spacer()
                        Text("\(songs.count) songs").font(.caption).foregroundStyle(.secondary)
                        Menu {
                            Picker("Sort songs", selection: $viewModel.sort) {
                                ForEach(LibrarySort.allCases) { Text($0.rawValue).tag($0) }
                            }
                        } label: {
                            Label("Sort", systemImage: "arrow.up.arrow.down").labelStyle(.iconOnly)
                        }
                    }
                }
                Section("Songs") {
                    if viewModel.isLoading, viewModel.songs.isEmpty {
                        ProgressView("Loading your library")
                    } else if let error = viewModel.error, viewModel.songs.isEmpty {
                        Text(error).foregroundStyle(.secondary)
                        Button("Retry") { viewModel.loadLibrary() }
                    } else if songs.isEmpty {
                        Text(viewModel.favoritesOnly ? "No favorite songs yet" : "No matching songs").foregroundStyle(
                            .secondary)
                    } else {
                        ForEach(songs, id: \.id) { song in
                            SongRow(song: song, isPlaying: song.id == playerController.snapshot.currentSongId) {
                                play(songs, id: song.id)
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .aureliaRootTabHeader(initialFavoritesOnly ? "Favorites" : "Library")
            .searchable(text: $viewModel.searchText, prompt: "Filter songs")
            .navigationDestination(for: LibrarySection.self) { section in
                switch section {
                case .albums: AlbumsView(isEmbedded: true)
                case .artists: ArtistsView(isEmbedded: true)
                case .playlists: PlaylistsView(isEmbedded: true)
                }
            }
            .refreshable { LibraryStore.shared.ensureLoaded(force: true) }
            .onAppear {
                if !initialized {
                    initialized = true
                    viewModel.favoritesOnly = initialFavoritesOnly
                }
                viewModel.loadLibrary()
            }
        }
        .aureliaScreen()
    }

    private func play(_ songs: [Song], id: String? = nil) {
        guard !songs.isEmpty, let session = SessionStore.shared.snapshot() else { return }
        let index = id.flatMap { id in songs.firstIndex { $0.id == id } } ?? 0
        playerController.setQueue(
            songs, serverUrl: session.credentials.serverUrl, token: session.credentials.token, startIndex: index)
    }
}

private enum LibrarySection: Hashable {
    case albums, artists, playlists
}
