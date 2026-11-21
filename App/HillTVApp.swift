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
        switch app.phase {
        case .launching:
            ProgressView()
        case .signedOut:
            LoginView()
        case .signedIn:
            if let client = app.client {
                MainTabView(client: client)
                    // New client (sign out/in) gets fresh tab state.
                    .id(ObjectIdentifier(client))
            } else {
                LoginView()
            }
        }
    }
}

struct MainTabView: View {
    let client: XtreamClient

    var body: some View {
        TabView {
            LiveTVView(client: client)
                .tabItem { Label("Live TV", systemImage: "tv") }
            FavoritesView(client: client)
                .tabItem { Label("Favorites", systemImage: "star") }
            MoviesView(client: client)
                .tabItem { Label("Movies", systemImage: "film") }
            SeriesView(client: client)
                .tabItem { Label("Series", systemImage: "rectangle.stack") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}
