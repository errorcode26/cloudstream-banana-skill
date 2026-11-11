package com.example.manga

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Multi-Probe JVM Test Suite for CloudStream 3 Manga & Comic Reader Plugins.
 *
 * Verifies catalog rows, pagination, multi-manga series loading, and chapter extraction.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExampleMangaTest {

    private lateinit var provider: ExampleMangaProvider

    private val testMangaUrls = listOf(
        "https://example.manga/manga/example-series-1",
        "https://example.manga/manga/example-series-2"
    )

    private val testSearchQueries = listOf("Example", "Action")

    @BeforeAll
    fun setup() {
        provider = ExampleMangaProvider()
    }

    @Test
    fun testProviderMetadata() {
        assertEquals("Example Manga & Comic Reader", provider.name)
        assertTrue(provider.supportedTypes.contains(TvType.Others))
        assertTrue(provider.hasMainPage)
    }

    @Test
    fun testMainPageCatalogAndPagination() = runBlocking {
        println("[*] Testing Manga Catalog & Pagination...")
        val request = MainPageRequest(
            name = "Latest Chapters",
            data = "https://example.manga/latest",
            horizontalScroll = true
        )

        // Page 1 Check
        val p1Response = provider.getMainPage(1, request)
        assertNotNull(p1Response)
        assertFalse(p1Response.items.isEmpty(), "Catalog Page 1 must return items")
        val p1Items = p1Response.items.first().list
        assertFalse(p1Items.isEmpty(), "Latest chapters list must contain items")
        println("    [+] Page 1: retrieved ${p1Items.size} manga entries")

        // Page 2 Check (Pagination Verification)
        val p2Response = provider.getMainPage(2, request)
        assertNotNull(p2Response)
        assertFalse(p2Response.items.isEmpty(), "Catalog Page 2 must return items")
        val p2Items = p2Response.items.first().list
        println("    [+] Page 2: retrieved ${p2Items.size} manga entries")
    }

    @Test
    fun testSearchMultipleQueries() = runBlocking {
        println("[*] Testing Multi-Query Search for Manga...")
        for (query in testSearchQueries) {
            val results = provider.search(query)
            assertNotNull(results)
            assertFalse(results.isEmpty(), "Search for '$query' should return manga titles")
            val first = results.first()
            assertEquals(TvType.Others, first.type)
            assertTrue(first.name.isNotBlank(), "Manga title must not be blank")
            println("    [+] Found ${results.size} manga for query '$query' (Top: '${first.name}')")
        }
    }

    @Test
    fun testMultipleMangaLoadChapters() = runBlocking {
        println("[*] Testing Multi-Manga Chapter Lists & CustomMedia Typology...")
        for (url in testMangaUrls) {
            val loadResponse = provider.load(url)
            assertNotNull(loadResponse, "load() must return a valid response for $url")
            assertTrue(loadResponse.name.isNotBlank(), "Manga title must not be blank")
            assertEquals(
                TvType.CustomMedia,
                loadResponse.type,
                "Manga response must return TvType.CustomMedia to suppress ExoPlayer"
            )

            val seriesResponse = loadResponse as? TvSeriesLoadResponse
            assertNotNull(seriesResponse, "Manga load response must be TvSeriesLoadResponse")
            val episodes = seriesResponse?.episodes ?: emptyList()
            assertFalse(episodes.isEmpty(), "Manga should contain chapters mapped as episodes")
            assertTrue(episodes.size >= 1, "Manga must contain at least 1 chapter")
            val firstEp = episodes.first()
            assertTrue(firstEp.name?.isNotBlank() == true, "Chapter name must not be blank")
            assertTrue(firstEp.data.isNotBlank(), "Chapter data payload must not be blank")
            println("    [+] Verified Manga: '${seriesResponse?.name}' (${episodes.size} chapters, Sample: '${firstEp.name}')")
        }
    }
}
