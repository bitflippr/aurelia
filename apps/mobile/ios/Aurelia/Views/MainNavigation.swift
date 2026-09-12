import SwiftUI

enum MainDestination: String, CaseIterable, Identifiable, Hashable {
    case home
    case songs
    case albums
    case artists
    case search
    case settings
    case favorites
    case playlists

    var id: String {
        rawValue
    }

    var title: String {
        switch self {
        case .home: "Home"
        case .songs: "Library"
        case .albums: "Albums"
        case .artists: "Artists"
        case .search: "Search"
        case .settings: "Settings"
        case .favorites: "Favorites"
        case .playlists: "Playlists"
        }
    }

    var systemImage: String {
        switch self {
        case .home: "house.fill"
        case .songs: "square.stack.fill"
        case .albums: "square.stack.fill"
        case .artists: "music.mic"
        case .search: "magnifyingglass"
        case .settings: "gearshape.fill"
        case .favorites: "heart"
        case .playlists: "music.note.list"
        }
    }

    @ViewBuilder
    func destinationView() -> some View {
        switch self {
        case .home:
            HomeView()
        case .songs:
            LibraryView()
        case .albums:
            AlbumsView()
        case .artists:
            ArtistsView()
        case .search:
            SearchView()
        case .settings:
            SettingsView()
        case .favorites:
            LibraryView(initialFavoritesOnly: true, showsCategories: false)
        case .playlists:
            PlaylistsView()
        }
    }
}

struct OptionalNavigationStack<Content: View>: View {
    var embedded: Bool
    @ViewBuilder var content: () -> Content
    var body: some View {
        if embedded { content() } else { NavigationStack { content() } }
    }
}
