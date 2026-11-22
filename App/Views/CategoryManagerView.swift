import HillTVCore
import SwiftUI

/// Full-screen list of every category the provider sends, where each one can
/// be hidden or shown. Opened from Settings. Live TV categories are grouped
/// the way the Live TV pills group them, so a whole group can go at once.
struct CategoryManagerView: View {
    let client: XtreamClient
    let store: CategoryVisibilityStore

    @State private var kind: CatalogKind = .live
    @State private var categories: [CatalogKind: [XtreamCategory]] = [:]
    @State private var states: [CatalogKind: LoadState] = [:]

    private struct Section: Identifiable {
        let id: String
        let title: String
        let categories: [XtreamCategory]
    }

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 16, alignment: .top), count: 3)

    private var current: [XtreamCategory] {
        categories[kind] ?? []
    }

    private var sections: [Section] {
        switch kind {
        case .live:
            return CategoryGroup.grouping(current).map {
                Section(id: $0.group.id, title: $0.group.title, categories: $0.categories)
            }
        case .movies, .series:
            return current.isEmpty ? [] : [Section(id: kind.id, title: Self.title(kind), categories: current)]
        }
    }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "Settings")
                    Text("Categories")
                        .font(.tvTitle)
                        .foregroundStyle(Theme.text)
                    Text("Hidden categories disappear from Live TV, Guide, Movies, Series, Home and Search. Favorites stay.")
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                }
                .padding(.leading, 12)

                toolbar
                    .focusSection()

                ScrollView(.vertical) {
                    LazyVStack(alignment: .leading, spacing: 32) {
                        ForEach(sections) { section in
                            sectionView(section)
                        }
                    }
                    .padding(12)
                }
                .overlay {
                    LoadStateView(state: states[kind] ?? .loading, emptyText: "No categories") {
                        Task { await load(kind, force: true) }
                    }
                }
                .focusSection()
            }
        }
        .preferredColorScheme(.dark)
        .task(id: kind) { await load(kind, force: false) }
    }

    private var toolbar: some View {
        HStack(spacing: 12) {
            ForEach(CatalogKind.allCases) { item in
                Button(Self.title(item)) {
                    kind = item
                }
                .buttonStyle(TVChipButtonStyle(isSelected: item == kind))
            }

            Spacer(minLength: 24)

            Text("\(store.hiddenCount(in: kind)) of \(current.count) hidden")
                .font(.tvSmall.monospacedDigit())
                .foregroundStyle(Theme.muted)

            Button("Show all") {
                store.showAll(in: kind)
            }
            .buttonStyle(TVChipButtonStyle())

            Button("Hide all") {
                store.setHidden(true, ids: current.map(\.id), in: kind)
            }
            .buttonStyle(TVChipButtonStyle())
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private func sectionView(_ section: Section) -> some View {
        let ids = section.categories.map(\.id)
        let hiddenHere = ids.filter { store.isHidden($0, in: kind) }.count
        let allHidden = hiddenHere == ids.count

        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 20) {
                Text(section.title)
                    .font(.tvHeading)
                    .foregroundStyle(Theme.text)
                Text("\(ids.count - hiddenHere) of \(ids.count) shown")
                    .font(.tvSmall.monospacedDigit())
                    .foregroundStyle(Theme.muted)
                Button(allHidden ? "Show group" : "Hide group") {
                    store.setHidden(!allHidden, ids: ids, in: kind)
                }
                .buttonStyle(TVChipButtonStyle())
            }
            .padding(.leading, 12)

            LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                ForEach(section.categories) { category in
                    let hidden = store.isHidden(category.id, in: kind)
                    Button {
                        store.toggle(category.id, in: kind)
                    } label: {
                        HStack(spacing: 16) {
                            Image(systemName: hidden ? "eye.slash" : "eye")
                                .frame(width: 34)
                            Text(category.name)
                                .lineLimit(1)
                                .strikethrough(hidden)
                            Spacer(minLength: 0)
                        }
                        .opacity(hidden ? 0.55 : 1)
                    }
                    .buttonStyle(TVRowButtonStyle(minHeight: 64, font: .tvMeta))
                }
            }
        }
    }

    private func load(_ kind: CatalogKind, force: Bool) async {
        if !force, categories[kind] != nil { return }
        states[kind] = .loading
        do {
            let result: [XtreamCategory]
            switch kind {
            case .live: result = try await client.liveCategories()
            case .movies: result = try await client.vodCategories()
            case .series: result = try await client.seriesCategories()
            }
            categories[kind] = result
            states[kind] = result.isEmpty ? .empty : .loaded
        } catch {
            // Switching tabs cancels the request; it reloads when the tab comes back.
            states[kind] = Task.isCancelled ? .loading : .failed(error.localizedDescription)
        }
    }

    static func title(_ kind: CatalogKind) -> String {
        switch kind {
        case .live: return "Live TV"
        case .movies: return "Movies"
        case .series: return "Series"
        }
    }
}
