# 01. Upstream Verification & Self-Healing Guide

> **CRITICAL DIRECTIVE FOR AI AGENTS**:
> Never assume CloudStream SDK methods, models, or Gradle configurations are frozen in time. If a build task fails with an unresolved symbol, deprecated method, or configuration error, **DO NOT GUESS**. Follow this verification protocol to inspect the official upstream repositories and align your code.

---

## 1. The Official Upstream Repositories

When inspecting signatures, models, build logic, or official documentation, query these exact GitHub repositories under the `recloudstream` organization:

| Component | Upstream GitHub Repository | Role & What to Check Here |
| :--- | :--- | :--- |
| **Core CloudStream SDK** | [`recloudstream/cloudstream`](https://github.com/recloudstream/cloudstream) | Kotlin Multiplatform SDK (`library/`), models, extractors, unpackers, and Android `Plugin` lifecycle (`app/`) |
| **CloudStream Gradle Plugin** | [`recloudstream/gradle`](https://github.com/recloudstream/gradle) | `com.lagradost.cloudstream3.gradle` plugin, Dex compilation, `cloudstream { }` extension options, caching |
| **Official Extensions Template** | [`recloudstream/TestPlugins`](https://github.com/recloudstream/TestPlugins) | Clean template repository recommended by official docs for forking and creating new plugins |
| **Official Built-in Extensions** | [`recloudstream/extensions`](https://github.com/recloudstream/extensions) | Reference implementations of working providers (`DailymotionProvider`, `InvidiousProvider`, `TwitchProvider`) |
| **Central Repository Index** | [`recloudstream/cs-repos`](https://github.com/recloudstream/cs-repos) | Central list of all verified public CloudStream plugin repositories (`repos-db.json`) |
| **Live API Documentation** | [`recloudstream/dokka`](https://github.com/recloudstream/dokka) | Live Dokka documentation hosted at `https://recloudstream.github.io/dokka/` |
| **Documentation Source** | [`recloudstream/csdocs`](https://github.com/recloudstream/csdocs) | Official developer guides, scraping tutorials, and JSON repo specifications |
| **NiceHttp Client** | [`recloudstream/NiceHttp`](https://github.com/recloudstream/NiceHttp) | Upstream repository of CloudStream's HTTP networking client |

---

## 2. Upstream File Target Map (Where Exact Signatures Live)

In modern CloudStream, the core SDK is modularized under Kotlin Multiplatform (`library/src/commonMain/kotlin/...`), while Android-specific plugin hooks reside under `app/src/main/java/...`.

### 1. Provider Core & Models (`library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt`)
* **Critical Note**: Models are NOT stored in separate files. `MainAPI.kt` houses all of the following core types:
  * Abstract base class: `MainAPI`
  * Content typology enum: `TvType` (`Movie`, `TvSeries`, `Anime`, `Others`, `CustomMedia`, `Cartoon`, `Live`, `Torrent`, `Documentary`, `AsianDrama`, `AudioBook`)
    * Note on `CustomMedia`: Used for interactive media (retro games, comics, manga) to instruct CloudStream to suppress the built-in ExoPlayer and let the plugin handle interaction.
  * Result builders: `newMovieLoadResponse`, `newTvSeriesLoadResponse`, `newAnimeLoadResponse`, `newMovieSearchResponse`, `newTvSeriesSearchResponse`
  * Data structures: `LoadResponse`, `SearchResponse`, `MainPageRequest`, `HomePageResponse`, `HomePageList`, `Episode`, `SubtitleFile`, `AudioFile`
  * Status enums: `DubStatus`, `VPNStatus`, `ShowStatus`, `ProviderType`

### 2. Stream Extractor Engine (`library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt`)
* Abstract base class: `ExtractorApi`
* Stream models: `ExtractorLink`, `ExtractorLinkType`, `Qualities`
* Extractor invocation: `loadExtractor(...)`

### 3. Built-in Stream Extractors (`library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/`)
* Base implementations ready for inheritance or mirroring (`Dailymotion`, `GenericHlsExtractor`, `BaseStreamExtractor`).
* Sub-package helper utilities: `library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/helper/`
  * `AesHelper.kt`: In-memory AES CBC decryption routines.
  * `CryptoJSHelper.kt`: CryptoJS compatibility and PBKDF2/AES helpers.
  * `JWPlayerHelper.kt`: JWPlayer configuration unpackers.

### 4. Android Plugin Entrypoint (`app/src/main/java/com/lagradost/cloudstream3/plugins/Plugin.kt`)
* Houses `Plugin : BasePlugin()`:
  * `open fun load(context: Context)`: Main plugin lifecycle initialization.
  * `var openSettings: ((context: Context) -> Unit)?`: Native settings dialog trigger.
  * `var resources: Resources?`: Dynamic resources resolver.
  * `fun registerVideoClickAction(...)`: Video click action registry.
* Cross-platform base: `library/src/commonMain/kotlin/com/lagradost/cloudstream3/plugins/BasePlugin.kt` & `CloudstreamPlugin.kt`.

### 5. Network & WebView Resolvers (`library/src/commonMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.kt`)
* Multiplatform resolver:
  * Common interface: `library/src/commonMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.kt`
  * Android implementation: `library/src/androidMain/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.android.kt`

### 6. Built-in Unpackers & Format Utilities (`library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/`)
* `JsUnpacker.kt`: Built-in Dean Edwards `eval(function(p,a,c,k,e,d)...)` unpacker.
* `M3u8Helper.kt`: Multi-quality HLS master playlist parser and generator (`generateM3u8`).
* `SubtitleHelper.kt`: Subtitle parsing and conversion utilities.
* `Coroutines.kt`: Concurrency and coroutine dispatchers.
* `ParCollections.kt` (in parent folder): Parallel mapping utilities (`amap`, `apmap`, `amapIndexed`).

---

## 3. Direct Raw GitHub URLs (Instant Fetching Without API Tokens)

Use standard HTTP GET or curl against raw GitHub URLs to inspect files instantly without authentication or rate limits:

| File | Raw Direct URL |
| :--- | :--- |
| `MainAPI.kt` | `https://raw.githubusercontent.com/recloudstream/cloudstream/master/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt` |
| `ExtractorApi.kt` | `https://raw.githubusercontent.com/recloudstream/cloudstream/master/library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt` |
| `Plugin.kt` | `https://raw.githubusercontent.com/recloudstream/cloudstream/master/app/src/main/java/com/lagradost/cloudstream3/plugins/Plugin.kt` |
| `TestPlugins build.gradle.kts` | `https://raw.githubusercontent.com/recloudstream/TestPlugins/master/build.gradle.kts` |
| `extensions build.gradle.kts` | `https://raw.githubusercontent.com/recloudstream/extensions/master/build.gradle.kts` |
| `gradle plugin build.gradle.kts` | `https://raw.githubusercontent.com/recloudstream/gradle/master/build.gradle.kts` |
| `repos-db.json` | `https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json` |
| Live Dokka MainAPI Index | `https://recloudstream.github.io/dokka/library/com.lagradost.cloudstream3/-main-a-p-i/index.html` |

---

## 4. The 3-Step Upstream Diagnostic Workflow

If compilation fails during `./gradlew :<PluginName>:make` or `testDebugUnitTest`:

```text
Step 1: Read the Gradle Error
   |-- Check the exact compiler message (e.g. Unresolved reference: 'addDate' or Method deprecated).

Step 2: Inspect Upstream Source (With Dynamic Recursive Fallback)
   |-- First attempt fetching from the direct raw URL:
      curl -s "https://raw.githubusercontent.com/recloudstream/cloudstream/master/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt"
   |-- Dynamic Fallback (If any path 404s due to upstream refactoring):
      Execute a recursive tree search to discover where the file moved:
      
      Option A (With GitHub CLI):
      gh api "repos/recloudstream/cloudstream/git/trees/master?recursive=1" --jq '.tree[].path' | grep -i <TargetFileName>
      (On Windows PowerShell: gh api "repos/recloudstream/cloudstream/git/trees/master?recursive=1" --jq '.tree[].path' | Select-String -Pattern "<TargetFileName>")

      Option B (Plain curl, no GitHub CLI or auth required):
      curl -s "https://api.github.com/repos/recloudstream/cloudstream/git/trees/master?recursive=1" | jq -r '.tree[].path' | grep -i <TargetFileName>
      (On Windows PowerShell without jq: (curl.exe -s "https://api.github.com/repos/recloudstream/cloudstream/git/trees/master?recursive=1" | ConvertFrom-Json).tree.path | Select-String -Pattern "<TargetFileName>")

Step 3: Self-Heal & Propagate
   |-- Adapt your plugin code to match the verified upstream signature.
   |-- Update the template in templates/ExamplePlugin/ so future plugins inherit the fix.
```

---

## 5. Key Upstream Version Rules

* **Serialization Standard**: Use `kotlinx.serialization` across all plugins for pure KMP compatibility. (Jackson was historically pinned to `2.13.1` due to older Android limits, but modern upstream plugins use Kotlin Serialization).
* **Bytecode Target**: Plugins must target Java 1.8 bytecode (`jvmTarget.set(JvmTarget.JVM_1_8) // Required`) as enforced by upstream `recloudstream/TestPlugins` and `recloudstream/extensions`.
* **Gradle Toolchain**: Pre-release builds use JDK 17 for the build environment.

---

## 6. Continuous Real-Time Upstream Extractor Synchronization

CloudStream extensions rely heavily on video file host extractors (`ExtractorApi`). The upstream team and community contributors frequently add new extractors and update domains in `library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/`.

### 6.1 The Upstream Extractor Synchronizer (`scripts/sync_upstream_extractors.py`)
During plugin creation, the agent automatically synchronizes with the latest upstream repository:

```bash
# Pull latest changes and rebuild index of 300+ native extractors:
python scripts/sync_upstream_extractors.py --sync
```

This clones or updates `scratch/cloudstream` (using `--depth 1`) and indexes all native extractors, their class hierarchies, names, domains, and source file locations into `scratch/native_extractors.json`.

> **WHY UPSTREAM PULLING PREVENTS STALE SKILLS**:
> Generic scraping rules degrade quickly as target sites and SDK APIs change. By pulling and inspecting the real `recloudstream/cloudstream` codebase, the agent builds plugins against the actual, living Android SDK rather than outdated documentation, guaranteeing accurate method signatures and access to the latest native extractors.

### 6.2 Instant URL Cross-Checking
Whenever a video embed or host URL is discovered during Phase 1 scraping, the agent immediately checks if an upstream extractor already exists:

```bash
python scripts/sync_upstream_extractors.py --check https://streamwish.to/e/abcd1234
```

* **Exact Match**: CloudStream already supports this host. Do not write custom extraction code. Pass the link to `loadExtractor(...)`.
* **Mirror Match**: Same player architecture on a new mirror domain (e.g. `mwish.pro`). Re-alias by subclassing the native extractor.
* **No Match / Broken**: Host uses unique logic or native extractor is outdated. Build a custom extractor in `<PluginName>Extractor.kt` (`registerExtractorAPI(...)`).

---

### 6.3 On-Demand Open-Source Extension Inspection (When Novel Architectures Arise)
When facing an unfamiliar player architecture, dynamic stream encryption, or major CloudStream SDK refactorings:
* **Community Repositories**: Proactively inspect working implementations across verified open-source community repositories (such as [`recloudstream/extensions`](https://github.com/recloudstream/extensions), [`recloudstream/TestPlugins`](https://github.com/recloudstream/TestPlugins), or curated community extension indexes).
* **Official Core Checks**: Inspect [`recloudstream/cloudstream`](https://github.com/recloudstream/cloudstream) commit history and issues if core SDK models or network behaviors change.
* **Strict On-Demand Trigger**: This is executed **only when required** (e.g., compile errors or unsolved player schemes) to ensure normal plugin generation remains fast, lean, and non-blocking.

---

## 7. Modern Kotlin Multiplatform (KMP) & Cross-Platform Support

Modern CloudStream 3 / 4 is built on pure Kotlin Multiplatform. The upstream team strongly recommends that all streaming plugins target pure KMP so that desktop (JVM) and mobile platforms run identical binaries without Android fragmentation.

### 7.1 The `isCrossPlatform = true` Flag
In `build.gradle.kts`:
```kotlin
cloudstream {
    // Enable cross-platform compilation to produce both .cs3 (Android Dex) and .jar (JVM/Desktop)
    isCrossPlatform = true
    requiresResources = false
}
```

### 7.2 The `:ensureJarCompatibility` Verification Task
When `isCrossPlatform = true` is enabled, Gradle registers and runs `EnsureJarCompatibilityTask` immediately after `:compilePluginJar`.
* It runs `jdeps --print-module-deps <Plugin>.jar` and checks for any `android.*` framework references.
* If any Android SDK imports (`android.content.*`, `android.view.*`, `android.util.*`) leak into standard streaming code, `:ensureJarCompatibility` will immediately fail the build:
  ```text
  The cross-platform JAR file contains Android imports! This will cause compatibility issues.
  Remove 'isCrossPlatform = true' or remove the Android imports.
  ```
* Standard streaming plugins MUST use pure KMP dependencies (Kotlin standard library, `NiceHttp`, `Jsoup`, `kotlinx.serialization`).

---

## 8. Official Production Index Generation (`./gradlew makePluginsJson`)

Upstream CloudStream requires a repository index (`plugins.json`) containing cryptographic checksums, file sizes, and binary URLs for each plugin.

### 8.1 Never Write `plugins.json` or Hashes Manually
Manually crafting `plugins.json` is strictly forbidden by upstream developers:
* Manual SHA-256 calculation frequently causes `fileHash` mismatches, leading CloudStream clients to reject downloads with "Checksum verification failed".
* Cross-platform plugins require `jarFileSize`, `jarUrl`, and `jarHash` in addition to `.cs3` entries.

### 8.2 The Official Gradle Automation
Always use the official Gradle task provided by `recloudstream/gradle`:
```bash
./gradlew makePluginsJson
```
This task:
1. Compiles the Android Dex `.cs3` package via `:make`.
2. Compiles the cross-platform JVM `.jar` via `:compilePluginJar` (when `isCrossPlatform = true`).
3. Runs `:ensureJarCompatibility` to verify no platform-specific leaks.
4. Executes `:writeCacheEntry` to compute true SHA-256 digests (`"sha256-" + hex`) and exact byte lengths.
5. Executes `:makePluginsJson` to aggregate all subproject metadata into `build/plugins.json`.
```json
[
  {
    "url": "https://raw.githubusercontent.com/user/repo/builds/ExamplePlugin.cs3",
    "status": 1,
    "version": 1,
    "name": "ExamplePlugin",
    "internalName": "ExamplePlugin",
    "authors": ["PluginAuthor"],
    "fileSize": 14523,
    "fileHash": "sha256-a1b2c3d4...",
    "jarFileSize": 18230,
    "jarUrl": "https://raw.githubusercontent.com/user/repo/builds/ExamplePlugin.jar",
    "jarHash": "sha256-e5f6g7h8..."
  }
]
```
