# 10. Comic, Manga & Document Reader Architecture

> **THE EXTENSION ARCHITECTURE**:
> CloudStream 3 is built as an ExoPlayer video streaming platform. However, using the **`loadLinks` Dialog Hijack Pattern**, you can build full-featured, native comic and manga reader extensions with zoom, vertical scrolling, and chapter switching.

---

## 1. The `loadLinks` Dialog Hijack Pattern

When a user selects an item in CloudStream's episode list, the app invokes `loadLinks(data, ...)` and waits for an `ExtractorLink` to pass to ExoPlayer.

### The Hijack Recipe:
1. **Typology Distinction**: In `build.gradle.kts` declare `tvTypes = listOf("Others")` for the CloudStream UI category filter chip. In Kotlin `load()`, return `newTvSeriesLoadResponse(..., TvType.CustomMedia)` — `TvType.CustomMedia` explicitly instructs CloudStream's internal player router to suppress ExoPlayer.
2. Map each manga or comic issue as a `newEpisode(chapterUrl)`.
3. In `loadLinks`:
   * Retrieve the active Android activity via `CommonActivity.activity as? AppCompatActivity`.
   * Immediately launch a custom fullscreen `DialogFragment` (e.g. `ComicReaderDialogFragment`).
   * **Return `true` without passing any `ExtractorLink` to `callback`**.
4. **Result**: ExoPlayer never launches. Instead, the native comic reading UI opens seamlessly!

```kotlin
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val activity = CommonActivity.activity as? AppCompatActivity
    if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
        withContext(Dispatchers.Main) {
            val readerDialog = ComicReaderDialogFragment.newInstance(
                chapterUrl = data,
                chapterTitle = "Chapter 1"
            )
            readerDialog.show(activity.supportFragmentManager, "ComicReader")
        }
    }
    // Return true with NO extractor links passed to callback
    return true
}
```

---

## 2. Cloudflare Cookie Interceptor for Image CDNs

Manga image hosts (CDN servers) frequently protect image files with Cloudflare Turnstile or hotlink protection:

```kotlin
import okhttp3.Interceptor
import okhttp3.Response

object ComicImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            // Critical: Stripping X-Requested-With prevents Cloudflare from flagging WebView
            .removeHeader("X-Requested-With")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 ...")

        // Inject active clearance cookies
        val savedCookies = MyPlugin.cfCookies
        if (savedCookies.isNotEmpty()) {
            builder.header("Cookie", savedCookies)
        }

        val response = chain.proceed(builder.build())

        // Capture set-cookie refreshes dynamically
        response.headers("Set-Cookie").forEach { cookieHeader ->
            if (cookieHeader.contains("cf_clearance=")) {
                MyPlugin.cfCookies = cookieHeader.substringBefore(";").trim()
            }
        }

        return response
    }
}
```

---

## 3. Reader Dialog Features

A production comic reader dialog should implement:
* **Vertical Continuous Scroll**: A `RecyclerView` using a vertical `LinearLayoutManager` displaying full-width page images.
* **Pinch-to-Zoom**: Wrap images in an interactive zoomable image view (e.g. `PhotoView`).
* **Double-Tap Quick Zoom**: Center-focused 2x zoom toggle.
* **Prefetch Engine**: As the user reads page 5, asynchronously prefetch pages 6 to 10 into cache to eliminate loading latency.
* **Fullscreen Immersion**: Hide system navigation and status bars using Android's `WindowInsetsController`.
