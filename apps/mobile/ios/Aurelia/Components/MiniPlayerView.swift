import AureliaCore
import SwiftUI
import os

struct MiniPlayerView: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.colorScheme) private var colorScheme
    @Environment(AudioPlayerController.self) private var playerController
    @State private var favoriteCache: [String: Bool] = [:]
    @State private var isFavoriteLoading = false
    private let logger = Logger(subsystem: "com.aurelia.app", category: "MiniPlayerView")
    var onTap: () -> Void
    var onLyricsTap: (() -> Void)?
    var onQueueTap: (() -> Void)?
    var maximumWidth: CGFloat = 768
    var usesCompactLayout = false
    var usesSystemAccessory = false

    private var snapshot: PlayerSnapshot {
        playerController.snapshot
    }

    private var currentSong: Song? {
        let queue = playerController.getQueue()
        let currentIndex = playerController.getCurrentQueueIndex()
        guard currentIndex >= 0, currentIndex < queue.count else { return nil }
        return queue[currentIndex]
    }

    private var isFavorite: Bool {
        guard let songId = snapshot.currentSongId else { return false }
        if let cached = favoriteCache[songId] {
            return cached
        }
        return currentSong?.isFavorite ?? false
    }

    var body: some View {
        Group {
            if sizeClass == .regular && !usesCompactLayout {
                iPadMiniPlayerBar
            } else {
                compactMiniPlayerBar
            }
        }
        .onAppear {
            cacheFavoriteForCurrentSongIfNeeded()
        }
        .onChange(of: snapshot.currentSongId) { _, _ in
            cacheFavoriteForCurrentSongIfNeeded()
        }
        .onReceive(LibraryStore.shared.$snapshot) { _ in
            guard let id = snapshot.currentSongId,
                let favorite = LibraryStore.shared.favoriteState(for: id)
            else { return }
            favoriteCache[id] = favorite
        }
    }

    private var iPadMiniPlayerBar: some View {
        HStack(spacing: AureliaSpacing.m) {
            leftControls
                .frame(maxWidth: .infinity, alignment: .leading)

            centerNowPlaying
                .frame(maxWidth: .infinity)

            rightActions
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.horizontal, AureliaSpacing.m)
        .padding(.vertical, AureliaSpacing.s)
        .tint(.primary)
        .frame(height: 64)
        .glassEffectIfAvailable()
        .overlay {
            MiniPlayerVisualizerOverlay(
                isPlaying: snapshot.isPlaying,
                opacity: 0.25,
                isCapsuleStyle: true,
                boost: 1.0
            )
        }
        .clipShape(Capsule())
        .overlay {
            Capsule()
                .fill(Color.black.opacity(colorScheme == .dark ? 0.22 : 0.16))
                .allowsHitTesting(false)
        }
        .overlay {
            Capsule()
                .stroke(Color.white.opacity(colorScheme == .dark ? 0.16 : 0.24), lineWidth: 0.8)
                .allowsHitTesting(false)
        }
        .shadow(
            color: Color.black.opacity(colorScheme == .dark ? 0.38 : 0.12),
            radius: 16,
            x: 0,
            y: 8
        )
        .frame(maxWidth: maximumWidth)
        .contentShape(Capsule())
    }

    private var compactMiniPlayerBar: some View {
        HStack(spacing: 12) {
            Button(action: onTap) {
                HStack(spacing: 12) {
                    AlbumArtView(
                        url: snapshot.albumArtUrl, size: .miniPlayer,
                        customDimension: usesSystemAccessory ? 30 : nil
                    )
                    .padding(.leading, usesSystemAccessory ? 6 : 0)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(snapshot.title)
                            .font(.subheadline.weight(.medium))
                            .lineLimit(1)
                        Text(snapshot.artist)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }

                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Now playing: \(snapshot.title), \(snapshot.artist)")

            HStack(spacing: 0) {
                MiniPlayerUIKitButton(
                    accessibilityLabel: "Previous track",
                    systemName: "backward.fill",
                    fontSize: 17,
                    isEnabled: snapshot.hasPrevious,
                    action: { playerController.skipPrevious() }
                )
                .frame(width: 44, height: 44)

                MiniPlayerUIKitButton(
                    accessibilityLabel: snapshot.isPlaying ? "Pause" : "Play",
                    systemName: snapshot.isPlaying ? "pause.fill" : "play.fill",
                    fontSize: 24,
                    fontWeight: .bold,
                    action: { togglePlayPause() }
                )
                .frame(width: 44, height: 44)

                MiniPlayerUIKitButton(
                    accessibilityLabel: "Next track",
                    systemName: "forward.fill",
                    fontSize: 17,
                    isEnabled: snapshot.hasNext,
                    action: { playerController.skipNext() }
                )
                .frame(width: 44, height: 44)
            }
        }
        .padding(.horizontal, AureliaSpacing.s)
        .padding(.vertical, usesSystemAccessory ? 0 : AureliaSpacing.xs)
        .tint(.primary)
        .frame(maxWidth: .infinity, minHeight: usesSystemAccessory ? 44 : 64)
        .miniPlayerSurface(isSystemAccessory: usesSystemAccessory)
        .overlay {
            MiniPlayerVisualizerOverlay(
                isPlaying: snapshot.isPlaying,
                opacity: 0.25,
                isCapsuleStyle: true,
                boost: 1.0
            )
        }
        .contentShape(Rectangle())
    }

    private var leftControls: some View {
        HStack(spacing: 16) {
            miniControlButton(
                systemName: "shuffle",
                isActive: snapshot.isShuffled,
                action: { playerController.toggleShuffle() }
            )

            miniControlButton(
                systemName: "backward.fill",
                isEnabled: snapshot.hasPrevious,
                action: { playerController.skipPrevious() }
            )

            playPauseButton

            miniControlButton(
                systemName: "forward.fill",
                isEnabled: snapshot.hasNext,
                action: { playerController.skipNext() }
            )

            miniControlButton(
                systemName: snapshot.repeatMode == .one ? "repeat.1" : "repeat",
                isActive: snapshot.repeatMode != .none,
                action: { playerController.cycleRepeatMode() }
            )
        }
    }

    private var centerNowPlaying: some View {
        Button(action: onTap) {
            HStack(spacing: AureliaSpacing.s) {
                AlbumArtView(url: snapshot.albumArtUrl, size: .small)
                    .shadow(color: Color.black.opacity(0.18), radius: 6, x: 0, y: 3)

                VStack(alignment: .leading, spacing: 2) {
                    Text(snapshot.title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .foregroundStyle(.primary)

                    Text(snapshot.artist)
                        .font(.caption)
                        .lineLimit(1)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, AureliaSpacing.s)
            .padding(.vertical, AureliaSpacing.xs)
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .buttonStyle(.plain)
    }

    private var rightActions: some View {
        HStack(spacing: 16) {
            miniControlButton(
                systemName: "quote.bubble.fill",
                action: { onLyricsTap?() ?? onTap() }
            )

            miniControlButton(
                systemName: "list.bullet",
                action: { onQueueTap?() ?? onTap() }
            )

            miniControlButton(
                systemName: isFavorite ? "heart.fill" : "heart",
                tint: isFavorite ? .red : .secondary,
                isEnabled: !isFavoriteLoading,
                action: toggleFavorite
            )
        }
    }

    private var playPauseButton: some View {
        Button {
            togglePlayPause()
        } label: {
            Image(systemName: snapshot.isPlaying ? "pause.fill" : "play.fill")
                .font(.system(size: 22, weight: .bold))
                .frame(width: 44, height: 44)
                .foregroundStyle(.primary)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func miniControlButton(
        systemName: String,
        tint: Color? = nil,
        isEnabled: Bool = true,
        isActive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .foregroundStyle(tint ?? (isActive ? .primary : .secondary))
                .opacity(isEnabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
    }

    private func togglePlayPause() {
        if snapshot.isPlaying {
            playerController.pause()
        } else {
            playerController.resume()
        }
    }

    private func cacheFavoriteForCurrentSongIfNeeded() {
        guard let song = currentSong else { return }
        if favoriteCache[song.id] == nil {
            favoriteCache[song.id] = song.isFavorite ?? false
        }
    }

    private func toggleFavorite() {
        guard !isFavoriteLoading else { return }
        guard let songId = snapshot.currentSongId else { return }

        let sessionStore = SessionStore.shared
        guard let serverUrl = sessionStore.serverUrl,
            let token = sessionStore.token,
            let userId = sessionStore.userId
        else { return }

        let isCurrentlyFavorite = isFavorite
        let targetFavoriteState = !isCurrentlyFavorite
        isFavoriteLoading = true

        Task.detached {
            do {
                let newState = try await AureliaCore.toggleFavorite(
                    serverUrl: serverUrl,
                    token: token,
                    userId: userId,
                    itemId: songId,
                    isFavorite: targetFavoriteState
                )
                await MainActor.run {
                    favoriteCache[songId] = newState
                    LibraryStore.shared.updateFavorite(songId: songId, isFavorite: newState)
                    isFavoriteLoading = false
                }
            } catch {
                if await !AuthInterceptor.shared.handlePotentialAuthError(error) {
                    logger.error("Failed to toggle favorite from mini player: \(error)")
                }
                await MainActor.run {
                    isFavoriteLoading = false
                }
            }
        }
    }
}

private struct MiniPlayerVisualizerOverlay: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(AudioPlayerController.self) private var playerController
    let isPlaying: Bool
    let opacity: Double
    let isCapsuleStyle: Bool
    let boost: CGFloat

    var body: some View {
        let visualizerState = playerController.visualizerState
        let shouldShow =
            visualizerState.enabled
            && isPlaying
            && !visualizerState.frequencyData.isEmpty

        Group {
            if shouldShow {
                if isCapsuleStyle {
                    AudioVisualizerCanvas(
                        frequencyData: visualizerState.frequencyData,
                        waveformData: visualizerState.waveformData,
                        style: visualizerState.style,
                        accentColor: AureliaPalette.tint(for: colorScheme),
                        boost: boost
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .opacity(opacity)
                    .clipShape(Capsule())
                    .transition(.opacity)
                } else {
                    AudioVisualizerCanvas(
                        frequencyData: visualizerState.frequencyData,
                        waveformData: visualizerState.waveformData,
                        style: visualizerState.style,
                        accentColor: AureliaPalette.tint(for: colorScheme),
                        boost: boost
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .opacity(opacity)
                    .transition(.opacity)
                }
            }
        }
        .allowsHitTesting(false)
        .animation(.easeOut(duration: 0.2), value: shouldShow)
    }
}

// MARK: - UIKit Button for Tab Bar Bottom Accessory

/// A UIKit-backed button that properly participates in UIKit's gesture exclusion
/// system, preventing conflicts with the `tabViewBottomAccessory` system gesture.
/// SwiftUI `Button` and `onTapGesture` both conflict with the system's press
/// animation, causing it to start and abruptly cancel. UIKit `UIButton` handles
/// this correctly because it operates within the same gesture recognition system.
private struct MiniPlayerUIKitButton: UIViewRepresentable {
    let accessibilityLabel: String
    let systemName: String
    var fontSize: CGFloat = 17
    var fontWeight: UIImage.SymbolWeight = .semibold
    var isEnabled: Bool = true
    let action: () -> Void

    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(type: .system)
        button.addTarget(
            context.coordinator, action: #selector(Coordinator.tapped), for: .touchUpInside)
        button.setContentHuggingPriority(.required, for: .horizontal)
        button.setContentHuggingPriority(.required, for: .vertical)
        button.tintColor = .label
        return button
    }

    func updateUIView(_ button: UIButton, context: Context) {
        context.coordinator.action = action
        let config = UIImage.SymbolConfiguration(pointSize: fontSize, weight: fontWeight)
        let image = UIImage(systemName: systemName, withConfiguration: config)
        button.setImage(image, for: .normal)
        button.accessibilityLabel = accessibilityLabel
        button.isEnabled = isEnabled
        button.alpha = isEnabled ? 1 : 0.4
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(action: action)
    }

    final class Coordinator: NSObject {
        var action: () -> Void

        init(action: @escaping () -> Void) {
            self.action = action
        }

        @objc func tapped() {
            action()
        }
    }
}

extension View {
    @ViewBuilder
    fileprivate func miniPlayerSurface(isSystemAccessory: Bool) -> some View {
        if isSystemAccessory {
            // The tab accessory owns its Liquid Glass surface, shape, and width.
            // Nesting another glass layer obscures its translucency and clips its edge.
            self
        } else {
            aureliaGlass(in: Capsule())
        }
    }

    @ViewBuilder
    fileprivate func glassEffectIfAvailable() -> some View {
        if #available(iOS 26.0, *) {
            glassEffect()
        } else {
            background(.ultraThinMaterial)
        }
    }
}
