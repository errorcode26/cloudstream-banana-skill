package com.example.game

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CompletableDeferred

class ExampleGameProvider : MainAPI() {
    override var name = "Example Retro Game Collection"
    override var supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "classics" to "Featured Retro Games"
    )

    private val games = listOf(
        GameItem(
            id = "game_1",
            title = "Example Retro Platformer",
            url = "https://example.games/game_1",
            year = 1990,
            poster = "https://raw.githubusercontent.com/google/material-design-icons/master/png/hardware/gamepad/materialicons/48dp/2x/baseline_gamepad_black_48dp.png",
            plot = "A classic 16-bit action adventure platformer running directly inside CloudStream.",
            tags = listOf("Action", "Platformer", "Retro", "16-Bit")
        )
    )

    data class GameItem(
        val id: String,
        val title: String,
        val url: String,
        val year: Int,
        val poster: String,
        val plot: String,
        val tags: List<String>
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
        val items = games.map { g ->
            newMovieSearchResponse(g.title, g.url, TvType.Others) {
                this.posterUrl = g.poster
            }
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase()
        return games.filter { g ->
            g.title.lowercase().contains(q) ||
            g.tags.any { it.lowercase().contains(q) }
        }.map { g ->
            newMovieSearchResponse(g.title, g.url, TvType.Others) {
                this.posterUrl = g.poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val game = games.find { it.url == url } ?: games.first()
        return newMovieLoadResponse(
            game.title,
            game.url,
            TvType.CustomMedia,
            game.id
        ) {
            this.posterUrl = game.poster
            this.year = game.year
            this.plot = game.plot
            this.tags = game.tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val act = CommonActivity.activity ?: return false
        val deferred = CompletableDeferred<Boolean>()

        act.runOnUiThread {
            try {
                ExampleGameLauncher.launch(act, data) {
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
