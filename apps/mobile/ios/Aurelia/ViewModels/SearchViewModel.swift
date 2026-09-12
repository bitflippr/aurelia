import AureliaCore
import Combine
import Foundation
import Observation

/// One index per library revision; typing never scans the full library on the UI thread.
@MainActor
@Observable
final class SearchViewModel {
    var query = "" { didSet { search() } }
    private(set) var results: [LibrarySearchHit] = []
    private(set) var songsById: [String: Song] = [:]
    private(set) var isLoading = false
    private(set) var isSearching = false
    private(set) var error: String?
    @ObservationIgnored private var subscription: AnyCancellable?
    @ObservationIgnored private var indexTask: Task<Void, Never>?
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    private var index: LibrarySearchIndex?
    private var librarySongs: [Song] = []
    private var profilePath: String?
    private var revision = 0
    private var request = 0

    func load() {
        if subscription == nil {
            subscription = LibraryStore.shared.$snapshot.sink { [weak self] snapshot in
                self?.receive(snapshot)
            }
        }
        LibraryStore.shared.ensureLoaded()
    }

    private func receive(_ snapshot: LibrarySnapshot) {
        isLoading = snapshot.isLoading
        error = snapshot.error
        let songs = snapshot.songs ?? []
        guard snapshot.profilePath != profilePath || songs != librarySongs else { return }
        profilePath = snapshot.profilePath
        librarySongs = songs
        revision += 1
        let generation = revision
        indexTask?.cancel()
        searchTask?.cancel()
        index = nil
        results = []
        songsById = [:]
        isSearching = !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        indexTask = Task {
            let built = await Task.detached(priority: .userInitiated) {
                (
                    LibrarySearchIndex(songs: songs),
                    Dictionary(songs.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
                )
            }.value
            guard !Task.isCancelled, generation == revision else { return }
            index = built.0
            songsById = built.1
            search()
        }
    }

    private func search() {
        request += 1
        let generation = request
        searchTask?.cancel()
        results = []
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            isSearching = false
            return
        }
        guard let index else {
            isSearching = isLoading || indexTask != nil
            return
        }
        isSearching = true
        searchTask = Task {
            do { try await Task.sleep(for: .milliseconds(150)) } catch { return }
            let hits = await Task.detached(priority: .userInitiated) {
                index.search(query: text, limitPerKind: 50)
            }.value
            guard !Task.isCancelled, generation == request else { return }
            results = hits
            isSearching = false
        }
    }
}
