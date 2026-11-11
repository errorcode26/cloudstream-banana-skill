# 03. High-Speed Scraping, Hydration & Adaptive Concurrency Guide

> **CORE PRINCIPLE**:
> Slow scraping leads to timed-out searches, frozen TV interfaces, and Out-Of-Memory (OOM) crashes on low-RAM devices (Firesticks, Android TV boxes). This guide details the exact high-speed scraping patterns and memory-aware concurrency management used by production extensions.

---

## 1. Phase 1 Concentrated Link Extractor Probe & Pre-Flight Scorecard

> **UNBREAKABLE AGENT MANDATE**:
> AI agents must **NEVER** write a single line of Kotlin, create `.kt` files, or scaffold a plugin module until the concentrated link extractor probe executes and outputs the complete **Pre-Flight Scorecard**. If a target cannot yield playable links within the strict SLA, rushing to generate Kotlin code is a 100% waste of developer time.

Before touching Gradle or writing Kotlin, run the diagnostic probe (`scripts/probe_target.py` or scratchpad):
```bash
python scripts/probe_target.py <target_url>
```

### The 4 Pillars of the Pre-Flight Scorecard:

Every pre-flight probe must evaluate and output the following 4 pillars directly to the developer:

1. **Title Sanitization Comparison (Balanced Non-Destructive)**:
   - Compares raw scraped titles against cleaned titles.
   - Verifies that technical release noise (`1080p`, `x264`, `HEVC`, `WEB-DL`, audio codecs, site watermarks) is removed.
   - Verifies that canonical release identifiers like `(2024)` or `(Season 1-4)` are **preserved** (vital for Cinemeta/TMDB metadata resolution).

2. **Image Renderability Check (Live HTTP Verification)**:
   - Sends HTTP HEAD/GET requests for scraped poster/backdrop URLs.
   - Confirms: HTTP 200 OK, valid image MIME (`image/jpeg`, `image/png`, `image/webp`), and content-length > 100 bytes.
   - Flags dead placeholders, base64 data URIs, or broken hotlink protection.

3. **TV Series Season & Episode Hierarchy Traversal**:
   - For episodic content, verifies that episodes retain their parent season context by scanning document headings (`h1..h6`).
   - Ensures child episodes labeled simply `"Episode 1"` or `"E01"` do **not** collapse into `Season 1`.
   - Explicitly rejects batch archive links (`.zip`, `.rar`, `"Batch"`).

4. **Link Extraction Velocity & Streamability Benchmark (≤ 10s Ceiling, Non-Blocking Feedback)**:
   - Measures hop-by-hop latency in milliseconds over Pure HTTP.
   - Evaluates fake client-side countdown timers for instant zero-wait bypass (< 500 ms).
   - Probes terminal streamability via `Range: bytes=0-1024` returning HTTP 200 or 206 Partial Content.
   - **Non-Blocking Rule**: Flexible ceiling of up to 10 seconds. The agent provides live, continuous feedback so the developer can make informed choices rather than hitting rigid, arbitrary abort blocks.

---

### The Universal Probing Script (`scripts/probe_target.py`):
```python
import sys
import json
import re

try:
    from curl_cffi import requests
    HAS_CURL_CFFI = True
except ImportError:
    import requests
    HAS_CURL_CFFI = False

def probe(url):
    import os
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    }
    
    # 0. Check for manual developer-assisted clearance session
    session_file = "scratch/cf_session.json"
    if os.path.exists(session_file):
        try:
            with open(session_file, "r", encoding="utf-8") as f:
                sess = json.load(f)
                if sess.get("cookies"):
                    headers["Cookie"] = sess["cookies"]
                if sess.get("userAgent"):
                    headers["User-Agent"] = sess["userAgent"]
            print("[+] Loaded active clearance session from scratch/cf_session.json")
        except Exception:
            pass
    
    # 1. Probe with standard HTTP or TLS impersonation
    if HAS_CURL_CFFI:
        resp = requests.get(url, headers=headers, impersonate="chrome120", timeout=10)
    else:
        resp = requests.get(url, headers=headers, timeout=10)
        
    print(f"[*] Status: {resp.status_code}")
    print(f"[*] Server Header: {resp.headers.get('Server', 'Unknown')}")
    
    # 2. Check for Verification Screen (Pure HTTP Triage)
    if resp.status_code in (403, 503) or "challenges.cloudflare.com" in resp.text:
        print(f"[!] Verification challenge encountered (HTTP {resp.status_code}). Initiating Pure HTTP Triage Protocol...")
        html = run_pure_http_cf_triage(url, headers)
        if not html:
            print("[!] Pure HTTP triage could not establish direct session.")
            print("[!] Launching Interactive Browser Bridge: Opening real browser window for user verification...")
            print("[!] Agent automatically acquires session cookies from localhost:9222 without copying/pasting tokens (Playbook 04).")
            return
    else:
        html = resp.text

    # 3. Check for Next.js / Nuxt / SvelteKit Hydration
    next_match = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', html)
    if next_match:
        data = json.loads(next_match.group(1))
        print("[+] HYDRATION FOUND: Next.js __NEXT_DATA__ detected!")
        print(f"[+] Build ID: {data.get('buildId')}")
        print("[+] Action: Parse JSON directly with parsedSafe<T>() instead of DOM parsing.")
        return

    nuxt_match = re.search(r'<script id="__NUXT_DATA__"[^>]*>(.*?)</script>', html) or re.search(r'window\.__NUXT__\s*=\s*(.*?);</script>', html)
    if nuxt_match:
        print("[+] HYDRATION FOUND: Nuxt __NUXT_DATA__ detected!")
        print("[+] Action: Parse Nuxt JSON payload directly.")
        return
        
    svelte_match = re.search(r'__data\.json', html)
    if svelte_match:
        print("[+] HYDRATION FOUND: SvelteKit __data.json detected!")
        return

    # 4. Check for WordPress REST API
    wp_api = url.rstrip("/") + "/wp-json/wp/v2/posts?per_page=5"
    try:
        wp_resp = requests.get(wp_api, headers=headers, timeout=5)
        if wp_resp.status_code == 200:
            print("[+] API FOUND: WordPress REST API active at /wp-json/wp/v2/posts")
            return
    except Exception:
        pass

    # 5. Fallback: Standard SSR / DOM
    print("[+] ARCHITECTURE: Standard Server-Side Rendered HTML. Use Jsoup document.select().")
    inspect_selectors(html)

def run_pure_http_cf_triage(url, headers):
    """
    Executes a 100% pure HTTP triage to establish direct client connectivity
    without requiring any browser, selenium, or headless engines.
    """
    # Probe 1: Pure HTTP Subdomain & API Routing
    # Verification challenges are frequently attached to desktop HTML pages, but excluded from REST/AJAX and mobile routes.
    from urllib.parse import urlparse
    parsed = urlparse(url)
    base = f"{parsed.scheme}://{parsed.netloc}"
    
    probe_routes = [
        f"{base}/api/v1/movies",
        f"{base}/api/movies",
        f"{base}/ajax/movie/list",
        f"{base}/wp-json/wp/v2/posts?per_page=5",
        f"{parsed.scheme}://m.{parsed.netloc}/",
        f"{parsed.scheme}://api.{parsed.netloc}/"
    ]
    
    for route in probe_routes:
        try:
            r = requests.get(route, headers=headers, timeout=5)
            if r.status_code == 200 and ("application/json" in r.headers.get("Content-Type", "") or "film" in r.text):
                print(f"[+] DIRECT HTTP ENDPOINT RESOLVED: Direct route returned 200: {route}")
                return r.text
        except Exception:
            pass

    # Probe 2: Pure HTTP Sitemap & Feed Extraction
    # Sitemaps reveal the site's complete URL and media structure over pure HTTP.
    for sitemap_url in [f"{base}/sitemap.xml", f"{base}/sitemap_index.xml", f"{base}/feed"]:
        try:
            r = requests.get(sitemap_url, headers=headers, timeout=5)
            if r.status_code == 200 and ("xml" in r.headers.get("Content-Type", "") or "<urlset" in r.text):
                print(f"[+] SITEMAP ACCESSIBLE: Discovered unshielded XML feed at {sitemap_url}")
                # We can inspect URL routing rules directly from the XML
                return r.text
        except Exception:
            pass

    # Probe 3: Pure HTTP Mirror TLD Rotation
    # Many streaming platforms maintain multiple TLDs (.to, .is, .st, .wf, .vg).
    # Often only the main domain is under aggressive protection, while mirrors are accessible via direct HTTP.
    tlds = [".to", ".is", ".st", ".wf", ".vg", ".me", ".tv"]
    for tld in tlds:
        mirror_netloc = re.sub(r'\.[a-z]{2,6}$', tld, parsed.netloc)
        if mirror_netloc != parsed.netloc:
            mirror_url = f"{parsed.scheme}://{mirror_netloc}/"
            try:
                r = requests.get(mirror_url, headers=headers, timeout=5)
                if r.status_code == 200 and "challenges.cloudflare.com" not in r.text:
                    print(f"[+] UNPROTECTED MIRROR FOUND: {mirror_url} returned HTTP 200 on pure HTTP!")
                    return r.text
            except Exception:
                pass

    # Probe 4: Pure HTTP Cached / Archive Snapshot
    # Query public web archive API to inspect the real DOM selectors over pure HTTP without hitting Cloudflare.
    try:
        archive_api = f"https://archive.org/wayback/available?url={url}"
        r = requests.get(archive_api, headers=headers, timeout=5).json()
        snapshot_url = r.get("archived_snapshots", {}).get("closest", {}).get("url")
        if snapshot_url:
            print(f"[*] Fetching pure HTTP cached DOM snapshot from Web Archive: {snapshot_url}")
            snap_resp = requests.get(snapshot_url, headers=headers, timeout=10)
            if snap_resp.status_code == 200:
                print("[+] SUCCESS: Archived DOM retrieved over pure HTTP for selector inspection.")
                return snap_resp.text
    except Exception:
        pass

    return None

def inspect_selectors(html):
    """Quickly scans HTML for common streaming site selectors."""
    from bs4 import BeautifulSoup
    soup = BeautifulSoup(html, "html.parser")
    
    # 1. Home card container search
    cards = soup.select(".film-item, .flw-item, .movie-card, .item, .card, div[class*='item']")
    print(f"[*] Found {len(cards)} candidate media cards on home page.")
    if cards:
        c = cards[0]
        title = c.select_one("h2, h3, .title, a[title]")
        poster = c.select_one("img[data-src], img[data-lazy-src], img[src]")
        link = c.select_one("a[href]")
        print(f"    - Sample Title: {title.text.strip() if title else 'Not found'}")
        print(f"    - Sample Poster: {poster.get('data-src') or poster.get('src') if poster else 'Not found'}")
        print(f"    - Sample Link: {link.get('href') if link else 'Not found'}")
        
    # 2. Player/iframe search
    iframes = soup.select("iframe[src], iframe[data-src]")
    print(f"[*] Found {len(iframes)} candidate video iframes.")
    for idx, f in enumerate(iframes[:3]):
        print(f"    - Iframe [{idx}]: {f.get('data-src') or f.get('src')}")

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "https://example.com"
    probe(target)
```

### Universal Streaming Site Anatomy Cheat-Sheet (What to Search For)

When inspecting dumped HTML or API payloads during reconnaissance, search for these standard patterns:

| Component | Standard CSS Selectors & Attributes | Standard JSON API Keys |
| :--- | :--- | :--- |
| **Media Cards** | `.film-item`, `.flw-item`, `.movie-card`, `div[class*="item"]`, `div[class*="card"]` | `data.items`, `results`, `posts`, `subjects` |
| **Title** | `h2.film-name a`, `h3 a`, `a.title`, `img[alt]` | `title`, `name`, `original_name` |
| **Poster Image** | `img[data-src]`, `img[data-lazy-src]`, `img[src]`, `meta[property="og:image"]` | `poster_path`, `cover.url`, `thumb`, `poster` |
| **Detail URL / ID**| `a[href*="/movie/"]`, `a[href*="/series/"]`, `a[href*="/watch/"]`, `a[data-id]` | `id`, `slug`, `subjectId`, `media_id` |
| **Media Typology** | Check href for `/tv/` or badges `.badge-series`, `span[class*="type"]` | `type == "tv"`, `is_series == true`, `subjectType == 2` |
| **Episodes Grid** | `ul.episodes li a`, `div.episode-item a`, `div[data-season] a`, `select#season` | `data.episodes`, `seasons[].episodes[]` |
| **Video Iframes** | `iframe[src*="embed"]`, `iframe[data-src]`, `iframe[src*="/player/"]` | `embed_url`, `player_url`, `servers[].link` |
| **Direct Streams**| `video source[src]`, regex `(?i)(https?://[^\s"']+\.m3u8[^\s"']*)` | `stream_url`, `file`, `hls_url`, `dash_url` |

---

## 2. Hydration Framework Parsing (Next.js, Nuxt, SvelteKit & REST APIs)

Modern web platforms embed their initial state directly into HTML as serialized JSON to accelerate client hydration. Parsing these JSON objects directly is **10x to 50x faster** and completely immune to DOM class changes:

### 1. Next.js (`__NEXT_DATA__`)
Next.js sites embed the complete page props inside `<script id="__NEXT_DATA__">`:
```kotlin
val document = app.get(targetUrl).document
val nextDataJson = document.selectFirst("script#__NEXT_DATA__")?.data()
if (!nextDataJson.isNullOrBlank()) {
    val root = mapper.readTree(nextDataJson)
    val pageProps = root["props"]?.get("pageProps")
    val items = pageProps?.get("items") ?: pageProps?.get("data")
    // Map items directly to search or load responses
}
```

### 2. Nuxt.js (`__NUXT_DATA__`)
Nuxt 3 applications serialize state into `<script id="__NUXT_DATA__">`:
```kotlin
val nuxtData = document.selectFirst("script#__NUXT_DATA__")?.data()
// Parse Nuxt array-indexed reactive payload
```

### 3. SvelteKit (`__data.json`)
SvelteKit provides clean JSON endpoints by appending `/__data.json` to the target route:
```kotlin
// Example: https://example.com/movie/avatar/__data.json
val svelteData = app.get("$movieUrl/__data.json").text
```

### 4. WordPress REST API
Sites running WordPress often expose public REST API endpoints without authentication:
```kotlin
// Fetches 20 recent posts formatted as clean JSON objects:
val wpPosts = app.get("$mainUrl/wp-json/wp/v2/posts?per_page=20").parsedSafe<List<WpPost>>()
```

---

## 3. High-Speed Direct Deserialization (`kotlinx.serialization` & `parsedSafe<T>()`)

Traditional HTML DOM parsing with dozens of `.select()` calls is slow and fragile. When an endpoint or hydration tag returns JSON, map directly into strongly-typed Kotlin data classes using `kotlinx.serialization`.

> [!IMPORTANT]
> **Strict Upstream Directive: `kotlinx.serialization` ONLY. Never Use `org.json.JSONObject`**:
> Upstream CloudStream core developers strongly mandate `kotlinx.serialization` across all plugins:
> 1. `org.json.JSONObject` is untyped, error-prone, verbose, and Android-platform-locked (blocks pure KMP / desktop JAR compatibility).
> 2. Always annotate models with `@Serializable` and use `com.lagradost.cloudstream3.utils.AppUtils.parseJson<T>()`, `AppUtils.tryParseJson<T>()`, or `app.get().parsedSafe<T>()`.
> 3. To encode an object to JSON, use `myObject.toJson()` from `AppUtils`.

### The Modern KMP Serialization Recipe:
```kotlin
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponseRoot(
    val results: List<MediaItem>? = null,
    @SerialName("total_pages") val totalPages: Int? = null
)

@Serializable
data class MediaItem(
    val id: String,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null
)

// 1. One-line high-speed network fetch & deserialization:
val data = app.get("$mainUrl/api/search?q=$query").parsedSafe<SearchResponseRoot>()

// 2. Or parsing raw JSON strings safely:
val directParsed = tryParseJson<SearchResponseRoot>(jsonString)

// 3. Serializing back to JSON:
val jsonString = data?.toJson()

val searchCards = data?.results?.map { item ->
    newMovieSearchResponse(item.title, item.id, TvType.Movie) {
        this.posterUrl = item.posterPath
        this.year = item.releaseYear
    }
} ?: emptyList()
```

---

## 4. Title Sanitization & Image URL Normalization (`CommonUtils`)

Raw scraped data from web pages is notoriously dirty. Always process titles and image URLs through `CommonUtils` before instantiating responses:

### 1. Title Sanitization (`CommonUtils.cleanTitle`)
* **Problem**: Scraped titles often look like `"Avatar: The Way of Water (2022) [1080p] [Dual Audio] WEBRip x264 &amp; Watch Free"`.
* **Impact**: Raw strings look messy on TV screens and cause TMDB/Cinemeta lookups to fail because the search query contains junk keywords.
* **Solution**:
  ```kotlin
  // Unescapes HTML entities, strips quality/audio tags, removes brackets and site watermarks:
  val cleanTitle = CommonUtils.cleanTitle(rawTitle)
  // Yields: "Avatar: The Way of Water"
  ```

### 2. Image URL Resolution (`CommonUtils.cleanImageUrl`)
* **Problem**: Sites frequently serve relative paths (`/uploads/thumb.jpg`), protocol-relative URLs (`//cdn.example.com/...`), or dummy tracking SVGs (`data:image/svg+xml...`).
* **Impact**: On Android devices, Coil/BitmapFactory fails silently, displaying blank grey boxes instead of posters.
* **Solution**:
  ```kotlin
  // Normalizes protocol/root-relative paths and discards SVG/1x1 placeholders:
  val validPosterUrl = CommonUtils.cleanImageUrl(rawPosterUrl, mainUrl)
  ```

---

## 5. Device-Adaptive Concurrency & Semaphore Throttling

Running uncontrolled parallel coroutines (`async` / `amap`) with 50+ concurrent requests will crash Android TV sticks (<2GB RAM) with an `OutOfMemoryError`.

### Device Profile Detection:
```kotlin
enum class DeviceProfile(val maxConcurrency: Int) {
    LOW_END(8),    // < 2GB RAM or < 4 CPU cores (TV Sticks)
    MID_RANGE(20), // 2-4GB RAM, 4-6 cores
    HIGH_END(40);  // > 4GB RAM, > 6 cores (Phones, Flagship TVs)

    companion object {
        fun detect(context: android.content.Context): DeviceProfile {
            val actManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)

            val totalRamMB = memInfo.totalMem / (1024 * 1024)
            val cores = Runtime.getRuntime().availableProcessors()

            return when {
                totalRamMB < 2048 || cores < 4 -> LOW_END
                totalRamMB < 4096 || cores < 6 -> MID_RANGE
                else                           -> HIGH_END
            }
        }
    }
}
```

### Throttled Parallel Execution (`parallelMap`):
Use a `Semaphore` to bound concurrent requests:
```kotlin
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

suspend fun <T, R> Iterable<T>.parallelMap(
    concurrency: Int,
    transform: suspend (T) -> R
): List<R> = coroutineScope {
    val semaphore = Semaphore(concurrency)
    map { item ->
        async {
            semaphore.withPermit {
                transform(item)
            }
        }
    }.awaitAll()
}
```

---

## 6. CloudStream's Native `.amap`

For standard concurrent episode or server link resolution without extra boilerplate, use CloudStream's built-in `.amap`:

```kotlin
// Resolves all server links concurrently rather than sequentially:
val extractedLinks = mutableListOf<ExtractorLink>()
serverUrls.amap { serverUrl ->
    loadExtractor(serverUrl, subtitleCallback) { link ->
        extractedLinks.add(link)
    }
}
```

---

## 7. Intelligent Dual-TTL In-Memory Caching

Prevent redundant network calls during navigation and search with a dual-TTL cache:

```kotlin
import java.util.concurrent.ConcurrentHashMap

object ApiCache {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val SUCCESS_TTL = 10 * 60 * 1000L // 10 minutes for valid responses
    private const val FAILURE_TTL = 2 * 60 * 1000L  // 2 minutes for failed attempts

    data class CacheEntry(val data: String, val timestamp: Long, val isSuccess: Boolean)

    fun get(key: String): String? {
        val entry = cache[key] ?: return null
        val age = System.currentTimeMillis() - entry.timestamp
        val maxAge = if (entry.isSuccess) SUCCESS_TTL else FAILURE_TTL
        return if (age < maxAge) entry.data else null
    }

    fun put(key: String, data: String, isSuccess: Boolean = true) {
        cache[key] = CacheEntry(data, System.currentTimeMillis(), isSuccess)
    }
}

---

## 8. Terminal Streamability & Byte-Range Feasibility Probing

Before scaffolding a plugin, verify that the target site actually resolves to **streamable video streams** rather than unplayable file-locker archives, torrents, or dead landing pages:

```python
def probe_streamability(terminal_url):
    """
    Verifies that a terminal media link supports HTTP Range byte streaming
    returning HTTP 200 or 206 Partial Content.
    """
    import urllib.request
    headers = {"User-Agent": "Mozilla/5.0", "Range": "bytes=0-1024"}
    req = urllib.request.Request(terminal_url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            code = resp.getcode()
            content_type = resp.headers.get("Content-Type", "")
            accept_ranges = resp.headers.get("Accept-Ranges", "")
            print(f"[*] Streamability Status: HTTP {code}")
            print(f"[*] Content-Type: {content_type}")
            print(f"[*] Accept-Ranges: {accept_ranges}")
            
            is_streamable = code in (200, 206) and any(
                t in content_type.lower() or terminal_url.lower().endswith(ext)
                for t, ext in [
                    ("video", ".mp4"), ("video", ".mkv"),
                    ("mpegurl", ".m3u8"), ("dash+xml", ".mpd"),
                    ("octet-stream", ".mkv")
                ]
            )
            return is_streamable
    except Exception as e:
        print(f"[!] Streamability probe failed: {e}")
        return False
```

---

## 9. Contextual Season Hierarchy Traversal (Multi-Season WordPress & CMS)

On WordPress or CMS blogs hosting multi-season series, child episode links or archive pages (e.g. `episodes.blog/archives/...`) frequently label links only as `"Episode 1"`, `"Episode 2"`, omitting season numbers. Calling `extractSeasonAndEpisode("Episode 1")` yields `season = null`, which naively collapses all seasons into `Season 1`.

### The Contextual Traversal Pattern:
Scan headings in document order, track `currentSeason`, and associate child links with the active season:

```kotlin
val archiveSeasons = mutableListOf<Pair<String, Int>>()
var currentSeason = 1
val seasonRegex = Regex("""(?i)\b(?:Season\s*|S)(\d+)\b""")

val content = document.selectFirst("div.entry-content, div.thecontent") ?: document
content.select("h1, h2, h3, h4, h5, h6, p").forEach { el ->
    val text = el.text().trim()

    // Detect single season headings (ignoring multi-season spans like 'Season 1-3')
    if (el.tagName().matches(Regex("""(?i)h[1-6]"""))) {
        val isRange = text.contains(Regex("""(?i)Season\s*\d+\s*-\s*\d+""")) ||
                      text.contains(Regex("""(?i)S\d+\s*-\s*S?\d+"""))
        if (!isRange) {
            val match = seasonRegex.find(text)
            if (match != null) {
                currentSeason = match.groupValues[1].toIntOrNull() ?: currentSeason
            }
        }
    }

    // Tag episode links found under this heading with the active season
    el.select("a[href]").forEach { a ->
        val href = a.attr("href").trim()
        val txt = a.text().trim()
        val isBatch = txt.contains("Batch", ignoreCase = true) ||
                      txt.contains("Zip", ignoreCase = true) ||
                      href.contains(".zip", ignoreCase = true)

        if (!isBatch && href.isNotBlank() && (txt.contains("Episode", true) || href.contains("episodes"))) {
            archiveSeasons.add(Pair(href, currentSeason))
        }
    }
}
```

---

## 10. Filtering Batch & Zip Archives in Link Harvesters

Video scrapers must explicitly reject batch archive links to prevent passing `.zip` or `.rar` files to CloudStream's ExoPlayer:

```kotlin
fun Element.isStreamableVideoLink(): Boolean {
    val text = this.text().trim()
    val href = this.attr("href").trim()

    val isArchive = text.contains("Batch", ignoreCase = true) ||
                    text.contains("Zip", ignoreCase = true) ||
                    text.contains("Rar", ignoreCase = true) ||
                    href.endsWith(".zip", ignoreCase = true) ||
                    href.endsWith(".rar", ignoreCase = true)

    return !isArchive && href.startsWith("http")
}
```
```
