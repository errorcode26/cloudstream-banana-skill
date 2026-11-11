package com.example

import org.jsoup.parser.Parser

object CommonUtils {

    private val QUALITY_REGEX = Regex(
        """(?i)\b(2160p|1080p|720p|480p|360p|4k|uhd|hdr|hdr10\+?|dv|dolby\s*vision|web-?dl|web-?rip|bluray|brrip|bdrip|hdtv|cam|hdcam|ts|dvd|dvdrip|remux)\b"""
    )

    private val CODEC_AUDIO_REGEX = Regex(
        """(?i)\b(x264|x265|h\.?264|h\.?265|hevc|avc|aac|ac3|dts(-hd)?|atmos|ddp?(\+)?(\s*\d\.\d)?|dual\s*audio|multi\s*audio|hindi\s*dubbed|eng\s*subs?|esubs?|multi\s*subs?)\b"""
    )

    private val SITE_WATERMARK_REGEX = Regex(
        """(?i)\b(watch\s*online(\s*free)?|full\s*movie|download(\s*free)?|stream\s*free|hd\s*movie|hindi\s*medium)\b"""
    )

    private val BRACKETED_NOISE_REGEX = Regex(
        """\[[^\]]*?(?:1080|720|480|4k|uhd|dual|dub|sub|rip|bluray|web|audio|hevc|x264|x265).*?\]""",
        RegexOption.IGNORE_CASE
    )

    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")

    private val SEASON_REGEX = Regex("""(?i)\b(?:season|s)\s*(\d+)\b""")

    private val SEASON_EP_REGEX = Regex(
        """(?i)(?:s(?:eason)?\s*(\d+))?\s*(?:e(?:pisode)?|ep|\bx)\s*(\d+)"""
    )

    private val SERIES_CLASS_REGEX = Regex(
        """(?i)\b(?:category-)?(?:tv-series|tv-shows|web-series|drama|anime-series)\b"""
    )

    private val SERIES_KEYWORD_REGEX = Regex(
        """(?i)\b(?:season|seasons|series|s\d+|episodes?|ep\d+)\b"""
    )

    /**
     * Multi-Factor Series Detector:
     * Inspects DOM element classes, title keywords, URL slugs, and child elements
     * to deterministically differentiate TV Series from Movies.
     */
    fun isTvSeries(
        title: String,
        url: String = "",
        classes: String = "",
        hasEpisodes: Boolean = false
    ): Boolean {
        if (hasEpisodes) return true
        if (classes.isNotBlank() && classes.contains(SERIES_CLASS_REGEX)) return true
        if (url.contains("/series/") || url.contains("/tv/") || url.contains("/show/")) return true
        return title.contains(SERIES_KEYWORD_REGEX)
    }

    /**
     * Production Title Sanitizer:
     * Unescapes HTML entities, strips release clutter, and selectively preserves Season/Year where needed.
     *
     * @param raw The raw scraped string from HTML.
     * @param keepSeason If true, preserves "(Season X)" for TV series cards in CloudStream UI.
     * @param keepYear If true, preserves "(YYYY)" for display titles. Set false for pure TMDB/search queries.
     */
    fun cleanTitle(
        raw: String,
        keepSeason: Boolean = true,
        keepYear: Boolean = true
    ): String {
        if (raw.isBlank()) return ""

        // 1. Unescape HTML entities (&amp; -> &, &#039; -> ', etc.)
        var title = Parser.unescapeEntities(raw, false).trim()

        // 2. Strip leading promotional prefixes
        title = title.replace(Regex("""^(?:Download|Watch)\s+""", RegexOption.IGNORE_CASE), "").trim()

        // 3. Fast-path: Check for canonical (Season X-Y) or (Year) patterns
        val canonicalMatch = Regex("""^(.*?(\((?:Season\s*[\d\-]+|\d{4})\)))""", RegexOption.IGNORE_CASE).find(title)
        if (canonicalMatch != null && keepSeason && keepYear) {
            return canonicalMatch.groupValues[1].trim()
        }

        // 4. Extract Season range/number and Year before stripping noise
        val seasonStr = Regex("""(?i)\b(?:Season|S)\s*([\d\-]+)\b""").find(title)?.groupValues?.get(1)
        val year = extractYear(title)

        // 5. Remove bracketed release clutter like [1080p] [Dual Audio] [E04 Added]
        title = title.replace(Regex("""\[(?:S\d+)?E\d+[^\]]*Added\]""", RegexOption.IGNORE_CASE), " ")
        title = BRACKETED_NOISE_REGEX.replace(title, " ")

        // 6. Strip quality, codec, audio, and promotional watermarks
        title = QUALITY_REGEX.replace(title, " ")
        title = CODEC_AUDIO_REGEX.replace(title, " ")
        title = SITE_WATERMARK_REGEX.replace(title, " ")

        // 7. Strip trailing season/episode mentions
        title = title.replace(Regex("""(?i)\b(?:season|s)\s*[\d\-]+.*$"""), " ")
        title = title.replace(Regex("""(?i)\b(?:episode|ep)\s*\d+.*$"""), " ")

        // 8. Clean trailing noise like "| HD" or "- Watch Online"
        title = title.replace(Regex("""[-|/:]\s*$"""), " ")

        // 9. Strip raw year brackets, preserving pure base name
        title = title.replace(Regex("""\(\s*(?:19|20)\d{2}\s*\)"""), " ")

        // 10. Normalize base title
        val baseTitle = title
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', ':', '/', '.', '_')

        // 11. Re-append clean Season and Year tags where requested
        val parts = mutableListOf<String>()
        if (keepSeason && seasonStr != null) {
            parts.add("Season $seasonStr")
        }
        if (keepYear && year != null) {
            parts.add(year.toString())
        }

        return if (parts.isEmpty()) {
            baseTitle
        } else {
            "$baseTitle " + parts.joinToString(" ") { "($it)" }
        }
    }

    /**
     * Extracts valid 4-digit release year (1900..2099).
     */
    fun extractYear(raw: String): Int? {
        return YEAR_REGEX.findAll(raw)
            .mapNotNull { it.value.toIntOrNull() }
            .firstOrNull { it in 1900..2099 }
    }

    /**
     * Extracts Season and Episode numbers from text or URL slug.
     * e.g. "S02E05" -> Pair(2, 5); "Episode 12" -> Pair(null, 12)
     */
    fun extractSeasonAndEpisode(raw: String): Pair<Int?, Int?> {
        val match = SEASON_EP_REGEX.find(raw) ?: return Pair(null, null)
        val season = match.groupValues.getOrNull(1)?.toIntOrNull()
        val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
        return Pair(season, episode)
    }

    /**
     * Preserves Quality & Audio Tags for Stream Mirrors:
     * While main titles should be clean, video stream mirrors (in loadLinks) NEED tags
     * so users can choose between resolutions (1080p, 720p) and audio languages.
     * e.g. cleanStreamMirrorName("Server 1", "Movie 1080p Dual Audio HEVC")
     * -> "Server 1 [1080p] [Dual Audio] [HEVC]"
     */
    fun cleanStreamMirrorName(serverName: String, rawTitle: String): String {
        val tags = mutableListOf<String>()

        val qualityMatch = QUALITY_REGEX.find(rawTitle)?.value
        if (qualityMatch != null) tags.add(qualityMatch.uppercase())

        val codecAudioMatch = CODEC_AUDIO_REGEX.findAll(rawTitle).map { it.value }.distinct()
        for (m in codecAudioMatch) {
            tags.add(m.replaceFirstChar { it.uppercase() })
        }

        return if (tags.isEmpty()) {
            serverName
        } else {
            "$serverName " + tags.joinToString(" ") { "[$it]" }
        }
    }

    /**
     * Cleans and validates image URLs:
     * - Resolves protocol-relative URLs ("//cdn...").
     * - Resolves root-relative URLs ("/uploads/...").
     * - Discards dummy base64/SVG tracking placeholders.
     */
    fun cleanImageUrl(url: String?, baseUrl: String = ""): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()

        if (trimmed.startsWith("data:image", ignoreCase = true) ||
            trimmed.contains("1x1.gif") ||
            trimmed.contains("placeholder")
        ) {
            return null
        }

        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> if (baseUrl.isNotBlank()) "${baseUrl.trimEnd('/')}$trimmed" else trimmed
            else -> if (baseUrl.isNotBlank()) "${baseUrl.trimEnd('/')}/$trimmed" else trimmed
        }
    }
}
