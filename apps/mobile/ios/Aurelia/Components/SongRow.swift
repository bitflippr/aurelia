import AureliaCore
import SwiftUI

struct SongRow: View {
    @Environment(\.colorScheme) private var colorScheme
    let song: Song
    var isPlaying: Bool = false
    var showTrackNumber: Bool = false
    var onTap: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onTap) {
                HStack(spacing: 12) {
                    // Track number or album art
                    if showTrackNumber {
                        Text("\(song.trackNumber ?? 0)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .frame(width: 28)
                    } else {
                        AlbumArtView(url: song.albumArtUrl, size: .small)
                    }

                    // Song info
                    VStack(alignment: .leading, spacing: 2) {
                        Text(song.name)
                            .font(.body)
                            .foregroundStyle(isPlaying ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                            .lineLimit(1)

                        HStack(spacing: 4) {
                            if isPlaying {
                                Image(systemName: "waveform")
                                    .font(.caption2)
                                    .foregroundStyle(.tint)
                                    .symbolEffect(.variableColor.iterative, isActive: true)
                            }
                            Text(song.artists?.joined(separator: ", ") ?? "")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }

                    Spacer()

                    // Duration
                    if let duration = song.duration {
                        Text(TimeFormatter.formatDuration(Int64(duration * 1000)))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                }
                .padding(.vertical, 8)
                .contentShape(Rectangle())
                .background(
                    RoundedRectangle(cornerRadius: AureliaRadius.m, style: .continuous)
                        .fill(isPlaying ? AureliaPalette.tint(for: colorScheme).opacity(0.12) : Color.clear)
                )
            }
            .buttonStyle(.plain)
            Image(systemName: "ellipsis")
                .font(.body.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(width: 36, height: 44)
                .contentShape(Rectangle())
                .songActions(song, menu: true)
                .accessibilityLabel("More options for \(song.name)")
        }
        .songActions(song)
    }
}
