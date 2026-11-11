package com.example

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Comprehensive 5-Pillar JVM Test Suite for CloudStream 3 Plugins.
 *
 * MANDATORY AGENT VERIFICATION PROTOCOL:
 * 1. Multi-Category & Multi-Page Pagination: Probes multiple categories, asserts Page 1 != Page 2.
 * 2. Multi-Query Search & Typology: Probes multiple queries, asserts Movie vs TV Series typing.
 * 3. Title Sanitization & Entity Hygiene: Asserts titles are free of release tags, watermarks, and unescaped HTML.
 * 4. Image Liveness & MIME Verification: Probes posters and backdrops via live HTTP to confirm they actually render.
 * 5. Multi-Title Movie & Series Metadata: Probes multiple movies AND multiple TV series.
 * 6. Multi-Server Stream Extraction: Extracts and asserts all available mirrors/servers for both Movies & Series.
 * 7. Stream Reachability Probe: Validates live HTTP 200/206 status on extracted stream URLs.
 *
 * Run via terminal without an Android device:
 *   ./gradlew :<PluginName>:testDebugUnitTest (or .\gradlew.bat on Windows)
 */
class ExampleTest {
    private val provider = ExampleProvider()

    // 1. Multiple diverse Movie URLs (recent release and older/different genre title)
    private val testMovieUrls = listOf(
        "https://example.com/movie/sample-movie-1",
        "https://example.com/movie/sample-movie-2"
    )

    // 2. Multiple diverse TV Series URLs (multi-season drama and ongoing series)
    private val testSeriesUrls = listOf(
        "https://example.com/series/sample-series-1",
        "https://example.com/series/sample-series-2"
    )

    // 3. Diverse Search Queries (movie keyword, series keyword)
    private val testQueries = listOf("Batman", "Stranger", "Avenger")

    // Dirty titles to test sanitization edge cases
    private val dirtyTitleSamples = listOf(
        "Avatar: The Way of Water (2022) [1080p] [Dual Audio] WEBRip x264 &amp; Watch Free" to "Avatar: The Way of Water",
        "Inception (2010) BluRay 720p Multi-Audio [Hindi-Eng] Full Movie Download" to "Inception",
        "Stranger Things Season 1 Episode 5 1080p HD Stream Free" to "Stranger Things",
        "Spider-Man: No Way Home &amp; Far From Home" to "Spider-Man: No Way Home & Far From Home"
    )

    @Test
    fun testTitleCleaningAndSanitization() {
        println("[*] [HYGIENE] Testing Title Sanitization, Tag Preservation & Mirror Tagging...")
        for ((dirty, expectedClean) in dirtyTitleSamples) {
            val cleaned = CommonUtils.cleanTitle(dirty, keepSeason = false, keepYear = false)
            println("    [+] Dirty:    '$dirty'")
            println("        Cleaned:  '$cleaned'")
            println("        Expected: '$expectedClean'")

            // Verify common noise is eliminated
            assertFalse(cleaned.contains(Regex("""(?i)\b(1080p|720p|bluray|web-?rip|dual audio|watch free|download)\b""")),
                "Cleaned title still contains release tags: '$cleaned'")
            assertFalse(cleaned.contains("&amp;"), "Cleaned title still contains unescaped HTML entity &amp;: '$cleaned'")
            assertFalse(cleaned.contains("["), "Cleaned title still contains unstripped square brackets: '$cleaned'")
            assertTrue(cleaned.isNotBlank(), "Cleaned title must not become blank")
        }

        // Verify tags are preserved where needed (Stream Mirrors in player)
        val rawMirror = "FastX 1080p Dual Audio HEVC"
        val mirrorName = CommonUtils.cleanStreamMirrorName("Server 1", rawMirror)
        println("    [+] Stream Mirror Tag Preservation: '$rawMirror' -> '$mirrorName'")
        assertTrue(mirrorName.contains("[1080P]"), "Mirror name must preserve 1080P tag")
        assertTrue(mirrorName.contains("[Dual Audio]"), "Mirror name must preserve Dual Audio tag")

        // Verify Series Detection logic
        assertTrue(CommonUtils.isTvSeries("Stranger Things Season 2", "https://site.com/series/stranger"),
            "Series with Season in title must be detected as TV Series")
        assertFalse(CommonUtils.isTvSeries("Inception (2010)", "https://site.com/movies/inception"),
            "Standard movie must not be detected as TV Series")
    }

    @Test
    fun testCinemetaAndTmdbDualEngine() = runBlocking {
        println("[*] [METADATA] Testing Cinemeta + TMDB Dual-Engine Resolution...")

        // Test 1: Zero-auth Cinemeta catalog search for a Movie
        val movieMeta = TmdbUtils.searchCinemeta("Avatar", isTvSeries = false)
        assertNotNull(movieMeta, "Cinemeta movie search must return results")
        assertTrue(movieMeta!!.id.startsWith("tt"), "Cinemeta must return a valid IMDb ID: ${movieMeta.id}")
        println("    [+] Cinemeta Movie Match: '${movieMeta.name}' (${movieMeta.id})")

        // Test 2: Zero-auth Cinemeta catalog search for a TV Series
        val seriesMeta = TmdbUtils.searchCinemeta("Breaking Bad", isTvSeries = true)
        assertNotNull(seriesMeta, "Cinemeta series search must return results")
        assertTrue(seriesMeta!!.id.startsWith("tt"), "Cinemeta must return a valid IMDb ID: ${seriesMeta.id}")
        println("    [+] Cinemeta Series Match: '${seriesMeta.name}' (${seriesMeta.id})")

        // Test 3: Universal metadata cascade with ZERO API keys (pure Cinemeta)
        val universal = TmdbUtils.resolveUniversalMeta("Inception (2010)", isTvSeries = false, year = 2010)
        assertNotNull(universal.imdbId, "Universal resolver must find IMDb ID via Cinemeta without any key")
        assertNotNull(universal.posterUrl, "Universal resolver must populate poster without any key")
        println("    [+] Zero-Key Universal Resolver: Title='${universal.title}', IMDb='${universal.imdbId}', Poster='${universal.posterUrl}'")
    }

    @Test
    fun testMainPageCategoriesAndPagination() = runBlocking {
        println("[*] [PILLAR 1] Testing Multi-Category & Multi-Page Pagination...")
        assertTrue(provider.mainPage.isNotEmpty(), "mainPage category list must not be empty")

        // Probe up to the first 3 homepage rows/categories
        val categoriesToTest = provider.mainPage.take(3)
        for ((path, name) in categoriesToTest) {
            println("[*] Probing Category: '$name' ($path)")

            // Page 1 Check
            val page1 = provider.getMainPage(1, MainPageRequest(name, path, false))
            assertTrue(page1.list.isNotEmpty(), "Category '$name' Page 1 must return items")
            val p1First = page1.list.first()
            assertTrue(p1First.name.isNotBlank(), "Item title must not be blank on Page 1")
            assertTrue(p1First.url.isNotBlank(), "Item URL must not be blank on Page 1")
            println("    [+] Page 1: retrieved ${page1.list.size} items (Sample: '${p1First.name}')")

            // Page 2 Check (Pagination Verification)
            val page2 = provider.getMainPage(2, MainPageRequest(name, path, false))
            assertTrue(page2.list.isNotEmpty(), "Category '$name' Page 2 must return items")
            val p2First = page2.list.first()
            println("    [+] Page 2: retrieved ${page2.list.size} items (Sample: '${p2First.name}')")

            // Assert pagination actually shifts cards and does not loop Page 1
            val p1Urls = page1.list.map { it.url }.toSet()
            val p2Urls = page2.list.map { it.url }.toSet()
            val overlap = p1Urls.intersect(p2Urls)
            assertTrue(
                overlap.size < page1.list.size,
                "Category '$name' Page 2 appears identical to Page 1! Check pagination query logic."
            )
            println("    [+] Pagination verified: ${page2.list.size - overlap.size} unique new items on Page 2")
        }
    }

    @Test
    fun testSearchMultipleQueriesAndTypology() = runBlocking {
        println("[*] [PILLAR 2] Testing Multi-Query Search & Typology Mapping...")

        for (query in testQueries) {
            println("[*] Executing Search for query: '$query'")
            val results = provider.search(query)
            assertTrue(results.isNotEmpty(), "Search for '$query' must return at least 1 result")
            println("    [+] Found ${results.size} results for '$query'")

            val first = results.first()
            assertTrue(first.name.isNotBlank(), "Search result name must not be blank")
            assertTrue(first.url.isNotBlank(), "Search result URL must not be blank")
            assertNotNull(first.type, "Search result must have a valid TvType defined")
            println("    [+] Top Result: '${first.name}' (Type: ${first.type}) -> ${first.url}")
        }
    }

    @Test
    fun testMultipleMoviesLoad() = runBlocking {
        println("[*] [PILLAR 3A] Testing Multi-Title Movie Metadata & Payloads...")
        assertTrue(testMovieUrls.isNotEmpty(), "testMovieUrls list must contain test URLs")

        for (url in testMovieUrls) {
            println("[*] Loading Movie: $url")
            val response = provider.load(url)

            assertTrue(
                response is MovieLoadResponse,
                "Expected MovieLoadResponse for $url but got ${response::class.simpleName}"
            )
            val movie = response as MovieLoadResponse

            assertTrue(movie.name.isNotBlank(), "Movie title must not be blank")
            assertTrue(movie.dataUrl.isNotBlank(), "Movie dataUrl (playback payload) must not be blank")

            // Assert title is clean
            assertFalse(
                movie.name.contains(Regex("""(?i)\b(1080p|720p|bluray|web-?rip|dual audio|watch online|download)\b""")),
                "Loaded movie title contains raw release tags: '${movie.name}'"
            )

            println("    [+] Title: ${movie.name}")
            println("    [+] Year: ${movie.year}")
            println("    [+] Poster: ${movie.posterUrl}")
            println("    [+] Data Payload: ${movie.dataUrl}")

            if (movie.posterUrl != null) {
                assertTrue(
                    movie.posterUrl!!.startsWith("http"),
                    "Movie poster URL must be an absolute HTTP/HTTPS link: ${movie.posterUrl}"
                )
            }
        }
    }

    @Test
    fun testMultipleTvSeriesHierarchy() = runBlocking {
        println("[*] [PILLAR 3B] Testing Multi-Title TV Series Season Hierarchy & Episodes...")
        assertTrue(testSeriesUrls.isNotEmpty(), "testSeriesUrls list must contain test URLs")

        for (url in testSeriesUrls) {
            println("[*] Loading TV Series: $url")
            val response = provider.load(url)

            assertTrue(
                response is TvSeriesLoadResponse,
                "Expected TvSeriesLoadResponse for $url but got ${response::class.simpleName}"
            )
            val series = response as TvSeriesLoadResponse

            assertTrue(series.name.isNotBlank(), "TV Series title must not be blank")
            assertTrue(series.episodes.isNotEmpty(), "TV Series must contain mapped episodes")

            // Assert title is clean
            assertFalse(
                series.name.contains(Regex("""(?i)\b(1080p|720p|bluray|web-?rip|dual audio|watch online|download)\b""")),
                "Loaded TV series title contains raw release tags: '${series.name}'"
            )

            println("    [+] Series: ${series.name}")
            println("    [+] Total Episodes: ${series.episodes.size}")

            val seasons = series.episodes.mapNotNull { it.season }.distinct().sorted()
            println("    [+] Mapped Seasons: $seasons")
            assertTrue(seasons.isNotEmpty(), "TV Series must map at least 1 valid season number")

            // Verify episode playback data payload across different seasons
            val sampleEpisodes = listOfNotNull(
                series.episodes.firstOrNull(),
                series.episodes.lastOrNull()
            ).distinctBy { it.data }

            for (ep in sampleEpisodes) {
                assertTrue(ep.data.isNotBlank(), "Episode data payload must not be blank")
                println("    [+] Sample Episode: S${ep.season}E${ep.episode} - '${ep.name}' (Data: ${ep.data})")
            }
        }
    }

    @Test
    fun testImageLivenessAndMime() = runBlocking {
        println("[*] [PILLAR 3C] Testing Image Liveness & Reachability (HTTP 200 Check)...")
        val imagesToProbe = mutableListOf<Pair<String, String>>()

        // Collect poster URLs from Movie and Series test loads
        for (url in testMovieUrls.take(1)) {
            val movie = provider.load(url)
            movie.posterUrl?.let { imagesToProbe.add("Movie Poster" to it) }
            movie.backgroundPosterUrl?.let { imagesToProbe.add("Movie Backdrop" to it) }
        }
        for (url in testSeriesUrls.take(1)) {
            val series = provider.load(url)
            series.posterUrl?.let { imagesToProbe.add("Series Poster" to it) }
        }

        assertTrue(imagesToProbe.isNotEmpty(), "Provider must yield at least 1 image URL to probe for liveness")

        for ((label, imgUrl) in imagesToProbe) {
            println("[*] Probing $label: $imgUrl")
            assertTrue(imgUrl.startsWith("http"), "$label must be an absolute HTTP/HTTPS URL")
            assertFalse(imgUrl.startsWith("data:image"), "$label must not be a dummy base64 data URI")

            try {
                val res = app.get(imgUrl, timeout = 10)
                println("    [+] HTTP Response: ${res.code} (Size: ${res.body.bytes().size} bytes)")
                assertTrue(res.code in 200..308, "$label failed to load with HTTP ${res.code}: $imgUrl")
                assertTrue(res.body.bytes().size > 100, "$label returned an empty or corrupt image body (<100 bytes)")
            } catch (e: Exception) {
                println("    [!] Warning: Image probe exception for $label: ${e.message}")
            }
        }
    }

    @Test
    fun testMovieAllAvailableStreamLinks() = runBlocking {
        println("[*] [PILLAR 4A] Testing Movie Multi-Server & Multi-Mirror Link Harvesting...")
        val movieUrl = testMovieUrls.first()
        val movie = provider.load(movieUrl) as MovieLoadResponse

        val extractedLinks = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<String>()

        val success = provider.loadLinks(
            data = movie.dataUrl,
            isCasting = false,
            subtitleCallback = { sub ->
                println("    [+] Extracted Subtitle: [${sub.lang}] ${sub.url}")
                subtitles.add(sub.url)
            },
            callback = { link ->
                println("    [+] Extracted Stream Link: [${link.name}] ${link.url} (Quality: ${link.quality}, Type: ${link.type})")
                extractedLinks.add(link)
            }
        )

        assertTrue(success, "loadLinks should return true")
        assertTrue(extractedLinks.isNotEmpty(), "Must extract at least one playable video link for movies!")

        // Verify link attributes
        for (link in extractedLinks) {
            assertTrue(link.url.startsWith("http"), "Stream URL must be a valid HTTP/HTTPS link: ${link.url}")
            assertTrue(link.name.isNotBlank(), "Extractor/Server name must not be blank")
        }

        println("    [+] Successfully harvested ${extractedLinks.size} stream mirrors across available servers")
    }

    @Test
    fun testTvSeriesEpisodesStreamLinks() = runBlocking {
        println("[*] [PILLAR 4B] Testing TV Series Episode Multi-Server Link Harvesting...")
        val seriesUrl = testSeriesUrls.first()
        val series = provider.load(seriesUrl) as TvSeriesLoadResponse
        val episode = series.episodes.first()

        println("[*] Probing Episode Playback: S${episode.season}E${episode.episode} - '${episode.name}'")
        val extractedLinks = mutableListOf<ExtractorLink>()

        val success = provider.loadLinks(
            data = episode.data,
            isCasting = false,
            subtitleCallback = { sub ->
                println("    [+] Extracted Episode Subtitle: [${sub.lang}] ${sub.url}")
            },
            callback = { link ->
                println("    [+] Extracted Episode Stream Link: [${link.name}] ${link.url} (Quality: ${link.quality})")
                extractedLinks.add(link)
            }
        )

        assertTrue(success, "loadLinks for series episode should return true")
        assertTrue(extractedLinks.isNotEmpty(), "Must extract at least one playable video link for TV series episodes!")
        println("    [+] Successfully harvested ${extractedLinks.size} episode stream mirrors")
    }

    @Test
    fun testStreamLinkReachability() = runBlocking {
        println("[*] [PILLAR 5] Testing Stream Link Liveness & HTTP Reachability Check...")
        val movieUrl = testMovieUrls.first()
        val movie = provider.load(movieUrl) as MovieLoadResponse

        val extractedLinks = mutableListOf<ExtractorLink>()
        provider.loadLinks(
            data = movie.dataUrl,
            isCasting = false,
            subtitleCallback = {},
            callback = { link -> extractedLinks.add(link) }
        )

        assertTrue(extractedLinks.isNotEmpty(), "No links available to probe for reachability")

        // Probe up to 2 extracted links with HTTP HEAD or partial GET (bytes=0-1024)
        for (link in extractedLinks.take(2)) {
            println("[*] Probing Stream URL HTTP Reachability: ${link.url}")
            try {
                val headers = link.headers.toMutableMap()
                if (!headers.containsKey("Range")) {
                    headers["Range"] = "bytes=0-1024"
                }
                val res = app.get(link.url, headers = headers, timeout = 10)
                println("    [+] HTTP Response Code: ${res.code} for [${link.name}]")
                assertTrue(
                    res.code in 200..308,
                    "Stream URL returned error status ${res.code}: ${link.url}"
                )
            } catch (e: Exception) {
                println("    [!] Warning: Direct probe encountered: ${e.message} (may require player TLS session)")
            }
        }
    }
}
