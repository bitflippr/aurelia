import AureliaCore
import SwiftUI

struct HomeView: View {
    @Environment(\.aureliaSidebarVisible) private var sidebarVisible
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(AudioPlayerController.self) private var player
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var viewModel = HomeViewModel()
    @State private var showAll = false

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                let width = max(geometry.size.width - 40, 240)
                let wide = horizontalSizeClass == .regular && width >= 650 && !typeSize.isAccessibilitySize
                Group {
                    if viewModel.isLoading, viewModel.allSongs.isEmpty {
                        ProgressView("Loading your library").frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if viewModel.allSongs.isEmpty {
                        ContentUnavailableView {
                            Label(
                                viewModel.error == nil ? "Your music starts here" : "Couldn't load your library",
                                systemImage: "music.note")
                        } description: {
                            Text(viewModel.error ?? "Add music to your server, then refresh your library.")
                        } actions: {
                            Button("Refresh") { viewModel.refresh() }
                        }
                    } else {
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 28) {
                                Text("Find a vibe")
                                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                                    .accessibilityAddTraits(.isHeader)
                                listeningSection(width: width, wide: wide)
                                albumShelf(width: width, wide: wide)
                                if wide {
                                    HStack(alignment: .top, spacing: 28) {
                                        rediscovery.frame(maxWidth: .infinity, alignment: .topLeading)
                                        genres(wide: true).frame(maxWidth: .infinity, alignment: .topLeading)
                                    }
                                } else {
                                    rediscovery
                                    genres(wide: horizontalSizeClass == .regular && !typeSize.isAccessibilitySize)
                                }
                            }
                            .padding(.horizontal, 20)
                            .padding(.top, 8)
                            .padding(.bottom, 24)
                        }
                        .refreshable { viewModel.refresh() }
                    }
                }
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !sidebarVisible {
                    ToolbarItem(placement: .topBarTrailing) { AccountMenuButton() }
                }
            }
            .navigationDestination(for: AlbumRoute.self) { AlbumDetailView(albumId: $0.id, albumName: $0.name) }
            .onAppear { viewModel.loadHomeData() }
        }
        .aureliaScreen()
    }

    private func heading(_ title: String) -> some View {
        Text(title).font(.system(.title2, design: .rounded, weight: .bold)).accessibilityAddTraits(.isHeader)
    }

    private func listeningSection(width: CGFloat, wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            heading("Let it play")
            if !viewModel.mixes.isEmpty {
                if typeSize.isAccessibilitySize {
                    VStack(spacing: 12) { ForEach(viewModel.mixes) { mix in mixCard(mix) } }
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 14) {
                            ForEach(viewModel.mixes) { mix in
                                mixCard(mix).frame(width: wide ? (width - 14) / 2 : width)
                            }
                        }
                        .scrollTargetLayout()
                    }
                    .scrollTargetBehavior(.viewAligned)
                }
            } else if viewModel.isLoadingMixes {
                ProgressView("Making your mixes").frame(maxWidth: .infinity).frame(height: 144)
            } else {
                mixCard(
                    HomeMix(
                        id: "shuffle", title: "Your library, on shuffle", artists: [],
                        artwork: viewModel.recentlyAddedAlbums.compactMap(\.albumArtUrl),
                        songs: viewModel.allSongs.shuffled()))
            }
            AureliaGlassGroup {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { listeningActions }
                    VStack(alignment: .leading, spacing: 12) { listeningActions }
                }
            }
        }
    }

    @ViewBuilder private var listeningActions: some View {
        Button {
            viewModel.play(viewModel.allSongs.shuffled(), player: player)
        } label: {
            Label("Shuffle library", systemImage: "shuffle").padding(.vertical, 4)
        }.aureliaGlassButton(prominent: true).controlSize(.large)
        Button {
            if let song = viewModel.allSongs.randomElement() { viewModel.play([song], player: player) }
        } label: {
            Label("Surprise me", systemImage: "sparkles").padding(.vertical, 4)
        }.aureliaGlassButton().controlSize(.large)
    }

    private func mixCard(_ mix: HomeMix) -> some View {
        Button {
            viewModel.play(mix.songs, player: player)
        } label: {
            HStack(spacing: 14) {
                if !typeSize.isAccessibilitySize {
                    MixArtwork(urls: mix.artwork).frame(width: 88, height: 88)
                }
                VStack(alignment: .leading, spacing: 5) {
                    Text(mix.title).font(.system(.headline, design: .rounded, weight: .bold)).lineLimit(2)
                    if !mix.artists.isEmpty {
                        Text(mix.artists.joined(separator: ", ")).font(.subheadline).foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "play.fill")
                    .font(.title3).frame(width: 44, height: 44)
                    .foregroundStyle(.white).background(Color.accentColor, in: Circle())
            }
            .padding(16)
            .frame(minHeight: 140)
            .background(Color.accentColor.opacity(0.14), in: RoundedRectangle(cornerRadius: 24))
            .contentShape(RoundedRectangle(cornerRadius: 24))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Play \(mix.title). \(mix.artists.joined(separator: ", "))")
    }

    @ViewBuilder private func albumShelf(width: CGFloat, wide: Bool) -> some View {
        if !viewModel.recentlyAddedAlbums.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                heading("Fresh on your shelf")
                if wide {
                    let count = max(3, Int((width + 16) / 176))
                    let dimension = (width - CGFloat(count - 1) * 16) / CGFloat(count)
                    LazyVGrid(
                        columns: Array(repeating: GridItem(.flexible(), spacing: 16), count: count),
                        alignment: .leading, spacing: 20
                    ) {
                        ForEach(viewModel.recentlyAddedAlbums) { album in albumCard(album, dimension: dimension) }
                    }
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 14) {
                            ForEach(viewModel.recentlyAddedAlbums) { album in
                                albumCard(album, dimension: typeSize.isAccessibilitySize ? 180 : 148)
                            }
                        }
                    }
                }
            }
        }
    }

    private func albumCard(_ album: AlbumItem, dimension: CGFloat) -> some View {
        NavigationLink(value: AlbumRoute(id: album.id, name: album.name)) {
            VStack(alignment: .leading, spacing: 6) {
                AlbumArtView(url: album.albumArtUrl, size: .medium, customDimension: dimension)
                Text(album.name).font(.subheadline.weight(.semibold)).lineLimit(2)
                Text(album.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }.frame(width: dimension, alignment: .leading)
        }.buttonStyle(.plain)
    }

    @ViewBuilder private var rediscovery: some View {
        if !viewModel.rediscover.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    heading("Worth another listen")
                    Spacer(minLength: 8)
                    if viewModel.rediscover.count > 3 {
                        Button(showAll ? "Show less" : "See all") { withAnimation { showAll.toggle() } }.font(
                            .subheadline)
                    }
                }
                ForEach(showAll ? viewModel.rediscover : Array(viewModel.rediscover.prefix(3)), id: \.id) { song in
                    SongRow(song: song, isPlaying: song.id == player.snapshot.currentSongId) {
                        viewModel.play(viewModel.rediscover, startingAt: song.id, player: player)
                    }
                }
            }
        }
    }

    @ViewBuilder private func genres(wide: Bool) -> some View {
        if !viewModel.topGenres.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                heading("A little of everything")
                LazyVGrid(
                    columns: wide
                        ? Array(repeating: GridItem(.flexible(minimum: 0), spacing: 10), count: 3)
                        : [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 200 : 130))],
                    alignment: .leading, spacing: 10
                ) {
                    ForEach(Array(viewModel.topGenres.prefix(wide ? 9 : 10)), id: \.self) { genre in
                        Button {
                            viewModel.playGenre(genre, player: player)
                        } label: {
                            if wide {
                                VStack(alignment: .leading, spacing: 12) {
                                    Image(systemName: "music.note").font(.title2)
                                    Text(genre).font(.subheadline.weight(.semibold)).lineLimit(2)
                                }
                                .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
                                .padding(12)
                            } else {
                                Label(genre, systemImage: "music.note").font(.subheadline)
                                    .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading).padding(10)
                            }
                        }
                        .buttonStyle(.plain)
                        .background(Color.accentColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
                    }
                }
            }
        }
    }
}

private struct MixArtwork: View {
    let urls: [String]
    var body: some View {
        GeometryReader { geometry in
            if urls.count > 1 {
                let side = (geometry.size.width - 3) / 2
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 3), GridItem(.flexible(), spacing: 3)], spacing: 3) {
                    ForEach(0..<4) { index in
                        AlbumArtView(url: urls[index % urls.count], size: .small, customDimension: side)
                    }
                }
            } else {
                AlbumArtView(url: urls.first, size: .medium, customDimension: geometry.size.width)
            }
        }.clipShape(RoundedRectangle(cornerRadius: 15))
    }
}

struct AlbumRoute: Hashable, Identifiable {
    let id: String
    let name: String
}
struct ArtistRoute: Hashable {
    let id: String
    let name: String
}
struct PlaylistRoute: Hashable {
    let id: String
    let name: String
}
