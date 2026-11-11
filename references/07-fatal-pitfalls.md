# 07. The 16 CloudStream Pitfalls & Technical Realities

> **UPSTREAM FACT-CHECKED REFERENCE**:
> Every technical mechanism described below is directly verified against `recloudstream/cloudstream` (SDK/App) and `recloudstream/gradle` (Build Tooling). Avoid these common mistakes when authoring, packaging, and maintaining CloudStream extensions.

---

## 1. Manual `plugins.json` Creation & Bogus `fileHash`
* **Upstream Ground Truth**: In `recloudstream/gradle`, `WriteCacheEntryTask.kt` computes `fileHash = "sha256-" + sha256(cs3)` and `fileSize = cs3.length()`, as well as `jarHash` and `jarFileSize` when `isCrossPlatform = true`. In the app, `RepositoryManager.kt` verifies:
  ```kotlin
  if (expectedFileHash != null) {
      val downloadHash = sha256(tempFile)
      if (expectedFileHash != downloadHash) {
          tempFile.delete()
          throw IllegalStateException("Extension hash mismatch when validating '${file.name}'!")
      }
  }
  ```
* **Consequence**: Handcrafting `plugins.json` or authoring arbitrary SHA-256 strings causes CloudStream to reject the plugin download with `Extension hash mismatch` or malformed digest errors.
* **The Rule**: **NEVER** write `plugins.json` or calculate `fileHash` / `jarHash` by hand. Always run the official Gradle automation:
  ```bash
  ./gradlew makePluginsJson
  ```
  *(On Windows PowerShell: `.\gradlew.bat makePluginsJson`)*

---

## 2. Missing `setRepo(...)` in `build.gradle.kts`
* **Upstream Ground Truth**: In `recloudstream/gradle`, `CloudstreamExtension.kt` requires `setRepo(...)` (e.g. `setRepo("username/repo")`). `WriteCacheEntryTask` uses this to populate `url` (the direct raw `.cs3` download link) and `repositoryUrl` (the project web URL). Furthermore, `PluginManager.kt` line 377 skips auto-download if `repositoryUrl` is null or blank:
  ```kotlin
  if (sitePlugin.repositoryUrl.isNullOrBlank()) {
      return@mapNotNull null
  }
  ```
* **Consequence**: If `setRepo(...)` is omitted, the generated `plugins.json` will have blank or missing repository URLs, preventing the app from locating `.cs3` binaries and skipping auto-updates.
* **The Rule**: Always define `setRepo(...)` in the `subprojects { cloudstream { ... } }` or root build configuration.

---

## 3. Broken or Unoptimized `iconUrl` Formats
* **Upstream Ground Truth**: In `ExtensionsScreen.kt` (line 1530) and `PluginAdapter.kt` (line 157), icon loading is wrapped with Coil/Compose image loaders with explicit error fallbacks:
  ```kotlin
  AsyncImage(
      model = remapUrl(url = iconUrl, size = 24.dp),
      contentDescription = null,
      error = defaultIcon
  )
  ```
* **Consequence**: Broken URLs, 404s, or unsupported icon formats do **not** crash the app; they simply fall back to the default extension puzzle icon (`R.drawable.ic_baseline_extension_24`). However, low-resolution or non-standard icons degrade UI aesthetics on Android TV and mobile.
* **The Rule**: Always provide public, direct `.png` or `.svg` URLs hosted on reliable CDNs or raw GitHub to ensure crisp rendering across all screen densities.

---

## 4. Invalid `TvType` String Values in `build.gradle.kts`
* **Upstream Ground Truth**: In `AndroidRepositoryManager.kt` line 126, CloudStream parses plugin categories dynamically:
  ```kotlin
  tvTypes = plugin.tvTypes?.mapNotNull { runCatching { TvType.valueOf(it) }.getOrNull() }
  ```
  The valid enum constants in `library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt` are:
  `Movie`, `AnimeMovie`, `TvSeries`, `Cartoon`, `Anime`, `OVA`, `Torrent`, `Documentary`, `AsianDrama`, `Live`, `NSFW`, `Others`, `Music`, `AudioBook`, `CustomMedia`, `Audio`, `Podcast`, `Video`.
* **Consequence**: Typographical errors (such as typing `"Movies"` plural instead of `"Movie"`, or `"Series"` instead of `"TvSeries"`) cause `runCatching` to return null, silently dropping the category from the plugin's metadata. Notice that fine-grained types like `AnimeMovie` and `OVA` are officially supported and mapped to the Anime category chip in `ExtensionsScreen.kt`.
* **The Rule**: Always use the exact singular enum names defined in `TvType`.

---

## 5. The 15-Minute Client-Side Repository Cache
* **Upstream Ground Truth**: In `RepositoryManager.kt` lines 164 & 172, CloudStream fetches repositories and plugin manifests with an internal 15-minute cache:
  ```kotlin
  app.get(convertRawGitUrl(url), cacheTime = 15, cacheUnit = TimeUnit.MINUTES)
  app.get(convertRawGitUrl(pluginUrls), cacheTime = 15, cacheUnit = TimeUnit.MINUTES)
  ```
* **Consequence**: When testing locally, modifying `plugins.json` or `repo.json` on your hosting server will not immediately reflect in the CloudStream app if re-queried within 15 minutes, because the client serves the cached manifest.
* **The Rule**: During rapid local development, remove and re-add the repository URL in the app, restart the app, or use `version = -1` (`PLUGIN_VERSION_ALWAYS_UPDATE`) to bypass cached checks.

---

## 6. Missing `version` Bump on Bug Fixes (Cached Plugin Stagnation)
* **Upstream Ground Truth**: In `PluginManager.kt` line 230:
  ```kotlin
  onlineData.plugin.version > savedData.version || onlineData.plugin.version == PLUGIN_VERSION_ALWAYS_UPDATE
  ```
  where `const val PLUGIN_VERSION_ALWAYS_UPDATE = -1`.
* **Consequence**: CloudStream only updates an installed extension if the online version is strictly greater than the currently installed version (or if version is `-1`). If you recompile `:make` but leave `version = 1`, CloudStream assumes the plugin is current and skips downloading the new `.cs3` Dex file.
* **The Rule**: Increment `version = X + 1` in `build.gradle.kts` on every bug fix before running `:make` (or use `version = -1` during development).

---

## 7. Episode Links Season Collapse (CMS Heading Hierarchy)
* **What happens**: On CMS/WordPress sites, episode download buttons often only display `"Episode 1"`, `"Episode 2"`, omitting the season number. Passing `"Episode 1"` to naive extractors results in `season = null`, defaulting all episodes to `Season 1`.
* **Consequence**: Episodes from Season 2 and Season 3 overwrite or merge into Season 1, corrupting multi-season series navigation.
* **The Rule**: Traverse parent post headings (`h1..h6`) in document order to track `currentSeason` context (ignoring multi-season spans like `Season 1-3`), and tag subsequent episode links with `currentSeason`.

---

## 8. Destructive Title Sanitization
* **What happens**: Overly aggressive regexes that strip everything after the first `(` or strip arbitrary numbers destroy critical metadata like `(Season 1-4)`, `(2024)`, and multi-part subtitles.
* **Consequence**: Users cannot determine which season or year they are watching, and Cinemeta/TMDB metadata queries fail due to altered titles.
* **The Rule**: Use balanced, non-destructive title cleaning that preserves canonical `(Season X-Y)` ranges and `(Year)` release tags:
  `Regex("""^(.*?(\((?:Season\s*[\d\-]+|\d{4})\)))""", RegexOption.IGNORE_CASE)`
  Only strip technical audio/video specs (`480p`, `720p`, `1080p`, `x264`, `web-dl`, codecs, and site watermarks).

---

## 9. Unfiltered Batch/Zip Archives in Video Streams
* **What happens**: Media sites frequently include `"Batch/Zip File"` download buttons alongside individual episode streams.
* **Consequence**: ExoPlayer cannot parse or stream compressed zip/rar archives, leading to immediate playback failures.
* **The Rule**: Explicitly filter out any links matching `"Batch"`, `"Zip"`, or `".zip"` before passing URLs to `newEpisode` or `loadLinks`.

---

## 10. Using Jackson Instead of `kotlinx.serialization`
* **Upstream Ground Truth**: CloudStream's core library is built around Kotlin Multiplatform. The upstream team explicitly recommends `kotlinx.serialization` with `@Serializable` and `AppUtils.parseJson<T>()` / `AppUtils.tryParseJson<T>()` / `app.get().parsedSafe<T>()`.
* **Consequence**: Jackson was historically pinned to `2.13.1` due to older Android bytecode limits, but it couples plugins to JVM/Android runtimes.
* **The Rule**: Use `kotlinx.serialization` across all plugins for clean, typed, multiplatform parsing.

---

## 11. Releasing with `status = 0` (Marked Disabled by Author)
* **Upstream Ground Truth**: In `ExtensionsScreen.kt` lines 1210-1211, CloudStream inspects:
  ```kotlin
  val isPluginDisabledByAuthor = plugin.info.status == PROVIDER_STATUS_DOWN // 0
  val isDownloadDisabled = isPluginDisabledByAuthor || plugin.source == null
  ```
* **Consequence**: Setting `status = 0` writes `"status": 0` into `plugins.json`. The CloudStream app displays the plugin with a `visibility_off_24px` icon and disables the download action so users cannot install it. Note that `status = 0` does **not** stop Gradle from compiling the module.
* **The Rule**: Keep `status = 0` while a plugin is under construction or broken. Change to `status = 1` (`Ok`) for verified production releases.

---

## 12. Premature Scaffolding Before Feasibility Probing
* **What happens**: Scaffolding full Gradle modules and Kotlin provider classes before verifying that video streams can actually be extracted over HTTP.
* **Consequence**: When the upstream source turns out to enforce unpassable dynamic bot challenges, paid locker accounts, or dead servers, all written code is wasted.
* **The Rule**: Execute Phase 1 live HTTP probing first. Verify title sanitization, image reachability, episode hierarchy, and stream extraction before scaffolding Kotlin source files.

---

## 13. Submitting to Fake Client-Side Timers
* **What happens**: Calling `Thread.sleep()` or `delay(10000)` to wait out countdown timers on link shorteners.
* **Consequence**: Frozen UI threads and ExoPlayer timeouts.
* **The Rule**: Execute the **Zero-Wait Protocol** to bypass client-side countdowns in < 500ms via pure HTTP by inspecting form POST payloads, unhiding pre-rendered DOM elements, or calling unlock endpoints directly.

---

## 14. Unsolicited Plugin Settings Bloat
* **Upstream Ground Truth**: `openSettings: ((context: Context) -> Unit)?` is an Android-specific hook defined in `Plugin.kt` that requires Android `Context` and Android UI classes.
* **Consequence**: Including settings classes or fragments couples the plugin to Android SDK runtimes, breaking pure KMP and cross-platform desktop JAR compilation (`isCrossPlatform = true`).
* **The Rule**: Keep plugins configuration-free by default. Only implement `openSettings` when the user explicitly requests plugin settings.

---

## 15. Questionnaire Harassment Before Feasibility Probing
* **What happens**: Stalling the user with intake questionnaires before testing whether the site is viable.
* **The Rule**: Probe the source first. Only ask for user input or preferences once streaming feasibility is confirmed.

---

## 16. Using `org.json.JSONObject` or Android Framework Imports in KMP Plugins
* **Upstream Ground Truth**: In `recloudstream/gradle`, task `:ensureJarCompatibility` executes:
  ```kotlin
  commandLine("jdeps", "--print-module-deps", jar.absolutePath)
  // ...
  if ("android." in output) throw GradleException(
      "The cross-platform JAR file contains Android imports! " +
      "Remove 'isCrossPlatform = true' or remove the Android imports."
  )
  ```
* **Consequence**: Importing `android.content.*`, `android.view.*`, `android.util.Base64`, or `org.json.JSONObject` causes `:ensureJarCompatibility` to fail the build immediately when `isCrossPlatform = true` is enabled.
* **The Rule**: In standard streaming plugins, use pure Kotlin Multiplatform dependencies: Kotlin stdlib, `NiceHttp`, `Jsoup`, and `kotlinx.serialization`. Never import Android framework classes in standard providers.
