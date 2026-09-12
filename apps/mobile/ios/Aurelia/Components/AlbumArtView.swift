import SwiftUI
import UIKit

struct AlbumArtView: View {
    let url: String?
    var size: ArtSize = .medium
    var customDimension: CGFloat?
    var cornerRadius: CGFloat?
    var showsBorder = true
    @State private var loadedImage: UIImage? = nil
    @State private var loadedKey: String? = nil

    enum ArtSize {
        case miniPlayer, small, medium, large, extraLarge

        var dimension: CGFloat {
            switch self {
            case .miniPlayer: 36
            case .small: 48
            case .medium: 140
            case .large: 220
            case .extraLarge: 300
            }
        }

        var cornerRadius: CGFloat {
            switch self {
            case .miniPlayer: 5
            case .small: 6
            case .medium: 10
            case .large: 14
            case .extraLarge: 16
            }
        }

        func cornerRadius(for dimension: CGFloat?) -> CGFloat {
            let baseRadius = cornerRadius
            let baseDimension = self.dimension
            guard let dimension else { return baseRadius }
            let ratio = baseRadius / baseDimension
            return max(6, dimension * ratio)
        }
    }

    var body: some View {
        let dimension = customDimension ?? size.dimension
        let radius = cornerRadius ?? size.cornerRadius(for: dimension)
        Group {
            if let loadedImage {
                Image(uiImage: loadedImage)
                    .resizable()
                    .scaledToFill()
            } else {
                placeholder
            }
        }
        .frame(width: dimension, height: dimension)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .background(
            RoundedRectangle(cornerRadius: radius, style: .continuous)
                .fill(.quaternary)
        )
        .overlay {
            if showsBorder {
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .stroke(Color.white.opacity(0.18), lineWidth: 1)
            }
        }
        .task(id: requestKey(for: dimension)) {
            await loadImage(targetDimension: dimension)
        }
    }

    private var placeholder: some View {
        ZStack {
            Color(.systemGray5)
            Image(systemName: "music.note")
                .font(.system(size: (customDimension ?? size.dimension) * 0.3))
                .foregroundStyle(.secondary)
        }
    }

    @MainActor
    private func loadImage(targetDimension: CGFloat) async {
        guard let requestKey = requestKey(for: targetDimension),
              let urlString = url,
              let imageUrl = URL(string: urlString)
        else {
            loadedImage = nil
            loadedKey = nil
            return
        }

        guard loadedKey != requestKey || loadedImage == nil else {
            return
        }

        // Keep showing the previous image while loading the new one
        // (don't nil out loadedImage) to avoid a flash to placeholder.
        loadedKey = requestKey

        let fetched = await ImageCache.shared.fetchImage(
            for: imageUrl,
            targetSize: CGSize(width: targetDimension, height: targetDimension)
        )
        // Only update if this is still the current request (task hasn't been cancelled/superseded)
        if loadedKey == requestKey {
            loadedImage = fetched
        }
    }

    private func requestKey(for targetDimension: CGFloat) -> String? {
        guard let url else { return nil }
        return "\(url)#\(Int(targetDimension.rounded(.up)))"
    }
}
