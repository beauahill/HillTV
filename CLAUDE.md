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

Released as v0.2.0 (Nov 2026), the first version Beau called ready. Beau runs it on the Apple TV day to day. v0.3.0-rc1 (Nov 2026) is a release candidate for 0.3.0: live buffer and live delay settings, and KSPlayer for live channels. It is the first release built with KSPlayer, and the repo became GPL-3.0 for it (Beau's decision, `LICENSE`). The repo variable `USE_KSPLAYER=true` is set (Beau asked for the KSPlayer build on `main` so atvloadly installs it), so every push to `main` and every tag builds with KSPlayer; the IPA has `-ks` in its name. To cut a release: bump `MARKETING_VERSION` in `project.yml` (numbers only; "rc1" goes in the tag), merge to `main`, then push a `v*` tag on `main`; CI builds the IPA and attaches it to the GitHub release. A tag with a hyphen publishes as a prerelease and leaves "Latest" alone. To make 0.3.0 final, tag `v0.3.0` on `main`. Every push to `main` also publishes an automatic "Build N" prerelease. Keep every release to one IPA: atvloadly (the Source tab, merged Sept 2026; Beau's copy may need updating to have it) follows a repo's releases, newest first, with a pre-release switch, and picks the IPA by a file-name filter it derives on first install (for this repo, the word "hilltv"). Two IPAs in one release match that filter twice and the update stops with "choose one". Read from atvloadly's `internal/source/github.go` and `match.go`, Nov 2026. A manual workflow run follows its checkbox exactly, so `gh workflow run build.yml --ref <branch>` still makes an Apple-player-only build. Manual runs never publish a release, on any ref; only pushes do.

`HillTVCore`: Xtream client, lenient decoding, URL building with correct credential encoding, short EPG with base64 titles, series info in both dict and array shapes, VOD info (`get_vod_info`), M3U parser, `WatchHistory`, `SearchIndex`, `GuideWindow`, `EPGSchedule`, `CategoryGroup`, `CategoryVisibility`, `Scoreboard`, `GameMatcher`, `PlaybackSettings`, `ChannelName`. 92 XCTest cases, passing in CI's Linux job.

`App/` (AVPlayer build) compiles green in CI as of Nov 2026. First device test (Nov 2026, with the older fixed sidebar): installs and runs. Focus between sidebar and content worked, and so did long-press Add to Favorites.

- Menu at a section's root used to quit the app. Now `MainShell` sends focus to the sidebar via `.onExitCommand`, only when that section's `NavigationPath` (on its model) is empty, so detail pages still pop normally. Needs a device check.
- Menu on the rail used to quit the app (reported by Beau on the TV, Nov 2026). Beau asked for it to close the rail and go back to the content, so now it does: `MainShell.leaveRail()`. Together with the item above, Menu at the top level only moves between rail and content and never closes the app; the TV button does. Compiled, not run on the TV. Check: Menu on the rail lands somewhere sensible in the content and the rail collapses; in a section with nothing to focus (Favorites when empty) focus comes back to the rail and the app doesn't end up with nothing focused.
- Focus rings were cut off at scroll view edges. Poster grids, Home rails and season chips now use `.scrollClipDisabled()`, and `MainShell` masks the content so overflow can't cover the sidebar. Needs a device check.

Added after that test, compiled but not yet run on the TV:

- Continue watching (AVPlayer only). Progress is saved every 10 s and when the player closes. Check that Menu during playback still closes the player and the position is saved, that Resume lands at the right spot, and that the Home rail updates.
- Search. Beau has used it on the TV: the keyboard appears and results work. It has since moved to a full-screen page; see the Search item further down for what to check. Still unmeasured: how long the first catalog load takes on a big provider.
- Guide grid. Check focus moves cell to cell and row to row, the "now" line lines up, and listings fill in as you scroll. Times rely on `parsePanelDate`, which assumes UTC when a panel sends no timestamps, so some panels may show shifted times.

"Control Room" redesign (Nov 2026, option C of three mockups Beau picked from). Beau ran it on the TV and said it looks great so far, without going through this list item by item. Still worth checking each of these on device:

- Icon rail. Confirmed on the TV: it widens with labels while focused and collapses when focus moves right into the content. Not yet confirmed: Menu at a section's root coming back to the rail, and the wide highlight and labels not being clipped.
- Live TV chips. Left and right move along the row, Select changes category, down goes to the channel list, up comes back. "All" opens the full category grid and Menu closes it. After a pick from the grid the chip row should scroll to the selected chip.
- Live TV rows. "On now" titles and progress should fill in as rows scroll into view, and channels with no guide data should say so instead of spinning. The detail pane on the right should follow focus after a short pause.
- Live preview (Settings > Live TV preview). Off by default unless the account reports two or more connections. When on: the picture should start about a second after focus rests on a channel, stop when the player opens, when the app leaves the foreground and after three minutes on one channel, and never block the full player from starting. On a one-connection account with the preview forced on, Select waits 0.7 s after stopping the preview before opening the player; check that is enough for the panel to free the connection.
- Home. The search chip should open the Search page, the hero's Play and Details buttons should both be reachable, and the "Live now on your favorites" cards should show what's on.
- Colors and type from across the room: blue focus fill on rows, muted text on the dark surfaces, the condensed hero title.

Where the build differs from the mockup: the rail sits inside the tvOS safe area (like the old sidebar did) rather than against the screen edge, Live TV has an "All" category grid the mockup didn't show, chips span the full width instead of sitting above the list, and the preview has the defaults above.

Added after that (Nov 2026, option 1 of three category-screen mockups). Compiled green, never run on the TV. Check on device:

- Live TV pills. Left and right move along the big pills, Select changes group, down goes to the smaller category row and then the list. The last category used in each group should come back when its pill is picked again. Check how sensibly the provider's categories were sorted into groups, and what ends up in More.
- "All" chip. Shown for groups with 2 to 12 categories. It should list every channel in the group once, even one the provider files under two categories.
- Hiding categories (Settings > Categories). Toggle one, hide a whole Live TV group, use Hide all and Show all on each tab. Hidden categories should be gone from Live TV, Guide, Movies, Series, the Home rails and Search results, and a hidden category that was selected should give way to the next visible one. Hiding everything should show a message pointing back to Settings. Choices should survive quitting the app and signing out and in.
- Sports > Games. Beau has it running on the TV. Reported there: many scores showed as "…", because long team names squeezed them in the row and the scoreboard's middle column was too narrow for the big digits; scores now have fixed size and layout priority (not yet rechecked). Still to judge: scores, logos and game clocks should appear within a few seconds and update each minute. Each row names the channel it matched or says "Maybe" or "No matching channel"; judge how often the match is right on a real provider. Select on a single sure match should tune in; anything else should open a list, and picking from it should play once the list closes. With no network, Sports should just show channels. Beau also reported one freeze on the TV (Nov 2026, 0.2.0): on Games, with a game highlighted, while the rows still said "Finding channels". Not reproduced, and the cause is not proven. The likely one was matching: every game was checked against every sports channel (4.9 s for 120 games and 5,000 channels in a debug build on a laptop, 0.26 s now), and a second run could start while the first was still going. `GameMatcher` now looks up candidates in a word index and `ScoreboardStore.rematch` cancels the run it replaces. Rows say "Finding channels" until the first matching has finished. If it freezes again, the next suspect is the size of the Games list itself: it merges every Sports category with no limit, unlike "All".
- Search. The system search keyboard is built for the full screen width and never fit beside the rail: first its left letters were clipped, then after a 56 pt nudge "z" ran off the right edge (both seen on the TV). Search now opens as a full-screen page with no rail (Beau's pick over a search box with the full-screen keyboard). First check: Menu closes the page. It is the only way out, and it relies on the system closing a full-screen cover, the same way the player closes. If Beau is ever stuck there, add an explicit exit; don't add one blind. Then check the whole letter row is on screen through "z" and the keys after it, results appear while typing, and detail pages and playback opened from results close back to Search. The section behind is rebuilt when Search closes, so it comes back at its top, not where it was scrolled.

Where the build differs from the mockup: "All" only appears for groups of 2 to 12 categories (the mockup showed it everywhere), Sports uses "Games" in its place with every sports channel listed under the games, a game with several possible channels opens a list instead of tuning, and long category rows get a grid button.

Added after 0.2.0 (Nov 2026), on the session branch, compiled but not run on the TV: Settings > Live buffer and Settings > Live delay. Check on device:

- With both on their defaults, live playback should be exactly as in 0.2.0.
- Live delay. Tune to a news channel with an on-screen clock and compare it with a phone, on Standard and then on Closest to live. Closest should be visibly nearer real time; note by how much, and whether it pauses more. Apple's documentation doesn't say whether a distance below the stream's recommendation is honored or quietly clamped, so "no change" is a possible result.
- Keep up. After the stream stalls to load, the picture should skip ahead rather than stay further behind. Pressing Pause and Play should not skip ahead: the catch-up is documented as following buffering, not a user pause.
- Live buffer. 1 second should start no slower and may stall more; 30 seconds should ride out a weak connection. Changes apply the next time a channel starts.

Added after v0.3.0-rc1 (Nov 2026), on the session branch, compiled but not run on the TV. Beau asked for both. Check on device:

- Channel names (option B of four mockups). Names show without the country prefix and quality label, and those labels show small and muted: under the name where the row has room (Guide, Favorites, Search tiles), after it where the row already has a second line (Live TV rows and detail pane, Home cards, the game channel list). The player title is the clean name alone. The rules were written without seeing Beau's provider: every example in the tests is a guess at common formats. Beau was asked for a photo of a channel list or 10 to 15 real names (locals, one network with several feeds, one foreign channel). When they arrive, add them to `ChannelNameTests` first, then fix the rules. Check: names that lost something they should have kept, prefixes that were not removed, and whether two feeds of one network can still be told apart at a glance. Beau's example was "CBS - Cheyenne - 13"; putting a name's parts in a set order is not built, because it depends on what the provider sends. The number in Live TV rows is Xtream's `num`, which may be list order rather than a broadcast number; not checked against real data.
- Guide > Favorites. "Favorites" is the first entry in the Guide's category list, with no icon (the mockup had one). The Guide opens on it the first time when there are favorites, the same rule Live TV uses. Check: listings fill in, long-press removes a channel and the row goes at once, focus lands somewhere sensible after removing the focused row (and after removing the last one), and with no favorites the grid says how to add one. With every category hidden the Guide shows Favorites alone.

KSPlayer build (Nov 2026): compiles green in CI against KSPlayer 5.6.2, pinned by commit in `project-ksplayer.yml`. It exists to cut the delay behind live broadcasts by playing raw MPEG-TS. Beau ran build 28 on the TV with the new player and said it "seems to work really well", without clock numbers and without going through this list. Still to check on device, with a `-ks` build installed (Settings > About shows "KSPlayer: Included") and "KSPlayer for live channels" picked in Settings > Player:

- Reported on the TV: in the closed captioning menu "the text and highlight accents are a little off". Beau said it was the tabbed panel that Down opens (`VideoSettingView`, Subtitle tab first), not the popup from the captions button. No photo, so what exactly is off is still a guess. The panel is a `.sheet` attached outside the `.tint(.white)` KSPlayer gives its control bar, so it is the one KSPlayer screen the app's blue tint and `Theme.text` can reach. `KSPlayerEngineView` now puts `.tint(.white)` and `.foregroundColor(nil)` on `KSVideoPlayerView`. If the app's blue was reaching the panel, white now replaces it; if it wasn't, the panel has moved from the system's default tint to white, so check the selected tab and a picker's chosen mark are still visible. The other line relies on `foregroundColor(nil)` clearing an inherited `foregroundStyle`, which Apple doesn't document; it does nothing if that is wrong. Not rechecked on the TV. If the panel still looks off, get a photo before trying anything else: a highlight that doesn't line up, or anything in the captions popup, is KSPlayer's or the system's own drawing, and the fix for that is replacing its controls, which needs mockups first.

- A live channel plays at all, with sound, and Menu exits. KSPlayer's own view handles Menu: it hides its controls if they are showing, otherwise dismisses, so it can take two presses.
- The clock test: a news channel's on-screen clock against a phone, on the Apple player and then on KSPlayer. This is the whole point; note the difference in seconds.
- A dead channel shows the app's error screen, not a black screen.
- Movies and episodes still open in the Apple player and still resume.
- KSPlayer draws its own on-screen controls, which don't match the app. Restyling or replacing them is a mockup question for later.
- If live playback breaks, switch back to "Apple player" in Settings. Nothing else depends on KSPlayer.

Beau's Linux laptop has no Swift toolchain either, but it has Docker and the `swift:6.2` image (3.8 GB, pulled Nov 2026), so core tests and a syntax check of the app run locally:

```
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD/Packages/HillTVCore:/src" -w /src swift:6.2 swift test
docker run --rm -v "$PWD:/src:ro" -w /src swift:6.2 bash -c 'swiftc -parse $(find App -name "*.swift")'
```

The first leaves a `.build` folder in the package; it is ignored by git. Some cloud sessions have no Swift toolchain, so `swift test` and the `test-runner` subagent can't run there. Push and let CI's `core-tests` job run them; on a branch, start it with a manual `workflow_dispatch` (only `main` pushes trigger automatically, and releases only publish from `main` and tags).

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
App/Views/                   Home, Search, Login, LiveTV (pills, chips, rows, detail pane, category picker), SportsViews (game rows, scoreboard pane, channel picker), CategoryManager, Guide, Movies + MovieDetail, Series (+ detail), Favorites, Settings, shared components (Rail, PosterCard, ChannelTile, LiveNowCard, ChannelNameLabel)
App/Model/WatchHistoryStore.swift  Continue watching, per account, cleared on sign out
Packages/HillTVCore/         No UI imports. Builds on tvOS, macOS, Linux.
```

Patterns to keep:

- Watch history stores IDs and rebuilds stream URLs with `movieURL(streamID:)` / `episodeURL(episodeID:)`. Never persist stream URLs: they contain the password.
- Search is a `fullScreenCover` over the shell, not a section: `MainShell.railSelection` opens it and leaves the current section selected. Anything that uses `.searchable` needs the full screen width on tvOS; don't put it back beside the rail. While the cover is up `MainShell` swaps the section for `Color.clear`, so nothing behind it holds a connection or polls. `AppModel` is passed into the cover explicitly.
- Each browse section (Home, Search, Live TV, Guide, Movies, Series) has an `@MainActor @Observable` model held as `@State` in `MainShell` and passed into the view, so switching sections in the rail keeps categories and caches. Models cache per category and drop late responses if the user moved on.
- Styling goes through `App/Views/Theme.swift`: color and type tokens, `TVRowButtonStyle` (rows), `TVActionButtonStyle` (actions, season chips), `TVPillButtonStyle` (the big Live TV pills), `TVChipButtonStyle` (filter chips), `TVCardButtonStyle` with `PosterCard` and `LiveNowCard` (cards). Blue is for focus, selection and progress; red only marks "live". A focused row fills blue with dark text, so anything inside one must flip color too (`tvSecondary()`, `FocusAwareProgressBar`). Use `ScrollView` + `LazyVStack` with these styles instead of `List`, whose focus platter fights the custom rows. To change the palette or type, edit the tokens at the top of `Theme.swift`; nothing else hardcodes colors.
- The rail (`Sidebar` in `HillTVApp.swift`) never changes its layout. Buttons stay icon-sized and the expanded panel, highlight and labels are drawn outside their frames, over the content. Keep it that way: the focus engine works from frames, and this keeps them identical collapsed or expanded. Menu on the rail goes to the content: SwiftUI can't move focus into a section whose views `MainShell` doesn't know, so `leaveRail()` disables the rail for 200 ms and the focus engine picks the content. `RailButtonStyle` must not react to `isEnabled`, or the rail would flicker when this happens.
- Guide, Movies and Series use `CategoryColumn`, which selects on focus with a 350 ms debounce, so up/down on the remote browses without clicking. Live TV is different: its pills and category chips select on click, because stepping across a row shouldn't reload the list at every stop.
- Live TV groups the provider's categories into pills with `CategoryGroup.classify` in core, which is keyword matching on the category name. A foreign country or language marker wins over a topic, so "FR | SPORT" and "LATINO SPORTS" go to World, not Sports (and their channels are not used for matching games); English-speaking markers like "US" and "UK" are ignored, so "UK | NEWS" is News. Every category lands in a group; unknown ones go to More. `LiveTVModel.groups` is stored and refreshed in `reconcileSelection()`, not computed per render. To fix a category that lands in the wrong group, add a keyword and a test case there. `LiveTVModel.Selection` is a pill plus a sub-selection: a provider category ID, `allID` (the group's channels merged, deduplicated by stream ID) or `gamesID` (Sports only). Favorites is a pill whose channels come from `FavoritesStore`.
- Channel names: `LiveStream.name` is always the provider's text. Favorites persist the whole struct, and `GameMatcher` and `SearchIndex` work on the raw name, so never store a cleaned one. For display, use `stream.displayName` (`ChannelName` in core) through `ChannelNameLabel`, or `.title` alone for a player title or a line of text. `ChannelName` only cuts what it is sure of: a known country or language marker followed by a separator, and quality labels at the end; anything else stays, and an empty result falls back to the whole name. Words that are also names ("RAW", "SD") count as labels only when bracketed or superscript. Any new screen that shows a channel name must use it too. Set `inFocusRow` on the label inside rows that fill blue on focus, and leave it off inside cards, which keep their text color.
- Guide favorites: `GuideModel.favoritesID` is a stand-in category ID. `select()` returns before the cache and the network for it, and `GuideView` reads the rows from `FavoritesStore`, not the model, so a removed favorite disappears at once. `CategoryColumn` is unchanged; the view puts the entry in front of the visible categories.
- Hidden categories: models keep every category and expose `visibleCategories`; views only ever use the visible ones. Each model's `reconcileSelection()` runs on appear, because categories can be hidden in Settings between visits. `HomeModel` rebuilds its rails when the hidden set changed. Search filters results by category. Favorites and continue watching are never filtered. Any new screen that lists categories or their contents must do the same.
- Sports scores come from ESPN's public scoreboard feed (`Scoreboard` and `ScoreboardClient` in core). It is unofficial and keyless, so treat every failure as "no scores" and fall back to plain channels; never show the user an error for it. Poll only while games are on screen (`LiveTVView.gamesActive`). Team logos load from the feed at run time; never commit them, the repo is public.
- `GameMatcher` ties a game to channels from text alone. Both teams named in a channel name or guide listing is a sure match; a network name alone is not, because many channels share one. Tune directly only on exactly one sure match and open `GameChannelPicker` otherwise. Don't loosen this without device results: a wrong silent tune is worse than a list. Matching goes through `GameMatcher.ChannelIndex`, which narrows each game to channels that have the first word of a team name or network; `sweep(for:in:)` checks every channel and exists so the tests can hold the narrowed result to it. Any new way to match must either keep that true (every match needs a word from `GameTerms.required`) or change `required`.
- `EPGStore` (held by `MainShell`, shared by Live TV and Home) fetches short EPG for rows that stay on screen 300 ms, four requests at a time, and caches results including empty ones. `EPGSchedule.needsFetch` in core decides when to ask again. Views read `epg.now`, which ticks every 30 s, instead of `Date()`, so progress moves without each row running a timer. Guide still has its own fetch queue; fold it into `EPGStore` when Guide moves to XMLTV.
- Playback settings (`PlaybackSettings` in core, `AppModel.playback`, applied in `AVPlayerEngineView`). The defaults must leave the player exactly as it was before the settings existed; only a non-default choice may change it. On AVPlayer, live buffer and live delay are one lever: a live stream can't hold more ahead than it plays behind the live edge, so `liveBufferSeconds(offsetTarget:)` caps the buffer by the chosen distance. The distance uses `configuredTimeOffsetFromLive`, set once per item after `recommendedTimeOffsetFromLive` is known, then a seek to positive infinity. These settings only trim the player's share of the delay. Most of it is the provider's HLS packaging; the real cut is raw MPEG-TS through KSPlayer (roadmap 2). KSPlayer and the live preview ignore these settings for now. The settings decode leniently, so add new ones the same way.
- Live preview: `LivePreviewView` is always AVPlayer with the HLS URL, whatever the main engine is. `LiveTVView.runPreview` owns its lifetime through `.task(id:)`. The preview must never hold a connection it can't be seen using: keep the stops for player open, scene inactive and idle timeout if this is reworked.
- `AppModel` goes in the environment. `.playerCover(item:)` presents the player and passes engine and user agent explicitly rather than relying on environment propagation into the cover.
- Player engine abstraction: `PlayerView` picks AVPlayer or KSPlayer. `KSPlayerEngineView.swift` has a `#if canImport(KSPlayerUI)` stub so nothing else needs conditional compilation; keep the stub's signature in step with the real view. KSPlayer is used for live channels only (`PlayerView` checks `item.isLive`), because its view has no start position and reports no progress: movies and episodes would lose resume and continue watching. The Apple player is always the default, even in a build that includes KSPlayer; the user opts in under Settings. Every change to the player now needs both builds green: `gh workflow run build.yml --ref <branch>` and the same with `-f ksplayer=true`. They no longer cancel each other.
- KSPlayer's core is a prebuilt binary; its API is in `Sources/KSPlayer.xcframework/tvos-arm64/KSPlayer.framework/Modules/KSPlayer.swiftmodule/arm64-apple-tvos.swiftinterface` in their repo, and its SwiftUI layer is source under `Sources/KSPlayerUI/SwiftUI`. Read those before changing `KSPlayerEngineView.swift` or moving the pinned commit. Its free build is GPL-3.0, which is why this repo is GPL-3.0 as of v0.3.0-rc1 (Beau's decision, Nov 2026) and releases are built with KSPlayer (`USE_KSPLAYER=true`). One thing is still Beau's call, so ask first: making KSPlayer the default player. Don't add code or assets under a license that GPL-3.0 can't include.
- Live URLs: AVPlayer gets `.m3u8`, KSPlayer gets `.ts` unless the panel's `allowed_output_formats` lacks it. See `AppModel.liveFormat`.
- Xtream JSON is messy. All decoding goes through the `lenient*` helpers and `LossyArray` so a bad row or a `"1"` vs `1` never fails a whole list. Add a test with a realistic ugly payload for every new endpoint.
- Credentials: query items need `+` encoded as `%2B` (`fixPlusEncoding`), path segments need `/?#;` encoded (`encodePathSegment`). Both are tested; don't rebuild URLs by hand elsewhere.

Swift settings: Swift 6 compiler, Swift 5 language mode (`SWIFT_VERSION: 5.0`). Move to Swift 6 mode later, once the app compiles and runs.

## tvOS gotchas

- No WebKit on tvOS. Web-based players (IPTVnator, PWAs) cannot be embedded; port logic instead.
- UserDefaults is capped around 500 KB and Caches can be purged. Keep channel lists and EPG in memory or Caches and refetch. Persist only small things (favorites, settings). For durable sync across Apple TVs, use iCloud key-value storage later.
- AVPlayer plays HLS and mp4 only. Raw MPEG-TS and most mkv need KSPlayer. A KSPlayer IPA is about 23 MB against 1.3 MB without it.
- Focus engine: everything interactive must be a `Button`, `NavigationLink` or have `.focusable()`. Test focus paths, not just looks.
- Menu/Back button: confirm on device that it dismisses the full-screen player from both engines. If AVPlayerViewController swallows it, add `.onExitCommand` handling.
- ATS is wide open (`NSAllowsArbitraryLoads`) because most panels are plain http. Fine for sideloading.
- Installing with atvloadly can sit at 100% and never finish, and new icon or Top Shelf art can fail to appear. Both happened in Nov 2026 and both were fixed by restarting the Apple TV. Try that before suspecting the build.
- App icon and Top Shelf images live in `App/Assets.xcassets/App Icon & Top Shelf Image.brandassets` and are named by `ASSETCATALOG_COMPILER_APPICON_NAME` in `project.yml`. The icon is "Sunrise" (Beau's pick of four): three layers, sky at the back, a white sun with a play triangle, two blue hills in front. Don't edit the PNGs by hand; change `Tools/make_icon.py` and run it (needs Pillow, plus google-chrome and a network connection for the Top Shelf wordmark, which uses Inter). CI fails if the built app has no `Assets.car`, and prints the icon entries from Info.plist and the assets inside `Assets.car`. Beau prefers PNG artwork with solid, bold shapes. On the TV (Nov 2026): the icon shows and Beau likes it, and the Top Shelf banner shows correctly. The banner did not appear until the Apple TV was restarted, so after installing a build with new icon or Top Shelf art, restart the box before judging it. Not yet confirmed: the layers shifting when a thumb slides on the clickpad with the icon in focus (the effect follows touch, not tilting the remote). CI shows the built app contains "App Icon" as an `ImageStack` with all three layers, so check after a restart, compare with another app's icon, and check Reduce Motion is off before changing the art.

## Roadmap (rough priority)

1. Get CI green with AVPlayer, install via atvloadly, smoke test on the TV.
2. KSPlayer: compiles and is pinned (Nov 2026), live channels only, opt-in. Next, in order: Beau's device test and clock measurement; then a stream format choice (TS or HLS); then, only if the plain build still lags, FFmpeg low-latency options (`formatContextOptions`, `isSecondOpen`, `liveAdaptivePlaybackRate`), one at a time with a measurement each; then resume and progress saving so it can play movies (mkv, DTS) too.
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
15. More settings, in the spirit of UHF. Only three of UHF's are confirmed from its release notes: a buffer in seconds (done), a preferred live stream format, TS or M3U8 (needs KSPlayer), and channel previews while browsing (done as Live TV preview). Beau was asked for photos of UHF's Settings screens for the rest. Candidates from UHF's feature list: reconnect automatically when a stream drops, a PIN lock on categories, default audio and subtitle language, renaming and reordering channels and categories, a guide time offset in hours (which would also cover the `parsePanelDate` gap below). If the Settings page grows much more it needs sub-pages, which is a layout change: mock it up first.
16. Multiple accounts.

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
