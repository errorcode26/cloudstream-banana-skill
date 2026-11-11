package com.example

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Pattern 1: Custom Extractor built from scratch.
 * Use this when the host uses unique token exchanges, packed JavaScript, or API gateways.
 */
open class ExampleExtractor : ExtractorApi() {
    override val name = "ExampleServer"
    override val mainUrl = "https://exampleserver.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to (referer ?: mainUrl)
        )

        val response = app.get(url, headers = headers)
        val document = response.document

        // Example: Direct file / iframe extraction from DOM or regex
        val videoSource = document.selectFirst("video source")?.attr("src")
            ?: Regex("""file:\s*["']([^"']+)["']""").find(response.text)?.groupValues?.get(1)

        if (!videoSource.isNullOrBlank()) {
            val streamUrl = fixUrl(videoSource)
            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
            val isDash = streamUrl.contains(".mpd", ignoreCase = true)

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = when {
                        isM3u8 -> ExtractorLinkType.M3U8
                        isDash -> ExtractorLinkType.DASH
                        else -> INFER_TYPE
                    }
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
        }

        // Subtitle extraction (if present on the host page)
        document.select("track[kind=captions], track[kind=subtitles]").forEach { track ->
            val subUrl = track.attr("src")
            val subLang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "English" }
            if (subUrl.isNotBlank()) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = subLang,
                        url = fixUrl(subUrl)
                    )
                )
            }
        }
    }
}

/**
 * Pattern 2: Re-aliasing an existing Extractor for domain mirrors.
 * When a video host changes or mirrors its domain without altering its player architecture,
 * inherit from the extractor and override mainUrl and name.
 */
class ExampleMirrorExtractor : ExampleExtractor() {
    override val name = "ExampleServer Mirror"
    override val mainUrl = "https://mirror.exampleserver.com"
}
