import Foundation

// MARK: - Now Playing State

struct NowPlayingState: Equatable {
    var title: String
    var artist: String
    var albumArtUrl: String?
    var isPlaying: Bool
    var isBuffering: Bool = false
    var hasPrevious: Bool = false
    var hasNext: Bool = false
    var albumId: String?
    var artistId: String?
    var albumName: String?
}

// MARK: - Player Snapshot

struct PlayerSnapshot: Equatable {
    var title: String = ""
    var artist: String = ""
    var albumArtUrl: String?
    var isPlaying: Bool = false
    var isBuffering: Bool = false
    var hasPrevious: Bool = false
    var hasNext: Bool = false
    var isShuffled: Bool = false
    var repeatMode: RepeatMode = .none
    var currentSongId: String?
    var currentAlbumId: String?
    var currentArtistId: String?
    var currentAlbumName: String?
    var playbackSpeed: Float = 1.0
    var codec: String?
    var bitRate: Int32?
    var sampleRate: Int32?
    var queueRevision: UInt64 = 0
}

// MARK: - Playback Position

/// Lightweight struct for frequently-updating playback position.
/// Separated from `PlayerSnapshot` so that views reading only display state
/// (title, art, controls) are not invalidated by position ticks.
struct PlaybackPosition: Equatable {
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var updateTimeMs: Int64 = 0
}

// MARK: - Repeat Mode

enum RepeatMode: Equatable {
    case none
    case one
    case all
}

// MARK: - Visualizer

enum VisualizerStyle: String, CaseIterable, Equatable {
    case bars
    case curve
    case wave

    var title: String {
        switch self {
        case .bars: "Bars"
        case .curve: "Curve"
        case .wave: "Wave"
        }
    }
}

struct VisualizerState: Equatable {
    var enabled: Bool = true
    var available: Bool = false
    var style: VisualizerStyle = .bars
    var frequencyData: [UInt8] = []
    var waveformData: [UInt8] = []
    var frameId: Int64 = 0
}

// MARK: - Featured Album

struct FeaturedAlbum: Identifiable, Equatable {
    var id: String
    var name: String
    var artist: String
    var albumArtUrl: String?
    var songCount: Int
}

// MARK: - Album Item

struct AlbumItem: Identifiable, Equatable {
    var id: String
    var name: String
    var artist: String
    var albumArtUrl: String?
    var songCount: Int
}

// MARK: - Lyrics

struct SyncedWord: Equatable {
    var time: TimeInterval
    var endTime: TimeInterval?
    var word: String
}

struct SyncedLine: Equatable {
    var time: TimeInterval
    var endTime: TimeInterval?
    var line: String
    var words: [SyncedWord]?
    var agentId: String?
    var translation: String?
}

struct LyricsSection: Equatable {
    var name: String
    var startTime: TimeInterval
    var endTime: TimeInterval
    var lines: [SyncedLine]
    var agentId: String?
}

struct LyricsAgent: Equatable {
    var id: String
    var agentType: String
}

struct Lyrics: Equatable {
    var plain: String?
    var synced: [SyncedLine]?
    var sections: [LyricsSection]?
    var agents: [LyricsAgent]?
    var songwriters: [String]?
    var language: String?
    var areFromRemote: Bool

    var isValid: Bool {
        (plain?.isEmpty == false) || (synced?.isEmpty == false)
    }

    /// Check if an agent ID refers to a background/other voice.
    func isBackgroundVocal(_ agentId: String?) -> Bool {
        guard let agentId, let agents else { return false }
        return agents.first(where: { $0.id == agentId })?.agentType == "other"
    }

    /// Check if an agent ID refers to a secondary vocalist — a `person` agent
    /// that is NOT the first (primary) person in the agents list.
    /// Apple Music right-aligns these lines to visually distinguish duet parts.
    func isSecondaryVocalist(_ agentId: String?) -> Bool {
        guard let agentId, let agents else { return false }
        guard let firstPerson = agents.first(where: { $0.agentType == "person" }) else { return false }
        if agentId == firstPerson.id { return false }
        return agents.first(where: { $0.id == agentId })?.agentType == "person"
    }
}

// MARK: - UI Constants

enum UIConstants {
    static let mostPlayedLimit = 10
    static let recentlyPlayedLimit = 10
    static let albumSectionLimit = 10
    static let featuredAlbumsLimit = 5
}
