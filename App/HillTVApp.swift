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
                    MainShell(client: client, visibility: app.categoryVisibility, order: app.channelOrder)
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
    case search
    case live
    case guide
    case favorites
    case movies
    case series
    case settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: return "Home"
        case .search: return "Search"
        case .live: return "Live TV"
        case .guide: return "Guide"
        case .favorites: return "Favorites"
        case .movies: return "Movies"
        case .series: return "Series"
        case .settings: return "Settings"
        }
    }

    var systemImage: String {
        switch self {
        case .home: return "house"
        case .search: return "magnifyingglass"
        case .live: return "tv"
        case .guide: return "calendar"
        case .favorites: return "heart"
        case .movies: return "film"
        case .series: return "square.grid.2x2"
        case .settings: return "slider.horizontal.3"
        }
    }
}

/// Icon rail on the left, the selected section on the right. Section models
/// live here so switching sections keeps each one's categories and caches.
///
/// Search is the exception: it opens as a full-screen page over the shell.
/// The system search keyboard is built for the full width of the screen and
/// doesn't fit beside the rail, where its last letters ran off the edge.
struct MainShell: View {
    let client: XtreamClient

    @Environment(AppModel.self) private var app
    @State private var section: AppSection = .home
    @State private var showingSearch = false
    @State private var home: HomeModel
    @State private var search: SearchModel
    @State private var live: LiveTVModel
    @State private var guide: GuideModel
    @State private var movies: MoviesModel
    @State private var series: SeriesModel
    @State private var epg: EPGStore
    @State private var scores: ScoreboardStore
    @FocusState private var sidebarFocus: AppSection?
    /// Briefly true to push focus out of the rail. See `leaveRail()`.
    @State private var railLocked = false

    init(client: XtreamClient, visibility: CategoryVisibilityStore, order: ChannelOrderStore) {
        self.client = client
        _home = State(initialValue: HomeModel(client: client, visibility: visibility))
        _search = State(initialValue: SearchModel(client: client, visibility: visibility))
        _live = State(initialValue: LiveTVModel(client: client, visibility: visibility, order: order))
        _guide = State(initialValue: GuideModel(client: client, visibility: visibility, order: order))
        _movies = State(initialValue: MoviesModel(client: client, visibility: visibility))
        _series = State(initialValue: SeriesModel(client: client, visibility: visibility))
        _epg = State(initialValue: EPGStore(client: client))
        _scores = State(initialValue: ScoreboardStore(userAgent: AppModel.userAgent))
    }

    var body: some View {
        HStack(spacing: 0) {
            // Read here, where the focus state lives, so the rail redraws when focus enters or leaves it.
            Sidebar(selection: railSelection, focus: $sidebarFocus, expanded: sidebarFocus != nil)
                .frame(width: Theme.railWidth, alignment: .leading)
                .focusSection()
                // Menu on the rail closes the rail and goes back to the
                // content, instead of quitting the app.
                .onExitCommand(perform: leaveRail)
                .disabled(railLocked)
                // The expanded rail draws over the content without moving it.
                .zIndex(1)

            visibleContent
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(.leading, 24)
                // Scroll views draw past their edges so focus rings aren't cut off.
                // Keep that overflow out of the rail, but let it run up, down and right.
                .mask {
                    Rectangle()
                        .padding(.vertical, -400)
                        .padding(.trailing, -400)
                }
                .focusSection()
                // At a section's root, Menu goes back to the rail instead of quitting.
                // With a detail page pushed, nil leaves Menu to pop the page as usual.
                .onExitCommand(perform: exitAction)
        }
        // Keeps "on now" progress moving while the shell is on screen.
        .task { await epg.runClock() }
        .fullScreenCover(isPresented: $showingSearch) {
            ZStack {
                Theme.background.ignoresSafeArea()
                SearchView(model: search)
            }
            .foregroundStyle(Theme.text)
            .tint(Theme.accent)
            .preferredColorScheme(.dark)
            // Passed explicitly rather than relying on the environment reaching into a cover.
            .environment(app)
        }
    }

    /// Picking Search in the rail opens the search page and leaves the
    /// current section selected, so Menu returns to where the user was.
    private var railSelection: Binding<AppSection> {
        Binding(
            get: { section },
            set: { item in
                if item == .search {
                    showingSearch = true
                } else {
                    section = item
                }
            }
        )
    }

    /// The section is taken down while Search covers it, so nothing behind
    /// the cover keeps working: no live preview holding a connection, no
    /// scores polling. Its model keeps the state for when it comes back.
    @ViewBuilder
    private var visibleContent: some View {
        if showingSearch {
            Color.clear
        } else {
            content
        }
    }

    /// Sends focus from the rail to the content. Nothing in SwiftUI moves
    /// focus into a section whose views it doesn't know, so the rail is made
    /// unfocusable for a moment and the focus engine picks the content, the
    /// only place left. If the content has nothing to focus (an empty or
    /// loading section), focus comes back to the rail when it unlocks.
    private func leaveRail() {
        guard !railLocked else { return }
        railLocked = true
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(200))
            railLocked = false
        }
    }

    private var exitAction: (() -> Void)? {
        guard contentAtRoot else { return nil }
        return { sidebarFocus = section }
    }

    private var contentAtRoot: Bool {
        switch section {
        case .home: return home.path.isEmpty
        case .movies: return movies.path.isEmpty
        case .series: return series.path.isEmpty
        // Search is never the shell's section; it has its own page.
        case .live, .guide, .favorites, .settings, .search: return true
        }
    }

    @ViewBuilder
    private var content: some View {
        switch section {
        case .home:
            HomeView(model: home, epg: epg) { showingSearch = true }
        case .search:
            // Never selected: see `railSelection`.
            EmptyView()
        case .live:
            LiveTVView(model: live, epg: epg, scores: scores)
        case .guide:
            GuideView(model: guide)
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

/// Icon rail. It widens to show labels while one of its items has focus.
///
/// The layout never changes: every button stays an icon-sized square inside
/// the collapsed width, and the wider panel, highlight and labels are drawn
/// past that frame, over the content. Focus geometry is the same collapsed
/// or expanded, and the content doesn't reflow.
///
/// Selection changes on click, not focus, so passing through the rail on the
/// way back from the content doesn't switch sections.
struct Sidebar: View {
    @Binding var selection: AppSection
    var focus: FocusState<AppSection?>.Binding
    /// True while one of the rail's items has focus.
    let expanded: Bool

    private static let primary = AppSection.allCases.filter { $0 != .settings }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RailMark(expanded: expanded)
                .padding(.bottom, 40)

            ForEach(Self.primary) { item in
                button(item)
            }

            Spacer(minLength: 0)

            button(.settings)
        }
        .padding(.horizontal, Sidebar.inset)
        .padding(.vertical, 20)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(alignment: .leading) {
            Theme.sidebar
                .overlay(alignment: .trailing) {
                    Theme.line.frame(width: 1)
                }
                .ignoresSafeArea()
                .frame(width: expanded ? Theme.railExpandedWidth : Theme.railWidth)
        }
        .animation(Theme.focusAnimation, value: expanded)
    }

    fileprivate static let inset: CGFloat = 12
    fileprivate static let itemSide: CGFloat = Theme.railWidth - inset * 2

    private func button(_ item: AppSection) -> some View {
        Button {
            selection = item
        } label: {
            Image(systemName: item.systemImage)
        }
        .buttonStyle(RailButtonStyle(title: item.title, isSelected: item == selection, expanded: expanded))
        .focused(focus, equals: item)
    }
}

/// Focus: blue fill with dark icon and label. Selected: blue tint and icon.
private struct RailButtonStyle: ButtonStyle {
    let title: String
    let isSelected: Bool
    let expanded: Bool

    func makeBody(configuration: Configuration) -> some View {
        RailBody(configuration: configuration, title: title, isSelected: isSelected, expanded: expanded)
    }

    private struct RailBody: View {
        let configuration: ButtonStyleConfiguration
        let title: String
        let isSelected: Bool
        let expanded: Bool
        @Environment(\.isFocused) private var isFocused

        private var fill: Color {
            if isFocused { return Theme.accent }
            return isSelected ? Theme.accent.opacity(0.18) : Color.clear
        }

        private var iconColor: Color {
            if isFocused { return Theme.onAccent }
            return isSelected ? Theme.accent : Theme.muted
        }

        var body: some View {
            configuration.label
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(iconColor)
                .frame(width: Sidebar.itemSide, height: Theme.rowHeight)
                .background(alignment: .leading) {
                    RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
                        .fill(fill)
                        .frame(width: expanded ? Theme.railExpandedWidth - Sidebar.inset * 2 : Sidebar.itemSide)
                }
                .overlay(alignment: .leading) {
                    Text(title)
                        .font(.tvMeta.weight(.medium))
                        .foregroundStyle(isFocused ? Theme.onAccent : Theme.text)
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.leading, Sidebar.itemSide + 4)
                        .opacity(expanded ? 1 : 0)
                }
                .contentShape(Rectangle())
                .opacity(configuration.isPressed ? 0.85 : 1)
                .animation(Theme.focusAnimation, value: isFocused)
        }
    }
}

/// The "H" badge at the top of the rail, with the wordmark beside it when expanded.
private struct RailMark: View {
    let expanded: Bool

    var body: some View {
        Text("H")
            .font(.system(size: 36, weight: .heavy).width(.condensed))
            .foregroundStyle(Theme.onAccent)
            .frame(width: 56, height: 56)
            .background(Theme.accent, in: RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
            .frame(width: Sidebar.itemSide)
            .overlay(alignment: .leading) {
                Wordmark(size: 36)
                    .fixedSize()
                    .padding(.leading, Sidebar.itemSide + 4)
                    .opacity(expanded ? 1 : 0)
            }
    }
}

/// "Hill" in white, "TV" in the accent color. Text only, no logo artwork yet.
struct Wordmark: View {
    var size: CGFloat = 44

    var body: some View {
        (Text("Hill").foregroundStyle(Theme.text) + Text("TV").foregroundStyle(Theme.accent))
            .font(.system(size: size, weight: .heavy))
    }
}
