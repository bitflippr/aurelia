import AureliaCore
import XCTest

@testable import Aurelia

@MainActor
final class PlaybackAndNavigationTests: XCTestCase {
    func testArtistNavigationDoesNotShiftIDsWhenMetadataIsMissing() {
        var song = Self.song("collaboration")
        song.artists = ["", "Second artist", "Duplicate"]
        song.artistIds = ["first", "second", "second", "without-name"]
        XCTAssertEqual(SongNavigation.artists(for: song), [ArtistRoute(id: "second", name: "Second artist")])
    }

    func testPlayNextOnEmptyQueuePreparesSongWithoutAutoplay() {
        let player = AudioPlayerController()
        defer { player.stop() }
        player.playNext(Self.song("first"), serverUrl: "http://127.0.0.1:1", token: "test")
        XCTAssertEqual(player.getQueue().map(\.id), ["first"])
        XCTAssertEqual(player.getCurrentQueueIndex(), 0)
        XCTAssertEqual(player.snapshot.currentSongId, "first")
        XCTAssertFalse(player.snapshot.isPlaying)
    }

    func testAddToEmptyQueuePreparesSongWithoutAutoplay() {
        let player = AudioPlayerController()
        defer { player.stop() }
        player.addToQueue(Self.song("first"), serverUrl: "http://127.0.0.1:1", token: "test")
        XCTAssertEqual(player.snapshot.currentSongId, "first")
        XCTAssertEqual(player.getCurrentQueueIndex(), 0)
        XCTAssertFalse(player.snapshot.isPlaying)
    }

    func testPlayNextPreservesDuplicateQueueEntriesAndCurrentPosition() {
        let player = AudioPlayerController()
        defer { player.stop() }
        let first = Self.song("first")
        let second = Self.song("second")
        player.setQueue([first, second], serverUrl: "http://127.0.0.1:1", token: "test", autoPlay: false)
        let revision = player.snapshot.queueRevision
        player.playNext(first, serverUrl: "http://127.0.0.1:1", token: "test")
        XCTAssertEqual(player.getQueue().map(\.id), ["first", "first", "second"])
        XCTAssertEqual(player.getCurrentQueueIndex(), 0)
        XCTAssertGreaterThan(player.snapshot.queueRevision, revision)
        player.addToQueue(second, serverUrl: "http://127.0.0.1:1", token: "test")
        XCTAssertEqual(player.getQueue().map(\.id), ["first", "first", "second", "second"])
    }

    static func song(_ id: String) -> Song {
        Song(
            id: id, name: id, itemType: "Audio", album: "Album", albumId: "album", artists: ["Artist"],
            artistIds: ["artist"], path: nil, duration: 30, albumArtUrl: nil, year: nil, playCount: nil,
            isFavorite: false, discNumber: 1, trackNumber: 1, container: "mp3", bitRate: nil, sampleRate: nil,
            codec: nil, genres: nil, premiereDate: nil, datePlayed: nil, dateCreated: nil, dateModified: nil,
            albumArtists: nil, lyrics: nil, imageTags: nil)
    }
}
