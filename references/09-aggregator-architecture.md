# 09. Mega-Aggregator Architecture (Multi-Source Orchestration)

> **CORE PRINCIPLE**:
> Mega-aggregators do not scrape a single website. Instead, they act as meta-engines orchestrating **50+ sub-providers** concurrently. When a user requests a movie or episode, the aggregator queries dozens of sources simultaneously, deduplicates the links, and feeds playable streams to ExoPlayer progressively.

---

## 1. The Sub-Provider Abstraction Model

Never hardcode monolithic scraper loops. Encapsulate each sub-provider into an isolated, functional data class:

```kotlin
package com.example.aggregator

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

data class SubProvider(
    val id: String,
    val name: String,
    val priority: Int = 10, // Lower = faster / higher priority
    val isAnimeOnly: Boolean = false,
    val isMovieOnly: Boolean = false,
    val invoke: suspend (
        meta: MediaMetadata,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) -> Unit
)

data class MediaMetadata(
    val title: String,
    val year: Int?,
    val isMovie: Boolean,
    val season: Int?,
    val episode: Int?,
    val tmdbId: Int?,
    val imdbId: String?,
    val anilistId: Int? = null
)
```

---

## 2. Progressive Stream Feeding (Instant Playback)

**The #1 Mistake in Aggregators**: Waiting for all 50 providers to finish before returning links. If one provider takes 8 seconds, the user stares at a spinner for 8 seconds.

### The Progressive Streaming Pattern:
CloudStream's `callback: (ExtractorLink) -> Unit` is asynchronous and streaming. As soon as the fastest sub-provider resolves a link (e.g. in 300ms), invoke `callback(link)` immediately. ExoPlayer begins playback in the background while the slower sub-providers continue resolving in parallel!

```kotlin
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean = coroutineScope {
    val meta = parseMediaMetadata(data)
    val activeProviders = getActiveProviders(meta)

    // Bound concurrency with Semaphore based on device profile (8 on TV, 40 on Phone)
    val semaphore = Semaphore(DeviceProfile.detect(context).maxConcurrency)

    activeProviders.forEach { provider ->
        launch(Dispatchers.IO) {
            semaphore.withPermit {
                try {
                    provider.invoke(meta, subtitleCallback) { link ->
                        // Pass links directly to ExoPlayer progressively
                        callback(link)
                    }
                } catch (e: Throwable) {
                    // Critical: Fault isolation prevents one dead site from killing the aggregator
                    Log.w("Aggregator", "Sub-provider ${provider.name} failed: ${e.message}")
                }
            }
        }
    }
    true
}
```

---

## 3. Sub-Provider Fault Isolation & Circuit Breaking

With 50+ websites, at least 5 will be down, blocked by Cloudflare, or timing out at any given moment.

### The Circuit Breaker Pattern:
Track failure counts in-memory. If a sub-provider fails 3 consecutive times, disable it for 15 minutes before retrying:

```kotlin
object CircuitBreaker {
    private val failureCounts = ConcurrentHashMap<String, Int>()
    private val disabledUntil = ConcurrentHashMap<String, Long>()

    fun canExecute(providerId: String): Boolean {
        val until = disabledUntil[providerId] ?: return true
        if (System.currentTimeMillis() > until) {
            disabledUntil.remove(providerId)
            failureCounts.remove(providerId)
            return true
        }
        return false
    }

    fun recordFailure(providerId: String) {
        val count = failureCounts.compute(providerId) { _, v -> (v ?: 0) + 1 } ?: 1
        if (count >= 3) {
            disabledUntil[providerId] = System.currentTimeMillis() + (15 * 60 * 1000L) // 15 mins
        }
    }

    fun recordSuccess(providerId: String) {
        failureCounts.remove(providerId)
        disabledUntil.remove(providerId)
    }
}
```

---

## 4. Universal ID Bridging (TMDB -> IMDb -> AniList -> MAL)

Aggregator sub-providers consume different ID standards:
* Western movie providers query by **IMDb ID** (`tt1234567`) or **TMDB ID**.
* Anime providers query by **AniList ID**, **MyAnimeList (MAL) ID**, or Japanese Romanized title (`jpTitle`).

### The Universal Bridge Recipe:
Fetch canonical IDs once at the root `load()` stage using `TmdbUtils.resolveUniversalMeta(title, isTvSeries, year)` (which cascades through Cinemeta fast catalog search and TMDB):

```kotlin
val meta = TmdbUtils.resolveUniversalMeta(rawTitle, isTvSeries, year)

val payload = AggregatorPayload(
    title = meta.title ?: rawTitle,
    year = meta.year ?: year,
    imdbId = meta.imdbId,
    tmdbId = meta.tmdbId,
    anilistId = null
)
```

Pack the canonical IDs into the payload:

```kotlin
data class AggregatorPayload(
    val title: String,
    val year: Int?,
    val imdbId: String?,
    val tmdbId: Int?,
    val anilistId: Int?,
    val malId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null
)
```
*(Serialize this data class to JSON or Base64 and pass it as the `data` parameter to `newEpisode(data)`).*
