import AureliaCore
import Foundation
import Observation
import os

@Observable
final class PlayerViewModel: @unchecked Sendable {
    var title = ""
    var artist = ""
    var albumArtUrl: String?
    var isPlaying = false
    var isBuffering = false
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var positionUpdateTimeMs: Int64 = 0
    var queue: [Song] = []
    var currentQueueIndex: Int = -1
    var lyrics: Lyrics?
    var lyricsUnavailable = false
    var showLyrics = false
    var isShuffled = false
    var repeatMode: RepeatMode = .none
    var currentSongId: String?
    var currentAlbumId: String?
    var currentArtistId: String?
    var currentAlbumName: String?
    var isFavorite = false
    var isFavoriteLoading = false
    var playbackSpeed: Float = 1.0
    var codec: String?
    var bitRate: Int32?
    var sampleRate: Int32?

    var hasPrevious: Bool {
        currentQueueIndex > 0
    }

    var hasNext: Bool {
        currentQueueIndex >= 0 && currentQueueIndex < queue.count - 1
    }

    var formatInfo: String? {
        var parts: [String] = []
        if let codec {
            parts.append(codec.split(separator: "/").last.map(String.init)?.uppercased() ?? codec.uppercased())
        }
        if let sampleRate { parts.append("\(Double(sampleRate) / 1000.0) kHz") }
        if let bitRate {
            let bitRateBps: Int32 = (bitRate > 0 && bitRate < 10_000) ? (bitRate * 1_000) : bitRate
            parts.append("\(bitRateBps / 1000) kbps")
        }
        return parts.isEmpty ? nil : parts.joined(separator: " / ")
    }

    private let sessionStore = SessionStore.shared
    private let logger = Logger(subsystem: "com.aurelia.app", category: "PlayerViewModel")
    private var lastFetchedSongId: String?
    private var favoriteCache: [String: Bool] = [:]
    private var queueRevision: UInt64?

    /// Call this to sync state from the player controller.
    func updateFrom(_ snapshot: PlayerSnapshot, position: PlaybackPosition, playerController: AudioPlayerController) {
        let previousSongId = currentSongId
        let newSongId = snapshot.currentSongId
        let didChangeSong = newSongId != previousSongId
        let shouldRefreshQueue = queueRevision != snapshot.queueRevision

        if shouldRefreshQueue {
            let latestQueue = playerController.getQueue()
            queue = latestQueue
            queueRevision = snapshot.queueRevision

            // Update favorite cache from queue only when queue changes.
            for song in latestQueue where favoriteCache[song.id] == nil {
                favoriteCache[song.id] = song.isFavorite ?? false
            }
        }

        let latestQueueIndex = playerController.getCurrentQueueIndex()

        setIfChanged(\.title, snapshot.title)
        setIfChanged(\.artist, snapshot.artist)
        setIfChanged(\.albumArtUrl, snapshot.albumArtUrl)
        setIfChanged(\.isPlaying, snapshot.isPlaying)
        setIfChanged(\.isBuffering, snapshot.isBuffering)
        setIfChanged(\.positionMs, position.positionMs)
        setIfChanged(\.durationMs, position.durationMs)
        setIfChanged(\.positionUpdateTimeMs, position.updateTimeMs)
        setIfChanged(\.currentQueueIndex, latestQueueIndex)
        setIfChanged(\.isShuffled, snapshot.isShuffled)
        setIfChanged(\.repeatMode, snapshot.repeatMode)
        setIfChanged(\.currentSongId, newSongId)
        setIfChanged(\.currentAlbumId, snapshot.currentAlbumId)
        setIfChanged(\.currentArtistId, snapshot.currentArtistId)
        setIfChanged(\.currentAlbumName, snapshot.currentAlbumName)
        setIfChanged(
            \.isFavorite, newSongId.flatMap { LibraryStore.shared.favoriteState(for: $0) ?? favoriteCache[$0] } ?? false
        )
        setIfChanged(\.playbackSpeed, snapshot.playbackSpeed)
        setIfChanged(\.codec, snapshot.codec)
        setIfChanged(\.bitRate, snapshot.bitRate)
        setIfChanged(\.sampleRate, snapshot.sampleRate)

        // Fetch lyrics for new song
        if let newSongId, !newSongId.isEmpty, didChangeSong, newSongId != lastFetchedSongId {
            lastFetchedSongId = newSongId
            fetchLyrics(songId: newSongId, artist: snapshot.artist, title: snapshot.title)
        }
    }

    // MARK: - Playback Controls

    func togglePlayPause(playerController: AudioPlayerController) {
        if isPlaying {
            playerController.pause()
        } else {
            playerController.resume()
        }
    }

    func seekTo(_ positionMs: Int64, playerController: AudioPlayerController) {
        playerController.seekTo(positionMs: positionMs)
    }

    func skipNext(playerController: AudioPlayerController) {
        playerController.skipNext()
    }

    func skipPrevious(playerController: AudioPlayerController) {
        playerController.skipPrevious()
    }

    func playQueueItem(_ index: Int, playerController: AudioPlayerController) {
        playerController.playQueueItem(index)
    }

    func toggleShuffle(playerController: AudioPlayerController) {
        playerController.toggleShuffle()
    }

    func cycleRepeatMode(playerController: AudioPlayerController) {
        playerController.cycleRepeatMode()
    }

    // MARK: - Lyrics

    func toggleLyrics() {
        if lyrics == nil, !title.isEmpty {
            fetchLyrics(songId: currentSongId, artist: artist, title: title)
        }
        showLyrics.toggle()
    }

    private func fetchLyrics(songId: String?, artist: String, title: String) {
        lyrics = nil
        lyricsUnavailable = false

        Task { @MainActor [self] in
            let serverUrl = sessionStore.serverUrl ?? ""
            let token = sessionStore.token ?? ""
            let itemId = songId ?? ""

            logger.info(
                "[Lyrics] Fetching lyrics for '\(title)' by '\(artist)' (itemId=\(itemId), serverUrl=\(serverUrl.prefix(30))..., hasToken=\(!token.isEmpty))"
            )

            let parsedLyrics = await getParsedLyrics(
                serverUrl: serverUrl,
                token: token,
                itemId: itemId,
                artist: artist,
                title: title
            )

            logger.info(
                "[Lyrics] Got ParsedLyrics from core: syncedLines=\(parsedLyrics.synced.count), plainLines=\(parsedLyrics.plain.count), areFromRemote=\(parsedLyrics.areFromRemote), hasSections=\(parsedLyrics.sections != nil), hasAgents=\(parsedLyrics.agents != nil), hasSongwriters=\(parsedLyrics.songwriters != nil), language=\(parsedLyrics.language ?? "nil")"
            )

            // Log first few synced lines for debugging
            for (i, line) in parsedLyrics.synced.prefix(5).enumerated() {
                let wordCount = line.words?.count ?? 0
                logger.info(
                    "[Lyrics] synced[\(i)]: timeMs=\(line.timeMs), endTimeMs=\(line.endTimeMs.map { String($0) } ?? "nil"), words=\(wordCount), agentId=\(line.agentId ?? "nil"), text='\(line.line.prefix(60))'"
                )
                if let words = line.words {
                    for (j, word) in words.prefix(3).enumerated() {
                        logger.info(
                            "[Lyrics] word[\(j)]: timeMs=\(word.timeMs), endTimeMs=\(word.endTimeMs.map { String($0) } ?? "nil"), '\(word.word)'"
                        )
                    }
                    if words.count > 3 {
                        logger.info("[Lyrics] ... and \(words.count - 3) more words")
                    }
                }
            }
            if parsedLyrics.synced.count > 5 {
                logger.info("[Lyrics] ... and \(parsedLyrics.synced.count - 5) more synced lines")
            }

            let parsed = LyricsParser.fromParsed(parsedLyrics)
            let isValid = parsed.isValid
            let hasSynced = parsed.synced != nil
            let syncedCount = parsed.synced?.count ?? 0
            let hasPlain = parsed.plain != nil
            let hasWordSync = parsed.synced?.first?.words != nil
            logger.info(
                "[Lyrics] After LyricsParser: isValid=\(isValid), hasSynced=\(hasSynced), syncedCount=\(syncedCount), hasPlain=\(hasPlain), hasWordSync=\(hasWordSync)"
            )

            if songId == currentSongId {
                if isValid {
                    logger.info("[Lyrics] Setting lyrics on view model (valid)")
                    lyrics = parsed
                } else {
                    lyricsUnavailable = true
                }
            } else {
                logger.info(
                    "[Lyrics] Song changed during fetch (was=\(songId ?? "nil"), now=\(self.currentSongId ?? "nil")), discarding result"
                )
            }
        }
    }

    // MARK: - Favorites

    func toggleFavorite() {
        guard !isFavoriteLoading, let songId = currentSongId else { return }
        guard let serverUrl = sessionStore.serverUrl,
            let token = sessionStore.token,
            let userId = sessionStore.userId
        else { return }
        let profilePath = sessionStore.getAppDataDir()
        let targetFavoriteState = !isFavorite

        isFavoriteLoading = true

        Task.detached { [self, targetFavoriteState] in
            do {
                let newState = try await AureliaCore.toggleFavorite(
                    serverUrl: serverUrl,
                    token: token,
                    userId: userId,
                    itemId: songId,
                    isFavorite: targetFavoriteState
                )
                await MainActor.run {
                    guard self.sessionStore.getAppDataDir() == profilePath else {
                        self.isFavoriteLoading = false
                        return
                    }
                    self.favoriteCache[songId] = newState
                    LibraryStore.shared.updateFavorite(songId: songId, isFavorite: newState)
                    if self.currentSongId == songId { self.isFavorite = newState }
                    self.isFavoriteLoading = false
                }
            } catch {
                if await !AuthInterceptor.shared.handlePotentialAuthError(error) {
                    logger.error("Failed to toggle favorite: \(error)")
                    await MainActor.run { self.isFavoriteLoading = false }
                }
            }
        }
    }

    // MARK: - Scrobble

    private func setIfChanged<T: Equatable>(_ keyPath: ReferenceWritableKeyPath<PlayerViewModel, T>, _ value: T) {
        if self[keyPath: keyPath] != value {
            self[keyPath: keyPath] = value
        }
    }

}
