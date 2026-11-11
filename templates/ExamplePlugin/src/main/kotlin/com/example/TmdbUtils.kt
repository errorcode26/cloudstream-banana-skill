package com.example

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Universal Dual-Engine Metadata Client (Cinemeta + TMDB).
 * Fully built with kotlinx.serialization and pure Kotlin Multiplatform (KMP).
 *
 * ARCHITECTURAL ADVANTAGES:
 * 1. Cinemeta Engine (Free, Zero API Keys, Public):
 *    - Instant title-to-IMDb resolution via catalog search (/catalog/{type}/top/search={query}.json).
 *    - Full high-res metadata, backdrops, cast, and episode stills by IMDb ID (/meta/{type}/{imdbId}.json).
 *    - Works out of the box with zero configuration or keys.
 * 2. TMDB Engine (Optional User API Key):
 *    - Official graphic transparent title logos (ClearLogo) via /images.
 *    - Canonical TMDB and IMDb ID cross-referencing.
 *    - Only queried when the developer or user provides their personal API key.
 */
object TmdbUtils {
    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val CINEMETA_API = "https://v3-cinemeta.strem.io"

    // Optional user/developer TMDB API key.
    // If null or blank, the plugin operates purely on Cinemeta without needing any API key.
    var tmdbApiKey: String? = null

    fun hasTmdbKey(): Boolean = !tmdbApiKey.isNullOrBlank()

    @Serializable
    data class CinemetaSearchResponse(
        val metas: List<CinemetaSearchItem>? = null
    )

    @Serializable
    data class CinemetaSearchItem(
        val id: String, // e.g. "tt0499549"
        val name: String? = null,
        val year: String? = null,
        val poster: String? = null,
        val description: String? = null,
        val genres: List<String>? = null
    ) {
        val parsedYear: Int?
            get() = year?.take(4)?.toIntOrNull()
    }

    @Serializable
    data class CinemetaMetaResponse(
        val meta: CinemetaMetaItem? = null
    )

    @Serializable
    data class CinemetaMetaItem(
        val id: String? = null,
        val name: String? = null,
        val type: String? = null,
        val poster: String? = null,
        val background: String? = null,
        val logo: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val releaseInfo: String? = null,
        val videos: List<CinemetaVideo>? = null
    )

    @Serializable
    data class CinemetaVideo(
        val id: String? = null,
        val name: String? = null,
        val title: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val number: Int? = null,
        val thumbnail: String? = null,
        val overview: String? = null,
        val description: String? = null,
        val released: String? = null,
        val firstAired: String? = null
    )

    @Serializable
    data class TmdbSearchResponse(
        val results: List<TmdbSearchResult>? = null
    )

    @Serializable
    data class TmdbSearchResult(
        val id: Int? = null
    )

    @Serializable
    data class TmdbFindResponse(
        @SerialName("movie_results") val movieResults: List<TmdbSearchResult>? = null,
        @SerialName("tv_results") val tvResults: List<TmdbSearchResult>? = null
    )

    @Serializable
    data class TmdbExternalIdsResponse(
        @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
        @SerialName("imdb_id") val imdbId: String? = null
    )

    @Serializable
    data class TmdbExternalIds(
        @SerialName("imdb_id") val imdbId: String? = null
    )

    @Serializable
    data class TmdbImagesResponse(
        val logos: List<TmdbLogo>? = null
    )

    @Serializable
    data class TmdbLogo(
        @SerialName("file_path") val filePath: String? = null,
        @SerialName("iso_639_1") val iso6391: String? = null
    )

    data class UniversalMetadata(
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val backgroundUrl: String? = null,
        val logoUrl: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val cinemetaVideos: List<CinemetaVideo> = emptyList()
    )

    /**
     * Fast Zero-Auth Cinemeta Catalog Search:
     * Searches movies or TV series without needing any API key, returning matching items with IMDb IDs.
     */
    suspend fun searchCinemeta(title: String, isTvSeries: Boolean): CinemetaSearchItem? {
        val type = if (isTvSeries) "series" else "movie"
        val clean = CommonUtils.cleanTitle(title, keepSeason = false, keepYear = false)
        val encoded = clean.encodeURLParameter()
        val url = "$CINEMETA_API/catalog/$type/top/search=$encoded.json"

        return try {
            val resp = app.get(url, timeout = 5).parsedSafe<CinemetaSearchResponse>()
                ?: tryParseJson<CinemetaSearchResponse>(app.get(url, timeout = 5).text)
            resp?.metas?.firstOrNull { it.id.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches high-resolution metadata, episode stills, and cast from Cinemeta by IMDb ID.
     * Zero API keys required.
     */
    suspend fun fetchCinemetaMeta(imdbId: String?, isTvSeries: Boolean): CinemetaMetaItem? {
        if (imdbId.isNullOrBlank()) return null
        val type = if (isTvSeries) "series" else "movie"
        val url = "$CINEMETA_API/meta/$type/$imdbId.json"

        return try {
            app.get(url, timeout = 5).parsedSafe<CinemetaMetaResponse>()?.meta
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves TMDB Movie or TV Series ID by title and optional release year.
     * Requires a valid TMDB API key; returns null if no key is configured.
     */
    suspend fun findTmdbId(
        title: String,
        isTvSeries: Boolean = false,
        year: Int? = null,
        apiKey: String? = tmdbApiKey
    ): Int? {
        if (apiKey.isNullOrBlank()) return null
        val endpoint = if (isTvSeries) "search/tv" else "search/movie"
        val clean = CommonUtils.cleanTitle(title, keepSeason = false, keepYear = false)
        val queryParam = clean.encodeURLParameter()
        val yearParam = if (year != null) {
            if (isTvSeries) "&first_air_date_year=$year" else "&year=$year"
        } else ""

        val url = "$TMDB_API/$endpoint?api_key=$apiKey&query=$queryParam$yearParam&include_adult=false"

        return try {
            app.get(url, timeout = 5).parsedSafe<TmdbSearchResponse>()?.results?.firstOrNull()?.id
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds TMDB ID from an IMDb ID via TMDB's /find endpoint.
     */
    suspend fun findTmdbIdByImdb(imdbId: String, apiKey: String? = tmdbApiKey): Int? {
        if (apiKey.isNullOrBlank() || imdbId.isBlank()) return null
        val url = "$TMDB_API/find/$imdbId?api_key=$apiKey&external_source=imdb_id"
        return try {
            val res = app.get(url, timeout = 5).parsedSafe<TmdbFindResponse>()
            res?.movieResults?.firstOrNull()?.id ?: res?.tvResults?.firstOrNull()?.id
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the IMDb ID using TMDB's external_ids endpoint.
     */
    suspend fun findImdbId(
        tmdbId: Int,
        isTvSeries: Boolean = false,
        apiKey: String? = tmdbApiKey
    ): String? {
        if (apiKey.isNullOrBlank()) return null
        val kind = if (isTvSeries) "tv" else "movie"
        val url = "$TMDB_API/$kind/$tmdbId?api_key=$apiKey&append_to_response=external_ids"

        return try {
            val res = app.get(url, timeout = 5).parsedSafe<TmdbExternalIdsResponse>()
            (res?.externalIds?.imdbId ?: res?.imdbId)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches official transparent graphic ClearLogo from TMDB.
     */
    suspend fun fetchLogoUrl(
        tmdbId: Int?,
        isTvSeries: Boolean = false,
        langCode: String = "en",
        apiKey: String? = tmdbApiKey
    ): String? {
        if (apiKey.isNullOrBlank() || tmdbId == null) return null
        val kind = if (isTvSeries) "tv" else "movie"
        val url = "$TMDB_API/$kind/$tmdbId/images?api_key=$apiKey"

        return try {
            val logos = app.get(url, timeout = 5).parsedSafe<TmdbImagesResponse>()?.logos ?: return null
            val match = logos.firstOrNull { it.iso6391?.equals(langCode, ignoreCase = true) == true && !it.filePath.isNullOrBlank() }
                ?: logos.firstOrNull { !it.filePath.isNullOrBlank() }

            match?.filePath?.let { "https://image.tmdb.org/t/p/w500$it" }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Universal Metadata Resolver (Cinemeta + TMDB Cascade):
     * - Uses Cinemeta (zero keys needed) for instant title search, IMDb ID, 4K posters/backdrops, and plot.
     * - Uses TMDB (if user key configured) for ClearLogos and canonical TMDB IDs.
     */
    suspend fun resolveUniversalMeta(
        rawTitle: String,
        isTvSeries: Boolean,
        year: Int? = null,
        knownImdbId: String? = null,
        apiKey: String? = tmdbApiKey
    ): UniversalMetadata {
        var resolvedImdbId = knownImdbId

        // 1. Fast Zero-Auth Cinemeta Search if IMDb ID is not known
        var cinemetaSearch: CinemetaSearchItem? = null
        if (resolvedImdbId.isNullOrBlank()) {
            cinemetaSearch = searchCinemeta(rawTitle, isTvSeries)
            resolvedImdbId = cinemetaSearch?.id
        }

        // 2. Resolve TMDB ID only if an API key is provided
        var resolvedTmdbId: Int? = null
        if (!apiKey.isNullOrBlank()) {
            if (!resolvedImdbId.isNullOrBlank()) {
                resolvedTmdbId = findTmdbIdByImdb(resolvedImdbId, apiKey)
            }
            if (resolvedTmdbId == null) {
                resolvedTmdbId = findTmdbId(rawTitle, isTvSeries, year, apiKey)
                if (resolvedImdbId.isNullOrBlank() && resolvedTmdbId != null) {
                    resolvedImdbId = findImdbId(resolvedTmdbId, isTvSeries, apiKey)
                }
            }
        }

        // 3. Fetch Full Cinemeta Node by IMDb ID for 4K artwork & episode stills (Zero keys needed)
        val cinemetaMeta = if (!resolvedImdbId.isNullOrBlank()) {
            fetchCinemetaMeta(resolvedImdbId, isTvSeries)
        } else null

        // 4. Fetch ClearLogo from TMDB (only if API key is provided)
        val logoUrl = if (!apiKey.isNullOrBlank()) {
            fetchLogoUrl(resolvedTmdbId, isTvSeries, apiKey = apiKey)
        } else null

        val poster = cinemetaMeta?.poster ?: cinemetaSearch?.poster
        val background = cinemetaMeta?.background
        val plot = cinemetaMeta?.description ?: cinemetaSearch?.description
        val genres = cinemetaMeta?.genres ?: cinemetaSearch?.genres

        return UniversalMetadata(
            tmdbId = resolvedTmdbId,
            imdbId = resolvedImdbId,
            title = cinemetaSearch?.name ?: CommonUtils.cleanTitle(rawTitle, keepSeason = false, keepYear = false),
            year = cinemetaSearch?.parsedYear ?: year,
            posterUrl = poster,
            backgroundUrl = background,
            logoUrl = logoUrl,
            plot = plot,
            tags = genres,
            cinemetaVideos = cinemetaMeta?.videos ?: emptyList()
        )
    }
}