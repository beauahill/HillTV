import HillTVCore
import Observation
import SwiftUI

@MainActor
@Observable
final class MoviesModel {
    let client: XtreamClient

    private(set) var categories: [XtreamCategory] = []
    private(set) var categoryState: LoadState = .idle
    private(set) var selectedCategoryID: String?
    private(set) var movies: [VODStream] = []
    private(set) var movieState: LoadState = .idle
    /// Pushed detail pages. Empty means Movies is at its root.
    var path = NavigationPath()

    private var cache: [String: [VODStream]] = [:]
    private var selectTask: Task<Void, Never>?
    private let visibility: CategoryVisibilityStore

    init(client: XtreamClient, visibility: CategoryVisibilityStore) {
        self.client = client
        self.visibility = visibility
    }

    /// Categories the user hasn't hidden.
    var visibleCategories: [XtreamCategory] {
        visibility.visible(categories, in: .movies)
    }

    var allHidden: Bool {
        !categories.isEmpty && visibleCategories.isEmpty
    }

    func loadCategories() async {
        if !categories.isEmpty {
            await reconcileSelection()
            return
        }
        guard categoryState != .loading else { return }
        categoryState = .loading
        do {
            categories = try await client.vodCategories()
            categoryState = categories.isEmpty ? .empty : .loaded
            await reconcileSelection()
        } catch {
            // Leaving the section cancels the request; retry next time instead of showing an error.
            categoryState = Task.isCancelled ? .idle : .failed(error.localizedDescription)
        }
    }

    /// Keeps the selection on a category that is still shown, since categories
    /// can be hidden in Settings between visits. Also refetches after a
    /// cancelled first load.
    private func reconcileSelection() async {
        let visible = visibleCategories
        if let id = selectedCategoryID, visible.contains(where: { $0.id == id }) {
            if movieState == .idle { await select(id) }
        } else if let first = visible.first {
            await select(first.id)
        } else {
            selectedCategoryID = nil
            movies = []
            movieState = .idle
        }
    }

    func scheduleSelect(_ categoryID: String) {
        selectTask?.cancel()
        selectTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            await self?.select(categoryID)
        }
    }

    func select(_ categoryID: String) async {
        if categoryID == selectedCategoryID, movieState == .loaded || movieState == .loading { return }
        selectedCategoryID = categoryID
        if let cached = cache[categoryID] {
            movies = cached
            movieState = cached.isEmpty ? .empty : .loaded
            return
        }
        movies = []
        movieState = .loading
        do {
            let result = try await client.vodStreams(categoryID: categoryID)
            cache[categoryID] = result
            guard selectedCategoryID == categoryID else { return }
            movies = result
            movieState = result.isEmpty ? .empty : .loaded
        } catch {
            guard selectedCategoryID == categoryID else { return }
            movieState = Task.isCancelled ? .idle : .failed(error.localizedDescription)
        }
    }

    func retry() {
        guard let id = selectedCategoryID else { return }
        movieState = .idle
        Task { await select(id) }
    }
}

struct MoviesView: View {
    @Bindable var model: MoviesModel
    @FocusState private var focusedCategory: String?

    var body: some View {
        NavigationStack(path: $model.path) {
            HStack(alignment: .top, spacing: 24) {
                CategoryColumn(
                    title: "Movies",
                    categories: model.visibleCategories,
                    selectedID: model.selectedCategoryID,
                    focus: $focusedCategory
                ) { category in
                    Task { await model.select(category.id) }
                }
                .frame(width: 360)
                .overlay {
                    LoadStateView(
                        state: model.allHidden ? .empty : model.categoryState,
                        emptyText: model.allHidden ? allCategoriesHiddenText : "No movie categories"
                    )
                }

                PosterGrid(items: model.movies) { movie in
                    NavigationLink(value: movie) {
                        PosterCard(
                            title: movie.name,
                            subtitle: movie.rating.map { String(format: "★ %.1f", $0) },
                            imageURL: movie.posterURL
                        )
                    }
                    .buttonStyle(TVCardButtonStyle())
                }
                // A new grid per category, so each one opens at its top instead of
                // where the last one was scrolled to.
                .id(model.selectedCategoryID)
                .frame(maxWidth: .infinity)
                .overlay {
                    LoadStateView(state: model.movieState, emptyText: "No movies in this category") { model.retry() }
                }
            }
            .navigationDestination(for: VODStream.self) { movie in
                MovieDetailView(client: model.client, movie: movie)
            }
        }
        .task { await model.loadCategories() }
        .onChange(of: focusedCategory) { _, newValue in
            if let newValue { model.scheduleSelect(newValue) }
        }
    }
}
