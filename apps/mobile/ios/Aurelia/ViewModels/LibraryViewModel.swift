import AureliaCore
import Combine
import Foundation
import Observation

@MainActor
@Observable
final class LibraryViewModel {
    private(set) var isLoading = false
    private(set) var songs: [Song] = []
    private(set) var visibleSongs: [Song] = []
    private(set) var error: String?
    var searchText = "" { didSet { rebuild() } }
    var favoritesOnly = false { didSet { rebuild() } }
    var sort: LibrarySort = .title { didSet { rebuild() } }
    @ObservationIgnored private var librarySubscription: AnyCancellable?
    @ObservationIgnored private var filterTask: Task<Void, Never>?
    private var revision = 0

    func loadLibrary() {
        guard librarySubscription == nil else { LibraryStore.shared.ensureLoaded(); return }
        librarySubscription = LibraryStore.shared.$snapshot.sink { [weak self] snapshot in
            guard let self else { return }
            let incoming = snapshot.songs ?? []
            if incoming != songs {
                songs = incoming
                rebuild()
            }
            isLoading = snapshot.isLoading
            error = snapshot.error
        }
        LibraryStore.shared.ensureLoaded()
    }

    private func rebuild() {
        revision += 1
        let generation = revision
        let source = songs
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let favorites = favoritesOnly
        let order = sort
        filterTask?.cancel()
        filterTask = Task {
            let filtered = await Task.detached(priority: .userInitiated) {
                source.filter {
                    (!favorites || $0.isFavorite == true)
                        && (query.isEmpty || $0.name.localizedStandardContains(query)
                            || ($0.artists ?? []).contains { $0.localizedStandardContains(query) })
                }.sorted { lhs, rhs in
                    switch order {
                    case .title: lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
                    case .artist:
                        (lhs.artists?.first ?? "", lhs.name, lhs.id) < (rhs.artists?.first ?? "", rhs.name, rhs.id)
                    case .recent: (lhs.dateCreated ?? "", lhs.id) > (rhs.dateCreated ?? "", rhs.id)
                    }
                }
            }.value
            guard !Task.isCancelled, revision == generation else { return }
            visibleSongs = filtered
        }
    }
}

enum LibrarySort: String, CaseIterable, Identifiable, Sendable {
    case title = "Title"
    case artist = "Artist"
    case recent = "Recently added"
    var id: Self { self }
}
