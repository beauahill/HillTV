import HillTVCore
import SwiftUI

@main
struct HillTVApp: App {
    @State private var model = AppModel()

    init() {
        KSPlayerSupport.configure()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .task { await model.restore() }
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            switch app.phase {
            case .launching:
                ProgressView()
            case .signedOut:
                LoginView()
            case .signedIn:
                if let client = app.client {
                    MainShell(client: client)
                        // New client (sign out/in) gets fresh section state.
                        .id(ObjectIdentifier(client))
                } else {
                    LoginView()
                }
            }
        }
        .foregroundStyle(Theme.text)
        .tint(Theme.accent)
        .preferredColorScheme(.dark)
    }
}

enum AppSection: String, CaseIterable, Identifiable {
    case home
    case live
    case favorites
    case movies
    case series
    case settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: return "Home"
        case .live: return "Live TV"
        case .favorites: return "Favorites"
        case .movies: return "Movies"
        case .series: return "Series"
        case .settings: return "Settings"
        }
    }

    var systemImage: String {
        switch self {
        case .home: return "house"
        case .live: return "tv"
        case .favorites: return "heart"
        case .movies: return "film"
        case .series: return "square.grid.2x2"
        case .settings: return "slider.horizontal.3"
        }
    }
}

/// Sidebar on the left, the selected section on the right. Section models
/// live here so switching sections keeps each one's categories and caches.
struct MainShell: View {
    let client: XtreamClient

    @State private var section: AppSection = .home
    @State private var home: HomeModel
    @State private var live: LiveTVModel
    @State private var movies: MoviesModel
    @State private var series: SeriesModel

    init(client: XtreamClient) {
        self.client = client
        _home = State(initialValue: HomeModel(client: client))
        _live = State(initialValue: LiveTVModel(client: client))
        _movies = State(initialValue: MoviesModel(client: client))
        _series = State(initialValue: SeriesModel(client: client))
    }

    var body: some View {
        HStack(spacing: 0) {
            Sidebar(selection: $section)
                .frame(width: Theme.sidebarWidth)
                .focusSection()

            content
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(.leading, 24)
                .focusSection()
        }
    }

    @ViewBuilder
    private var content: some View {
        switch section {
        case .home:
            HomeView(model: home)
        case .live:
            LiveTVView(model: live)
        case .favorites:
            FavoritesView(client: client)
        case .movies:
            MoviesView(model: movies)
        case .series:
            SeriesView(model: series)
        case .settings:
            SettingsView()
        }
    }
}

/// Selection changes on click, not focus, so passing through the sidebar
/// on the way back from the content doesn't switch sections.
struct Sidebar: View {
    @Binding var selection: AppSection

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 6) {
                Wordmark(size: 44)
                Eyebrow(text: "Live · Movies · Series", color: Theme.muted)
            }
            .padding(.leading, 24)
            .padding(.bottom, 56)

            ForEach(AppSection.allCases) { item in
                Button {
                    selection = item
                } label: {
                    Label(item.title, systemImage: item.systemImage)
                        .labelStyle(SidebarLabelStyle())
                }
                .buttonStyle(TVRowButtonStyle(isSelected: item == selection, font: .tvMeta.weight(.medium)))
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .frame(maxHeight: .infinity, alignment: .top)
        .background {
            Theme.sidebar
                .overlay(alignment: .trailing) {
                    Theme.line.frame(width: 1)
                }
                .ignoresSafeArea()
        }
    }
}

private struct SidebarLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 20) {
            configuration.icon
                .frame(width: 32)
            configuration.title
        }
    }
}

/// "Hill" in white, "TV" in mint. Text only, no logo artwork yet.
struct Wordmark: View {
    var size: CGFloat = 44

    var body: some View {
        (Text("Hill").foregroundStyle(Theme.text) + Text("TV").foregroundStyle(Theme.accent))
            .font(.system(size: size, weight: .bold, design: .serif))
    }
}
