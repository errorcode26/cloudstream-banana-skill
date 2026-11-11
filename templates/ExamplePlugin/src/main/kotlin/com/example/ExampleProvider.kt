package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.EmptyCoroutineContext

class ExampleProvider : MainAPI() {
    // Dynamic or static main domain
    override var mainUrl: String = runBlocking(EmptyCoroutineContext) {
        ExamplePlugin.getWorkingDomain("https://example.com")
    }

    override var name = "ExampleProvider"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Define homepage rows and categories
    override val mainPage = mainPageOf(
        "$mainUrl/trending/page/" to "Trending Now",
        "$mainUrl/movies/page/" to "Latest Movies",
        "$mainUrl/series/page/" to "TV Shows",
        "$mainUrl/anime/page/" to "Anime"
    )

    /**
     * Resilient HTML fetcher with automatic Cloudflare challenge fallback.
     */
    suspend fun fetchDocument(url: String): Document {
        val response = app.get(url)
        // If the page is intercepted by Cloudflare challenge / Turnstile gate:
        if (response.text.contains("Just a moment", ignoreCase = true) || response.code == 403 || response.code == 503) {
            return app.get(url, interceptor = CloudflareKiller()).document
        }
        return response.document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) request.data.removeSuffix("/page/") else "${request.data}$page"
        val document = fetchDocument(targetUrl)

        // Parse list items from the HTML grid
        val items = document.select(".film-poster, .movie-card, .item, article").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(
            name = request.name,
            list = items,
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keyword=$query"
        val document = fetchDocument(searchUrl)

        return document.select(".film-poster, .movie-card, .item, article").mapNotNull { element ->
            element.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDocument(url)

        val rawTitle = document.selectFirst("h1.title, .movie-title, .heading")?.text()?.trim()
            ?: throw ErrorLoadingException("Failed to find title")
        val title = CommonUtils.cleanTitle(rawTitle)

        val rawPoster = document.selectFirst(".poster img, .movie-thumb img, img.cover")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        }
        val poster = CommonUtils.cleanImageUrl(rawPoster, mainUrl)

        val rawBackground = document.selectFirst(".backdrop img, .banner img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        }
        val background = CommonUtils.cleanImageUrl(rawBackground, mainUrl)

        val plot = document.selectFirst(".description, .synopsis, .overview, .entry-content")?.text()?.trim()
        val year = CommonUtils.extractYear(rawTitle)
            ?: document.selectFirst(".year, .release-date, span[itemprop=dateCreated]")?.text()
                ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val tags = document.select(".genre a, .tags a").map { it.text().trim() }

        // Optional: Filter adult/NSFW content if native CloudStream setting has adult disabled
        val isAdult = tags.any { it.equals("Adult", true) || it.equals("Erotic", true) }
        if (isAdult && !settingsForProvider.enableAdult) {
            throw ErrorLoadingException("Content blocked by CloudStream Safe Mode.")
        }

        // Multi-factor series detection
        val episodeElements = document.select(".episodes-list a, .episode-item a, select#episode option")
        val isTvSeries = CommonUtils.isTvSeries(
            title = rawTitle,
            url = url,
            classes = document.select("article, body").attr("class"),
            hasEpisodes = episodeElements.isNotEmpty()
        )

        // Resolve canonical metadata via fast Cinemeta + TMDB cascade
        val meta = TmdbUtils.resolveUniversalMeta(rawTitle, isTvSeries, year)

        val finalPoster = meta.posterUrl ?: poster
        val finalBackdrop = meta.backgroundUrl ?: background
        val finalPlot = meta.plot ?: plot
        val finalTags = meta.tags ?: tags
        val finalYear = meta.year ?: year

        if (isTvSeries) {
            val cinemetaVideos = meta.cinemetaVideos

            val episodes = episodeElements.mapNotNull { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val epText = ep.text().trim()

                // Reject non-streamable batch/zip archives
                if (epText.contains("Batch", true) || epText.contains("Zip", true) || epHref.contains(".zip", true)) {
                    return@mapNotNull null
                }

                val (extractedSeason, extractedEpisode) = CommonUtils.extractSeasonAndEpisode(epText)
                val epNum = extractedEpisode ?: Regex("""(?i)(?:ep|episode)\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val seasonNum = extractedSeason ?: Regex("""(?i)(?:s|season)\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                val epMeta = cinemetaVideos.firstOrNull {
                    it.season == seasonNum && (it.episode == epNum || it.number == epNum)
                }
                val epName = epMeta?.name
                    ?: epMeta?.title?.takeIf { it.isNotBlank() }
                    ?: CommonUtils.cleanTitle(epText).ifBlank { "Episode $epNum" }
                val epThumb = epMeta?.thumbnail?.takeIf { it.isNotBlank() }
                val epDesc = epMeta?.overview ?: epMeta?.description
                val epAired = epMeta?.released ?: epMeta?.firstAired

                newEpisode(epHref) {
                    this.name = epName
                    this.episode = epNum
                    this.season = seasonNum
                    this.posterUrl = epThumb ?: finalPoster
                    this.description = epDesc ?: finalPlot
                    addDate(epAired)
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = finalYear
                this.tags = finalTags
                meta.logoUrl?.let { try { this.logoUrl = it } catch (_: Throwable) {} }
                addTMDbId(meta.tmdbId?.toString())
                addImdbId(meta.imdbId)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalBackdrop
            this.plot = finalPlot
            this.year = finalYear
            this.tags = finalTags
            meta.logoUrl?.let { try { this.logoUrl = it } catch (_: Throwable) {} }
            addTMDbId(meta.tmdbId?.toString())
            addImdbId(meta.imdbId)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fetchDocument(data)

        // Find all player iframes and stream server links
        val servers = mutableListOf<String>()
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src"))
            if (src != null) servers.add(src)
        }
        document.select("a[data-embed], button[data-src]").forEach { btn ->
            val embed = btn.attr("data-embed").ifBlank { btn.attr("data-src") }
            val fixed = fixUrlNull(embed)
            if (fixed != null) servers.add(fixed)
        }

        // Concurrently resolve all extractors and preserve mirror tags
        coroutineScope {
            servers.distinct().forEachIndexed { index, serverUrl ->
                launch {
                    val mirrorLabel = CommonUtils.cleanStreamMirrorName("Server ${index + 1}", serverUrl)
                    loadExtractor(serverUrl, subtitleCallback) { link ->
                        callback.invoke(
                            newExtractorLink(
                                mirrorLabel,
                                mirrorLabel,
                                link.url
                            ) {
                                this.quality = link.quality
                                this.type = link.type
                                this.headers = link.headers
                            }
                        )
                    }
                }
            }
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkTag = this.selectFirst("a[href]") ?: return null
        val rawTitle = this.selectFirst(".title, h2, h3, .movie-name")?.text()?.trim()
            ?: linkTag.attr("title").ifBlank { linkTag.text() }.trim()
        if (rawTitle.isBlank()) return null
        val title = CommonUtils.cleanTitle(rawTitle)

        val href = fixUrlNull(linkTag.attr("href")) ?: return null
        val rawPoster = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("data-original") }.ifBlank { img.attr("src") }
        }
        val poster = CommonUtils.cleanImageUrl(rawPoster, mainUrl)

        val isTv = CommonUtils.isTvSeries(
            title = rawTitle,
            url = href,
            classes = this.attr("class")
        )

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }
}
