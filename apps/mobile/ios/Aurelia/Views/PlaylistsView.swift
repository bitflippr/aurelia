import AureliaCore
import SwiftUI

struct PlaylistsView: View {
    var isEmbedded = false
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var viewModel = PlaylistViewModel()
    @State private var showCreateDialog = false
    @State private var newPlaylistName = ""

    private var isWide: Bool {
        horizontalSizeClass == .regular
    }

    var body: some View {
        OptionalNavigationStack(embedded: isEmbedded) {
            let columns = [
                GridItem(.adaptive(minimum: isWide ? 220 : 180), spacing: AureliaSpacing.m),
            ]

            Group {
                if viewModel.isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if viewModel.playlists.isEmpty {
                    ContentUnavailableView("No Playlists", systemImage: "music.note.list", description: Text("Create a playlist to get started"))
                } else {
                    if isWide {
                        ScrollView {
                            LazyVGrid(columns: columns, spacing: AureliaSpacing.m) {
                                ForEach(viewModel.playlists, id: \.id) { playlist in
                                    NavigationLink(value: PlaylistRoute(id: playlist.id, name: playlist.name)) {
                                        GlassCard(cornerRadius: AureliaRadius.m, padding: AureliaSpacing.m, showsShadow: false) {
                                            VStack(alignment: .leading, spacing: 10) {
                                                Image(systemName: "music.note.list")
                                                    .font(.system(size: 28))
                                                    .frame(width: 48, height: 48)
                                                    .background(.quaternary)
                                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                                                Text(playlist.name)
                                                    .font(.body)
                                                    .lineLimit(2)

                                                if let count = playlist.childCount {
                                                    Text("\(count) songs")
                                                        .font(.caption)
                                                        .foregroundStyle(.secondary)
                                                }
                                            }
                                        }
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, AureliaSpacing.xl)
                            .padding(.vertical, AureliaSpacing.l)
                        }
                    } else {
                        List {
                            ForEach(viewModel.playlists, id: \.id) { playlist in
                                GlassCard(cornerRadius: AureliaRadius.m, padding: AureliaSpacing.s, showsShadow: false) {
                                    NavigationLink(value: PlaylistRoute(id: playlist.id, name: playlist.name)) {
                                        HStack(spacing: 12) {
                                            Image(systemName: "music.note.list")
                                                .frame(width: 48, height: 48)
                                                .background(.quaternary)
                                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                                            VStack(alignment: .leading) {
                                                Text(playlist.name)
                                                if let count = playlist.childCount {
                                                    Text("\(count) songs")
                                                        .font(.caption)
                                                        .foregroundStyle(.secondary)
                                                }
                                            }
                                            Spacer()
                                        }
                                    }
                                }
                                .listRowSeparator(.hidden)
                                .listRowBackground(Color.clear)
                                .listRowInsets(EdgeInsets(top: 6, leading: AureliaSpacing.m, bottom: 6, trailing: AureliaSpacing.m))
                                .swipeActions(edge: .trailing) {
                                    Button(role: .destructive) {
                                        viewModel.deletePlaylist(playlist.id)
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                }
                            }
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                    }
                }
            }
            .navigationTitle("Playlists")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showCreateDialog = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .navigationDestination(for: PlaylistRoute.self) { route in
                PlaylistDetailView(playlistId: route.id, playlistName: route.name)
            }
            .alert("New Playlist", isPresented: $showCreateDialog) {
                TextField("Playlist name", text: $newPlaylistName)
                Button("Create") {
                    viewModel.createPlaylist(name: newPlaylistName)
                    newPlaylistName = ""
                }
                Button("Cancel", role: .cancel) {
                    newPlaylistName = ""
                }
            }
            .onAppear { viewModel.loadPlaylists() }
        }
        .aureliaScreen()
    }
}
