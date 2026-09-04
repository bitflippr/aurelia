import AureliaCore
import Combine
import Foundation
import Observation
import os

@MainActor
@Observable
final class LibraryViewModel {
    var isLoading = false
    var songs: [Song] = []
    var error: String?

    private let sessionStore = SessionStore.shared
    private let logger = Logger(subsystem: "com.aurelia.app", category: "LibraryViewModel")

    @ObservationIgnored private var librarySubscription: AnyCancellable?

    init() {
        librarySubscription = LibraryStore.shared.$snapshot.sink { [weak self] snapshot in
            self?.songs = snapshot.songs ?? []
            self?.isLoading = snapshot.isLoading
            self?.error = snapshot.error
        }
    }

    func loadLibrary() { LibraryStore.shared.ensureLoaded() }

    func playFromList(_ songId: String, playerController: AudioPlayerController) {
        guard let serverUrl = sessionStore.serverUrl, let token = sessionStore.token else { return }
        guard let startIndex = songs.firstIndex(where: { $0.id == songId }) else { return }
        playerController.setQueue(songs, serverUrl: serverUrl, token: token, startIndex: startIndex)
    }
}
