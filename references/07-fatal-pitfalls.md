# 07. The 15 Fatal CloudStream Pitfalls ("Never Do This")

> **CRITICAL DIRECTIVE**:
> These 15 mistakes are the most frequent reasons why plugins fail to appear, crash the CloudStream app, fail to update, freeze playback, or silently corrupt the repository index. Memorize them before building or releasing any plugin.

---

## 1. `fileHash` in `plugins.json` (Fatal App Crash)
* **What happens**: CloudStream throws `kotlinx.serialization.SerializationException: Unknown key 'fileHash'`.
* **Consequence**: The app silently rejects the entire repository, and **none** of your plugins will show up.
* **Golden Rule**: **NEVER** include `fileHash` in `plugins.json`.

---

## 2. `repositoryUrl` Mismatch in `plugins.json` (Ghost Plugin)
* **What happens**: If `repositoryUrl` in `plugins.json` doesn't 100% match the URL the user added in their CloudStream settings (e.g. `http://` vs `https://` or different IP), CloudStream hides the plugin.
* **Golden Rule**: **OMIT** `repositoryUrl` from `plugins.json` to allow dynamic repo URL resolution.

---

## 3. `.ico` Favicons (BitmapFactory Native Crash)
* **What happens**: Android native `BitmapFactory` cannot decode Windows ICO file formats.
* **Consequence**: The CloudStream app crashes instantly upon opening the plugin list.
* **Golden Rule**: **ALWAYS** use `.png` URLs for `iconUrl`.

---

## 4. Category Filter Chip Drops (`tvTypes`)
* **What happens**: CloudStream UI filter chips only recognize top-level `TvType`:
  * `"Movie"`, `"TvSeries"`, `"Anime"`, `"Others"`, `"Live"`, `"Cartoon"`.
* **Consequence**: Specifying fine-grained types like `"AnimeMovie"` or `"OVA"` in `tvTypes = listOf(...)` causes CloudStream's UI filter chips to drop the provider.
* **Golden Rule**: Only use top-level types in `build.gradle.kts`.

---

## 5. HTTP 304 Caching Lockup (Outdated Plugin Stuck)
* **What happens**: CloudStream sends `If-Modified-Since` on repo refreshes. Standard web servers reply with `304 Not Modified`.
* **Consequence**: Even if you compile a new `.cs3` file, CloudStream never downloads the update.
* **Golden Rule**: Always host repository files with `Cache-Control: no-store, no-cache, must-revalidate` (as handled automatically by the bundled server script `scripts/serve_cs3.py`).

---

## 6. Missing `version` Bump on Bug Fixes (Cached Plugin Stagnation)
* **What happens**: CloudStream on Android compares the integer `version` field in `manifest.json`. If you fix bugs or update code, recompile via `:make`, but leave `version = 1`, CloudStream assumes the plugin is already current and will **not** download or reload the updated `.dex` file.
* **Consequence**: The real app continues executing the old, broken bytecode indefinitely during local testing.
* **Golden Rule**: **ALWAYS** increment `version = X + 1` in `build.gradle.kts` on every single bug fix or test iteration before running `:make`.

---

## 7. Episode Links Season Collapse (Flattening Multi-Season Series into Season 1)
* **What happens**: On CMS/WordPress sites, episode archive pages or child download buttons only label links as `"Episode 1"`, `"Episode 2"`, omitting the season number. Calling `extractSeasonAndEpisode("Episode 1")` yields `season = null`, which naively defaults to `Season 1`.
* **Consequence**: Season 2 and Season 3 episodes overwrite or merge into Season 1, completely destroying multi-season series navigation.
* **Golden Rule**: Traverse parent post headings (`h1..h6`) in document order to track `currentSeason` context (ignoring multi-season spans like `Season 1-3`), and tag subsequent episode links/archives with `currentSeason`.

---

## 8. Destructive Title Sanitization (Overly Aggressive Truncation)
* **What happens**: Stripping everything after the first `(` or blindly stripping keywords destroys critical context like `(Season 1-4)`, `(2024)`, and multi-part subtitles.
* **Consequence**: Users cannot tell which season they are selecting, and Cinemeta/TMDB metadata queries fail due to missing release years or altered titles.
* **Golden Rule**: Use balanced, non-destructive title cleaning that preserves canonical `(Season X-Y)` ranges and `(Year)` release tags:
  `Regex("""^(.*?(\((?:Season\s*[\d\-]+|\d{4})\)))""", RegexOption.IGNORE_CASE)`
  Only strip technical audio/video specs (`480p`, `720p`, `1080p`, `x264`, `web-dl`, codecs, sizes, and site branding).

---

## 9. Unfiltered Batch/Zip Archives in Video Streams
* **What happens**: Post pages frequently offer `"Batch/Zip File"` download buttons alongside individual episode streams. If these URLs are passed to `loadLinks` or `newEpisode`, ExoPlayer attempts to buffer a `.zip` archive as a video file.
* **Consequence**: Fatal player playback errors, crashes, or infinite buffering spinners.
* **Golden Rule**: Explicitly filter out any URLs or anchor tags containing `"Batch"`, `"Zip"`, or `".zip"`.

---

## 10. Jackson Version Bumping (Bytecode Incompatibility)
* **What happens**: Upgrading Jackson dependencies above `2.13.1` in `build.gradle.kts` introduces bytecode dependencies incompatible with Android runtimes.
* **Golden Rule**: Strictly pin `com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1` and `jackson-databind:2.13.1`.

---

## 11. Global Compilation Pollution (`status = 0`)
* **What happens**: Leaving unfinished or broken plugins in the build pipeline breaks repository-wide tasks (`./gradlew make` or `.\gradlew.bat make`).
* **Golden Rule**: Always set `status = 0` in `build.gradle.kts` while a plugin is under construction. Only change to `status = 1` once all unit tests pass.

---

## 12. Premature Kotlin Scaffolding (Skipping the Pre-Flight Feasibility Scorecard)
* **What happens**: AI agents rush to create directory structures, manifest files, and Kotlin provider classes before verifying that links can actually be extracted and streamed over pure HTTP.
* **Consequence**: When the upstream source turns out to have unpassable dynamic ad shorteners, forced 10+ second delays, or dead lockers, all written Kotlin code is completely wasted and must be deleted.
* **Golden Rule**: **NEVER** write a single line of Kotlin code or scaffold a module until the Phase 1 Pre-Flight Probe executes and displays a complete passing scorecard:
  1. Cleaned vs. raw title comparison table.
  2. Live image HTTP renderability check (HTTP 200, valid `image/*` MIME, size > 100B).
  3. TV series season hierarchy check (confirming seasons didn't collapse into Season 1).
  4. Hop-by-hop link extraction velocity benchmark with **flexible timeline (≤ 10s ceiling, non-blocking real-time feedback)**.

---

## 13. Submitting to Fake Timers & High-Churn Multi-Hop Shorteners
* **What happens**: The agent attempts to use `Thread.sleep()` or `delay(10000)` to wait out countdown timers on link shorteners, or tries to write fragile extractors for multi-hop ad networks that rotate domains every two weeks.
* **Consequence**: Android TV and mobile users face frozen screens or ExoPlayer timeouts. The plugin breaks within days as shortener domains rotate, resulting in an unmaintainable sinkhole of developer time.
* **Golden Rule**: Always execute the **Zero-Wait Protocol** to bypass client-side countdowns in < 500ms via pure HTTP. If a shortener genuinely enforces server-side delays (> 10s) or requires interactive CAPTCHAs across intermediate hops, provide clear non-blocking feedback to the developer before touching any code.

---

## 14. Unsolicited Plugin Settings Bloat
* **What happens**: The agent automatically generates `SettingsFragment.kt` and wires up `openSettings` even when the developer never requested settings.
* **Consequence**: Unnecessary class count, bloated Dex bytecode, and an empty or confusing settings dialog in CloudStream's Extensions manager.
* **Golden Rule**: Keep extensions lean and configuration-free. Never scaffold settings unless the developer explicitly asks for them.

---

## 15. Questionnaire Harassment Before Feasibility Verification
* **What happens**: The agent stalls and pesters the developer with intake questionnaires (authors, TMDB keys, categories, settings preferences) before even verifying if the site can yield streamable links.
* **Consequence**: The developer wastes time answering configuration details, only for the agent to discover minutes later that the site is dead, bot-blocked, or unplayable.
* **Golden Rule**: Invert the workflow. Execute Phase 1 feasibility probing and upstream extractor cross-checking FIRST. Only confirm smart defaults after the source is 100% proven viable.
