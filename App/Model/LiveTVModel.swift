import Foundation
import HillTVCore
import Observation

/// What Live TV is showing: a big pill (Favorites or a category group) and,
/// inside a group, either one provider category or all of them merged.
@MainActor
@Observable
final class LiveTVModel {
    enum Pill: Hashable {
        case favorites
        case group(CategoryGroup)
    }

    struct Selection: Hashable {
        var pill: Pill
        /// A provider category ID, `allID` or `gamesID`. Nil for Favorites.
        var sub: String?
    }

    /// Sub-selection: every category in the group, merged into one list.
    static let allID = "hilltv.all"
    /// Sub-selection in Sports: games from the scores feed, then every
    /// sports channel.
    static let gamesID = "hilltv.games"
    /// Groups with more categories than this don't offer "All": it would
    /// mean that many requests and a list too long to be useful.
    static let mergeLimit = 12

    let client: XtreamClient

    private(set) var categories: [XtreamCategory] = []
    private(set) var categoryState: LoadState = .idle
    private(set) var selection: Selection?
    private(set) var streams: [LiveStream] = []
    private(set) var streamState: LoadState = .idle
    /// The channel the detail pane shows: the last one that held focus.
    private(set) var detailStreamID: Int?

    /// Channels across every Sports category, for matching games to
    /// channels. Empty until Games has been opened.
    private(set) var sportsChannels: [LiveStream] = []

    private var streamCache: [String: [LiveStream]] = [:]
    /// The sub-selection last used in each group, so coming back restores it.
    private var lastSub: [CategoryGroup: String] = [:]
    private let visibility: CategoryVisibilityStore

    init(client: XtreamClient, visibility: CategoryVisibilityStore) {
        self.client = client
        self.visibility = visibility
    }

    // MARK: Derived

    /// Categories the user hasn't hidden.
    var visibleCategories: [XtreamCategory] {
        visibility.visible(categories, in: .live)
    }

    var allHidden: Bool {
        !categories.isEmpty && visibleCategories.isEmpty
    }

    /// Category groups that have something to show, in pill order.
    var groups: [GroupedCategories] {
        CategoryGroup.grouping(visibleCategories)
    }

    var showsFavorites: Bool {
        selection?.pill == .favorites
    }

    /// The selected group's provider categories. Empty for Favorites.
    var subcategories: [XtreamCategory] {
        guard case .group(let group)? = selection?.pill else { return [] }
        return groups.first { $0.group == group }?.categories ?? []
    }

    var showsGames: Bool {
        selection?.sub == Self.gamesID
    }

    /// Whether the selected group offers "Games". Only Sports does.
    var offersGames: Bool {
        selection?.pill == .group(.sports)
    }

    /// Whether the selected group offers the merged "All" list. Sports
    /// doesn't: its Games list already ends with every sports channel.
    var offersAll: Bool {
        !offersGames && Self.offersAll(subcategories)
    }

    private static func offersAll(_ categories: [XtreamCategory]) -> Bool {
        (2...mergeLimit).contains(categories.count)
    }

    // MARK: Loading

    func loadCategories(startWithFavorites: Bool) async {
        if selection == nil, startWithFavorites {
            await select(.favorites)
        }
        if !categories.isEmpty {
            await reconcileSelection()
            return
        }
        guard categoryState != .loading else { return }
        categoryState = .loading
        do {
            categories = try await client.liveCategories()
            categoryState = categories.isEmpty ? .empty : .loaded
            await reconcileSelection()
        } catch {
            // Leaving the section cancels the request; retry next time instead of showing an error.
            categoryState = Task.isCancelled ? .idle : .failed(error.localizedDescription)
        }
    }

    func retryCategories(startWithFavorites: Bool) {
        categoryState = .idle
        Task { await loadCategories(startWithFavorites: startWithFavorites) }
    }

    func retryStreams() {
        streamState = .idle
        Task { await load() }
    }

    /// Nil clears it, for when a game row has focus instead of a channel.
    func showDetail(for streamID: Int?) {
        detailStreamID = streamID
    }

    // MARK: Selecting

    /// Pills change on click, not on focus: stepping across the row
    /// shouldn't reload the channel list at every stop.
    func select(_ pill: Pill) async {
        if selection?.pill == pill, streamState == .loaded || streamState == .loading { return }
        switch pill {
        case .favorites:
            selection = Selection(pill: pill, sub: nil)
        case .group(let group):
            let grouped = groups.first { $0.group == group }
            let categories = grouped?.categories ?? []
            let remembered = lastSub[group].flatMap { validSubs(group, categories).contains($0) ? $0 : nil }
            selection = Selection(pill: pill, sub: remembered ?? defaultSub(group, categories))
        }
        detailStreamID = nil
        await load()
    }

    /// Picks a provider category, "All" or "Games" inside the selected group.
    func select(sub: String) async {
        guard case .group(let group)? = selection?.pill else { return }
        if selection?.sub == sub, streamState == .loaded || streamState == .loading { return }
        selection?.sub = sub
        lastSub[group] = sub
        detailStreamID = nil
        await load()
    }

    private func validSubs(_ group: CategoryGroup, _ categories: [XtreamCategory]) -> [String] {
        var subs = categories.map(\.id)
        if group == .sports {
            subs.append(Self.gamesID)
        } else if Self.offersAll(categories) {
            subs.append(Self.allID)
        }
        return subs
    }

    private func defaultSub(_ group: CategoryGroup, _ categories: [XtreamCategory]) -> String? {
        if group == .sports { return Self.gamesID }
        return Self.offersAll(categories) ? Self.allID : categories.first?.id
    }

    /// Keeps the selection on something that is still shown, since
    /// categories can be hidden in Settings between visits. Also refetches
    /// after a cancelled first load.
    private func reconcileSelection() async {
        let groups = self.groups
        guard let selection else {
            await selectFirst(in: groups)
            return
        }
        switch selection.pill {
        case .favorites:
            return
        case .group(let group):
            guard let grouped = groups.first(where: { $0.group == group }) else {
                await selectFirst(in: groups)
                return
            }
            if let sub = selection.sub, validSubs(group, grouped.categories).contains(sub) {
                // "All" and "Games" depend on which categories are visible, so rebuild them.
                if streamState == .idle || sub == Self.allID || sub == Self.gamesID { await load() }
            } else {
                lastSub[group] = nil
                self.selection = Selection(pill: selection.pill, sub: defaultSub(group, grouped.categories))
                detailStreamID = nil
                await load()
            }
        }
    }

    private func selectFirst(in groups: [GroupedCategories]) async {
        if let first = groups.first {
            await select(.group(first.group))
        } else {
            await select(.favorites)
        }
    }

    // MARK: Channels for the selection

    private func load() async {
        guard let token = selection else { return }
        guard case .group(let group) = token.pill else {
            // Favorites: the view reads the channels from FavoritesStore.
            streams = []
            streamState = .loaded
            return
        }
        let grouped = groups.first { $0.group == group }?.categories ?? []
        guard let sub = token.sub else {
            streams = []
            streamState = .empty
            return
        }

        if sub == Self.allID || sub == Self.gamesID {
            let missing = grouped.filter { streamCache[$0.id] == nil }
            if !missing.isEmpty {
                streams = []
                // Games come from the scores feed and can show while the channels load.
                streamState = sub == Self.gamesID ? .loaded : .loading
                await fetch(missing)
                guard selection == token else { return }
            }
            let merged = Self.merge(grouped.compactMap { streamCache[$0.id] })
            if sub == Self.gamesID { sportsChannels = merged }
            if merged.isEmpty, grouped.contains(where: { streamCache[$0.id] == nil }) {
                streams = []
                streamState = Task.isCancelled ? .idle : .failed("Couldn't load these channels.")
            } else {
                streams = merged
                streamState = merged.isEmpty ? .empty : .loaded
            }
            return
        }

        if let cached = streamCache[sub] {
            streams = cached
            streamState = cached.isEmpty ? .empty : .loaded
            return
        }
        streams = []
        streamState = .loading
        do {
            let result = try await client.liveStreams(categoryID: sub)
            streamCache[sub] = result
            // Ignore late results if the user already moved on.
            guard selection == token else { return }
            streams = result
            streamState = result.isEmpty ? .empty : .loaded
        } catch {
            guard selection == token else { return }
            streamState = Task.isCancelled ? .idle : .failed(error.localizedDescription)
        }
    }

    /// Fetches categories four at a time. Failures are skipped: a merged
    /// list with one category missing beats no list.
    private func fetch(_ categories: [XtreamCategory]) async {
        let client = client
        var index = 0
        while index < categories.count, !Task.isCancelled {
            let batch = Array(categories[index..<min(index + 4, categories.count)])
            index += batch.count
            let results = await withTaskGroup(of: (String, [LiveStream]?).self) { group in
                for category in batch {
                    group.addTask {
                        (category.id, try? await client.liveStreams(categoryID: category.id))
                    }
                }
                var collected: [(String, [LiveStream]?)] = []
                for await result in group {
                    collected.append(result)
                }
                return collected
            }
            for (id, streams) in results {
                if let streams { streamCache[id] = streams }
            }
        }
    }

    /// One list from several categories, in order, without repeats. A channel
    /// listed under two categories would otherwise give two rows the same identity.
    private static func merge(_ lists: [[LiveStream]]) -> [LiveStream] {
        var seen = Set<Int>()
        return lists.joined().filter { seen.insert($0.id).inserted }
    }
}
