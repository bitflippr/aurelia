import AureliaCore
import SwiftUI

/// Resolve IDs by their original position so an unnamed collaborator cannot shift another artist's ID.
enum SongNavigation {
    static func artists(for song: Song) -> [ArtistRoute] {
        var seen = Set<String>()
        return (song.artistIds ?? []).enumerated().compactMap { index, id in
            guard !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                let names = song.artists, names.indices.contains(index),
                !names[index].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                seen.insert(id).inserted
            else { return nil }
            return ArtistRoute(id: id, name: names[index])
        }
    }
}

extension View {
    func songActions(_ song: Song, menu: Bool = false) -> some View {
        modifier(SongActionsModifier(song: song, menu: menu))
    }
}

private struct SongActionsModifier: ViewModifier {
    let song: Song
    var menu: Bool
    @Environment(AudioPlayerController.self) private var playerController
    @State private var destination: SongDestination?
    @State private var showPlaylists = false
    @State private var error: String?
    @State private var isUpdating = false
    @ObservedObject private var library = LibraryStore.shared

    private var isFavorite: Bool {
        library.favoriteState(for: song.id) ?? song.isFavorite ?? false
    }

    func body(content: Content) -> some View {
        trigger(content)
            .sheet(item: $destination) { route in
                NavigationStack {
                    Group {
                        switch route {
                        case .album(let id, let name): AlbumDetailView(albumId: id, albumName: name)
                        case .artist(let id, let name): ArtistDetailView(artistId: id, artistName: name)
                        }
                    }
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { destination = nil } } }
                }
            }
            .sheet(isPresented: $showPlaylists) { AddSongsToPlaylistView(songs: [song]) }
            .alert("Couldn't update song", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } }))
        {
            Button("OK") { error = nil }
        } message: {
            Text(error ?? "")
        }
    }

    @ViewBuilder
    private func trigger(_ content: Content) -> some View {
        if menu {
            Menu {
                actions
            } label: {
                content
            }
        } else {
            content.contextMenu { actions }
        }
    }

    @ViewBuilder
    private var actions: some View {
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") { enqueue(next: true) }
        Button("Add to Queue", systemImage: "text.badge.plus") { enqueue(next: false) }
        Button("Add to Playlist", systemImage: "music.note.list") { showPlaylists = true }
        Divider()
        if let id = song.albumId, !id.isEmpty {
            Button("Go to Album", systemImage: "square.stack") { destination = .album(id, song.album ?? "Album") }
        }
        let artists = SongNavigation.artists(for: song)
        if artists.count == 1, let artist = artists.first {
            Button("Go to Artist", systemImage: "person") { destination = .artist(artist.id, artist.name) }
        } else if !artists.isEmpty {
            Menu("Go to Artist", systemImage: "person.2") {
                ForEach(artists, id: \.id) { artist in
                    Button(artist.name) { destination = .artist(artist.id, artist.name) }
                }
            }
        }
        Button(
            isFavorite ? "Remove from Favorites" : "Add to Favorites", systemImage: isFavorite ? "heart.slash" : "heart"
        ) {
            toggleFavorite()
        }
        .disabled(isUpdating)
    }

    private func enqueue(next: Bool) {
        guard let session = SessionStore.shared.snapshot() else { return }
        if next {
            playerController.playNext(song, serverUrl: session.credentials.serverUrl, token: session.credentials.token)
        } else {
            playerController.addToQueue(
                song, serverUrl: session.credentials.serverUrl, token: session.credentials.token)
        }
    }

    private func toggleFavorite() {
        guard !isUpdating, let session = SessionStore.shared.snapshot() else { return }
        let target = !isFavorite
        isUpdating = true
        Task {
            defer { isUpdating = false }
            do {
                let value = try await AureliaCore.toggleFavorite(
                    serverUrl: session.credentials.serverUrl, token: session.credentials.token,
                    userId: session.credentials.userId, itemId: song.id, isFavorite: target)
                guard SessionStore.shared.getAppDataDir() == session.appDataDir else { return }
                LibraryStore.shared.updateFavorite(songId: song.id, isFavorite: value)
            } catch {
                if !AuthInterceptor.shared.handlePotentialAuthError(error) { self.error = error.localizedDescription }
            }
        }
    }
}

private enum SongDestination: Identifiable {
    case album(String, String)
    case artist(String, String)
    var id: String {
        switch self {
        case .album(let id, _): "album:\(id)"
        case .artist(let id, _): "artist:\(id)"
        }
    }
}

struct AddSongsToPlaylistView: View {
    let songs: [Song]
    @Environment(\.dismiss) private var dismiss
    @State private var playlists: [Playlist] = []
    @State private var loading = true
    @State private var saving = false
    @State private var error: String?
    @State private var name = ""

    var body: some View {
        NavigationStack {
            List {
                Section("New playlist") {
                    TextField("Playlist name", text: $name)
                    Button(songs.count == 1 ? "Create and Add Song" : "Create and Add Songs") { save(to: nil) }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                Section("Your playlists") {
                    if loading {
                        ProgressView()
                    } else if playlists.isEmpty {
                        Text("No playlists yet").foregroundStyle(.secondary)
                    }
                    ForEach(playlists, id: \.id) { playlist in
                        Button(playlist.name) { save(to: playlist.id) }
                    }
                }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }
            .disabled(saving)
            .overlay { if saving { ProgressView(songs.count == 1 ? "Adding song" : "Adding songs") } }
            .navigationTitle("Add to Playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .task {
                defer { loading = false }
                guard let session = SessionStore.shared.snapshot() else { return }
                do {
                    playlists = try await getPlaylists(
                        serverUrl: session.credentials.serverUrl, token: session.credentials.token,
                        userId: session.credentials.userId)
                } catch { self.error = error.localizedDescription }
            }
        }
    }

    private func save(to playlistId: String?) {
        guard let session = SessionStore.shared.snapshot() else { return }
        saving = true
        error = nil
        Task {
            defer { saving = false }
            do {
                if let playlistId {
                    try await addPlaylistItems(
                        serverUrl: session.credentials.serverUrl, token: session.credentials.token,
                        playlistId: playlistId, itemIds: songs.map(\.id))
                } else {
                    _ = try await createPlaylist(
                        serverUrl: session.credentials.serverUrl, token: session.credentials.token,
                        data: PlaylistCreateData(
                            name: name.trimmingCharacters(in: .whitespacesAndNewlines), ids: songs.map(\.id),
                            userId: session.credentials.userId, isPublic: false))
                }
                dismiss()
            } catch { self.error = error.localizedDescription }
        }
    }
}
