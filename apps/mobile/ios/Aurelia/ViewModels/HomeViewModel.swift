import AureliaCore
import Combine
import Foundation
import Observation

struct HomeMix: Identifiable, Sendable {
    let id: String
    let title: String
    let artists: [String]
    let artwork: [String]
    let songs: [Song]
}

@MainActor
@Observable
final class HomeViewModel {
    private(set) var isLoading = false
    private(set) var error: String?
    private(set) var rediscover: [Song] = []
    private(set) var recentlyAddedAlbums: [AlbumItem] = []
    private(set) var topGenres: [String] = []
    private(set) var mixes: [HomeMix] = []
    private(set) var isLoadingMixes = false
    private(set) var allSongs: [Song] = []
    @ObservationIgnored private var subscription: AnyCancellable?
    @ObservationIgnored private var sectionsTask: Task<Void, Never>?
    @ObservationIgnored private var mixesTask: Task<Void, Never>?
    private var profile: String?
    private var revision = 0
    private var mixesLoaded = false

    func loadHomeData() {
        if subscription == nil {
            subscription = LibraryStore.shared.$snapshot.sink { [weak self] snapshot in
                self?.receive(snapshot)
            }
        }
        LibraryStore.shared.ensureLoaded()
    }
    func refresh() { LibraryStore.shared.ensureLoaded(force: true) }

    private func receive(_ snapshot: LibrarySnapshot) {
        isLoading = snapshot.isLoading
        error = snapshot.error
        if profile != snapshot.profilePath {
            profile = snapshot.profilePath
            sectionsTask?.cancel()
            mixesTask?.cancel()
            revision += 1
            mixes = []
            mixesLoaded = false
            isLoadingMixes = false
            allSongs = []
            rediscover = []
            recentlyAddedAlbums = []
            topGenres = []
        }
        guard let songs = snapshot.songs, songs != allSongs else { return }
        allSongs = songs
        revision += 1
        let generation = revision
        sectionsTask?.cancel()
        sectionsTask = Task {
            let data = await Task.detached(priority: .userInitiated) {
                deriveMobileHomeData(
                    songs: songs, mostPlayedLimit: 20, recentlyPlayedLimit: 20, albumSectionLimit: 12,
                    featuredAlbumsLimit: 5)
            }.value
            guard !Task.isCancelled, revision == generation else { return }
            let favorites = songs.filter { $0.isFavorite == true }.sorted {
                ($0.datePlayed ?? "") < ($1.datePlayed ?? "")
            }.prefix(12)
            var seen = Set<String>()
            rediscover = (Array(favorites) + data.mostPlayed + data.recentlyPlayed).filter {
                seen.insert($0.id).inserted
            }.prefix(24).map { $0 }
            recentlyAddedAlbums = data.recentlyAdded.compactMap {
                guard let id = $0.id, !id.isEmpty else { return nil }
                return AlbumItem(
                    id: id, name: $0.name, artist: $0.artist, albumArtUrl: $0.albumArtUrl, songCount: Int($0.songCount))
            }
            var counts: [String: Int] = [:]
            for song in songs { for genre in song.genres ?? [] where !genre.isEmpty { counts[genre, default: 0] += 1 } }
            topGenres = counts.keys.sorted { counts[$0] == counts[$1] ? $0 < $1 : counts[$0]! > counts[$1]! }.prefix(10)
                .map { $0 }
            loadMixes(songs)
        }
    }

    private func loadMixes(_ songs: [Song]) {
        guard !mixesLoaded, let session = SessionStore.shared.snapshot() else { return }
        let played = songs.filter { ($0.playCount ?? 0) > 0 }
        guard !played.isEmpty else { return }
        mixesLoaded = true
        isLoadingMixes = true
        var counts: [String: Int64] = [:]
        for song in played {
            for id in song.artistIds ?? [] where !id.isEmpty { counts[id, default: 0] += Int64(song.playCount ?? 0) }
        }
        var seeds = counts.keys.sorted { counts[$0] == counts[$1] ? $0 < $1 : counts[$0]! > counts[$1]! }.prefix(3).map
        { $0 }
        if let top = played.max(by: { ($0.playCount ?? 0) < ($1.playCount ?? 0) }), !seeds.contains(top.id),
            seeds.count < 3
        {
            seeds.append(top.id)
        }
        mixesTask = Task {
            var loaded: [HomeMix] = []
            for seed in seeds {
                do {
                    let queue = Array(
                        try await getInstantMix(
                            serverUrl: session.credentials.serverUrl, token: session.credentials.token, itemId: seed
                        ).prefix(50))
                    guard !Task.isCancelled, profile == session.appDataDir else { return }
                    if !queue.isEmpty { loaded.append(Self.describeMix(seed: seed, songs: queue, library: songs)) }
                } catch {
                    guard !Task.isCancelled, profile == session.appDataDir else { return }
                    if AuthInterceptor.shared.handlePotentialAuthError(error) { return }
                }
            }
            guard !Task.isCancelled, profile == session.appDataDir else { return }
            mixes = loaded
            isLoadingMixes = false
        }
    }

    private static func describeMix(seed: String, songs: [Song], library: [Song]) -> HomeMix {
        let byId = Dictionary(library.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var genres: [String: Int] = [:]
        var names: [String: String] = [:]
        var artists: [String] = []
        var artwork: [String] = []
        for song in songs {
            let fallback = byId[song.id]
            for genre in (song.genres?.isEmpty == false ? song.genres : fallback?.genres) ?? [] {
                let clean = genre.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !clean.isEmpty else { continue }
                genres[clean.lowercased(), default: 0] += 1
                names[clean.lowercased()] = clean
            }
            for artist in (song.artists?.isEmpty == false ? song.artists : fallback?.artists) ?? []
            where !artist.isEmpty {
                if !artists.contains(where: { $0.lowercased() == artist.lowercased() }) { artists.append(artist) }
            }
            if let art = song.albumArtUrl ?? fallback?.albumArtUrl, !art.isEmpty, !artwork.contains(art) {
                artwork.append(art)
            }
        }
        let genre = genres.keys.sorted { genres[$0] == genres[$1] ? $0 < $1 : genres[$0]! > genres[$1]! }.first
        let title =
            genre.flatMap {
                genres[$0, default: 0] * 2 >= songs.count ? names[$0].map { "\($0.capitalized) mix" } : nil
            } ?? "Discovery mix"
        return HomeMix(
            id: seed, title: title, artists: Array(artists.prefix(3)), artwork: Array(artwork.prefix(4)), songs: songs)
    }

    func play(_ songs: [Song], startingAt id: String? = nil, player: AudioPlayerController) {
        guard !songs.isEmpty, let session = SessionStore.shared.snapshot() else { return }
        let index = id.flatMap { id in songs.firstIndex { $0.id == id } } ?? 0
        player.setQueue(
            songs, serverUrl: session.credentials.serverUrl, token: session.credentials.token, startIndex: index)
    }

    func playGenre(_ genre: String, player: AudioPlayerController) {
        play(allSongs.filter { $0.genres?.contains(genre) == true }.shuffled(), player: player)
    }
}
