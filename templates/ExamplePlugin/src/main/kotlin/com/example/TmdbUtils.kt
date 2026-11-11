package com.example

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import io.ktor.http.encodeURLParameter
import org.json.JSONObject

/**
 * Universal Dual-Engine Metadata Client (Cinemeta + TMDB).
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

    data class CinemetaSearchItem(
        val id: String, // e.g. "tt0499549"
        val name: String,
        val year: Int?,
        val poster: String?,
        val description: String?,
        val genres: List<String>?
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
        val cinemetaNode: JsonNode? = null
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
            val text = app.get(url, timeout = 5).text
            val json = JSONObject(text)
            val metas = json.optJSONArray("metas") ?: return null
            if (metas.length() == 0) return null

            val first = metas.getJSONObject(0)
            val imdbId = first.optString("id")
            if (imdbId.isBlank()) return null

            val year = first.optString("year").takeIf { it.isNotBlank() }?.toIntOrNull()
            val poster = first.optString("poster").takeIf { it.isNotBlank() }
            val desc = first.optString("description").takeIf { it.isNotBlank() }
            val genres = mutableListOf<String>()
            val genresArr = first.optJSONArray("genres")
            if (genresArr != null) {
                for (i in 0 until genresArr.length()) {
                    genres.add(genresArr.getString(i))
                }
            }

            CinemetaSearchItem(
                id = imdbId,
                name = first.optString("name", title),
                year = year,
                poster = poster,
                description = desc,
                genres = if (genres.isNotEmpty()) genres else null
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches high-resolution metadata, episode stills, and cast from Cinemeta by IMDb ID.
     * Zero API keys required.
     */
    suspend fun fetchCinemetaMeta(imdbId: String?, isTvSeries: Boolean): JsonNode? {
        if (imdbId.isNullOrBlank()) return null
        val type = if (isTvSeries) "series" else "movie"
        val url = "$CINEMETA_API/meta/$type/$imdbId.json"

        return try {
            val text = app.get(url, timeout = 5).text
            mapper.readTree(text)["meta"]
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
            val resp = app.get(url, timeout = 5).text
            val json = JSONObject(resp)
            val results = json.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            results.getJSONObject(0).optInt("id", -1).takeIf { it != -1 }
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
            val resp = app.get(url, timeout = 5).text
            val json = JSONObject(resp)
            val movieResults = json.optJSONArray("movie_results")
            if (movieResults != null && movieResults.length() > 0) {
                return movieResults.getJSONObject(0).optInt("id", -1).takeIf { it != -1 }
            }
            val tvResults = json.optJSONArray("tv_results")
            if (tvResults != null && tvResults.length() > 0) {
                return tvResults.getJSONObject(0).optInt("id", -1).takeIf { it != -1 }
            }
            null
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
            val resp = app.get(url, timeout = 5).text
            JSONObject(resp).optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }
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
            val resp = app.get(url, timeout = 5).text
            val logos = JSONObject(resp).optJSONArray("logos") ?: return null
            if (logos.length() == 0) return null

            var selectedPath: String? = null
            for (i in 0 until logos.length()) {
                val logo = logos.getJSONObject(i)
                val path = logo.optString("file_path")
                val lang = logo.optString("iso_639_1").lowercase()
                if (path.isNotBlank() && (lang == langCode || selectedPath == null)) {
                    selectedPath = path
                    if (lang == langCode) break
                }
            }

            selectedPath?.let { "https://image.tmdb.org/t/p/w500$it" }
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
        val cinemetaNode = if (!resolvedImdbId.isNullOrBlank()) {
            fetchCinemetaMeta(resolvedImdbId, isTvSeries)
        } else null

        // 4. Fetch ClearLogo from TMDB (only if API key is provided)
        val logoUrl = if (!apiKey.isNullOrBlank()) {
            fetchLogoUrl(resolvedTmdbId, isTvSeries, apiKey = apiKey)
        } else null

        val poster = cinemetaNode?.get("poster")?.asText() ?: cinemetaSearch?.poster
        val background = cinemetaNode?.get("background")?.asText()
        val plot = cinemetaNode?.get("overview")?.asText() ?: cinemetaSearch?.description
        val genres = cinemetaNode?.get("genres")?.map { it.asText() } ?: cinemetaSearch?.genres

        return UniversalMetadata(
            tmdbId = resolvedTmdbId,
            imdbId = resolvedImdbId,
            title = cinemetaSearch?.name ?: CommonUtils.cleanTitle(rawTitle, keepSeason = false, keepYear = false),
            year = cinemetaSearch?.year ?: year,
            posterUrl = poster,
            backgroundUrl = background,
            logoUrl = logoUrl,
            plot = plot,
            tags = genres,
            cinemetaNode = cinemetaNode
        )
    }
}
