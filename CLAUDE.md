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

`HillTVCore`: Xtream client, lenient decoding, URL building with correct credential encoding, short EPG with base64 titles, series info in both dict and array shapes, VOD info (`get_vod_info`), M3U parser. 17 XCTest cases, passing in CI's Linux job.

`App/` (AVPlayer build) compiles green in CI as of Nov 2026, including the IPTVnator-style restyle: sidebar shell, Home with hero and rails, movie and series detail pages. First device test (Nov 2026): installs and runs. Focus between sidebar and content works, and so does long-press Add to Favorites.

- Menu at a section's root used to quit the app. Now `MainShell` sends focus to the sidebar via `.onExitCommand`, only when that section's `NavigationPath` (on its model) is empty, so detail pages still pop normally. Needs a device check.
- Focus rings were cut off at scroll view edges. Poster grids, Home rails and season chips now use `.scrollClipDisabled()`, and `MainShell` masks the content so overflow can't cover the sidebar. Needs a device check.

Still never compiled: `KSPlayerEngineView.swift` (only built with `project-ksplayer.yml`). Written against KSPlayer `main` as of Nov 2026: `KSVideoPlayerView(coordinator:url:options:title:subtitleDataSource:)` (the labeled form, because two of their inits overlap), `KSOptions.firstPlayerType/secondPlayerType`, `options.isLive`, `options.userAgent`. If their API moved, check `Sources/KSPlayerUI/SwiftUI/KSVideoPlayerView.swift` in their repo.

Some cloud sessions have no Swift toolchain, so `swift test` and the `test-runner` subagent can't run there. Push and let CI's `core-tests` job run them; on a branch, start it with a manual `workflow_dispatch` (only `main` pushes trigger automatically, and releases only publish from `main` and tags).

XcodeGen output for both specs was verified (XcodeGen 2.48 on Linux): scheme, packages, Info.plist and build settings all generate correctly.

## Layout and architecture

```
App/HillTVApp.swift          @main, RootView (login vs shell), MainShell + Sidebar, Wordmark
App/Model/AppModel.swift     Session, engine choice, live format choice
App/Model/AccountStore.swift Keychain with UserDefaults fallback
App/Model/FavoritesStore.swift
App/Model/LoadState.swift    LoadState enum + LoadStateView placeholder
App/Player/                  PlayableItem, PlayerView, AVPlayer + KSPlayer engines
App/Views/Theme.swift        Color/type tokens, button styles, Eyebrow, Pill, ProgressBar, FlowLayout, Backdrop
App/Views/                   Home, Login, LiveTV (+ NowNextPanel), Movies + MovieDetail, Series (+ detail), Favorites, Settings, shared components
Packages/HillTVCore/         No UI imports. Builds on tvOS, macOS, Linux.
```

Patterns to keep:

- Each browse section (Home, Live TV, Movies, Series) has an `@MainActor @Observable` model held as `@State` in `MainShell` and passed into the view, so switching sections in the sidebar keeps categories and caches. Models cache per category and drop late responses if the user moved on.
- Styling goes through `App/Views/Theme.swift`: color and type tokens, `TVRowButtonStyle` (rows, sidebar), `TVActionButtonStyle` (actions, chips), `TVCardButtonStyle` with `PosterCard` (posters). Mint is for focus and progress only; selection is a mint bar. Use `ScrollView` + `LazyVStack` with these styles instead of `List`, whose focus platter fights the custom rows. The look follows IPTVnator's TV edition tokens (`apps/tv/src/tokens.css`), without its name or logo.
- Category columns select on focus with a 350 ms debounce, so up/down on the remote browses without clicking. EPG for the focused channel is fetched after 400 ms of focus.
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
- No app icon or Top Shelf image yet. tvOS needs a layered "App Icon & Top Shelf Image" brand asset in an asset catalog. Beau prefers PNG artwork with solid, bold shapes.

## Roadmap (rough priority)

1. Get CI green with AVPlayer, install via atvloadly, smoke test on the TV.
2. Turn on KSPlayer (`USE_KSPLAYER=true`), get it green, then pin a KSPlayer revision in `project-ksplayer.yml` instead of `branch: main`.
3. App icon and Top Shelf brand assets.
4. Channel zapping inside the player (up/down for prev/next channel in the current category), plus "last channel" quick switch.
5. Full XMLTV guide grid. Download `client.xmltvURL` (often gzipped, 10-100 MB), stream-parse with `XMLParser` off the main thread, store in SQLite in Caches, map by `epg_channel_id`.
6. Search across live, movies and series.
7. Catch-up / timeshift for channels with `tv_archive`. Xtream URL shape: `/timeshift/{user}/{pass}/{durationMinutes}/{Y-m-d:H-M}/{streamID}.ts`, where the date is in the panel's timezone (`server_info.timezone`), not the viewer's.
8. M3U playlists as a second source type, using the existing `M3UParser`.
9. Stalker/Ministra portals.
10. Resume positions, watch history, "continue watching" rail on Home. (VOD info pages are done.)
11. Multiple accounts.

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
