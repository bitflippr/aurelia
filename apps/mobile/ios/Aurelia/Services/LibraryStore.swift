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

    func reset() {
        generation &+= 1
        task?.cancel()
        task = nil
        loadedAt = nil
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
        task = Task {
            defer { if generation == requestGeneration { task = nil; snapshot.isLoading = false } }
            if snapshot.songs == nil {
                let cached = await Task.detached { try? loadCachedSongs(appDataDir: session.appDataDir) }.value
                guard !Task.isCancelled, generation == requestGeneration else { return }
                snapshot.songs = cached
            }
            do {
                let songs = try await fetchSongs(serverUrl: session.credentials.serverUrl, token: session.credentials.token, userId: session.credentials.userId, appDataDir: session.appDataDir)
                guard !Task.isCancelled, generation == requestGeneration else { return }
                snapshot.songs = songs
                loadedAt = Date()
            } catch is CancellationError {
                return
            } catch {
                guard generation == requestGeneration else { return }
                if !AuthInterceptor.shared.handlePotentialAuthError(error) { snapshot.error = error.localizedDescription }
            }
        }
    }
}
