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

    private var cache: [String: [VODStream]] = [:]
    private var selectTask: Task<Void, Never>?

    init(client: XtreamClient) {
        self.client = client
    }

    func loadCategories() async {
        guard categories.isEmpty, categoryState != .loading else { return }
        categoryState = .loading
        do {
            categories = try await client.vodCategories()
            categoryState = categories.isEmpty ? .empty : .loaded
            if let first = categories.first { await select(first.id) }
        } catch {
            categoryState = .failed(error.localizedDescription)
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
            movieState = .failed(error.localizedDescription)
        }
    }

    func retry() {
        guard let id = selectedCategoryID else { return }
        movieState = .idle
        Task { await select(id) }
    }
}

struct MoviesView: View {
    let model: MoviesModel
    @FocusState private var focusedCategory: String?

    var body: some View {
        NavigationStack {
            HStack(alignment: .top, spacing: 24) {
                CategoryColumn(
                    title: "Movies",
                    categories: model.categories,
                    selectedID: model.selectedCategoryID,
                    focus: $focusedCategory
                ) { category in
                    Task { await model.select(category.id) }
                }
                .frame(width: 360)
                .overlay { LoadStateView(state: model.categoryState, emptyText: "No movie categories") }

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
