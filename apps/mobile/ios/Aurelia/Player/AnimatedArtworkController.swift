import AVFoundation
import AureliaCore
import CoreImage
import Observation
import SwiftUI
import UIKit

/// A muted, independent visual loop. It never controls the audio session or music queue.
@MainActor
@Observable
final class AnimatedArtworkController {
    let player = AVQueuePlayer()
    private(set) var ready = false
    private(set) var backdrop: UIImage?
    private var looper: AVPlayerLooper?
    private var output: AVPlayerItemVideoOutput?
    private var outputItem: AVPlayerItem?
    private let context = CIContext(options: [.cacheIntermediates: false])
    private var generation = 0

    init() {
        player.isMuted = true
        player.volume = 0
        player.preventsDisplaySleepDuringVideoPlayback = false
        player.actionAtItemEnd = .advance
    }

    func load(itemId: String?) async {
        stop()
        guard let itemId, let session = SessionStore.shared.snapshot() else { return }
        let request = generation
        do {
            let artwork = try await getAnimatedArtwork(
                serverUrl: session.credentials.serverUrl, token: session.credentials.token, itemId: itemId)
            guard !Task.isCancelled, generation == request, SessionStore.shared.getAppDataDir() == session.appDataDir,
                let variant = artwork?.square ?? artwork?.tall
            else { return }
            let localURL = try await AnimatedArtworkCache.shared.localURL(
                for: variant, token: session.credentials.token)
            guard !Task.isCancelled, generation == request else { return }
            let item = AVPlayerItem(url: localURL)
            looper = AVPlayerLooper(player: player, templateItem: item)
        } catch {
            // The optional artwork plugin may be absent. Static artwork remains visible.
        }
    }

    func setPlaying(_ playing: Bool) {
        if playing, looper != nil { player.play() } else { player.pause() }
    }

    /// Sample the same decoder at a small resolution for a matching blurred backdrop.
    func updateBackdrop() {
        guard let item = player.currentItem else { return }
        if outputItem !== item {
            if let output, let outputItem { outputItem.remove(output) }
            let next = AVPlayerItemVideoOutput(pixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
            ])
            item.add(next)
            output = next
            outputItem = item
        }
        guard let output, output.hasNewPixelBuffer(forItemTime: item.currentTime()),
            let buffer = output.copyPixelBuffer(forItemTime: item.currentTime(), itemTimeForDisplay: nil)
        else { return }
        let input = CIImage(cvPixelBuffer: buffer)
        let scale = 64 / max(input.extent.width, input.extent.height)
        let small = input.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let blurred = small.clampedToExtent().applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: 6])
            .cropped(to: small.extent)
        guard let image = context.createCGImage(blurred, from: small.extent) else { return }
        backdrop = UIImage(cgImage: image)
        ready = true
    }

    func stop() {
        generation += 1
        player.pause()
        looper?.disableLooping()
        looper = nil
        if let output, let outputItem { outputItem.remove(output) }
        output = nil
        outputItem = nil
        player.removeAllItems()
        ready = false
        backdrop = nil
    }
}

struct ArtworkVideoView: UIViewRepresentable {
    let player: AVQueuePlayer
    func makeUIView(context: Context) -> ArtworkPlayerLayerView {
        let view = ArtworkPlayerLayerView()
        view.playerLayer.videoGravity = .resizeAspectFill
        view.playerLayer.player = player
        return view
    }
    func updateUIView(_ uiView: ArtworkPlayerLayerView, context: Context) { uiView.playerLayer.player = player }
    static func dismantleUIView(_ uiView: ArtworkPlayerLayerView, coordinator: ()) { uiView.playerLayer.player = nil }
}

final class ArtworkPlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}
