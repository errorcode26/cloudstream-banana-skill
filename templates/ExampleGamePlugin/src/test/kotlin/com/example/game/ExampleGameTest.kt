package com.example.game

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Multi-Probe JVM Test Suite for CloudStream 3 Retro Game Plugins.
 *
 * Verifies catalog rows, pagination, multi-game loading, and launcher payloads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExampleGameTest {

    private lateinit var provider: ExampleGameProvider

    private val testGameUrls = listOf(
        "https://example.games/game_1",
        "https://example.games/game_2"
    )

    private val testSearchQueries = listOf("Platformer", "Action")

    @BeforeAll
    fun setup() {
        provider = ExampleGameProvider()
    }

    @Test
    fun testProviderMetadata() {
        assertEquals("Example Retro Game Collection", provider.name)
        assertTrue(provider.supportedTypes.contains(TvType.Others))
        assertTrue(provider.hasMainPage)
    }

    @Test
    fun testMainPageCatalogAndPagination() = runBlocking {
        println("[*] Testing Game Catalog & Pagination...")
        val request = MainPageRequest(
            name = "Featured Retro Games",
            data = "classics",
            horizontalScroll = true
        )

        // Page 1 Check
        val p1Response = provider.getMainPage(1, request)
        assertNotNull(p1Response)
        assertFalse(p1Response.items.isEmpty(), "Catalog Page 1 must return items")
        val p1Games = p1Response.items.first().list
        assertFalse(p1Games.isEmpty(), "Featured games list must contain items")
        println("    [+] Page 1: retrieved ${p1Games.size} games")

        // Page 2 Check (Pagination Verification)
        val p2Response = provider.getMainPage(2, request)
        assertNotNull(p2Response)
        assertFalse(p2Response.items.isEmpty(), "Catalog Page 2 must return items")
        val p2Games = p2Response.items.first().list
        println("    [+] Page 2: retrieved ${p2Games.size} games")
    }

    @Test
    fun testSearchMultipleQueries() = runBlocking {
        println("[*] Testing Multi-Query Search for Games...")
        for (query in testSearchQueries) {
            val results = provider.search(query)
            assertNotNull(results)
            assertFalse(results.isEmpty(), "Search for '$query' should return games")
            val first = results.first()
            assertEquals(TvType.Others, first.type)
            assertTrue(first.name.isNotBlank(), "Game title must not be blank")
            println("    [+] Found ${results.size} games for query '$query' (Top: '${first.name}')")
        }
    }

    @Test
    fun testMultipleGamesLoad() = runBlocking {
        println("[*] Testing Multi-Game ROM Payloads & CustomMedia Typology...")
        for (url in testGameUrls) {
            val loadResponse = provider.load(url)
            assertNotNull(loadResponse, "load() must return a valid response for $url")
            assertTrue(loadResponse.name.isNotBlank(), "Game title must not be blank")
            assertEquals(
                TvType.CustomMedia,
                loadResponse.type,
                "Game response must return TvType.CustomMedia to suppress ExoPlayer"
            )
            assertNotNull(loadResponse.posterUrl, "Game poster URL must not be null")
            println("    [+] Verified Game Title: '${loadResponse.name}' (Type: ${loadResponse.type})")
        }
    }
}
