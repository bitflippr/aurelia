import AureliaCore
import Combine
import Foundation
import Observation
import os

@MainActor
@Observable
final class HomeViewModel {
    var isLoading = false
    var error: String?
    var featuredAlbums: [FeaturedAlbum] = []
    var currentFeaturedIndex = 0
    var mostPlayed: [Song] = []
    var recentlyPlayed: [Song] = []
    var recentlyAddedAlbums: [AlbumItem] = []
    var randomAlbums: [AlbumItem] = []
    var nowPlaying: NowPlayingState?
    var currentSongId: String?

    private let sessionStore = SessionStore.shared
    private let logger = Logger(subsystem: "com.aurelia.app", category: "HomeViewModel")
    private var allSongs: [Song] = []
    @ObservationIgnored private var librarySubscription: AnyCancellable?
    private var libraryProfile: String?

    private struct HomeSections {
        let mostPlayed: [Song]
        let recentlyPlayed: [Song]
        let recentlyAddedAlbums: [AlbumItem]
        let randomAlbums: [AlbumItem]
        let featuredAlbums: [FeaturedAlbum]
    }

    private struct HomeSectionLimits: Sendable {
        let mostPlayed: Int
        let recentlyPlayed: Int
        let albumSection: Int
        let featuredAlbums: Int
    }

    init() {
        librarySubscription = LibraryStore.shared.$snapshot.sink { [weak self] snapshot in
            guard let self else { return }
            if libraryProfile != snapshot.profilePath {
                libraryProfile = snapshot.profilePath
                allSongs = []
                currentFeaturedIndex = 0
                applyHomeSections(Self.computeHomeSections([], limits: sectionLimits))
            }
            if let songs = snapshot.songs, songs != allSongs {
                allSongs = songs
                applyHomeSections(Self.computeHomeSections(songs, limits: sectionLimits))
            }
            isLoading = snapshot.isLoading
            error = snapshot.error
        }
    }

    private var sectionLimits: HomeSectionLimits {
        HomeSectionLimits(mostPlayed: UIConstants.mostPlayedLimit, recentlyPlayed: UIConstants.recentlyPlayedLimit, albumSection: UIConstants.albumSectionLimit, featuredAlbums: UIConstants.featuredAlbumsLimit)
    }

    func loadHomeData() { LibraryStore.shared.ensureLoaded() }

    @MainActor
    private func applyHomeSections(_ sections: HomeSections) {
        mostPlayed = sections.mostPlayed
        recentlyPlayed = sections.recentlyPlayed
        recentlyAddedAlbums = sections.recentlyAddedAlbums
        randomAlbums = sections.randomAlbums
        featuredAlbums = sections.featuredAlbums
        isLoading = false
    }

    private nonisolated static func computeHomeSections(_ songs: [Song], limits: HomeSectionLimits) -> HomeSections {
        let derived = deriveMobileHomeData(
            songs: songs,
            mostPlayedLimit: Int64(limits.mostPlayed),
            recentlyPlayedLimit: Int64(limits.recentlyPlayed),
            albumSectionLimit: Int64(limits.albumSection),
            featuredAlbumsLimit: Int64(limits.featuredAlbums)
        )

        return HomeSections(
            mostPlayed: derived.mostPlayed,
            recentlyPlayed: derived.recentlyPlayed,
            recentlyAddedAlbums: derived.recentlyAdded.map {
                AlbumItem(
                    id: $0.id ?? "",
                    name: $0.name,
                    artist: $0.artist,
                    albumArtUrl: $0.albumArtUrl,
                    songCount: Int($0.songCount)
                )
            },
            randomAlbums: derived.randomAlbums.map {
                AlbumItem(
                    id: $0.id ?? "",
                    name: $0.name,
                    artist: $0.artist,
                    albumArtUrl: $0.albumArtUrl,
                    songCount: Int($0.songCount)
                )
            },
            featuredAlbums: derived.featuredAlbums.map {
                FeaturedAlbum(
                    id: $0.id ?? "",
                    name: $0.name,
                    artist: $0.artist,
                    albumArtUrl: $0.albumArtUrl,
                    songCount: Int($0.songCount)
                )
            }
        )
    }

    // MARK: - Playback

    func playSongFromList(_ songId: String, songList: [Song], playerController: AudioPlayerController) {
        guard let serverUrl = sessionStore.serverUrl, let token = sessionStore.token else { return }
        guard let startIndex = songList.firstIndex(where: { $0.id == songId }) else { return }
        currentSongId = songId
        playerController.setQueue(songList, serverUrl: serverUrl, token: token, startIndex: startIndex)
    }

    func playAlbum(_ albumId: String, playerController: AudioPlayerController) {
        guard let serverUrl = sessionStore.serverUrl, let token = sessionStore.token else { return }
        let albumSongs = allSongs
            .filter { $0.albumId == albumId }
            .sorted { ($0.trackNumber ?? 0) < ($1.trackNumber ?? 0) }
        guard !albumSongs.isEmpty else { return }
        currentSongId = albumSongs[0].id
        playerController.setQueue(albumSongs, serverUrl: serverUrl, token: token)
    }

    func shuffleAlbum(_ albumId: String, playerController: AudioPlayerController) {
        guard let serverUrl = sessionStore.serverUrl, let token = sessionStore.token else { return }
        let albumSongs = allSongs.filter { $0.albumId == albumId }.shuffled()
        guard !albumSongs.isEmpty else { return }
        currentSongId = albumSongs[0].id
        playerController.setQueue(albumSongs, serverUrl: serverUrl, token: token)
    }

    // MARK: - Featured Navigation

    func nextFeaturedAlbum() {
        guard !featuredAlbums.isEmpty else { return }
        currentFeaturedIndex = (currentFeaturedIndex + 1) % featuredAlbums.count
    }

    func previousFeaturedAlbum() {
        guard !featuredAlbums.isEmpty else { return }
        currentFeaturedIndex = currentFeaturedIndex > 0 ? currentFeaturedIndex - 1 : featuredAlbums.count - 1
    }

}
