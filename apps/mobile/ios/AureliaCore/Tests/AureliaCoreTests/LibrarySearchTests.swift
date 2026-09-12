import AureliaCore
import XCTest

final class LibrarySearchTests: XCTestCase {
    func testSearchReturnsArtistsAlbumsAndSongsThroughSwiftBindings() {
        let index = LibrarySearchIndex(songs: [
            song(id: "one", name: "Night Song", artist: "Night Players", album: "Night Music")
        ])
        let hits = index.search(query: "night", limitPerKind: 10)
        XCTAssertEqual(Set(hits.map(\.kind)), Set([.artist, .album, .song]))
        XCTAssertEqual(hits.first { $0.kind == .artist }?.id, "artist")
        XCTAssertEqual(hits.first { $0.kind == .album }?.id, "album")
    }

    func testSearchNormalizesDiacriticsAndHonorsPerKindLimit() {
        let index = LibrarySearchIndex(
            songs: (0..<8).map { song(id: "\($0)", name: "Beyoncé \($0)", artist: "Beyoncé", album: "Beyoncé live") })
        let hits = index.search(query: "beyonce", limitPerKind: 2)
        XCTAssertEqual(hits.filter { $0.kind == .song }.count, 2)
        XCTAssertEqual(hits.filter { $0.kind == .artist }.count, 1)
        XCTAssertEqual(hits.filter { $0.kind == .album }.count, 1)
        XCTAssertTrue(index.search(query: "   ", limitPerKind: 10).isEmpty)
    }

    private func song(id: String, name: String, artist: String, album: String) -> Song {
        Song(
            id: id, name: name, itemType: "Audio", album: album, albumId: "album", artists: [artist],
            artistIds: ["artist"], path: nil, duration: 30, albumArtUrl: nil, year: nil, playCount: nil,
            isFavorite: false, discNumber: 1, trackNumber: 1, container: "mp3", bitRate: nil, sampleRate: nil,
            codec: nil, genres: nil, premiereDate: nil, datePlayed: nil, dateCreated: nil, dateModified: nil,
            albumArtists: nil, lyrics: nil, imageTags: nil)
    }
}
