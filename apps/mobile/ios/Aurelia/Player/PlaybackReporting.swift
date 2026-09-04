import AureliaCore
import Foundation
import os

/// Playback owns reports and captures the session when sound starts.
@MainActor
final class PlaybackReporting {
    private struct Playing {
        let id: String
        let serverURL: String
        let token: String
    }
    private enum Event {
        case start(Playing, Int64)
        case progress(Playing, Int64, Bool)
        case stop(Playing, Int64)
    }
    private var current: Playing?
    private var position: Int64 = 0
    private var lastReported: Int64 = 0
    private var paused = true
    private var pending: [Event] = []
    private var worker: Task<Void, Never>?
    private let logger = Logger(subsystem: "com.aurelia.app", category: "PlaybackReporting")

    func update(songID: String?, serverURL: String, token: String, positionMs: Int64, isPlaying: Bool) {
        if current?.id != songID { finish() }
        if current == nil, isPlaying, let songID {
            let playing = Playing(id: songID, serverURL: serverURL, token: token)
            current = playing
            lastReported = positionMs
            enqueue(.start(playing, max(0, positionMs) * 10_000))
        }
        guard let current else { return }
        position = max(0, positionMs)
        if paused != !isPlaying || abs(position - lastReported) >= 10_000 {
            enqueue(.progress(current, position * 10_000, !isPlaying))
            lastReported = position
        }
        paused = !isPlaying
    }

    func finish(positionMs: Int64? = nil) {
        guard let playing = current else { return }
        current = nil
        enqueue(.stop(playing, max(0, positionMs ?? position) * 10_000))
    }

    private func enqueue(_ event: Event) {
        guard pending.count < 32 else {
            logger.warning("Playback report queue is full")
            return
        }
        pending.append(event)
        guard worker == nil else { return }
        worker = Task {
            while !pending.isEmpty {
                let event = pending.removeFirst()
                do {
                    switch event {
                    case let .start(play, ticks):
                        try await reportPlaybackStartEvent(
                            serverUrl: play.serverURL, token: play.token, itemId: play.id, positionTicks: ticks)
                    case let .progress(play, ticks, paused):
                        try await reportPlaybackProgressEvent(
                            serverUrl: play.serverURL, token: play.token, itemId: play.id,
                            positionTicks: ticks, isPaused: paused)
                    case let .stop(play, ticks):
                        try await reportPlaybackStopEvent(
                            serverUrl: play.serverURL, token: play.token, itemId: play.id, positionTicks: ticks)
                    }
                } catch { logger.debug("Playback report failed: \(error)") }
            }
            worker = nil
        }
    }
}
