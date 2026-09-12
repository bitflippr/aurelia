import AureliaCore
import SwiftUI

struct PlaylistDetailView: View {
    let playlistId: String
    let playlistName: String

    @Environment(AudioPlayerController.self) private var playerController
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel = PlaylistViewModel()

    private var isWide: Bool {
        horizontalSizeClass == .regular
    }

    var body: some View {
        let horizontalPadding: CGFloat = isWide ? AureliaSpacing.xl : AureliaSpacing.m

        Group {
            if viewModel.detailIsLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = viewModel.detailError {
                ContentUnavailableView("Failed to Load", systemImage: "exclamationmark.triangle", description: Text(error))
            } else if viewModel.detailSongs.isEmpty {
                ContentUnavailableView("Empty Playlist", systemImage: "music.note.list", description: Text("This playlist has no songs"))
            } else {
                ScrollView {
                    if isWide {
                        HStack(alignment: .top, spacing: AureliaSpacing.xl) {
                            playlistSummary
                                .frame(maxWidth: 360)

                            playlistSongs
                                .frame(maxWidth: .infinity, alignment: .top)
                        }
                        .padding(.horizontal, horizontalPadding)
                        .padding(.vertical, AureliaSpacing.l)
                    } else {
                        VStack(spacing: AureliaSpacing.l) {
                            playlistSummary
                            playlistSongs
                        }
                        .padding(.horizontal, horizontalPadding)
                        .padding(.vertical, AureliaSpacing.l)
                    }
                }
            }
        }
        .navigationTitle(playlistName)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { viewModel.loadPlaylistDetail(playlistId: playlistId) }
        .aureliaScreen()
    }

    private var playlistSummary: some View {
        GlassCard(cornerRadius: AureliaRadius.l, padding: AureliaSpacing.l) {
            VStack(spacing: AureliaSpacing.m) {
                Image(systemName: "music.note.list")
                    .font(.system(size: 64))
                    .frame(width: 160, height: 160)
                    .background(.quaternary)
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))

                Text(playlistName)
                    .font(.title2.bold())
                Text("\(viewModel.detailSongs.count) songs")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                HStack(spacing: 16) {
                    Button {
                        viewModel.playPlaylist(playerController: playerController)
                    } label: {
                        Label("Play", systemImage: "play.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)

                    Button {
                        viewModel.shufflePlaylist(playerController: playerController)
                    } label: {
                        Label("Shuffle", systemImage: "shuffle")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    private var playlistSongs: some View {
        GlassCard(cornerRadius: AureliaRadius.l, padding: AureliaSpacing.m) {
            LazyVStack(spacing: 0) {
                ForEach(Array(viewModel.detailSongs.enumerated()), id: \.offset) { index, song in
                    SongRow(song: song, isPlaying: song.id == playerController.snapshot.currentSongId) {
                        viewModel.playPlaylist(startIndex: index, playerController: playerController)
                    }
                    if index != viewModel.detailSongs.count - 1 {
                        Divider()
                    }
                }
            }
        }
    }
}
