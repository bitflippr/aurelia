import AureliaCore
import Combine
import Foundation

struct LibrarySnapshot {
    var profilePath: String?
    var songs: [Song]?
    var isLoading = false
    var error: String?
}

@MainActor
final class LibraryStore: ObservableObject {
    static let shared = LibraryStore()
    @Published private(set) var snapshot = LibrarySnapshot()
    private var task: Task<Void, Never>?
    private var loadedAt: Date?
    private var generation: UInt64 = 0
    private var favoriteOverrides: [String: Bool] = [:]
    private var favoritesById: [String: Bool] = [:]
    private var favoriteRevision: UInt64 = 0

    func favoriteState(for songId: String) -> Bool? { favoritesById[songId] }

    func updateFavorite(songId: String, isFavorite: Bool) {
        favoriteRevision &+= 1
        favoritesById[songId] = isFavorite
        favoriteOverrides[songId] = isFavorite
        guard let index = snapshot.songs?.firstIndex(where: { $0.id == songId }) else { return }
        snapshot.songs?[index].isFavorite = isFavorite
    }

    func reset() {
        generation &+= 1
        task?.cancel()
        task = nil
        loadedAt = nil
        favoriteOverrides = [:]
        favoritesById = [:]
        favoriteRevision = 0
        snapshot = LibrarySnapshot()
    }

    func ensureLoaded(force: Bool = false) {
        guard let session = SessionStore.shared.snapshot() else {
            snapshot.error = "Missing session data"
            return
        }
        if snapshot.profilePath != session.appDataDir {
            reset()
            snapshot.profilePath = session.appDataDir
        }
        guard task == nil else { return }
        if !force, let loadedAt, Date().timeIntervalSince(loadedAt) < 60 { return }
        snapshot.isLoading = true
        snapshot.error = nil
        let requestGeneration = generation
        let requestFavoriteRevision = favoriteRevision
        task = Task {
            defer { if generation == requestGeneration { task = nil; snapshot.isLoading = false } }
            if snapshot.songs == nil {
                let cached = await Task.detached { try? loadCachedSongs(appDataDir: session.appDataDir) }.value
                guard !Task.isCancelled, generation == requestGeneration else { return }
                if let cached { publish(cached) }
            }
            do {
                let songs = try await fetchSongs(serverUrl: session.credentials.serverUrl, token: session.credentials.token, userId: session.credentials.userId, appDataDir: session.appDataDir)
                guard !Task.isCancelled, generation == requestGeneration else { return }
                if favoriteRevision == requestFavoriteRevision { favoriteOverrides = [:] }
                publish(songs)
                loadedAt = Date()
            } catch is CancellationError {
                return
            } catch {
                guard generation == requestGeneration else { return }
                if !AuthInterceptor.shared.handlePotentialAuthError(error) { snapshot.error = error.localizedDescription }
            }
        }
    }
    private func publish(_ songs: [Song]) {
        let resolved = songs.map { song in
            var song = song
            if let favorite = favoriteOverrides[song.id] { song.isFavorite = favorite }
            return song
        }
        favoritesById = Dictionary(resolved.map { ($0.id, $0.isFavorite ?? false) }, uniquingKeysWith: { first, _ in first })
        snapshot.songs = resolved
    }

}
