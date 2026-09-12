import AureliaCore
import SwiftUI
import UIKit

struct PlayerView: View {
    enum Panel: Hashable {
        case none
        case lyrics
        case queue
    }

    private enum ArtSwipeAxisLock {
        case undecided
        case horizontal
        case vertical
    }

    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.colorScheme) private var colorScheme
    @Environment(AudioPlayerController.self) private var playerController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage("animatedArtworkEnabled") private var animatedArtworkEnabled = true
    @State private var animatedArtwork = AnimatedArtworkController()
    var onClose: (() -> Void)?

    @State private var viewModel = PlayerViewModel()
    @State private var isDragging = false
    @State private var dragPosition: Double = 0
    @State private var activePanel: Panel = .none
    @State private var usesSplitLayout = false
    @State private var artSwipeOffset: CGFloat = 0
    @State private var artSwipeAxisLock: ArtSwipeAxisLock = .undecided
    var initialPanel: Panel = .none

    private let artSwipeThreshold: CGFloat = 72
    private let artSwipeMaxOffset: CGFloat = 110

    var body: some View {
        GeometryReader { geometry in
            let split = AureliaLayout.usesSplitPlayer(
                width: geometry.size.width, height: geometry.size.height,
                accessibilitySize: typeSize.isAccessibilitySize)
            let horizontalInset: CGFloat = split ? 36 : 16
            let topSafeInset = max(geometry.safeAreaInsets.top, statusBarTopInset)
            let contentTopPadding = topSafeInset + (split ? 36 : 64)
            let bottomPadding = max(geometry.safeAreaInsets.bottom + 12, 24)
            let contentHeight = max(0, geometry.size.height - contentTopPadding - bottomPadding)
            let width = max(geometry.size.width - horizontalInset * 2, 240)

            ZStack {
                PlayerBackgroundVisualizer(isPlaying: viewModel.isPlaying)
                if split {
                    splitPlayerLayout(width: width, height: contentHeight)
                        .padding(.horizontal, horizontalInset)
                        .padding(.top, contentTopPadding)
                        .padding(.bottom, bottomPadding)
                } else {
                    compactPlayerLayout(
                        playerWidth: min(width, 500), horizontalInset: horizontalInset,
                        contentTopPadding: contentTopPadding, bottomPadding: bottomPadding,
                        centeredContentHeight: contentHeight)
                }
            }
            .onAppear { updateLayout(split: split) }
            .onChange(of: split) { _, value in updateLayout(split: value) }
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: .center)
            .background {
                PlayerArtworkBackdrop(
                    albumArtUrl: viewModel.albumArtUrl, videoFrame: animatedArtwork.backdrop,
                    isPlaying: viewModel.isPlaying && scenePhase == .active && !reduceMotion)
            }
            .overlay(alignment: .top) {
                topBar(topSafeInset: topSafeInset)
            }
        }
        .preferredColorScheme(.dark)
        .task(
            id: "\(viewModel.currentAlbumId ?? viewModel.currentSongId ?? "")-\(animatedArtworkEnabled)-\(reduceMotion)"
        ) {
            guard animatedArtworkEnabled, !reduceMotion else {
                animatedArtwork.stop()
                return
            }
            await animatedArtwork.load(itemId: viewModel.currentSongId)
            animatedArtwork.setPlaying(viewModel.isPlaying && scenePhase == .active)
        }
        .task(id: viewModel.isPlaying && scenePhase == .active && animatedArtworkEnabled && !reduceMotion) {
            let playing = viewModel.isPlaying && scenePhase == .active && animatedArtworkEnabled && !reduceMotion
            animatedArtwork.setPlaying(playing)
            guard playing else { return }
            while !Task.isCancelled {
                animatedArtwork.updateBackdrop()
                do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
            }
        }
        .onDisappear { animatedArtwork.stop() }
        .onReceive(LibraryStore.shared.$snapshot) { _ in
            viewModel.updateFrom(
                playerController.snapshot, position: playerController.playbackPosition,
                playerController: playerController)
        }
        .onChange(of: playerController.snapshot) { _, snapshot in
            viewModel.updateFrom(
                snapshot, position: playerController.playbackPosition, playerController: playerController)
        }
        .onChange(of: playerController.playbackPosition) { _, position in
            viewModel.updateFrom(playerController.snapshot, position: position, playerController: playerController)
        }
        .onChange(of: viewModel.showLyrics) { _, isShown in
            if !isShown, activePanel == .lyrics {
                withAnimation(.easeInOut(duration: 0.35)) {
                    activePanel = .none
                }
            }
        }
        .onAppear {
            viewModel.updateFrom(
                playerController.snapshot, position: playerController.playbackPosition,
                playerController: playerController)
            if initialPanel != .none {
                withAnimation(.easeInOut(duration: 0.35)) {
                    activePanel = initialPanel
                }
                if initialPanel == .lyrics, viewModel.showLyrics == false {
                    viewModel.toggleLyrics()
                }
            }
        }
    }

    private func updateLayout(split: Bool) {
        usesSplitLayout = split
    }

    private func selectPanel(_ panel: Panel) {
        withAnimation(.easeInOut(duration: 0.35)) {
            activePanel = panel
        }
        if panel == .lyrics, !viewModel.showLyrics { viewModel.toggleLyrics() }
        if panel != .lyrics, viewModel.showLyrics { viewModel.toggleLyrics() }
    }

    private func splitPlayerLayout(width: CGFloat, height: CGFloat) -> some View {
        let leftWidth = activePanel == .none ? min(500, width) : min(500, (width - 64) * 0.43)
        let artSize = min(leftWidth, max(180, height - 260))
        return HStack(alignment: .center, spacing: 64) {
            ScrollView(showsIndicators: false) {
                VStack(spacing: 16) {
                    artworkHero(artSize: artSize)
                    HStack(alignment: .center, spacing: 12) {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(viewModel.title).font(.title2.weight(.semibold)).lineLimit(2)
                            Text(viewModel.artist).font(.title3).foregroundStyle(.white.opacity(0.70)).lineLimit(2)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Button("Favorite", systemImage: viewModel.isFavorite ? "heart.fill" : "heart") {
                            viewModel.toggleFavorite()
                        }
                        .labelStyle(.iconOnly).frame(width: 44, height: 44)
                        .foregroundStyle(.white).buttonStyle(.plain)
                        .disabled(viewModel.isFavoriteLoading)
                        if viewModel.queue.indices.contains(viewModel.currentQueueIndex) {
                            Image(systemName: "ellipsis")
                                .font(.title3.weight(.semibold))
                                .frame(width: 44, height: 44)
                                .songActions(viewModel.queue[viewModel.currentQueueIndex], menu: true)
                                .accessibilityLabel("Song actions")
                        }
                    }
                    progressSection
                    playbackControls
                    HStack(spacing: 12) {
                        panelChip(
                            icon: "quote.bubble", title: "Lyrics", isActive: activePanel == .lyrics,
                            action: toggleLyricsPanel)
                        panelChip(
                            icon: "list.bullet", title: "Queue", isActive: activePanel == .queue,
                            isEnabled: !viewModel.queue.isEmpty, action: toggleQueuePanel)
                        AudioRoutePicker().frame(width: 44, height: 44).colorScheme(.dark)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .frame(minHeight: height)
            }.frame(width: leftWidth)
            if activePanel != .none {
                VStack(spacing: 22) {
                    HStack {
                        Spacer()
                        Button {
                            selectPanel(.none)
                        } label: {
                            Image(systemName: "xmark")
                                .frame(width: 44, height: 44)
                        }
                        .aureliaGlassButton()
                        .accessibilityLabel("Close player panel")
                    }
                    if activePanel == .lyrics { lyricsCard } else { queueCard }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
    }

    private func compactPlayerLayout(
        playerWidth: CGFloat,
        horizontalInset: CGFloat,
        contentTopPadding: CGFloat,
        bottomPadding: CGFloat,
        centeredContentHeight: CGFloat
    ) -> some View {
        let artworkWidth = playerWidth

        return ViewThatFits(in: .vertical) {
            VStack(spacing: AureliaSpacing.l) {
                compactArtworkPanelSlot(width: artworkWidth)
                controlsColumn(horizontalPadding: AureliaSpacing.m)
                    .frame(maxWidth: playerWidth)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
            .frame(height: centeredContentHeight, alignment: .bottom)
            .padding(.horizontal, horizontalInset)
            .padding(.top, contentTopPadding)
            .padding(.bottom, bottomPadding)

            ScrollView(showsIndicators: false) {
                VStack(spacing: AureliaSpacing.l) {
                    compactArtworkPanelSlot(width: playerWidth)

                    controlsColumn(horizontalPadding: AureliaSpacing.m)
                        .frame(maxWidth: playerWidth)
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, horizontalInset)
                .padding(.top, contentTopPadding)
                .padding(.bottom, max(bottomPadding, AureliaSpacing.xl))
            }
        }
    }

    private func compactArtworkPanelSlot(width: CGFloat) -> some View {
        Group {
            if activePanel == .none {
                artworkHero(artSize: width)
                    .frame(width: width, height: width, alignment: .center)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            } else {
                panelView
                    .frame(width: width, alignment: .topLeading)
                    .frame(maxHeight: .infinity, alignment: .topLeading)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
        }
        .frame(
            maxWidth: .infinity,
            maxHeight: .infinity,
            alignment: activePanel == .none ? .bottom : .top
        )
    }

    private func artworkHero(artSize: CGFloat) -> some View {
        let cornerRadius: CGFloat = 16
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        return ZStack {
            AlbumArtView(
                url: viewModel.albumArtUrl, size: .extraLarge, customDimension: artSize,
                cornerRadius: cornerRadius, showsBorder: false)
            ArtworkVideoView(player: animatedArtwork.player)
                .frame(width: artSize, height: artSize)
                .opacity(animatedArtwork.ready ? 1 : 0)
                .allowsHitTesting(false)
        }
        .frame(width: artSize, height: artSize)
        .clipShape(shape)
        .shadow(color: Color.black.opacity(colorScheme == .dark ? 0.36 : 0.16), radius: 18, x: 0, y: 10)
        .overlay(
            shape.strokeBorder(Color.white.opacity(colorScheme == .dark ? 0.18 : 0.30), lineWidth: 1)
        )
        .offset(x: artSwipeOffset)
        .highPriorityGesture(albumArtSwipeGesture)
    }

    private func controlsColumn(horizontalPadding: CGFloat) -> some View {
        VStack(spacing: AureliaSpacing.m) {
            songInfo
            progressSection
            playbackControls
            secondaryControls
        }
        .padding(.horizontal, horizontalPadding)
        .padding(.vertical, AureliaSpacing.l)
    }

    private var panelView: some View {
        Group {
            switch activePanel {
            case .lyrics:
                lyricsCard
            case .queue:
                queueCard
            case .none:
                EmptyView()
            }
        }
    }

    private var songInfo: some View {
        VStack(spacing: 4) {
            Text(viewModel.title)
                .font(.title2.weight(.bold))
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.8)

            Text(viewModel.artist)
                .font(.headline.weight(.medium))
                .foregroundStyle(.white.opacity(0.82))
                .lineLimit(1)

            if let formatInfo = viewModel.formatInfo {
                Text(formatInfo)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.58))
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var progressSection: some View {
        VStack(spacing: 6) {
            Slider(
                value: Binding(
                    get: { isDragging ? dragPosition : Double(viewModel.positionMs) },
                    set: { newValue in
                        dragPosition = newValue
                        isDragging = true
                    }
                ),
                in: 0...max(Double(viewModel.durationMs), 1),
                onEditingChanged: { editing in
                    if !editing {
                        viewModel.seekTo(Int64(dragPosition), playerController: playerController)
                        isDragging = false
                    }
                }
            )
            .tint(.white)

            HStack {
                Text(TimeFormatter.formatDuration(isDragging ? Int64(dragPosition) : viewModel.positionMs))
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.70))
                    .monospacedDigit()

                Spacer()

                Text(TimeFormatter.formatDuration(viewModel.durationMs))
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.70))
                    .monospacedDigit()
            }
        }
    }

    private var playbackControls: some View {
        HStack(spacing: usesSplitLayout ? 0 : 8) {
            iconControl(
                systemName: "shuffle",
                isActive: viewModel.isShuffled,
                action: { viewModel.toggleShuffle(playerController: playerController) }
            )

            if usesSplitLayout { Spacer(minLength: 0) }

            iconControl(
                systemName: "backward.fill",
                isEnabled: viewModel.hasPrevious,
                action: { viewModel.skipPrevious(playerController: playerController) }
            )

            if usesSplitLayout { Spacer(minLength: 0) }

            if usesSplitLayout {
                playPauseButton.buttonStyle(.plain)
            } else {
                playPauseButton
                    .aureliaGlassButton(prominent: true)
                    .buttonBorderShape(.circle)
            }

            if usesSplitLayout { Spacer(minLength: 0) }

            iconControl(
                systemName: "forward.fill",
                isEnabled: viewModel.hasNext,
                action: { viewModel.skipNext(playerController: playerController) }
            )

            if usesSplitLayout { Spacer(minLength: 0) }

            iconControl(
                systemName: viewModel.repeatMode == .one ? "repeat.1" : "repeat",
                isActive: viewModel.repeatMode != .none,
                action: { viewModel.cycleRepeatMode(playerController: playerController) }
            )
        }
    }

    private var playPauseButton: some View {
        Button {
            viewModel.togglePlayPause(playerController: playerController)
        } label: {
            Image(systemName: viewModel.isPlaying ? "pause.fill" : "play.fill")
                .font(.system(size: usesSplitLayout ? 40 : 34, weight: .bold))
                .frame(width: 68, height: 68)
                .foregroundStyle(.white)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(viewModel.isPlaying ? "Pause" : "Play")
    }

    private var secondaryControls: some View {
        HStack(spacing: AureliaSpacing.s) {
            panelChip(
                icon: "quote.bubble",
                title: "Lyrics",
                isActive: activePanel == .lyrics,
                action: toggleLyricsPanel
            )

            panelChip(
                icon: "list.bullet",
                title: "Queue",
                isActive: activePanel == .queue,
                isEnabled: !viewModel.queue.isEmpty,
                action: toggleQueuePanel
            )

            panelChip(
                icon: viewModel.isFavorite ? "heart.fill" : "heart",
                title: "Favorite",
                isActive: viewModel.isFavorite,
                tint: viewModel.isFavorite ? AureliaPalette.tint(for: colorScheme) : .white,
                isEnabled: !viewModel.isFavoriteLoading,
                action: { viewModel.toggleFavorite() }
            )
        }
        .frame(maxWidth: .infinity)
    }

    private var lyricsCard: some View {
        VStack(alignment: .leading, spacing: AureliaSpacing.s) {
            if viewModel.lyricsUnavailable {
                ContentUnavailableView(
                    "No lyrics available", systemImage: "quote.bubble",
                    description: Text("Keep listening, or open the queue."))
            } else if let lyrics = viewModel.lyrics {
                LyricsView(
                    lyrics: lyrics,
                    positionMs: viewModel.positionMs,
                    isPlaying: viewModel.isPlaying,
                    updateTimeMs: viewModel.positionUpdateTimeMs,
                    onSeekToMs: { targetMs in
                        viewModel.seekTo(targetMs, playerController: playerController)
                    },
                    usesExpandedLayout: usesSplitLayout
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            } else {
                VStack(spacing: AureliaSpacing.s) {
                    ProgressView()
                        .tint(.white)
                    Text("Loading lyrics…")
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.74))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(.horizontal, AureliaSpacing.s)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var queueCard: some View {
        VStack(alignment: .leading, spacing: AureliaSpacing.s) {
            panelHeader(title: "Up Next", subtitle: "\(viewModel.queue.count) tracks")

            ScrollView(showsIndicators: false) {
                LazyVStack(spacing: 2) {
                    ForEach(Array(viewModel.queue.enumerated()), id: \.offset) { index, song in
                        queueRow(song: song, index: index)
                    }
                }
            }
        }
        .padding(.horizontal, AureliaSpacing.s)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    @ViewBuilder
    private func topBar(topSafeInset: CGFloat) -> some View {
        if usesSplitLayout {
            Button {
                if let onClose { onClose() } else { dismiss() }
            } label: {
                Capsule()
                    .fill(.white.opacity(0.5))
                    .frame(width: 60, height: 5)
                    .frame(width: 100, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close player")
            .frame(maxWidth: .infinity)
            .padding(.top, topSafeInset)
        } else {
            HStack(spacing: AureliaSpacing.s) {
                Button {
                    if let onClose {
                        onClose()
                    } else {
                        dismiss()
                    }
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.headline.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .foregroundStyle(.white)
                        .aureliaGlass(in: Circle(), interactive: true)
                }
                .buttonStyle(.plain)

                Spacer()
                Text("Now Playing").font(.subheadline.weight(.semibold)).foregroundStyle(.white.opacity(0.8))
                Spacer()

                if viewModel.queue.indices.contains(viewModel.currentQueueIndex) {
                    let song = viewModel.queue[viewModel.currentQueueIndex]
                    Image(systemName: "ellipsis")
                        .font(.headline).foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .aureliaGlass(in: Circle(), interactive: true)
                        .songActions(song, menu: true)
                        .accessibilityLabel("Song actions")
                }
            }
            .padding(.horizontal, AureliaSpacing.m)
            .padding(.top, topSafeInset + AureliaSpacing.s)
            .padding(.bottom, AureliaSpacing.s)
            .background(
                LinearGradient(
                    colors: [Color.black.opacity(0.24), Color.black.opacity(0)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
        }
    }

    private var statusBarTopInset: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .map(\.safeAreaInsets.top)
            .max() ?? 0
    }

    private func iconControl(
        systemName: String,
        isEnabled: Bool = true,
        isActive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(
                    .system(
                        size: usesSplitLayout && ["backward.fill", "forward.fill"].contains(systemName) ? 30 : 23,
                        weight: .semibold)
                )
                .foregroundStyle(isActive ? .white : .white.opacity(0.84))
                .opacity(isEnabled ? 1 : 0.4)
                .frame(minWidth: 44, minHeight: 44)
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
    }

    private func panelChip(
        icon: String,
        title: String,
        isActive: Bool,
        tint: Color = .white,
        isEnabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(tint.opacity(isEnabled ? 1 : 0.5))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(minHeight: 28)
                .aureliaGlass(in: Capsule(), interactive: true)
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
    }

    private func panelHeader(title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.headline.weight(.semibold))
                .foregroundStyle(.white)

            Text(subtitle)
                .font(.caption)
                .foregroundStyle(.white.opacity(0.58))
        }
    }

    private func queueRow(song: Song, index: Int) -> some View {
        let isCurrent = index == viewModel.currentQueueIndex

        return Button {
            viewModel.playQueueItem(index, playerController: playerController)
        } label: {
            HStack(spacing: AureliaSpacing.s) {
                Text(isCurrent ? "•" : "\(index + 1)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(isCurrent ? .white : .white.opacity(0.56))
                    .frame(width: 20, alignment: .leading)

                VStack(alignment: .leading, spacing: 2) {
                    Text(song.name)
                        .font(.subheadline.weight(isCurrent ? .semibold : .medium))
                        .lineLimit(1)
                        .foregroundStyle(isCurrent ? .white : .white.opacity(0.84))

                    Text(song.artists?.joined(separator: ", ") ?? "")
                        .font(.caption)
                        .lineLimit(1)
                        .foregroundStyle(.white.opacity(0.56))
                }

                Spacer()
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(isCurrent ? Color.white.opacity(0.14) : Color.clear)
            )
        }
        .buttonStyle(.plain)
    }

    private func toggleLyricsPanel() {
        if activePanel == .lyrics {
            if viewModel.showLyrics {
                viewModel.toggleLyrics()
            }
            withAnimation(.easeInOut(duration: 0.35)) {
                activePanel = .none
            }
            return
        }

        if viewModel.showLyrics == false {
            viewModel.toggleLyrics()
        }

        withAnimation(.easeInOut(duration: 0.35)) {
            activePanel = .lyrics
        }
    }

    private func toggleQueuePanel() {
        guard !viewModel.queue.isEmpty else { return }

        if activePanel == .queue {
            withAnimation(.easeInOut(duration: 0.35)) {
                activePanel = .none
            }
            return
        }

        if viewModel.showLyrics {
            viewModel.toggleLyrics()
        }

        withAnimation(.easeInOut(duration: 0.35)) {
            activePanel = .queue
        }
    }

    private var albumArtSwipeGesture: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard activePanel == .none || usesSplitLayout else { return }
                if artSwipeAxisLock == .undecided {
                    let horizontal = abs(value.translation.width)
                    let vertical = abs(value.translation.height)
                    if max(horizontal, vertical) < 6 {
                        return
                    }
                    artSwipeAxisLock = horizontal >= vertical ? .horizontal : .vertical
                }

                guard artSwipeAxisLock == .horizontal else { return }
                let bounded = max(-artSwipeMaxOffset, min(value.translation.width, artSwipeMaxOffset))
                artSwipeOffset = bounded
            }
            .onEnded { value in
                defer {
                    withAnimation(.interactiveSpring(response: 0.28, dampingFraction: 0.86)) {
                        artSwipeOffset = 0
                    }
                    artSwipeAxisLock = .undecided
                }

                guard activePanel == .none || usesSplitLayout, artSwipeAxisLock == .horizontal else { return }

                let projected = value.predictedEndTranslation.width
                let triggerDistance =
                    abs(projected) > abs(value.translation.width) ? projected : value.translation.width

                if triggerDistance <= -artSwipeThreshold, viewModel.hasNext {
                    viewModel.skipNext(playerController: playerController)
                } else if triggerDistance >= artSwipeThreshold, viewModel.hasPrevious {
                    viewModel.skipPrevious(playerController: playerController)
                }
            }
    }
}

private struct PlayerBackgroundVisualizer: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(AudioPlayerController.self) private var playerController
    let isPlaying: Bool

    var body: some View {
        let visualizerState = playerController.visualizerState
        let shouldShow =
            visualizerState.enabled
            && isPlaying
            && !visualizerState.frequencyData.isEmpty

        Group {
            if shouldShow {
                AudioVisualizerCanvas(
                    frequencyData: visualizerState.frequencyData,
                    waveformData: visualizerState.waveformData,
                    style: visualizerState.style,
                    accentColor: AureliaPalette.tint(for: colorScheme),
                    boost: 1.0
                )
                .frame(height: 160)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .opacity(0.40)
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.25), value: shouldShow)
    }
}

struct AudioVisualizerCanvas: View {
    let frequencyData: [UInt8]
    let waveformData: [UInt8]
    let style: VisualizerStyle
    let accentColor: Color
    var boost: CGFloat = 1.0

    var body: some View {
        Canvas(rendersAsynchronously: true) { context, size in
            guard !frequencyData.isEmpty || !waveformData.isEmpty else { return }

            switch style {
            case .bars:
                drawBars(context: &context, size: size)
            case .curve:
                drawCurve(context: &context, size: size)
            case .wave:
                drawWave(context: &context, size: size)
            }
        }
        .allowsHitTesting(false)
    }

    private func drawBars(context: inout GraphicsContext, size: CGSize) {
        guard !frequencyData.isEmpty else { return }

        let barCount = 64
        let barWidth = size.width / CGFloat(barCount)
        let heightScale = (size.height / 255) * boost
        let dataCount = frequencyData.count
        let dataStep = CGFloat(dataCount) / CGFloat(barCount)

        let gradient = Gradient(colors: [
            accentColor.opacity(0.13),
            accentColor.opacity(0.50),
        ])
        let shading = GraphicsContext.Shading.linearGradient(
            gradient,
            startPoint: CGPoint(x: 0, y: 0),
            endPoint: CGPoint(x: 0, y: size.height)
        )

        for i in 0..<barCount {
            let dataIndex = min(dataCount - 1, Int(CGFloat(i) * dataStep))
            let value = CGFloat(Int(frequencyData[dataIndex]))
            let barHeight = min(value * heightScale, size.height)
            let width = max(1, barWidth - 2)
            let rect = CGRect(
                x: CGFloat(i) * barWidth,
                y: size.height - barHeight,
                width: width,
                height: barHeight
            )
            context.fill(Path(rect), with: shading)
        }
    }

    private func drawCurve(context: inout GraphicsContext, size: CGSize) {
        guard !frequencyData.isEmpty else { return }

        let sampleCount = 64
        let stepX = size.width / CGFloat(max(sampleCount - 1, 1))
        let heightScale = (size.height / 255) * boost
        let dataCount = frequencyData.count
        let dataStep = CGFloat(dataCount) / CGFloat(sampleCount)
        var crestPoints: [CGPoint] = []
        crestPoints.reserveCapacity(sampleCount)

        for i in 0..<sampleCount {
            let dataIndex = min(dataCount - 1, Int(CGFloat(i) * dataStep))
            let value = CGFloat(Int(frequencyData[dataIndex]))
            let amplitude = min(value * heightScale, size.height)
            crestPoints.append(CGPoint(x: CGFloat(i) * stepX, y: size.height - amplitude))
        }

        guard crestPoints.count >= 2 else { return }

        var fillPath = Path()
        fillPath.move(to: CGPoint(x: 0, y: size.height))
        fillPath.addLine(to: crestPoints[0])
        for i in 0..<(crestPoints.count - 1) {
            let current = crestPoints[i]
            let next = crestPoints[i + 1]
            fillPath.addQuadCurve(
                to: CGPoint(x: (current.x + next.x) / 2, y: (current.y + next.y) / 2),
                control: current
            )
        }
        fillPath.addLine(to: crestPoints[crestPoints.count - 1])
        fillPath.addLine(to: CGPoint(x: size.width, y: size.height))
        fillPath.closeSubpath()

        let fillGradient = Gradient(colors: [
            accentColor.opacity(0.09),
            accentColor.opacity(0.25),
            accentColor.opacity(0.44),
        ])
        let fillShading = GraphicsContext.Shading.linearGradient(
            fillGradient,
            startPoint: CGPoint(x: 0, y: 0),
            endPoint: CGPoint(x: 0, y: size.height)
        )
        context.fill(fillPath, with: fillShading)

        var strokePath = Path()
        strokePath.move(to: crestPoints[0])
        for i in 0..<(crestPoints.count - 1) {
            let current = crestPoints[i]
            let next = crestPoints[i + 1]
            strokePath.addQuadCurve(
                to: CGPoint(x: (current.x + next.x) / 2, y: (current.y + next.y) / 2),
                control: current
            )
        }
        strokePath.addLine(to: crestPoints[crestPoints.count - 1])

        context.stroke(
            strokePath,
            with: .color(accentColor.opacity(0.56)),
            style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
        )
    }

    private func drawWave(context: inout GraphicsContext, size: CGSize) {
        guard !waveformData.isEmpty else { return }

        let dataCount = waveformData.count
        let sliceWidth = size.width / CGFloat(dataCount)
        let centerY = size.height / 2

        var path = Path()
        var x: CGFloat = 0

        for i in 0..<dataCount {
            let deviation = (CGFloat(Int(waveformData[i])) - 128) * boost
            let y = centerY - (deviation / 128) * centerY
            let point = CGPoint(x: x, y: y)
            if i == 0 {
                path.move(to: point)
            } else {
                path.addLine(to: point)
            }
            x += sliceWidth
        }

        context.stroke(
            path,
            with: .color(accentColor.opacity(0.50)),
            style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
        )
    }
}

struct LyricsView: View {
    let lyrics: Lyrics
    let positionMs: Int64
    let isPlaying: Bool
    let updateTimeMs: Int64
    var onSeekToMs: ((Int64) -> Void)?
    var usesExpandedLayout = false
    @State private var lastCenteredIndex: Int?

    private var lyricFont: Font {
        .system(
            size: usesExpandedLayout ? 42 : 25,
            weight: usesExpandedLayout ? .bold : .semibold,
            design: usesExpandedLayout ? .default : .rounded)
    }

    /// Whether these lyrics contain any word-level sync data.
    private var hasWordSync: Bool {
        lyrics.synced?.contains(where: { $0.words?.isEmpty == false }) ?? false
    }

    var body: some View {
        if let synced = lyrics.synced, !synced.isEmpty {
            if hasWordSync {
                // Use TimelineView for high-frequency updates (~60fps) to drive
                // smooth word-level karaoke fill, similar to Apple Music.
                TimelineView(.animation(minimumInterval: nil, paused: !isPlaying)) { timeline in
                    let interpolatedMs = interpolatedPositionMs(at: timeline.date)
                    syncedLyricsView(synced, currentPositionMs: interpolatedMs)
                }
            } else {
                syncedLyricsView(synced, currentPositionMs: positionMs)
            }
        } else if let plain = lyrics.plain, !plain.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            plainLyricsView(plain)
        } else {
            ContentUnavailableView(
                "No Lyrics", systemImage: "quote.bubble", description: Text("No lyrics were found for this track.")
            )
            .foregroundStyle(.white.opacity(0.78))
        }
    }

    // MARK: - Time Interpolation

    /// Interpolate the current playback position from the last known snapshot
    /// using system uptime, giving us sub-frame accuracy for word fill.
    private func interpolatedPositionMs(at _: Date) -> Int64 {
        guard isPlaying, updateTimeMs > 0 else { return positionMs }
        let nowMs = Int64(ProcessInfo.processInfo.systemUptime * 1000)
        let elapsed = nowMs - updateTimeMs
        return positionMs + max(0, elapsed)
    }

    // MARK: - Section Labels

    /// Build a lookup of line start-time to section name for displaying dividers.
    private var sectionLabels: [TimeInterval: String] {
        guard let sections = lyrics.sections else { return [:] }
        var labels: [TimeInterval: String] = [:]
        for section in sections {
            if !section.name.isEmpty, let firstLine = section.lines.first {
                labels[firstLine.time] = section.name
            }
        }
        return labels
    }

    // MARK: - Line State

    /// Describes a line's visual state for rendering.
    /// - `active`: the primary line being sung — words fill with karaoke animation.
    /// - `inactive`: not the current line — uniform dim opacity, no glow.
    private enum LineState: Equatable {
        case active
        case inactive
    }

    // MARK: - Active Line / Word Detection

    private func activeLineIndex(in synced: [SyncedLine], currentPositionMs: Int64) -> Int? {
        guard !synced.isEmpty else { return nil }

        let currentTime = Double(currentPositionMs) / 1000.0
        let tolerance = 0.01
        var low = 0
        var high = synced.count - 1
        var bestIndex: Int?

        // Binary search for the last line whose start time <= currentTime.
        // If we're in a gap between lines, this keeps the previous line active
        // for continuity (matching Apple Music behavior).
        while low <= high {
            let mid = (low + high) / 2
            if synced[mid].time <= currentTime + tolerance {
                bestIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return bestIndex
    }

    /// Determine the visual state of a line given its index and the active line.
    /// Like Apple Music, once a line is no longer the primary active line it
    /// immediately becomes inactive — there is no lingering "finishing" state.
    private func lineState(for index: Int, activeIdx: Int?) -> LineState {
        guard let activeIdx else { return .inactive }
        return index == activeIdx ? .active : .inactive
    }

    /// For a line, determine which word is currently being sung.
    private func activeWordIndex(in line: SyncedLine, currentPositionMs: Int64) -> Int? {
        guard let words = line.words, !words.isEmpty else { return nil }
        let currentTime = Double(currentPositionMs) / 1000.0
        let tolerance = 0.01

        var result: Int?
        for i in stride(from: words.count - 1, through: 0, by: -1) {
            if words[i].time <= currentTime + tolerance {
                result = i
                break
            }
        }
        return result
    }

    /// Compute the progress (0...1) through a specific word for the gradient fill.
    private func wordProgress(
        word: SyncedWord, nextWord: SyncedWord?, lineEndTime: TimeInterval?, currentPositionMs: Int64
    ) -> Double {
        let currentTime = Double(currentPositionMs) / 1000.0
        let endTime =
            word.endTime
            ?? nextWord?.time
            ?? lineEndTime
            ?? (word.time + 0.5)
        let duration = endTime - word.time
        guard duration > 0 else { return 1 }
        let elapsed = currentTime - word.time
        return min(1, max(0, elapsed / duration))
    }

    // MARK: - Synced Lyrics View

    private func syncedLyricsView(_ synced: [SyncedLine], currentPositionMs: Int64) -> some View {
        let labels = sectionLabels
        let activeIdx = activeLineIndex(in: synced, currentPositionMs: currentPositionMs)
        return GeometryReader { geometry in
            ScrollViewReader { proxy in
                ScrollView(showsIndicators: false) {
                    LazyVStack(spacing: usesExpandedLayout ? 28 : 10) {
                        Color.clear.frame(height: usesExpandedLayout ? geometry.size.height / 2 : 120)

                        ForEach(Array(synced.enumerated()), id: \.offset) { index, line in
                            if let label = labels[line.time] {
                                Text(label.uppercased())
                                    .font(.caption2)
                                    .fontWeight(.semibold)
                                    .tracking(1.5)
                                    .foregroundStyle(.white.opacity(0.30))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.top, index == 0 ? 0 : 8)
                            }

                            Button {
                                let targetMs = Int64((line.time * 1000.0).rounded())
                                onSeekToMs?(targetMs)
                                lastCenteredIndex = index
                                withAnimation(.easeInOut(duration: 0.22)) {
                                    proxy.scrollTo(index, anchor: .center)
                                }
                            } label: {
                                let state = lineState(for: index, activeIdx: activeIdx)
                                let isBackground = lyrics.isBackgroundVocal(line.agentId)
                                let isSecondary = lyrics.isSecondaryVocalist(line.agentId)
                                let isWordSynced = line.words?.isEmpty == false

                                if isWordSynced {
                                    wordSyncedLine(
                                        line: line,
                                        state: state,
                                        isBackground: isBackground,
                                        isSecondary: isSecondary,
                                        currentPositionMs: currentPositionMs
                                    )
                                    .contentShape(Rectangle())
                                } else {
                                    lyricLineWithTranslation(
                                        line: line,
                                        isActive: state == .active,
                                        isBackground: isBackground,
                                        isSecondary: isSecondary
                                    )
                                    .contentShape(Rectangle())
                                }
                            }
                            .buttonStyle(.plain)
                            .blur(radius: usesExpandedLayout && index != activeIdx ? 3 : 0)
                            .animation(.easeInOut(duration: 0.35), value: index == activeIdx)
                            .id(index)
                        }

                        Color.clear.frame(height: usesExpandedLayout ? geometry.size.height / 2 : 140)
                    }
                    .padding(.horizontal, AureliaSpacing.m)
                }
                .mask(
                    LinearGradient(
                        colors: [.clear, .black, .black, .clear],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .onAppear {
                    centerActiveLine(in: synced, proxy: proxy, currentPositionMs: currentPositionMs, animated: false)
                }
                .onChange(of: activeLineIndex(in: synced, currentPositionMs: currentPositionMs)) { _, _ in
                    centerActiveLine(in: synced, proxy: proxy, currentPositionMs: currentPositionMs, animated: true)
                }
                .onChange(of: geometry.size) { _, _ in
                    lastCenteredIndex = nil
                    centerActiveLine(in: synced, proxy: proxy, currentPositionMs: currentPositionMs, animated: false)
                }
            }
        }
    }

    // MARK: - Word-Synced Line (Apple Music Style)

    /// Renders a line with per-word karaoke fill.
    ///
    /// Each word is an individual `Text` inside a `WordFlowLayout` so that:
    /// 1. Each word's gradient is scoped to its own bounds (not the whole line).
    /// 2. Words wrap naturally across visual lines.
    /// 3. Repeated words ("la la la") maintain stable identity via their index.
    ///
    /// Two visual states driven by `LineState`:
    /// - **active**: full karaoke — words fill progressively, glow, scale up.
    /// - **inactive**: not the current line — uniform dim opacity, no glow.
    ///   Like Apple Music, when a new line starts the previous line dims
    ///   immediately with no lingering highlight.
    private func wordSyncedLine(
        line: SyncedLine, state: LineState, isBackground: Bool, isSecondary: Bool, currentPositionMs: Int64
    ) -> some View {
        let words = line.words ?? []
        let isActive = state == .active
        let activeWord = isActive ? activeWordIndex(in: line, currentPositionMs: currentPositionMs) : nil

        let brightOpacity: Double = isBackground ? 0.75 : 0.98
        let inactiveOpacity: Double = isBackground ? 0.25 : 0.50

        // Glow: active lines glow proportionally to progress.
        let glowRadius: CGFloat =
            if let aw = activeWord, isActive, !words.isEmpty {
                CGFloat(6.0 * Double(aw + 1) / Double(words.count))
            } else {
                0
            }

        // Precompute which words have a leading space (inter-word gap from TTML/LRC).
        // We strip it from the display text and let the layout handle spacing,
        // so wrapped lines don't start with visible indentation.
        let wordEntries: [(text: String, hasGap: Bool)] = words.map { w in
            let hasGap = w.word.hasPrefix(" ")
            let trimmed = hasGap ? String(w.word.drop(while: { $0 == " " })) : w.word
            return (text: trimmed, hasGap: hasGap)
        }

        // Ratio between dim and bright — used inside the sweep gradient
        // so the gradient stays purely relative while .opacity() on each
        // word handles the actual brightness level (and is animatable).
        let dimRatio: Double = inactiveOpacity / brightOpacity

        // Secondary vocalist lines are right-aligned (Apple Music duet style).
        let alignment: HorizontalAlignment = isSecondary ? .trailing : .leading
        let frameAlignment: Alignment = isSecondary ? .trailing : .leading
        let scaleAnchor: UnitPoint = isSecondary ? .trailing : .leading

        return VStack(alignment: alignment, spacing: 4) {
            WordFlowLayout(alignTrailing: isSecondary, spaceWidth: usesExpandedLayout ? 11 : 7.5) {
                ForEach(Array(words.enumerated()), id: \.offset) { wIdx, word in
                    let entry = wordEntries[wIdx]

                    // Per-word opacity drives brightness and IS animatable
                    // by SwiftUI, giving us a smooth fade on state transitions.
                    let wordOpacity: Double = {
                        guard isActive, let aw = activeWord else {
                            return inactiveOpacity
                        }
                        return wIdx <= aw ? brightOpacity : inactiveOpacity
                    }()

                    // Gradient stops — only the active word gets a sweep;
                    // all others are solid white (brightness via opacity above).
                    let stops: [Gradient.Stop] = {
                        guard isActive, let aw = activeWord, wIdx == aw else {
                            return [.init(color: .white, location: 0)]
                        }
                        let nextWord = wIdx + 1 < words.count ? words[wIdx + 1] : nil
                        let progress = wordProgress(
                            word: word, nextWord: nextWord, lineEndTime: line.endTime,
                            currentPositionMs: currentPositionMs)
                        return [
                            .init(color: .white, location: max(0, progress - 0.01)),
                            .init(color: .white.opacity(dimRatio), location: min(1, progress + 0.01)),
                        ]
                    }()

                    // The sweep word's gradient snaps instantly (gradients
                    // aren't animatable), so its .opacity() must also snap
                    // to avoid a brief double-dim.  Other words keep the
                    // smooth fade for active→inactive transitions.
                    let isSweepWord = isActive && wIdx == activeWord

                    Text(entry.text)
                        .foregroundStyle(
                            .linearGradient(
                                stops: stops,
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .opacity(wordOpacity)
                        .layoutValue(key: WordGapKey.self, value: entry.hasGap)
                        .animation(isSweepWord ? nil : .easeOut(duration: 0.35), value: state)
                }
            }
            .font(lyricFont)
            .italic(isBackground)
            .shadow(color: .white.opacity(isActive ? 0.45 : 0), radius: glowRadius, x: 0, y: 0)
            .frame(maxWidth: .infinity, alignment: frameAlignment)
            .fixedSize(horizontal: false, vertical: true)
            .scaleEffect(isActive ? 1.015 : 1.0, anchor: scaleAnchor)
            .animation(.easeInOut(duration: 0.35), value: state)

            // Translation text below the word-synced line
            if let translation = line.translation, !translation.isEmpty {
                Text(translation)
                    .font(.system(size: 17, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(state == .active ? 0.65 : 0.35))
                    .frame(maxWidth: .infinity, alignment: frameAlignment)
                    .multilineTextAlignment(isSecondary ? .trailing : .leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .scaleEffect(isActive ? 1.015 : 1.0, anchor: scaleAnchor)
                    .animation(.easeInOut(duration: 0.35), value: state)
            }
        }
    }

    // MARK: - Plain Line (line-synced / no word data)

    private func plainLyricsView(_ plain: String) -> some View {
        ScrollView(showsIndicators: false) {
            Text(plain)
                .font(usesExpandedLayout ? .title2 : .body)
                .lineSpacing(6)
                .foregroundStyle(.white.opacity(0.88))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, AureliaSpacing.m)
        }
    }

    private func lyricLine(_ text: String, isActive: Bool, isBackground: Bool = false, isSecondary: Bool = false)
        -> some View
    {
        let opacity: Double =
            if isActive {
                isBackground ? 0.75 : 0.98
            } else {
                isBackground ? 0.25 : 0.50
            }
        let frameAlignment: Alignment = isSecondary ? .trailing : .leading
        let textAlignment: TextAlignment = isSecondary ? .trailing : .leading
        let scaleAnchor: UnitPoint = isSecondary ? .trailing : .leading

        return Text(text.isEmpty ? " " : text)
            .font(lyricFont)
            .italic(isBackground)
            .foregroundStyle(.white.opacity(opacity))
            .frame(maxWidth: .infinity, alignment: frameAlignment)
            .multilineTextAlignment(textAlignment)
            .fixedSize(horizontal: false, vertical: true)
            .scaleEffect(isActive ? 1.015 : 1.0, anchor: scaleAnchor)
            .animation(.easeInOut(duration: 0.35), value: isActive)
    }

    /// Renders a lyric line with optional translation below it (Apple Music style).
    private func lyricLineWithTranslation(
        line: SyncedLine, isActive: Bool, isBackground: Bool = false, isSecondary: Bool = false
    ) -> some View {
        let alignment: HorizontalAlignment = isSecondary ? .trailing : .leading
        let frameAlignment: Alignment = isSecondary ? .trailing : .leading
        let textAlignment: TextAlignment = isSecondary ? .trailing : .leading
        let scaleAnchor: UnitPoint = isSecondary ? .trailing : .leading

        return VStack(alignment: alignment, spacing: 4) {
            lyricLine(line.line, isActive: isActive, isBackground: isBackground, isSecondary: isSecondary)

            if let translation = line.translation, !translation.isEmpty {
                Text(translation)
                    .font(.system(size: 17, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(isActive ? 0.65 : 0.35))
                    .frame(maxWidth: .infinity, alignment: frameAlignment)
                    .multilineTextAlignment(textAlignment)
                    .fixedSize(horizontal: false, vertical: true)
                    .scaleEffect(isActive ? 1.015 : 1.0, anchor: scaleAnchor)
                    .animation(.easeInOut(duration: 0.35), value: isActive)
            }
        }
    }

    // MARK: - Scroll Centering

    private func centerActiveLine(
        in synced: [SyncedLine], proxy: ScrollViewProxy, currentPositionMs: Int64, animated: Bool
    ) {
        guard let active = activeLineIndex(in: synced, currentPositionMs: currentPositionMs),
            active != lastCenteredIndex
        else { return }

        DispatchQueue.main.async {
            if animated {
                withAnimation(.easeInOut(duration: 0.35)) {
                    proxy.scrollTo(active, anchor: .center)
                }
            } else {
                proxy.scrollTo(active, anchor: .center)
            }

            lastCenteredIndex = active
        }
    }
}

// MARK: - Word Flow Layout

/// Layout value key that marks whether a word had a leading space (inter-word gap).
/// Used by `WordFlowLayout` to add spacing only between words that originally had
/// a space separator — and to suppress that space at the start of a wrapped row
/// so there's no visible indentation.
private nonisolated struct WordGapKey: LayoutValueKey {
    nonisolated static let defaultValue: Bool = false
}

/// A custom `Layout` that arranges children inline like text, wrapping to the
/// next line when children exceed the available width. Used for word-synced
/// lyrics so each word is its own view (with its own gradient bounds) while
/// still wrapping naturally.
///
/// Uses `WordGapKey` to determine inter-word spacing: words that had a leading
/// space in the source data get a space-width gap before them, except when they
/// land at the start of a wrapped row.
private struct WordFlowLayout: Layout, @unchecked Sendable {
    /// When true, rows are right-aligned within the container (for secondary vocalist).
    var alignTrailing: Bool = false

    /// Approximate space width for the caller's lyrics font.
    var spaceWidth: CGFloat = 7.5

    nonisolated func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache _: inout ()) -> CGSize {
        let rows = computeRows(proposal: proposal, subviews: subviews)
        guard let lastRow = rows.last else { return .zero }
        let height = lastRow.yOffset + lastRow.height
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: min(width, proposal.width ?? .infinity), height: height)
    }

    nonisolated func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache _: inout ())
    {
        let rows = computeRows(proposal: proposal, subviews: subviews)
        var subviewIndex = 0
        for row in rows {
            // Leading-aligned rows start at bounds.minX;
            // trailing-aligned rows are pushed to the right edge.
            let rowOriginX = alignTrailing ? bounds.maxX - row.width : bounds.minX

            var x = rowOriginX
            for posInRow in 0..<row.count {
                let subview = subviews[subviewIndex]
                let size = subview.sizeThatFits(.unspecified)
                // Add inter-word gap if this word had a leading space — but not
                // at the start of a row (to avoid indentation on wrapped lines).
                if posInRow > 0, subview[WordGapKey.self] {
                    x += spaceWidth
                }
                subview.place(at: CGPoint(x: x, y: bounds.minY + row.yOffset), proposal: .unspecified)
                x += size.width
                subviewIndex += 1
            }
        }
    }

    private struct Row {
        var count: Int
        var width: CGFloat
        var height: CGFloat
        var yOffset: CGFloat
    }

    private nonisolated func computeRows(proposal: ProposedViewSize, subviews: Subviews) -> [Row] {
        let maxWidth = proposal.width ?? .infinity
        var rows: [Row] = []
        var currentRow = Row(count: 0, width: 0, height: 0, yOffset: 0)

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            let gap: CGFloat = (currentRow.count > 0 && subview[WordGapKey.self]) ? spaceWidth : 0
            let neededWidth = size.width + gap

            if currentRow.count > 0, currentRow.width + neededWidth > maxWidth {
                // Wrap to next row — no gap at the start of a new row.
                rows.append(currentRow)
                let nextY = currentRow.yOffset + currentRow.height
                currentRow = Row(count: 1, width: size.width, height: size.height, yOffset: nextY)
            } else {
                currentRow.width += neededWidth
                currentRow.height = max(currentRow.height, size.height)
                currentRow.count += 1
            }
        }

        if currentRow.count > 0 {
            rows.append(currentRow)
        }

        return rows
    }
}
