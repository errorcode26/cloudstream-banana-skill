package com.example.manga

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CompletableDeferred
import org.jsoup.nodes.Document

class ExampleMangaProvider : MainAPI() {
    override var name = "Example Manga & Comic Reader"
    override var mainUrl = "https://example.manga"
    override var supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/latest" to "Latest Chapters",
        "$mainUrl/popular" to "Popular Manga"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = listOf(
            newMovieSearchResponse(
                name = "Example Manga Series",
                url = "$mainUrl/manga/example-series",
                type = TvType.Others
            ) {
                this.posterUrl = "https://raw.githubusercontent.com/google/material-design-icons/master/png/action/book/materialicons/48dp/2x/baseline_book_black_48dp.png"
            }
        )
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return listOf(
            newMovieSearchResponse(
                name = "Example Manga Series",
                url = "$mainUrl/manga/example-series",
                type = TvType.Others
            ) {
                this.posterUrl = "https://raw.githubusercontent.com/google/material-design-icons/master/png/action/book/materialicons/48dp/2x/baseline_book_black_48dp.png"
            }
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val chapters = listOf(
            newEpisode("$url/chapter-1") {
                this.name = "Chapter 1: The Beginning"
                this.episode = 1
            },
            newEpisode("$url/chapter-2") {
                this.name = "Chapter 2: The Adventure Continues"
                this.episode = 2
            }
        )

        return newTvSeriesLoadResponse(
            name = "Example Manga Series",
            url = url,
            type = TvType.CustomMedia,
            episodes = chapters
        ) {
            this.posterUrl = "https://raw.githubusercontent.com/google/material-design-icons/master/png/action/book/materialicons/48dp/2x/baseline_book_black_48dp.png"
            this.plot = "A manga series reader running seamlessly inside CloudStream."
            this.tags = listOf("Manga", "Action", "Adventure")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val act = CommonActivity.activity ?: return false

        // In a live provider, scrape image URLs from the chapter page:
        // val doc = app.get(data).document
        // val pages = doc.select(".reader-area img").mapNotNull { it.attr("src") }
        val samplePages = listOf(
            "https://raw.githubusercontent.com/google/material-design-icons/master/png/action/book/materialicons/48dp/2x/baseline_book_black_48dp.png"
        )

        val deferred = CompletableDeferred<Boolean>()

        act.runOnUiThread {
            try {
                ExampleMangaReaderDialog.launch(
                    activity = act,
                    title = "Reading Chapter",
                    pages = samplePages
                ) {
                    if (!deferred.isCompleted) {
                        deferred.complete(true)
                    }
                }
            } catch (t: Throwable) {
                if (!deferred.isCompleted) {
                    deferred.complete(false)
                }
            }
        }

        return deferred.await()
    }
}
