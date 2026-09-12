import SwiftUI

struct MainView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(AudioPlayerController.self) private var playerController
    @State private var selection: MainDestination = .home
    @State private var playerPresentationProgress: CGFloat = 0
    @State private var playerInitialPanel: PlayerView.Panel = .none

    var body: some View {
        MainTabView(
            selectedTab: $selection,
            playerPresentationProgress: $playerPresentationProgress,
            onMiniPlayerTap: { openPlayer(animated: true, panel: .none) },
            onMiniPlayerLyricsTap: { openPlayer(animated: true, panel: .lyrics) },
            onMiniPlayerQueueTap: { openPlayer(animated: true, panel: .queue) }
        )
        .overlay(alignment: .top) {
            GeometryReader { geometry in
                let containerHeight = max(
                    geometry.size.height + geometry.safeAreaInsets.top + geometry.safeAreaInsets.bottom, 1)

                if playerPresentationProgress > 0.0001 {
                    PlayerView(onClose: { closePlayer(animated: true) }, initialPanel: playerInitialPanel)
                        .frame(width: geometry.size.width, height: containerHeight)
                        .frame(maxWidth: .infinity, alignment: .top)
                        .ignoresSafeArea()
                        .offset(y: (1 - playerPresentationProgress) * containerHeight)
                        .simultaneousGesture(fullPlayerDismissGesture(containerHeight: containerHeight))
                        .allowsHitTesting(playerPresentationProgress > 0.01)
                        .zIndex(30)
                }
            }
            .ignoresSafeArea()
        }
        .onChange(of: playerController.snapshot.currentSongId) { _, newSongId in
            if newSongId == nil {
                closePlayer(animated: false)
            }
        }
        .tint(AureliaPalette.tint(for: colorScheme))
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: playerController.snapshot.currentSongId)
        .onReceive(NotificationCenter.default.publisher(for: .aureliaMenuCommand)) { notification in
            guard let command = notification.object as? AureliaMenuCommand else { return }
            handleMenuCommand(command)
        }
    }

    private func openPlayer(animated: Bool, panel: PlayerView.Panel = .none) {
        playerInitialPanel = panel
        if playerPresentationProgress <= 0 {
            playerPresentationProgress = 0.0001
        }
        let action = { playerPresentationProgress = 1 }
        if animated {
            withAnimation(.interactiveSpring(response: 0.34, dampingFraction: 0.88)) {
                action()
            }
        } else {
            action()
        }
    }

    private func closePlayer(animated: Bool) {
        let action = { playerPresentationProgress = 0 }
        if animated {
            withAnimation(.interactiveSpring(response: 0.34, dampingFraction: 0.9)) {
                action()
            }
        } else {
            action()
        }
    }

    private func fullPlayerDismissGesture(containerHeight: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { value in
                guard shouldTrackFullPlayerDismissDrag(value) else { return }
                let closeDistance = max(containerHeight * 0.8, 420)
                let progress = 1 - min(max(value.translation.height / closeDistance, 0), 1)
                playerPresentationProgress = progress
            }
            .onEnded { value in
                guard shouldTrackFullPlayerDismissDrag(value) else { return }
                let projectedDownDistance = max(value.translation.height, value.predictedEndTranslation.height)
                let shouldClose = projectedDownDistance > containerHeight * 0.18 || playerPresentationProgress < 0.6
                if shouldClose {
                    closePlayer(animated: true)
                } else {
                    openPlayer(animated: true)
                }
            }
    }

    private func shouldTrackFullPlayerDismissDrag(_ value: DragGesture.Value) -> Bool {
        let height = value.translation.height
        let width = value.translation.width
        let startsNearTop = value.startLocation.y <= 180
        return startsNearTop && height > 0 && abs(height) > abs(width) * 0.8
    }

    private func handleMenuCommand(_ command: AureliaMenuCommand) {
        switch command {
        case .goHome:
            selection = .home
        case .goSongs:
            selection = .songs
        case .goAlbums:
            selection = UIDevice.current.userInterfaceIdiom == .pad ? .albums : .songs
        case .goArtists:
            selection = UIDevice.current.userInterfaceIdiom == .pad ? .artists : .songs
        case .goSearch:
            selection = .search
        case .goSettings:
            selection = .settings
        case .openNowPlaying:
            guard playerController.snapshot.currentSongId != nil else { return }
            openPlayer(animated: true)
        case .togglePlayPause:
            if playerController.snapshot.isPlaying {
                playerController.pause()
            } else {
                playerController.resume()
            }
        case .nextTrack:
            playerController.skipNext()
        case .previousTrack:
            playerController.skipPrevious()
        case .toggleShuffle:
            playerController.toggleShuffle()
        case .cycleRepeatMode:
            playerController.cycleRepeatMode()
        }
    }

}
