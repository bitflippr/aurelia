import Accelerate
import AureliaCore
import AVFAudio
import AVFoundation
import CoreMedia
import MediaPlayer
import MediaToolbox
import Observation
import os
import UIKit

/// iOS audio player using AVQueuePlayer with Now Playing integration.
/// Equivalent to Android's PlayerController + PlaybackService.
@Observable
final class AudioPlayerController: @unchecked Sendable {
    // MARK: - Published State

    private(set) var snapshot = PlayerSnapshot()
    private(set) var playbackPosition = PlaybackPosition()
    private(set) var visualizerState = VisualizerState()
    private(set) var isReady = false

    // MARK: - Private State

    private var player: AVQueuePlayer
    private var songQueue: [Song] = [] {
        didSet { snapshot.queueRevision &+= 1 }
    }
    private var queueBeforeShuffle: [Song]?
    private var currentIndex: Int = -1
    private var lastServerUrl: String = ""
    private var lastToken: String = ""
    private var playSessionIdsByItemId: [String: String] = [:]
    private var loadedQueueRange: ClosedRange<Int>?
    private var timeObserver: Any?
    private var audioSessionConfigured = false
    private var visualizerDisplayLink: CADisplayLink?
    private var visualizerDisplayLinkProxy: VisualizerDisplayLinkProxy?
    private var visualizerTargetFrequency: [UInt8] = []
    private var visualizerTargetWaveform: [UInt8] = []
    private let sessionStore = SessionStore.shared
    private let playbackReporting = PlaybackReporting()
    private let visualizerAnalyzer = PlayerAudioTapAnalyzer()
    private let logger = Logger(subsystem: "com.aurelia.app", category: "AudioPlayer")

    // Keep queue depth conservative to avoid overloading providers with parallel transcodes.
    private static let maxPreloadedItems = 3
    private static let initialPreloadedItems = 1

    init() {
        player = AVQueuePlayer()
        player.allowsExternalPlayback = false
        // Configure for background playback
        player.automaticallyWaitsToMinimizeStalling = true
        configureAudioSession()
        setupRemoteTransportControls()
        setupNotifications()
        setupTimeObserver()
        configureVisualizerFromStoredSettings()
        setupVisualizerDisplayLink()
        visualizerAnalyzer.onFrame = { [weak self] frame in
            guard let self else { return }
            applyVisualizerFrame(frame)
        }
        // Begin receiving remote control events early for Control Center integration
        UIApplication.shared.beginReceivingRemoteControlEvents()
    }

    @MainActor
    deinit {
        if let observer = timeObserver {
            player.removeTimeObserver(observer)
        }
        visualizerDisplayLink?.invalidate()
        visualizerDisplayLink = nil
        visualizerDisplayLinkProxy = nil
        visualizerAnalyzer.onFrame = nil
        player.pause()
        player.removeAllItems()
    }

    // MARK: - Audio Session

    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
            audioSessionConfigured = true
            logger.info("Audio session configured successfully")
        } catch {
            logger.error("Failed to configure audio session: \(error)")
            audioSessionConfigured = false
        }
    }

    private func ensureAudioSession() {
        guard !audioSessionConfigured else { return }
        configureAudioSession()
    }

    // MARK: - Visualizer

    func setVisualizerEnabled(_ enabled: Bool) {
        sessionStore.visualizerEnabled = enabled
        if visualizerState.enabled != enabled {
            visualizerState.enabled = enabled
        }
        visualizerAnalyzer.setEnabled(enabled)
        if !enabled {
            clearVisualizerData(keepAvailability: true)
        }
    }

    func setVisualizerStyle(_ style: VisualizerStyle) {
        sessionStore.visualizerStyle = style.rawValue
        if visualizerState.style != style {
            visualizerState.style = style
        }
    }

    func refreshVisualizerSettings() {
        let enabled = sessionStore.visualizerEnabled
        let style = VisualizerStyle(rawValue: sessionStore.visualizerStyle) ?? .bars

        if visualizerState.enabled != enabled {
            visualizerState.enabled = enabled
        }
        if visualizerState.style != style {
            visualizerState.style = style
        }

        visualizerAnalyzer.setEnabled(enabled)
        if !enabled {
            clearVisualizerData(keepAvailability: true)
        }
    }

    private func configureVisualizerFromStoredSettings() {
        visualizerState = VisualizerState(
            enabled: sessionStore.visualizerEnabled,
            available: false,
            style: VisualizerStyle(rawValue: sessionStore.visualizerStyle) ?? .bars,
            frequencyData: [],
            waveformData: [],
            frameId: 0
        )
        visualizerAnalyzer.setEnabled(visualizerState.enabled)
    }

    private func setupVisualizerDisplayLink() {
        let proxy = VisualizerDisplayLinkProxy { [weak self] in
            self?.handleVisualizerDisplayTick()
        }
        let displayLink = CADisplayLink(target: proxy, selector: #selector(VisualizerDisplayLinkProxy.tick))
        displayLink.add(to: .main, forMode: .common)
        displayLink.isPaused = true
        visualizerDisplayLinkProxy = proxy
        visualizerDisplayLink = displayLink
    }

    private func updateVisualizerDisplayLinkState() {
        let hasData = !visualizerTargetFrequency.isEmpty || !visualizerState.frequencyData.isEmpty
        let shouldRun = visualizerState.enabled && snapshot.isPlaying && hasData
        visualizerDisplayLink?.isPaused = !shouldRun
    }

    private func handleVisualizerDisplayTick() {
        guard visualizerState.enabled else { return }
        guard !visualizerTargetFrequency.isEmpty || !visualizerTargetWaveform.isEmpty else { return }

        var didUpdate = false

        if !visualizerTargetFrequency.isEmpty {
            if visualizerState.frequencyData.count != visualizerTargetFrequency.count {
                visualizerState.frequencyData = visualizerTargetFrequency
                didUpdate = true
            } else {
                var next = visualizerState.frequencyData
                var didChange = false
                for i in next.indices {
                    let blended = blendByteValue(
                        current: next[i],
                        target: visualizerTargetFrequency[i],
                        rise: 0.62,
                        fall: 0.36
                    )
                    if blended != next[i] {
                        next[i] = blended
                        didChange = true
                    }
                }
                if didChange {
                    visualizerState.frequencyData = next
                    didUpdate = true
                }
            }
        }

        if !visualizerTargetWaveform.isEmpty {
            if visualizerState.waveformData.count != visualizerTargetWaveform.count {
                visualizerState.waveformData = visualizerTargetWaveform
                didUpdate = true
            } else {
                var next = visualizerState.waveformData
                var didChange = false
                for i in next.indices {
                    let blended = blendByteValue(
                        current: next[i],
                        target: visualizerTargetWaveform[i],
                        rise: 0.55,
                        fall: 0.32
                    )
                    if blended != next[i] {
                        next[i] = blended
                        didChange = true
                    }
                }
                if didChange {
                    visualizerState.waveformData = next
                    didUpdate = true
                }
            }
        }

        if didUpdate {
            visualizerState.frameId += 1
            if !visualizerState.available {
                visualizerState.available = true
            }
        }
    }

    private func blendByteValue(current: UInt8, target: UInt8, rise: Float, fall: Float) -> UInt8 {
        if current == target { return current }
        let currentFloat = Float(current)
        let targetFloat = Float(target)
        let rate = targetFloat > currentFloat ? rise : fall
        let updated = currentFloat + (targetFloat - currentFloat) * rate
        let quantized = Int(updated.rounded())
        return UInt8(max(0, min(255, quantized)))
    }

    private func applyVisualizerFrame(_ frame: PlayerAudioTapAnalyzer.FrameData) {
        guard visualizerState.enabled else { return }

        visualizerTargetFrequency = frame.frequencyData
        visualizerTargetWaveform = frame.waveformData
        if visualizerState.frequencyData.isEmpty {
            visualizerState.frequencyData = frame.frequencyData
        }
        if visualizerState.waveformData.isEmpty {
            visualizerState.waveformData = frame.waveformData
        }
        visualizerState.frameId = frame.frameId
        if !visualizerState.available {
            visualizerState.available = true
        }
        updateVisualizerDisplayLinkState()
    }

    private func clearVisualizerData(keepAvailability: Bool) {
        visualizerTargetFrequency.removeAll(keepingCapacity: true)
        visualizerTargetWaveform.removeAll(keepingCapacity: true)
        visualizerState.frequencyData = []
        visualizerState.waveformData = []
        visualizerState.frameId = 0
        if !keepAvailability {
            visualizerState.available = false
        }
        updateVisualizerDisplayLinkState()
    }

    // MARK: - Remote Controls (Lock Screen / Control Center)

    private func setupRemoteTransportControls() {
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.addTarget { [weak self] _ in
            self?.resume()
            return .success
        }

        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }

        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            self?.skipNext()
            return .success
        }

        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            self?.skipPrevious()
            return .success
        }

        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seekTo(positionMs: Int64(event.positionTime * 1000))
            return .success
        }

        commandCenter.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            if snapshot.isPlaying {
                pause()
            } else {
                resume()
            }
            return .success
        }
    }

    // MARK: - Notifications

    private func setupNotifications() {
        NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self else { return }
            // Extract Sendable values before entering isolated context
            let notificationObject = notification.object as? AVPlayerItem
            Task { @MainActor in
                guard let item = notificationObject else { return }
                guard self.shouldHandleTrackEndedNotification(for: item) else { return }
                self.handleTrackEnded()
            }
        }

        // Handle audio session interruptions
        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self else { return }
            // Extract Sendable values before entering isolated context
            let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let optionsValue = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt
            Task { @MainActor in
                self.handleAudioSessionInterruption(typeValue: typeValue, optionsValue: optionsValue)
            }
        }

        // Handle app lifecycle
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                self.logger.info("App entered background, ensuring audio session stays active")
                self.ensureAudioSessionActive()
            }
        }

        NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in
                self.logger.info("App will enter foreground")
                self.ensureAudioSessionActive()
                self.refreshVisualizerSettings()
            }
        }
    }

    private func handleAudioSessionInterruption(typeValue: UInt?, optionsValue: UInt?) {
        guard let typeValue,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue)
        else {
            return
        }

        switch type {
        case .began:
            logger.info("Audio session interruption began")
        // Interruption began, audio will pause automatically
        case .ended:
            logger.info("Audio session interruption ended")
            if let optionsValue {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume), snapshot.isPlaying {
                    logger.info("Resuming playback after interruption")
                    resume()
                }
            }
        @unknown default:
            break
        }
    }

    private func ensureAudioSessionActive() {
        do {
            let session = AVAudioSession.sharedInstance()
            if !session.isOtherAudioPlaying {
                try session.setActive(true)
                logger.info("Audio session reactivated")
            }
        } catch {
            logger.error("Failed to reactivate audio session: \(error)")
        }
    }

    // MARK: - Time Observer

    private func setupTimeObserver() {
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.updateSnapshot()
            }
        }

        // Observe rate changes to detect unexpected pauses
        _ = player.observe(\.rate, options: [.new]) { [weak self] _, change in
            MainActor.assumeIsolated {
                guard let self else { return }
                let newRate = change.newValue ?? 0
                self.logger.info("Player rate changed to: \(newRate)")
            }
        }
    }

    // MARK: - Queue Management

    func setQueue(_ songs: [Song], serverUrl: String, token: String, startIndex: Int = 0, autoPlay: Bool = true) {
        playbackReporting.finish(positionMs: playbackPosition.positionMs)
        ensureAudioSession()
        songQueue = songs
        playSessionIdsByItemId.removeAll(keepingCapacity: true)
        queueBeforeShuffle = snapshot.isShuffled ? songs : nil
        lastServerUrl = serverUrl
        lastToken = token

        guard let safeStart = normalizedStartIndex(startIndex, queueCount: songs.count) else {
            player.removeAllItems()
            loadedQueueRange = nil
            currentIndex = -1
            clearVisualizerData(keepAvailability: false)
            updateSnapshot()
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }

        rebuildPlayerQueue(startingAt: safeStart, preloadItemCount: Self.initialPreloadedItems)
        if snapshot.isShuffled {
            shuffleUpcomingQueueInPlace()
        }

        if autoPlay {
            player.play()
        } else {
            player.pause()
        }

        scheduleDeferredPreload()
        updateSnapshot()
        updateNowPlayingInfo()
    }

    func getQueue() -> [Song] {
        songQueue
    }

    func getCurrentQueueIndex() -> Int {
        currentIndex
    }

    func addToQueue(_ song: Song, serverUrl: String, token: String) {
        guard !songQueue.isEmpty, currentIndex >= 0 else {
            setQueue([song], serverUrl: serverUrl, token: token, autoPlay: false)
            return
        }
        lastServerUrl = serverUrl
        lastToken = token
        songQueue.append(song)
        queueBeforeShuffle?.append(song)

        if loadedQueueRange != nil {
            preloadUpcomingItems()
        }
    }

    func playNext(_ song: Song, serverUrl: String, token: String) {
        guard !songQueue.isEmpty, currentIndex >= 0 else {
            setQueue([song], serverUrl: serverUrl, token: token, autoPlay: false)
            return
        }
        lastServerUrl = serverUrl
        lastToken = token
        let insertIndex = min(currentIndex + 1, songQueue.count)
        songQueue.insert(song, at: min(insertIndex, songQueue.count))
        if var baseQueue = queueBeforeShuffle {
            if currentIndex >= 0,
               currentIndex < songQueue.count,
               let currentSongId = songQueue[safe: currentIndex]?.id,
               let baseCurrentIndex = baseQueue.firstIndex(where: { $0.id == currentSongId })
            {
                let baseInsertIndex = min(baseCurrentIndex + 1, baseQueue.count)
                baseQueue.insert(song, at: baseInsertIndex)
            } else {
                baseQueue.append(song)
            }
            queueBeforeShuffle = baseQueue
        }

        if currentIndex >= 0, currentIndex < songQueue.count {
            replaceUpcomingItems()
            updateSnapshot()
            updateNowPlayingInfo()
        }
    }

    func playQueueItem(_ index: Int) {
        guard index >= 0, index < songQueue.count else { return }
        playbackReporting.finish(positionMs: playbackPosition.positionMs)
        ensureAudioSession()

        rebuildPlayerQueue(startingAt: index, preloadItemCount: Self.initialPreloadedItems)
        player.play()
        scheduleDeferredPreload()
        updateSnapshot()
        updateNowPlayingInfo()
    }

    // MARK: - Playback Controls

    func play(_ song: Song, serverUrl: String, token: String) {
        setQueue([song], serverUrl: serverUrl, token: token)
    }

    func pause() {
        logger.info("Pause called")
        player.pause()
        updateSnapshot()
        updateNowPlayingInfo()
    }

    func play() {
        resume()
    }

    func resume() {
        if player.currentItem == nil, currentIndex >= 0, currentIndex < songQueue.count {
            playQueueItem(currentIndex)
            return
        }
        ensureAudioSession()
        player.play()
        updateSnapshot()
        updateNowPlayingInfo()
    }

    func stop() {
        playbackReporting.finish(positionMs: playbackPosition.positionMs)
        player.pause()
        player.removeAllItems()
        songQueue.removeAll()
        playSessionIdsByItemId.removeAll(keepingCapacity: true)
        queueBeforeShuffle = nil
        loadedQueueRange = nil
        currentIndex = -1
        clearVisualizerData(keepAvailability: true)
        updateSnapshot()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    func seekTo(positionMs: Int64) {
        guard currentIndex >= 0, currentIndex < songQueue.count else { return }
        let song = songQueue[currentIndex]

        let durationMs = effectiveDurationMs(for: song)
        let targetPosition = max(0, min(positionMs, durationMs > 0 ? durationMs : positionMs))
        seek(to: Double(targetPosition) / 1000.0)

        updateSnapshot()
        updateNowPlayingInfo()
    }

    func seek(to time: Double) {
        let cmTime = CMTime(seconds: time, preferredTimescale: 1000)
        player.seek(to: cmTime) { [weak self] finished in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if finished {
                    logger.debug("Seek completed to \(time, privacy: .public)s")
                } else {
                    logger.debug("Seek interrupted before completion")
                }
                updateSnapshot()
                updateNowPlayingInfo()
            }
        }
    }

    func skipNext() {
        guard currentIndex + 1 < songQueue.count else { return }
        playbackReporting.finish(positionMs: playbackPosition.positionMs)
        if loadedQueueRange?.contains(currentIndex + 1) != true {
            rebuildPlayerQueue(startingAt: currentIndex)
        }
        currentIndex += 1
        player.advanceToNextItem()
        if let range = loadedQueueRange {
            loadedQueueRange = currentIndex ... max(currentIndex, range.upperBound)
        } else {
            loadedQueueRange = currentIndex ... currentIndex
        }
        preloadUpcomingItems()
        updateSnapshot()
        updateNowPlayingInfo()
    }

    func skipPrevious() {
        if currentIndex > 0 {
            currentIndex -= 1
            playQueueItem(currentIndex)
        } else {
            // Seek to beginning
            player.seek(to: .zero)
        }
        updateSnapshot()
        updateNowPlayingInfo()
    }

    func toggleShuffle() {
        snapshot.isShuffled.toggle()

        if snapshot.isShuffled {
            queueBeforeShuffle = songQueue
            // Match platform-native behavior: keep current playback uninterrupted.
            if currentIndex >= 0, currentIndex < songQueue.count {
                shuffleUpcomingQueueInPlace()
            }
        } else {
            restoreQueueAfterShuffle()
            queueBeforeShuffle = nil
        }

        updateSnapshot()
        updateNowPlayingInfo()
    }

    func cycleRepeatMode() {
        switch snapshot.repeatMode {
        case .none: snapshot.repeatMode = .one
        case .one: snapshot.repeatMode = .all
        case .all: snapshot.repeatMode = .none
        }
        updateSnapshot()
    }

    // MARK: - Track Transition

    private func handleTrackEnded() {
        playbackReporting.finish(positionMs: playbackPosition.durationMs)
        if snapshot.repeatMode == .one {
            // Replay current track
            player.seek(to: .zero)
            player.play()
            return
        }

        if currentIndex + 1 < songQueue.count {
            currentIndex += 1
            if let range = loadedQueueRange {
                loadedQueueRange = currentIndex ... max(currentIndex, range.upperBound)
            } else {
                loadedQueueRange = currentIndex ... currentIndex
            }
            preloadUpcomingItems()
            updateSnapshot()
            updateNowPlayingInfo()
        } else if snapshot.repeatMode == .all, !songQueue.isEmpty {
            // Loop back to beginning
            playQueueItem(0)
        } else {
            // End of queue
            updateSnapshot()
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        }
    }

    // MARK: - Snapshot

    private func updateSnapshot() {
        syncCurrentIndexWithCurrentItemIfNeeded()
        let currentTime = player.currentTime()
        let currentSeconds = CMTimeGetSeconds(currentTime)
        var positionMs: Int64 = currentSeconds.isFinite ? max(0, Int64(currentSeconds * 1000)) : 0

        let song = currentIndex >= 0 && currentIndex < songQueue.count ? songQueue[currentIndex] : nil
        let durationMs = effectiveDurationMs(for: song)
        if durationMs > 0 {
            positionMs = min(max(0, positionMs), durationMs)
        }

        // Always update position (separate from snapshot so views reading
        // only display state aren't invalidated by the 500ms timer tick).
        let newPosition = PlaybackPosition(
            positionMs: positionMs,
            durationMs: durationMs,
            updateTimeMs: Int64(ProcessInfo.processInfo.systemUptime * 1000)
        )
        if playbackPosition != newPosition {
            playbackPosition = newPosition
        }

        // Update Now Playing elapsed time during playback for smooth progress display
        if player.rate > 0, MPNowPlayingInfoCenter.default().nowPlayingInfo != nil {
            var nowPlayingInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(positionMs) / 1000.0
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
        }

        // Build candidate and only replace snapshot when display fields change.
        // Position/duration/updateTime are tracked separately via `playbackPosition`
        // so that views reading only display state are not invalidated by position ticks.
        let candidate = PlayerSnapshot(
            title: song?.name ?? "",
            artist: song?.artists?.joined(separator: ", ") ?? "",
            albumArtUrl: song?.albumArtUrl,
            isPlaying: player.rate > 0,
            isBuffering: player.currentItem?.status == .unknown,
            hasPrevious: currentIndex > 0,
            hasNext: currentIndex + 1 < songQueue.count,
            isShuffled: snapshot.isShuffled,
            repeatMode: snapshot.repeatMode,
            currentSongId: song?.id,
            currentAlbumId: song?.albumId,
            currentArtistId: song?.artistIds?.first,
            currentAlbumName: song?.album,
            playbackSpeed: player.rate != 0 ? player.rate : snapshot.playbackSpeed,
            codec: song?.codec,
            bitRate: song?.bitRate.flatMap { Int32($0) },
            sampleRate: song?.sampleRate.flatMap { Int32($0) },
            queueRevision: snapshot.queueRevision
        )

        if snapshot != candidate {
            snapshot = candidate
        }
        playbackReporting.update(
            songID: song?.id, serverURL: lastServerUrl, token: lastToken,
            positionMs: playbackPosition.positionMs, isPlaying: player.rate > 0)
        updateVisualizerDisplayLinkState()
    }

    // MARK: - Now Playing Info (Lock Screen / Control Center)

    private func updateNowPlayingInfo() {
        guard currentIndex >= 0, currentIndex < songQueue.count else { return }
        let song = songQueue[currentIndex]

        let info: [String: Any] = [
            MPMediaItemPropertyTitle: song.name,
            MPMediaItemPropertyArtist: song.artists?.joined(separator: ", ") ?? "",
            MPMediaItemPropertyAlbumTitle: song.album ?? "",
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(playbackPosition.positionMs) / 1000.0,
            MPMediaItemPropertyPlaybackDuration: Double(playbackPosition.durationMs) / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate: player.rate,
        ]

        // Load artwork asynchronously
        if let artUrlString = song.albumArtUrl, let artUrl = URL(string: artUrlString) {
            let targetSongId = song.id
            Task.detached { [targetSongId] in
                let image = await ImageCache.shared.fetchImage(
                    for: artUrl,
                    targetSize: CGSize(width: 512, height: 512)
                )
                guard let image else { return }
                let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                await MainActor.run { [weak self] in
                    guard let self, snapshot.currentSongId == targetSongId else { return }
                    var currentInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                    currentInfo[MPMediaItemPropertyArtwork] = artwork
                    MPNowPlayingInfoCenter.default().nowPlayingInfo = currentInfo
                }
            }
        }

        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: - URL Building

    private func normalizedStartIndex(_ index: Int, queueCount: Int) -> Int? {
        guard queueCount > 0 else { return nil }
        return min(max(index, 0), queueCount - 1)
    }

    private func rebuildPlayerQueue(startingAt index: Int, firstItemOverrideUrl: String? = nil, preloadItemCount: Int = AudioPlayerController.maxPreloadedItems) {
        guard let safeStart = normalizedStartIndex(index, queueCount: songQueue.count) else {
            player.removeAllItems()
            loadedQueueRange = nil
            currentIndex = -1
            return
        }

        player.removeAllItems()
        currentIndex = safeStart

        let preloadCount = max(1, min(preloadItemCount, Self.maxPreloadedItems))
        let endIndex = min(songQueue.count - 1, safeStart + preloadCount - 1)
        for queueIndex in safeStart ... endIndex {
            let url: String = if queueIndex == safeStart, let firstItemOverrideUrl {
                firstItemOverrideUrl
            } else {
                buildStreamUrl(for: songQueue[queueIndex])
            }
            if let playerItem = makePlayerItem(url: url, song: songQueue[queueIndex]) {
                player.insert(playerItem, after: nil)
            }
        }
        loadedQueueRange = safeStart ... endIndex
    }

    private func preloadUpcomingItems() {
        guard currentIndex >= 0, currentIndex < songQueue.count else {
            loadedQueueRange = nil
            return
        }

        guard var range = loadedQueueRange else {
            rebuildPlayerQueue(startingAt: currentIndex)
            return
        }

        if range.lowerBound != currentIndex {
            range = currentIndex ... max(currentIndex, range.upperBound)
        }

        let desiredEnd = min(songQueue.count - 1, currentIndex + Self.maxPreloadedItems - 1)
        var nextIndex = range.upperBound + 1

        while nextIndex <= desiredEnd {
            let url = buildStreamUrl(for: songQueue[nextIndex])
            if let item = makePlayerItem(url: url, song: songQueue[nextIndex]) {
                player.insert(item, after: nil)
            }
            range = currentIndex ... nextIndex
            nextIndex += 1
        }

        loadedQueueRange = range
    }

    /// Queue edits must retain the current item, its elapsed time, and paused state.
    private func replaceUpcomingItems() {
        guard currentIndex >= 0, currentIndex < songQueue.count,
              let currentItem = player.currentItem else { return }
        for item in player.items() where item !== currentItem {
            player.remove(item)
        }
        loadedQueueRange = currentIndex ... currentIndex
        preloadUpcomingItems()
    }

    private func scheduleDeferredPreload() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            self?.preloadUpcomingItems()
        }
    }

    private func buildStreamUrl(
        for song: Song,
        serverUrl: String? = nil,
        token: String? = nil
    ) -> String {
        let resolvedServerUrl = serverUrl ?? lastServerUrl
        let resolvedToken = token ?? lastToken
        guard !resolvedServerUrl.isEmpty, !resolvedToken.isEmpty else {
            return buildMobileStreamUrl(
                serverUrl: resolvedServerUrl,
                token: resolvedToken,
                itemId: song.id,
                container: song.container
            )
        }

        let isFlacSource = song.container?.lowercased() == "flac" || song.codec?.lowercased() == "flac"

        // For FLAC files, use /Items/{id}/File to serve the raw file with its seek table
        // intact. The /universal endpoint pipes through FFmpeg even during "direct play",
        // which strips the FLAC seek table and causes AVPlayer to drift when seeking.
        if isFlacSource {
            let baseUrl = "\(resolvedServerUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/Items/\(song.id)/File"
            guard var components = URLComponents(string: baseUrl) else {
                return buildMobileStreamUrl(serverUrl: resolvedServerUrl, token: resolvedToken, itemId: song.id, container: song.container)
            }
            components.queryItems = [.init(name: "apiKey", value: resolvedToken)]
            if let finalUrl = components.url?.absoluteString {
                return finalUrl
            }
        }

        let supportedAudioCodecs = "mp3,aac,m4a|aac,m4b|aac,flac,alac,m4a|alac,m4b|alac,webma,webm|webma,wav,aiff,aiff|aif"
        let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? "aurelia-ios-\(UUID().uuidString)"
        let playSessionId = playSessionId(for: song.id)

        var queryItems: [URLQueryItem] = [
            .init(name: "apiKey", value: resolvedToken),
            .init(name: "deviceId", value: deviceId),
            .init(name: "container", value: supportedAudioCodecs),
            .init(name: "playSessionId", value: playSessionId),
            .init(name: "startTimeTicks", value: "0"),
            .init(name: "audioCodec", value: "aac"),
            .init(name: "transcodingContainer", value: "mp4"),
            .init(name: "transcodingProtocol", value: "hls"),
            .init(name: "maxStreamingBitrate", value: "999999999"),
        ]
        if let userId = sessionStore.userId, !userId.isEmpty {
            queryItems.append(.init(name: "userId", value: userId))
        }

        let baseUrl = "\(resolvedServerUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/Audio/\(song.id)/universal"
        guard var components = URLComponents(string: baseUrl) else {
            return buildMobileStreamUrl(
                serverUrl: resolvedServerUrl,
                token: resolvedToken,
                itemId: song.id,
                container: song.container
            )
        }
        components.queryItems = queryItems
        if let finalUrl = components.url?.absoluteString {
            return finalUrl
        }

        return buildMobileStreamUrl(
            serverUrl: resolvedServerUrl,
            token: resolvedToken,
            itemId: song.id,
            container: song.container
        )
    }

    private func makePlayerItem(url: String, song: Song? = nil) -> AVPlayerItem? {
        guard let itemUrl = URL(string: url) else {
            logger.error("Invalid stream URL: \(url)")
            return nil
        }
        let asset = AVURLAsset(url: itemUrl, options: avUrlAssetOptions(for: song))
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 30

        attachVisualizerTap(to: item)
        return item
    }

    private func avUrlAssetOptions(for song: Song?) -> [String: Any]? {
        var options: [String: Any] = [:]

        if !lastToken.isEmpty && resolveBackendProvider(token: lastToken) == .jellyfin {
            // Jellyfin playback supports explicit token header.
            options["AVURLAssetHTTPHeaderFieldsKey"] = ["X-Emby-Token": lastToken]
        }

        let isFlac = song?.container?.lowercased() == "flac" || song?.codec?.lowercased() == "flac"
        if isFlac {
            // Ask AVFoundation to parse exact timing metadata (important for FLAC seek tables).
            options[AVURLAssetPreferPreciseDurationAndTimingKey] = true
        }

        return options.isEmpty ? nil : options
    }

    private func resolveBackendProvider(token: String) -> BackendProvider {
        return .jellyfin
    }

    private func attachVisualizerTap(to item: AVPlayerItem) {
        Task { @MainActor [weak self, weak item] in
            guard let self, let item else { return }

            do {
                let tracks = try await item.asset.loadTracks(withMediaType: .audio)
                guard let track = tracks.first else { return }
                guard let tap = visualizerAnalyzer.makeAudioTap() else { return }

                let params = AVMutableAudioMixInputParameters(track: track)
                params.audioTapProcessor = tap
                let mix = AVMutableAudioMix()
                mix.inputParameters = [params]
                item.audioMix = mix
            } catch {
                logger.debug("Skipping visualizer tap: \(error.localizedDescription)")
            }
        }
    }

    private func effectiveDurationMs(for song: Song?) -> Int64 {
        if let item = player.currentItem {
            let itemDuration = CMTimeGetSeconds(item.duration)
            if itemDuration.isFinite, itemDuration > 0 {
                return Int64(itemDuration * 1000)
            }
        }
        if let songDuration = song?.duration, songDuration > 0 {
            return Int64(songDuration * 1000)
        }
        return 0
    }

    private func playSessionId(for itemId: String) -> String {
        if let existing = playSessionIdsByItemId[itemId] {
            return existing
        }
        let generated = UUID().uuidString.lowercased()
        playSessionIdsByItemId[itemId] = generated
        return generated
    }

    private func shouldHandleTrackEndedNotification(for endedItem: AVPlayerItem) -> Bool {
        guard currentIndex >= 0, currentIndex < songQueue.count else {
            return endedItem == player.currentItem
        }
        guard let endedSongId = songId(from: endedItem) else {
            return endedItem == player.currentItem
        }
        return endedSongId == songQueue[currentIndex].id
    }

    private func syncCurrentIndexWithCurrentItemIfNeeded() {
        guard !songQueue.isEmpty else { return }
        guard let item = player.currentItem else { return }
        guard let itemSongId = songId(from: item) else { return }

        let currentSongId = (currentIndex >= 0 && currentIndex < songQueue.count) ? songQueue[currentIndex].id : nil
        guard currentSongId != itemSongId else { return }

        let anchorIndex = max(currentIndex, 0)
        guard let matchedIndex = nearestQueueIndex(forSongId: itemSongId, around: anchorIndex) else { return }
        guard matchedIndex != currentIndex else { return }

        currentIndex = matchedIndex
        if let range = loadedQueueRange {
            loadedQueueRange = currentIndex ... max(currentIndex, range.upperBound)
        } else {
            loadedQueueRange = currentIndex ... currentIndex
        }
        preloadUpcomingItems()
    }

    private func nearestQueueIndex(forSongId songId: String, around current: Int) -> Int? {
        var bestIndex: Int?
        var bestDistance = Int.max
        for (index, song) in songQueue.enumerated() where song.id == songId {
            let distance = abs(index - current)
            if distance < bestDistance {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private func songId(from item: AVPlayerItem) -> String? {
        guard let urlAsset = item.asset as? AVURLAsset else { return nil }
        let pathComponents = urlAsset.url.pathComponents

        if let audioIndex = pathComponents.firstIndex(of: "Audio"),
           audioIndex + 1 < pathComponents.count
        {
            let rawId = pathComponents[audioIndex + 1]
            return rawId.removingPercentEncoding ?? rawId
        }

        if let itemsIndex = pathComponents.firstIndex(of: "Items"),
           itemsIndex + 1 < pathComponents.count
        {
            let rawId = pathComponents[itemsIndex + 1]
            return rawId.removingPercentEncoding ?? rawId
        }

        return nil
    }

    private func shuffleUpcomingQueueInPlace() {
        guard currentIndex >= 0, currentIndex < songQueue.count else { return }
        guard let currentItem = player.currentItem else {
            rebuildPlayerQueue(startingAt: currentIndex)
            return
        }

        let upcomingStart = currentIndex + 1
        guard upcomingStart < songQueue.count else {
            loadedQueueRange = currentIndex ... currentIndex
            return
        }

        let playedAndCurrent = Array(songQueue.prefix(upcomingStart))
        var upcoming = Array(songQueue.suffix(from: upcomingStart))
        upcoming.shuffle()
        songQueue = playedAndCurrent + upcoming

        // Keep the current AVPlayerItem alive, replace only queued upcoming items.
        for item in player.items() where item !== currentItem {
            player.remove(item)
        }

        let endIndex = min(songQueue.count - 1, currentIndex + Self.maxPreloadedItems - 1)
        if upcomingStart <= endIndex {
            for queueIndex in upcomingStart ... endIndex {
                let url = buildStreamUrl(for: songQueue[queueIndex])
                if let item = makePlayerItem(url: url, song: songQueue[queueIndex]) {
                    player.insert(item, after: nil)
                }
            }
            loadedQueueRange = currentIndex ... endIndex
        } else {
            loadedQueueRange = currentIndex ... currentIndex
        }

        scheduleDeferredPreload()
    }

    private func restoreQueueAfterShuffle() {
        guard currentIndex >= 0, currentIndex < songQueue.count else { return }
        guard let originalQueue = queueBeforeShuffle else { return }
        let currentSong = songQueue[currentIndex]
        let currentOccurrence = occurrenceCount(
            songId: currentSong.id,
            in: songQueue,
            through: currentIndex
        )

        guard let currentItem = player.currentItem else {
            let restoredQueue = mergedQueuePreservingAdditions(base: originalQueue, current: songQueue)
            songQueue = restoredQueue
            if let restoredIndex = indexOfOccurrence(songId: currentSong.id, occurrence: currentOccurrence, in: restoredQueue) {
                currentIndex = restoredIndex
            } else if let fallbackIndex = restoredQueue.firstIndex(where: { $0.id == currentSong.id }) {
                currentIndex = fallbackIndex
            } else {
                currentIndex = restoredQueue.isEmpty ? -1 : min(currentIndex, restoredQueue.count - 1)
            }
            return
        }

        let restoredQueue = mergedQueuePreservingAdditions(base: originalQueue, current: songQueue)
        guard !restoredQueue.isEmpty else { return }

        songQueue = restoredQueue
        if let restoredIndex = indexOfOccurrence(songId: currentSong.id, occurrence: currentOccurrence, in: restoredQueue) {
            currentIndex = restoredIndex
        } else if let fallbackIndex = restoredQueue.firstIndex(where: { $0.id == currentSong.id }) {
            currentIndex = fallbackIndex
        } else {
            currentIndex = min(currentIndex, restoredQueue.count - 1)
        }

        for item in player.items() where item !== currentItem {
            player.remove(item)
        }

        let upcomingStart = currentIndex + 1
        let endIndex = min(songQueue.count - 1, currentIndex + Self.maxPreloadedItems - 1)
        if upcomingStart <= endIndex {
            for queueIndex in upcomingStart ... endIndex {
                let url = buildStreamUrl(for: songQueue[queueIndex])
                if let item = makePlayerItem(url: url, song: songQueue[queueIndex]) {
                    player.insert(item, after: nil)
                }
            }
            loadedQueueRange = currentIndex ... endIndex
        } else {
            loadedQueueRange = currentIndex ... currentIndex
        }

        scheduleDeferredPreload()
    }

    private func mergedQueuePreservingAdditions(base: [Song], current: [Song]) -> [Song] {
        var merged = base
        var remainingById: [String: Int] = [:]
        for song in base {
            remainingById[song.id, default: 0] += 1
        }

        for song in current {
            let remaining = remainingById[song.id, default: 0]
            if remaining > 0 {
                remainingById[song.id] = remaining - 1
            } else {
                merged.append(song)
            }
        }

        return merged
    }

    private func occurrenceCount(songId: String, in queue: [Song], through index: Int) -> Int {
        guard !queue.isEmpty else { return 1 }
        let upperBound = min(max(index, 0), queue.count - 1)
        return max(1, queue[0 ... upperBound].filter { $0.id == songId }.count)
    }

    private func indexOfOccurrence(songId: String, occurrence: Int, in queue: [Song]) -> Int? {
        var seen = 0
        for (index, song) in queue.enumerated() where song.id == songId {
            seen += 1
            if seen == occurrence {
                return index
            }
        }
        return nil
    }

}

private final class VisualizerDisplayLinkProxy: NSObject {
    private let onTick: () -> Void

    init(onTick: @escaping () -> Void) {
        self.onTick = onTick
    }

    @objc func tick() {
        onTick()
    }
}

private final class PlayerAudioTapAnalyzer {
    struct FrameData {
        let frequencyData: [UInt8]
        let waveformData: [UInt8]
        let frameId: Int64
    }

    nonisolated(unsafe) var onFrame: ((FrameData) -> Void)?

    private nonisolated(unsafe) var lock = os_unfair_lock_s()
    private nonisolated(unsafe) var enabled = true
    private nonisolated(unsafe) var lastEmitUptime: TimeInterval = 0
    private nonisolated(unsafe) var frameCounter: Int64 = 0
    private nonisolated(unsafe) var resetRequested = false

    private nonisolated(unsafe) var processingFormat = AudioStreamBasicDescription()
    private nonisolated(unsafe) var monoBuffer: [Float] = []
    private nonisolated(unsafe) var fftInput = [Float](repeating: 0, count: 256)
    private nonisolated(unsafe) var fftWindow = [Float](repeating: 0, count: 256)
    private nonisolated(unsafe) var fftWindowed = [Float](repeating: 0, count: 256)
    private nonisolated(unsafe) var splitReal = [Float](repeating: 0, count: 128)
    private nonisolated(unsafe) var splitImag = [Float](repeating: 0, count: 128)
    private nonisolated(unsafe) var fftMagnitudes = [Float](repeating: 0, count: 128)
    private nonisolated(unsafe) var frequencyByteBuffer = [UInt8](repeating: 0, count: 128)
    private nonisolated(unsafe) var waveformByteBuffer = [UInt8](repeating: 128, count: 256)
    private nonisolated(unsafe) var smoothedFrequency = [Float](repeating: 0, count: 128)
    private nonisolated(unsafe) var smoothedWaveform = [Float](repeating: 128, count: 256)
    private nonisolated(unsafe) var fftSetup: FFTSetup?
    private nonisolated(unsafe) var pendingFrame: FrameData?
    private nonisolated(unsafe) var isFrameDispatchScheduled = false

    private nonisolated static let fftSize = 256
    private nonisolated static let outputFrequencyBinCount = 128
    private nonisolated static let outputWaveformSampleCount = 256
    private nonisolated static let waveformCenter = 128
    private nonisolated static let outputFrameInterval: TimeInterval = 1.0 / 60.0

    private nonisolated static let attackSmoothing: Float = 0.8
    private nonisolated static let decaySmoothing: Float = 0.15
    private nonisolated static let minDb: Float = -100
    private nonisolated static let maxDb: Float = 0
    private nonisolated static let minMagnitudeRatio: Float = 1e-7
    private nonisolated static let maxFftMagnitude: Float = 180

    init() {
        let log2n = vDSP_Length(log2(Float(Self.fftSize)))
        fftSetup = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2))
        vDSP_hann_window(&fftWindow, vDSP_Length(Self.fftSize), Int32(vDSP_HANN_NORM))
    }

    nonisolated func setEnabled(_ enabled: Bool) {
        os_unfair_lock_lock(&lock)
        let wasEnabled = self.enabled
        self.enabled = enabled
        if enabled, !wasEnabled {
            resetRequested = true
            lastEmitUptime = 0
        }
        os_unfair_lock_unlock(&lock)
    }

    @_optimize(none)
    nonisolated func makeAudioTap() -> MTAudioProcessingTap? {
        let context = PlayerAudioTapContext(analyzer: self)
        let clientInfo = Unmanaged.passRetained(context).toOpaque()
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: clientInfo,
            init: aureliaTapInit,
            finalize: aureliaTapFinalize,
            prepare: aureliaTapPrepare,
            unprepare: aureliaTapUnprepare,
            process: aureliaTapProcess
        )

        var tap: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(
            kCFAllocatorDefault,
            &callbacks,
            kMTAudioProcessingTapCreationFlag_PostEffects,
            &tap
        )

        guard status == noErr else {
            Unmanaged<PlayerAudioTapContext>.fromOpaque(clientInfo).release()
            return nil
        }

        return tap
    }

    nonisolated func prepare(maxFrames _: CMItemCount, processingFormat: AudioStreamBasicDescription) {
        self.processingFormat = processingFormat
    }

    nonisolated func process(bufferList: UnsafeMutablePointer<AudioBufferList>, frameCount: Int) {
        guard frameCount > 0 else { return }

        var shouldProcess = false
        var shouldReset = false
        var nextFrameId: Int64 = 0

        os_unfair_lock_lock(&lock)
        if enabled {
            let now = ProcessInfo.processInfo.systemUptime
            if now - lastEmitUptime >= Self.outputFrameInterval {
                lastEmitUptime = now
                frameCounter += 1
                nextFrameId = frameCounter
                shouldReset = resetRequested
                resetRequested = false
                shouldProcess = true
            }
        }
        os_unfair_lock_unlock(&lock)

        guard shouldProcess else { return }
        if shouldReset {
            for i in smoothedFrequency.indices {
                smoothedFrequency[i] = 0
            }
            for i in smoothedWaveform.indices {
                smoothedWaveform[i] = Float(Self.waveformCenter)
            }
        }

        let monoCount = extractMonoSamples(from: bufferList, frameCount: frameCount)
        guard monoCount > 0 else { return }

        let frequency = processFrequency(sampleCount: monoCount)
        let waveform = processWaveform(sampleCount: monoCount)
        let frame = FrameData(frequencyData: frequency, waveformData: waveform, frameId: nextFrameId)
        enqueueFrameForDelivery(frame)
    }

    private nonisolated func enqueueFrameForDelivery(_ frame: FrameData) {
        var shouldSchedule = false
        os_unfair_lock_lock(&lock)
        pendingFrame = frame
        if !isFrameDispatchScheduled {
            isFrameDispatchScheduled = true
            shouldSchedule = true
        }
        os_unfair_lock_unlock(&lock)

        guard shouldSchedule else { return }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            var frameToDeliver: FrameData?
            os_unfair_lock_lock(&lock)
            frameToDeliver = pendingFrame
            pendingFrame = nil
            isFrameDispatchScheduled = false
            os_unfair_lock_unlock(&lock)

            if let frameToDeliver {
                onFrame?(frameToDeliver)
            }
        }
    }

    private nonisolated func extractMonoSamples(from bufferList: UnsafeMutablePointer<AudioBufferList>, frameCount: Int) -> Int {
        let audioBufferCount = Int(bufferList.pointee.mNumberBuffers)
        guard audioBufferCount > 0 else { return 0 }
        let firstAudioBuffer = withUnsafeMutablePointer(to: &bufferList.pointee.mBuffers) { $0 }
        let audioBuffers = UnsafeMutableBufferPointer(start: firstAudioBuffer, count: audioBufferCount)

        if monoBuffer.count < frameCount {
            monoBuffer = [Float](repeating: 0, count: frameCount)
        }

        let channelCount = max(Int(processingFormat.mChannelsPerFrame), 1)
        let isFloat = (processingFormat.mFormatFlags & kAudioFormatFlagIsFloat) != 0
        let isSignedInt = (processingFormat.mFormatFlags & kAudioFormatFlagIsSignedInteger) != 0
        let bytesPerChannel = max(Int(processingFormat.mBitsPerChannel / 8), 1)

        if isFloat {
            if audioBuffers.count == 1 {
                guard let data = audioBuffers[0].mData?.assumingMemoryBound(to: Float.self) else { return 0 }
                if channelCount == 1 {
                    for i in 0 ..< frameCount {
                        monoBuffer[i] = data[i]
                    }
                } else {
                    for i in 0 ..< frameCount {
                        let base = i * channelCount
                        var sum: Float = 0
                        for channel in 0 ..< channelCount {
                            sum += data[base + channel]
                        }
                        monoBuffer[i] = sum / Float(channelCount)
                    }
                }
            } else {
                let channelsToMix = min(audioBuffers.count, channelCount)
                guard channelsToMix > 0 else { return 0 }
                for i in 0 ..< frameCount {
                    var sum: Float = 0
                    for channel in 0 ..< channelsToMix {
                        guard let channelData = audioBuffers[channel].mData?.assumingMemoryBound(to: Float.self) else { continue }
                        sum += channelData[i]
                    }
                    monoBuffer[i] = sum / Float(channelsToMix)
                }
            }
            return frameCount
        }

        if isSignedInt, bytesPerChannel == 2 {
            if audioBuffers.count == 1 {
                guard let data = audioBuffers[0].mData?.assumingMemoryBound(to: Int16.self) else { return 0 }
                if channelCount == 1 {
                    for i in 0 ..< frameCount {
                        monoBuffer[i] = Float(data[i]) / 32768
                    }
                } else {
                    for i in 0 ..< frameCount {
                        let base = i * channelCount
                        var sum: Float = 0
                        for channel in 0 ..< channelCount {
                            sum += Float(data[base + channel]) / 32768
                        }
                        monoBuffer[i] = sum / Float(channelCount)
                    }
                }
            } else {
                let channelsToMix = min(audioBuffers.count, channelCount)
                guard channelsToMix > 0 else { return 0 }
                for i in 0 ..< frameCount {
                    var sum: Float = 0
                    for channel in 0 ..< channelsToMix {
                        guard let channelData = audioBuffers[channel].mData?.assumingMemoryBound(to: Int16.self) else { continue }
                        sum += Float(channelData[i]) / 32768
                    }
                    monoBuffer[i] = sum / Float(channelsToMix)
                }
            }
            return frameCount
        }

        return 0
    }

    private nonisolated func processFrequency(sampleCount: Int) -> [UInt8] {
        guard let fftSetup else { return [] }
        guard sampleCount > 0 else { return [UInt8](repeating: 0, count: Self.outputFrequencyBinCount) }

        for i in 0 ..< Self.fftSize {
            fftInput[i] = 0
        }
        if sampleCount >= Self.fftSize {
            let start = sampleCount - Self.fftSize
            for i in 0 ..< Self.fftSize {
                fftInput[i] = monoBuffer[start + i]
            }
        } else {
            let offset = Self.fftSize - sampleCount
            for i in 0 ..< sampleCount {
                fftInput[offset + i] = monoBuffer[i]
            }
        }

        vDSP_vmul(fftInput, 1, fftWindow, 1, &fftWindowed, 1, vDSP_Length(Self.fftSize))

        splitReal.withUnsafeMutableBufferPointer { realPtr in
            splitImag.withUnsafeMutableBufferPointer { imagPtr in
                guard let realBase = realPtr.baseAddress,
                      let imagBase = imagPtr.baseAddress else { return }
                var split = DSPSplitComplex(realp: realBase, imagp: imagBase)
                fftWindowed.withUnsafeBufferPointer { inputPtr in
                    guard let inputBase = inputPtr.baseAddress else { return }
                    inputBase.withMemoryRebound(to: DSPComplex.self, capacity: Self.fftSize / 2) { complexPtr in
                        vDSP_ctoz(complexPtr, 2, &split, 1, vDSP_Length(Self.fftSize / 2))
                    }
                }

                vDSP_fft_zrip(
                    fftSetup,
                    &split,
                    1,
                    vDSP_Length(log2(Float(Self.fftSize))),
                    FFTDirection(FFT_FORWARD)
                )

                fftMagnitudes.withUnsafeMutableBufferPointer { magnitudePtr in
                    guard let magnitudeBase = magnitudePtr.baseAddress else { return }
                    vDSP_zvmags(&split, 1, magnitudeBase, 1, vDSP_Length(Self.fftSize / 2))
                }
            }
        }

        let sourceCount = fftMagnitudes.count
        for i in 0 ..< Self.outputFrequencyBinCount {
            let sourceIndex = min(sourceCount - 1, (i * sourceCount) / Self.outputFrequencyBinCount)
            let magnitude = sqrt(fftMagnitudes[sourceIndex])
            let scaled = magnitudeToByteScale(magnitude)
            frequencyByteBuffer[i] = smoothAndQuantize(smoothedBuffer: &smoothedFrequency, index: i, rawValue: scaled)
        }

        return frequencyByteBuffer
    }

    private nonisolated func processWaveform(sampleCount: Int) -> [UInt8] {
        guard sampleCount > 0 else {
            return [UInt8](repeating: UInt8(Self.waveformCenter), count: Self.outputWaveformSampleCount)
        }

        for i in 0 ..< Self.outputWaveformSampleCount {
            let sourceIndex = min(sampleCount - 1, (i * sampleCount) / Self.outputWaveformSampleCount)
            let sample = max(-1, min(1, monoBuffer[sourceIndex]))
            let rawValue = max(0, min(255, (sample + 1) * 127.5))
            waveformByteBuffer[i] = smoothAndQuantize(smoothedBuffer: &smoothedWaveform, index: i, rawValue: rawValue)
        }
        return waveformByteBuffer
    }

    private nonisolated func smoothAndQuantize(smoothedBuffer: inout [Float], index: Int, rawValue: Float) -> UInt8 {
        let current = smoothedBuffer[index]
        let rate = rawValue > current ? Self.attackSmoothing : Self.decaySmoothing
        let updated = current + (rawValue - current) * rate
        smoothedBuffer[index] = updated
        return UInt8(max(0, min(255, Int(updated.rounded()))))
    }

    private nonisolated func magnitudeToByteScale(_ magnitude: Float) -> Float {
        if magnitude <= 0 { return 0 }
        let ratio = max(magnitude / Self.maxFftMagnitude, Self.minMagnitudeRatio)
        let db = 20 * log10f(ratio)
        let normalized = max(0, min(1, (db - Self.minDb) / (Self.maxDb - Self.minDb)))
        return normalized * 255
    }
}

private final class PlayerAudioTapContext {
    let analyzer: PlayerAudioTapAnalyzer

    nonisolated init(analyzer: PlayerAudioTapAnalyzer) {
        self.analyzer = analyzer
    }
}

private nonisolated func aureliaTapInit(
    tap _: MTAudioProcessingTap,
    clientInfo: UnsafeMutableRawPointer?,
    tapStorageOut: UnsafeMutablePointer<UnsafeMutableRawPointer?>
) {
    tapStorageOut.pointee = clientInfo
}

private nonisolated func aureliaTapFinalize(tap: MTAudioProcessingTap) {
    let storage = MTAudioProcessingTapGetStorage(tap)
    Unmanaged<PlayerAudioTapContext>.fromOpaque(storage).release()
}

private nonisolated func aureliaTapPrepare(
    tap: MTAudioProcessingTap,
    maxFrames: CMItemCount,
    processingFormat: UnsafePointer<AudioStreamBasicDescription>
) {
    let storage = MTAudioProcessingTapGetStorage(tap)
    let context = Unmanaged<PlayerAudioTapContext>.fromOpaque(storage).takeUnretainedValue()
    context.analyzer.prepare(maxFrames: maxFrames, processingFormat: processingFormat.pointee)
}

private nonisolated func aureliaTapUnprepare(tap _: MTAudioProcessingTap) {}

private nonisolated func aureliaTapProcess(
    tap: MTAudioProcessingTap,
    numberFrames: CMItemCount,
    flags: MTAudioProcessingTapFlags,
    bufferListInOut: UnsafeMutablePointer<AudioBufferList>,
    numberFramesOut: UnsafeMutablePointer<CMItemCount>,
    flagsOut: UnsafeMutablePointer<MTAudioProcessingTapFlags>
) {
    var localFlags = flags
    let status = MTAudioProcessingTapGetSourceAudio(
        tap,
        numberFrames,
        bufferListInOut,
        &localFlags,
        nil,
        numberFramesOut
    )

    guard status == noErr else {
        numberFramesOut.pointee = 0
        return
    }

    flagsOut.pointee = localFlags

    let storage = MTAudioProcessingTapGetStorage(tap)
    let context = Unmanaged<PlayerAudioTapContext>.fromOpaque(storage).takeUnretainedValue()
    context.analyzer.process(bufferList: bufferListInOut, frameCount: Int(numberFramesOut.pointee))
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
