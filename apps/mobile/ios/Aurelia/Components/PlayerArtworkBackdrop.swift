import CoreImage
import SwiftUI
import UIKit

/// Warps softened cover art into a flowing color field. Video already supplies blurred frames.
struct PlayerArtworkBackdrop: View {
    let albumArtUrl: String?
    let videoFrame: UIImage?
    let isPlaying: Bool
    @State private var cover: UIImage?
    @State private var elapsed: TimeInterval = 0
    @State private var startedAt: Date?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color(red: 0.045, green: 0.045, blue: 0.075)
                if let videoFrame {
                    Image(uiImage: videoFrame)
                        .resizable()
                        .scaledToFill()
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .clipped()
                        .opacity(0.7)
                } else {
                    TimelineView(.animation(minimumInterval: 1.0 / 20.0, paused: !isPlaying)) { timeline in
                        let time = elapsed + (startedAt.map { max(0, timeline.date.timeIntervalSince($0)) } ?? 0)
                        softenedArtwork
                            .frame(width: geometry.size.width, height: geometry.size.height)
                            .layerEffect(
                                ShaderLibrary.playerArtworkWarp(
                                    .float2(geometry.size),
                                    .image(Image("PlayerNoise")),
                                    .float(time * 0.35)),
                                maxSampleOffset: geometry.size
                            )
                            .opacity(0.85)
                    }
                }
                LinearGradient(
                    colors: [.black.opacity(0.18), .black.opacity(0.56)], startPoint: .top, endPoint: .bottom)
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onChange(of: isPlaying, initial: true) { _, playing in
            let now = Date.now
            if let startedAt { elapsed += max(0, now.timeIntervalSince(startedAt)) }
            startedAt = playing ? now : nil
        }
        .task(id: albumArtUrl) {
            // Drop the previous album's colors even if this cover is absent or fails to load.
            cover = nil
            guard let albumArtUrl, let url = URL(string: albumArtUrl),
                let image = await ImageCache.shared.fetchImage(for: url, targetSize: CGSize(width: 64, height: 64)),
                !Task.isCancelled
            else { return }
            cover = Self.soften(image)
        }
    }

    @ViewBuilder
    private var softenedArtwork: some View {
        if let cover {
            Image(uiImage: cover).resizable()
        } else {
            LinearGradient(
                colors: [Color(red: 0.24, green: 0.22, blue: 0.38), Color(red: 0.12, green: 0.2, blue: 0.25)],
                startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }

    /// Blur once at thumbnail resolution, rather than filtering a full-screen layer every frame.
    private static func soften(_ image: UIImage) -> UIImage? {
        guard let cgImage = image.cgImage else { return nil }
        let input = CIImage(cgImage: cgImage)
        let radius = max(input.extent.width, input.extent.height) * 0.12
        let blurred = input.clampedToExtent().applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: radius])
            .cropped(to: input.extent)
        let context = CIContext(options: [.cacheIntermediates: false])
        guard let output = context.createCGImage(blurred, from: input.extent) else { return nil }
        return UIImage(cgImage: output)
    }
}
