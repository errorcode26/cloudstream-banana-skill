# 06. Metadata Enrichment (Cinemeta + TMDB Dual-Engine)

> **CORE PRINCIPLE**:
> Many streaming websites have blurry posters, missing descriptions, or poor episode names. By mapping media to **Cinemeta** and **TMDB**, CloudStream automatically renders 4K episode stills, rich actor avatars, transparent ClearLogos, and enables native Trakt scrobbling.

---

## 1. Why the Dual-Engine Architecture Matters

| Capability | Cinemeta Engine | TMDB Engine |
| :--- | :--- | :--- |
| **Authentication** | **Zero API Keys Required** (Free, Public, Unmetered) | Requires API Key (Configurable with fallback) |
| **Title Search Speed** | Sub-200ms catalog matching | Subject to rate limits and key quotas |
| **Primary Output** | Canonical IMDb ID (`tt...`), 4K backdrops, episode stills | Transparent PNG ClearLogos, localized text |
| **Aggregator Role** | Reconciles disparate titles across 20+ sources | Provides graphic branding and cast photos |

---

## 2. Fast Zero-Auth Cinemeta Catalog Search

Cinemeta provides a public catalog search endpoint that resolves raw movie and series titles to their canonical IMDb IDs in under 200ms without needing an API key:

* **Movie Search**: `https://v3-cinemeta.strem.io/catalog/movie/top/search={query}.json`
* **Series Search**: `https://v3-cinemeta.strem.io/catalog/series/top/search={query}.json`

```kotlin
suspend fun searchCinemeta(title: String, isTvSeries: Boolean): CinemetaSearchItem? {
    val type = if (isTvSeries) "series" else "movie"
    val clean = CommonUtils.cleanTitle(title, keepSeason = false, keepYear = false)
    val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=${clean.encodeURLParameter()}.json"

    val json = JSONObject(app.get(url, timeout = 5).text)
    val first = json.optJSONArray("metas")?.optJSONObject(0) ?: return null

    return CinemetaSearchItem(
        id = first.getString("id"), // Canonical IMDb ID (e.g. "tt0499549")
        name = first.optString("name"),
        year = first.optString("year").toIntOrNull(),
        poster = first.optString("poster"),
        description = first.optString("description")
    )
}
```

---

## 3. High-Resolution Cinemeta Metadata by IMDb ID

Once an IMDb ID is known, Cinemeta returns high-resolution backdrops, localized overviews, director/cast lists, and episode thumbnails:

* **Endpoint**: `https://v3-cinemeta.strem.io/meta/{series|movie}/{imdbId}.json`

```kotlin
suspend fun fetchCinemetaMeta(imdbId: String, isTvSeries: Boolean): JsonNode? {
    val type = if (isTvSeries) "series" else "movie"
    val url = "https://v3-cinemeta.strem.io/meta/$type/$imdbId.json"
    return try {
        val json = app.get(url, timeout = 5).text
        mapper.readTree(json)["meta"]
    } catch (_: Exception) {
        null
    }
}
```

### 3.1 Mapping Cinemeta Series Episode Guide to `newEpisode`
Cinemeta series metadata includes a `videos` array containing official episode names, plot overviews, air dates, runtimes, and episode stills. Match them directly against your scraped `Pair(season, episode)` map:

```kotlin
val meta = fetchCinemetaMeta(imdbId, isTvSeries = true)
val videos = meta?.get("videos")?.toList() ?: emptyList()

for ((key, links) in episodeLinksMap) {
    val (season, epNum) = key
    val epMeta = videos.firstOrNull {
        it["season"]?.asInt() == season && (it["episode"]?.asInt() == epNum || it["number"]?.asInt() == epNum)
    }

    val epName = epMeta?.get("name")?.asText()
        ?: epMeta?.get("title")?.asText()?.takeIf { it.isNotBlank() }
        ?: "Episode $epNum"
    val epDesc = epMeta?.get("overview")?.asText()
        ?: epMeta?.get("description")?.asText()
        ?: ""
    val epThumb = epMeta?.get("thumbnail")?.asText()?.takeIf { it.isNotBlank() }
    val epAired = epMeta?.get("released")?.asText()
        ?: epMeta?.get("firstAired")?.asText()
    val runtime = epMeta?.get("runtime")?.asText()?.filter { it.isDigit() }?.toIntOrNull()

    episodes.add(
        newEpisode(links.joinToString(";")) {
            this.name = epName
            this.season = season
            this.episode = epNum
            this.posterUrl = epThumb ?: poster
            this.description = epDesc
            this.runTime = runtime
            addDate(epAired)
        }
    )
}
```

---

## 4. TMDB Transparent ClearLogo Integration

CloudStream natively renders official graphic title logos above backdrops via `logoUrl`:

```kotlin
suspend fun fetchTmdbLogo(
    tmdbId: Int?,
    isTvSeries: Boolean,
    apiKey: String? = TmdbUtils.tmdbApiKey
): String? {
    if (apiKey.isNullOrBlank() || tmdbId == null) return null
    val type = if (isTvSeries) "tv" else "movie"
    val url = "https://api.themoviedb.org/3/$type/$tmdbId/images?api_key=$apiKey"

    val resp = app.get(url, timeout = 5).text
    val logos = JSONObject(resp).optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val path = logos.getJSONObject(0).optString("file_path")
    return "https://image.tmdb.org/t/p/w500$path"
}
```

In your `newMovieLoadResponse` or `newTvSeriesLoadResponse`:
```kotlin
try { this.logoUrl = logoUrl } catch (_: Throwable) {}
```

---

## 5. Canonical Reconciliation in Mega-Aggregator Plugins

In multi-source aggregators that orchestrate 20+ scrapers (e.g. `SuperStream`, `StreamPlay`, `Ultima`):
1. **The Problem**: Each scraper uses different naming conventions:
   * Source A: `"Dune: Part Two (2024) [1080p]"`
   * Source B: `"Dune 2 2024 Dual Audio"`
   * Source C: `"Dune: Part 2 HD"`
2. **The Solution**: The aggregator queries Cinemeta / TMDB **once** using `resolveUniversalMeta()`:
   * Resolves canonical `imdbId = "tt15239678"` and `tmdbId = 693134`.
   * Standardizes the search query to canonical `"Dune: Part Two"`.
   * All 20+ downstream scrapers receive the verified canonical title, eliminating title-mismatch failures.

---

## 6. Native Trakt Scrobbling & Watch Progress

For CloudStream to sync playback progress with Trakt, you **must inject either an IMDb ID or a TMDB ID** into the `LoadResponse`:

```kotlin
return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
    this.posterUrl = meta.posterUrl ?: poster
    this.backgroundPosterUrl = meta.backgroundUrl ?: backdrop
    this.plot = meta.plot ?: description
    this.year = meta.year ?: releaseYear

    // Critical for Trakt & Stremio scrobbling:
    addImdbId(meta.imdbId)      // e.g. "tt15239678"
    addTMDbId(meta.tmdbId?.toString()) // e.g. "693134"
}
```
*Never pass null or blank strings to `addImdbId` or `addTMDbId`.*
