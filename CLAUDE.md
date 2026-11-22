# CLAUDE.md

Notes for Claude Code working on HillTV, a personal tvOS IPTV player (UHF/Chillio style). Read README.md for the human-facing overview.

## The big constraint: there is no Mac

Beau builds on Windows and Linux. GitHub Actions on `macos-26` is the compiler. The loop is: edit, commit, push, watch CI, read failures, fix.

```
gh run watch                         # follow the latest run
gh run view --log-failed             # just the failing step's log
gh workflow run build.yml -f ksplayer=true   # one-off KSPlayer build
```

The core package does build and test locally on Linux with a Swift toolchain, so put logic there whenever you can and test it before pushing:

```
swift test --package-path Packages/HillTVCore
```

Anything that imports SwiftUI, UIKit, AVKit or Security can only be compiled in CI. Keep those pushes small so a failed run points at one change. `swiftc -parse $(find App -name '*.swift')` catches syntax errors locally but does no type checking.

## Status when handed off

`HillTVCore`: Xtream client, lenient decoding, URL building with correct credential encoding, short EPG with base64 titles, series info in both dict and array shapes, VOD info (`get_vod_info`), M3U parser, `WatchHistory`, `SearchIndex`, `GuideWindow`, `EPGSchedule`, `CategoryGroup`, `CategoryVisibility`, `Scoreboard`, `GameMatcher`. 74 XCTest cases, passing in CI's Linux job.

`App/` (AVPlayer build) compiles green in CI as of Nov 2026. First device test (Nov 2026, with the older fixed sidebar): installs and runs. Focus between sidebar and content worked, and so did long-press Add to Favorites.

- Menu at a section's root used to quit the app. Now `MainShell` sends focus to the sidebar via `.onExitCommand`, only when that section's `NavigationPath` (on its model) is empty, so detail pages still pop normally. Needs a device check.
- Focus rings were cut off at scroll view edges. Poster grids, Home rails and season chips now use `.scrollClipDisabled()`, and `MainShell` masks the content so overflow can't cover the sidebar. Needs a device check.

Added after that test, compiled but not yet run on the TV:

- Continue watching (AVPlayer only). Progress is saved every 10 s and when the player closes. Check that Menu during playback still closes the player and the position is saved, that Resume lands at the right spot, and that the Home rail updates.
- Search (`.searchable` inside the custom shell). Check the keyboard appears, Menu from the keyboard behaves (the shell's `.onExitCommand` may send focus to the sidebar instead of closing it), and how long the first catalog load takes on a big provider.
- Guide grid. Check focus moves cell to cell and row to row, the "now" line lines up, and listings fill in as you scroll. Times rely on `parsePanelDate`, which assumes UTC when a panel sends no timestamps, so some panels may show shifted times.

"Control Room" redesign (Nov 2026, option C of three mockups Beau picked from). Beau ran it on the TV and said it looks great so far, without going through this list item by item. Still worth checking each of these on device:

- Icon rail. Confirmed on the TV: it widens with labels while focused and collapses when focus moves right into the content. Not yet confirmed: Menu at a section's root coming back to the rail, and the wide highlight and labels not being clipped.
- Live TV chips. Left and right move along the row, Select changes category, down goes to the channel list, up comes back. "All" opens the full category grid and Menu closes it. After a pick from the grid the chip row should scroll to the selected chip.
- Live TV rows. "On now" titles and progress should fill in as rows scroll into view, and channels with no guide data should say so instead of spinning. The detail pane on the right should follow focus after a short pause.
- Live preview (Settings > Live TV preview). Off by default unless the account reports two or more connections. When on: the picture should start about a second after focus rests on a channel, stop when the player opens, when the app leaves the foreground and after three minutes on one channel, and never block the full player from starting. On a one-connection account with the preview forced on, Select waits 0.7 s after stopping the preview before opening the player; check that is enough for the panel to free the connection.
- Home. The search chip should jump to Search, the hero's Play and Details buttons should both be reachable, and the "Live now on your favorites" cards should show what's on.
- Colors and type from across the room: blue focus fill on rows, muted text on the dark surfaces, the condensed hero title.

Where the build differs from the mockup: the rail sits inside the tvOS safe area (like the old sidebar did) rather than against the screen edge, Live TV has an "All" category grid the mockup didn't show, chips span the full width instead of sitting above the list, and the preview has the defaults above.

Added after that (Nov 2026, option 1 of three category-screen mockups). Compiled green, never run on the TV. Check on device:

- Live TV pills. Left and right move along the big pills, Select changes group, down goes to the smaller category row and then the list. The last category used in each group should come back when its pill is picked again. Check how sensibly the provider's categories were sorted into groups, and what ends up in More.
- "All" chip. Shown for groups with 2 to 12 categories. It should list every channel in the group once, even one the provider files under two categories.
- Hiding categories (Settings > Categories). Toggle one, hide a whole Live TV group, use Hide all and Show all on each tab. Hidden categories should be gone from Live TV, Guide, Movies, Series, the Home rails and Search results, and a hidden category that was selected should give way to the next visible one. Hiding everything should show a message pointing back to Settings. Choices should survive quitting the app and signing out and in.
- Sports > Games. Scores, logos and game clocks should appear within a few seconds and update each minute. Each row names the channel it matched or says "Maybe" or "No matching channel"; judge how often the match is right on a real provider. Select on a single sure match should tune in; anything else should open a list, and picking from it should play once the list closes. With no network, Sports should just show channels.
- Search. The system search keyboard is built for the full screen width and never fit beside the rail: first its left letters were clipped, then after a 56 pt nudge "z" ran off the right edge (both seen on the TV). Search now opens as a full-screen page with no rail (Beau's pick over a search box with the full-screen keyboard). Check the whole letter row is on screen, results appear while typing, Menu closes the page and returns to the section that was showing, and detail pages and playback opened from results close back to Search.

Where the build differs from the mockup: "All" only appears for groups of 2 to 12 categories (the mockup showed it everywhere), Sports uses "Games" in its place with every sports channel listed under the games, a game with several possible channels opens a list instead of tuning, and long category rows get a grid button.

Still never compiled: `KSPlayerEngineView.swift` (only built with `project-ksplayer.yml`). Written against KSPlayer `main` as of Nov 2026: `KSVideoPlayerView(coordinator:url:options:title:subtitleDataSource:)` (the labeled form, because two of their inits overlap), `KSOptions.firstPlayerType/secondPlayerType`, `options.isLive`, `options.userAgent`. If their API moved, check `Sources/KSPlayerUI/SwiftUI/KSVideoPlayerView.swift` in their repo.

Some cloud sessions have no Swift toolchain, so `swift test` and the `test-runner` subagent can't run there. Push and let CI's `core-tests` job run them; on a branch, start it with a manual `workflow_dispatch` (only `main` pushes trigger automatically, and releases only publish from `main` and tags).

XcodeGen output for both specs was verified (XcodeGen 2.48 on Linux): scheme, packages, Info.plist and build settings all generate correctly.

## Layout and architecture

```
App/HillTVApp.swift          @main, RootView (login vs shell), MainShell + Sidebar (the icon rail), Wordmark
App/Model/AppModel.swift     Session, engine choice, live format choice
App/Model/AccountStore.swift Keychain with UserDefaults fallback
App/Model/FavoritesStore.swift
App/Model/LoadState.swift    LoadState enum + LoadStateView placeholder
App/Model/EPGStore.swift     Short EPG per channel for Live TV and Home, plus a shared clock
App/Model/LiveTVModel.swift  Live TV selection: a pill (Favorites or a category group) plus a sub-selection
App/Model/CategoryVisibilityStore.swift  Hidden categories per account, kept on sign out
App/Model/ScoreboardStore.swift  Scores for Sports and the channels matched to each game
App/Player/                  PlayableItem, PlayerView, AVPlayer + KSPlayer engines, LivePreviewView
App/Views/Theme.swift        Color/type tokens, button styles, Eyebrow, Pill, ProgressBar, FocusAwareProgressBar, FlowLayout, Backdrop
App/Views/                   Home, Search, Login, LiveTV (pills, chips, rows, detail pane, category picker), SportsViews (game rows, scoreboard pane, channel picker), CategoryManager, Guide, Movies + MovieDetail, Series (+ detail), Favorites, Settings, shared components (Rail, PosterCard, ChannelTile, LiveNowCard)
App/Model/WatchHistoryStore.swift  Continue watching, per account, cleared on sign out
Packages/HillTVCore/         No UI imports. Builds on tvOS, macOS, Linux.
```

Patterns to keep:

- Watch history stores IDs and rebuilds stream URLs with `movieURL(streamID:)` / `episodeURL(episodeID:)`. Never persist stream URLs: they contain the password.
- Search is a `fullScreenCover` over the shell, not a section: `MainShell.railSelection` opens it and leaves the current section selected. Anything that uses `.searchable` needs the full screen width on tvOS; don't put it back beside the rail. While the cover is up `MainShell` swaps the section for `Color.clear`, so nothing behind it holds a connection or polls. `AppModel` is passed into the cover explicitly.
- Each browse section (Home, Search, Live TV, Guide, Movies, Series) has an `@MainActor @Observable` model held as `@State` in `MainShell` and passed into the view, so switching sections in the rail keeps categories and caches. Models cache per category and drop late responses if the user moved on.
- Styling goes through `App/Views/Theme.swift`: color and type tokens, `TVRowButtonStyle` (rows), `TVActionButtonStyle` (actions, season chips), `TVPillButtonStyle` (the big Live TV pills), `TVChipButtonStyle` (filter chips), `TVCardButtonStyle` with `PosterCard` and `LiveNowCard` (cards). Blue is for focus, selection and progress; red only marks "live". A focused row fills blue with dark text, so anything inside one must flip color too (`tvSecondary()`, `FocusAwareProgressBar`). Use `ScrollView` + `LazyVStack` with these styles instead of `List`, whose focus platter fights the custom rows. To change the palette or type, edit the tokens at the top of `Theme.swift`; nothing else hardcodes colors.
- The rail (`Sidebar` in `HillTVApp.swift`) never changes its layout. Buttons stay icon-sized and the expanded panel, highlight and labels are drawn outside their frames, over the content. Keep it that way: the focus engine works from frames, and this keeps them identical collapsed or expanded.
- Guide, Movies and Series use `CategoryColumn`, which selects on focus with a 350 ms debounce, so up/down on the remote browses without clicking. Live TV is different: its pills and category chips select on click, because stepping across a row shouldn't reload the list at every stop.
- Live TV groups the provider's categories into pills with `CategoryGroup.classify` in core, which is keyword matching on the category name. A foreign country or language marker wins over a topic, so "FR | SPORT" and "LATINO SPORTS" go to World, not Sports (and their channels are not used for matching games); English-speaking markers like "US" and "UK" are ignored, so "UK | NEWS" is News. Every category lands in a group; unknown ones go to More. `LiveTVModel.groups` is stored and refreshed in `reconcileSelection()`, not computed per render. To fix a category that lands in the wrong group, add a keyword and a test case there. `LiveTVModel.Selection` is a pill plus a sub-selection: a provider category ID, `allID` (the group's channels merged, deduplicated by stream ID) or `gamesID` (Sports only). Favorites is a pill whose channels come from `FavoritesStore`.
- Hidden categories: models keep every category and expose `visibleCategories`; views only ever use the visible ones. Each model's `reconcileSelection()` runs on appear, because categories can be hidden in Settings between visits. `HomeModel` rebuilds its rails when the hidden set changed. Search filters results by category. Favorites and continue watching are never filtered. Any new screen that lists categories or their contents must do the same.
- Sports scores come from ESPN's public scoreboard feed (`Scoreboard` and `ScoreboardClient` in core). It is unofficial and keyless, so treat every failure as "no scores" and fall back to plain channels; never show the user an error for it. Poll only while games are on screen (`LiveTVView.gamesActive`). Team logos load from the feed at run time; never commit them, the repo is public.
- `GameMatcher` ties a game to channels from text alone. Both teams named in a channel name or guide listing is a sure match; a network name alone is not, because many channels share one. Tune directly only on exactly one sure match and open `GameChannelPicker` otherwise. Don't loosen this without device results: a wrong silent tune is worse than a list.
- `EPGStore` (held by `MainShell`, shared by Live TV and Home) fetches short EPG for rows that stay on screen 300 ms, four requests at a time, and caches results including empty ones. `EPGSchedule.needsFetch` in core decides when to ask again. Views read `epg.now`, which ticks every 30 s, instead of `Date()`, so progress moves without each row running a timer. Guide still has its own fetch queue; fold it into `EPGStore` when Guide moves to XMLTV.
- Live preview: `LivePreviewView` is always AVPlayer with the HLS URL, whatever the main engine is. `LiveTVView.runPreview` owns its lifetime through `.task(id:)`. The preview must never hold a connection it can't be seen using: keep the stops for player open, scene inactive and idle timeout if this is reworked.
- `AppModel` goes in the environment. `.playerCover(item:)` presents the player and passes engine and user agent explicitly rather than relying on environment propagation into the cover.
- Player engine abstraction: `PlayerView` picks AVPlayer or KSPlayer. `KSPlayerEngineView.swift` has a `#if canImport(KSPlayerUI)` stub so nothing else needs conditional compilation.
- Live URLs: AVPlayer gets `.m3u8`, KSPlayer gets `.ts` unless the panel's `allowed_output_formats` lacks it. See `AppModel.liveFormat`.
- Xtream JSON is messy. All decoding goes through the `lenient*` helpers and `LossyArray` so a bad row or a `"1"` vs `1` never fails a whole list. Add a test with a realistic ugly payload for every new endpoint.
- Credentials: query items need `+` encoded as `%2B` (`fixPlusEncoding`), path segments need `/?#;` encoded (`encodePathSegment`). Both are tested; don't rebuild URLs by hand elsewhere.

Swift settings: Swift 6 compiler, Swift 5 language mode (`SWIFT_VERSION: 5.0`). Move to Swift 6 mode later, once the app compiles and runs.

## tvOS gotchas

- No WebKit on tvOS. Web-based players (IPTVnator, PWAs) cannot be embedded; port logic instead.
- UserDefaults is capped around 500 KB and Caches can be purged. Keep channel lists and EPG in memory or Caches and refetch. Persist only small things (favorites, settings). For durable sync across Apple TVs, use iCloud key-value storage later.
- AVPlayer plays HLS and mp4 only. Raw MPEG-TS and most mkv need KSPlayer.
- Focus engine: everything interactive must be a `Button`, `NavigationLink` or have `.focusable()`. Test focus paths, not just looks.
- Menu/Back button: confirm on device that it dismisses the full-screen player from both engines. If AVPlayerViewController swallows it, add `.onExitCommand` handling.
- ATS is wide open (`NSAllowsArbitraryLoads`) because most panels are plain http. Fine for sideloading.
- App icon and Top Shelf images live in `App/Assets.xcassets/App Icon & Top Shelf Image.brandassets` and are named by `ASSETCATALOG_COMPILER_APPICON_NAME` in `project.yml`. The icon is "Sunrise" (Beau's pick of four): three layers, sky at the back, a white sun with a play triangle, two blue hills in front. Don't edit the PNGs by hand; change `Tools/make_icon.py` and run it (needs Pillow, plus google-chrome and a network connection for the Top Shelf wordmark, which uses Inter). CI fails if the built app has no `Assets.car`, and prints the icon entries from Info.plist and the assets inside `Assets.car`. Beau prefers PNG artwork with solid, bold shapes. On the TV (Nov 2026): the icon shows and Beau likes it. Beau saw no layer movement on focus and no Top Shelf banner. CI shows the built app does contain "App Icon" as an `ImageStack` with all three layers at both scales, and both Top Shelf images, with `CFBundlePrimaryIcon` and `TVTopShelfImage` set, so the assets look right. Open questions for Beau: whether HillTV was in the top row (the banner only shows there), whether other apps' icons move when a thumb slides on the clickpad (the effect follows touch, not tilting the remote), and whether Reduce Motion is on.

## Roadmap (rough priority)

1. Get CI green with AVPlayer, install via atvloadly, smoke test on the TV.
2. Turn on KSPlayer (`USE_KSPLAYER=true`), get it green, then pin a KSPlayer revision in `project-ksplayer.yml` instead of `branch: main`.
3. App icon and Top Shelf brand assets: done (Nov 2026), needs a look on the TV.
4. Channel zapping inside the player (up/down for prev/next channel in the current category), plus "last channel" quick switch.
5. Full XMLTV guide. The Guide grid exists but uses short EPG per channel; switch its data source to XMLTV for full-day listings. Download `client.xmltvURL` (often gzipped, 10-100 MB), stream-parse with `XMLParser` off the main thread, store in SQLite in Caches, map by `epg_channel_id`.
6. Search: done (name matching). Later: search EPG titles once XMLTV is stored.
7. Catch-up / timeshift for channels with `tv_archive`. Xtream URL shape: `/timeshift/{user}/{pass}/{durationMinutes}/{Y-m-d:H-M}/{streamID}.ts`, where the date is in the panel's timezone (`server_info.timezone`), not the viewer's.
8. M3U playlists as a second source type, using the existing `M3UParser`.
9. Stalker/Ministra portals.
10. Resume and continue watching for KSPlayer (AVPlayer done). Watch history for live channels ("last channel").
11. Hand the preview's AVPlayer to the full-screen player on Select (AVPlayer engine), so the picture carries over with no second connection or rebuffer.
12. Sports: better game matching once XMLTV is stored (search every sports channel's listings, not just the ones already fetched), a setting for which leagues to follow, and letting the user move a category to a different pill.
13. Sync hidden categories and favorites between Apple TVs with iCloud key-value storage.
14. Android TV app (Kotlin, Compose for TV, Media3). Beau asked about it; the plan is to settle the Apple TV design on device first, then port `HillTVCore`'s logic and tests to Kotlin.
15. Multiple accounts.

Related known gap: `EPGEntry.parsePanelDate` assumes UTC when a panel sends no timestamps. It should use the panel timezone from `server_info`.

## Porting from IPTVnator

IPTVnator (github.com/4gray/iptvnator, MIT, Electron + Angular, TypeScript) already solves several roadmap items. Port the logic to Swift; don't reuse the name or logo (its MIT license excludes them; see TRADEMARK.md there). Useful places, as of Nov 2026:

- Stalker portal client and auth: `libs/portal/stalker/data-access/src/client.ts`, `.../lib/stalker-auth.api.ts`, `.../lib/stalker-token-cache.ts`, `.../lib/stalker-paged-client.ts`
- Streaming XMLTV parser and storage: `apps/electron-backend/src/app/workers/epg-streaming-parser.ts`, `epg-optional-gunzip.ts`, `epg-database.ts`
- Catch-up URL building: `libs/shared/m3u-utils/src/lib/catchup.utils.ts`
- Xtream panel timezone handling for timeshift and EPG strings: `libs/shared/interfaces/src/lib/xtream-server-timezone.util.ts`
- Mock servers for testing without a real provider: `apps/xtream-mock-server`, `apps/stalker-mock-server`

## House rules

- Never commit credentials, playlists with embedded logins, or provider URLs. Test fixtures use fake hosts like `line.example.com`.
- Don't commit the `.xcodeproj` or `App/Info.plist`; XcodeGen generates both. Change `project.yml` instead.
- In shell instructions, use the hyphenated `docker-compose` command, not `docker compose`.
- UI copy and docs: plain, direct language, no em dashes.
- Consult the advisor before a plan locks, after hitting the same error twice, and before marking a task done.
- Show mockup options and get Beau's pick before changing layout, navigation or visual style.
