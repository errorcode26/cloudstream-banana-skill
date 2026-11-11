package com.example

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin : Plugin() {

    override fun load(context: Context) {
        // Register the main provider
        registerMainAPI(ExampleProvider())

        // Register custom video host extractors (if custom ones are needed)
        // registerExtractorAPI(ExampleExtractor())
        // registerExtractorAPI(ExampleMirrorExtractor())

        // OPTIONAL: Plugin Settings Screen (Disabled by default — only enable if requested by dev)
        // this.openSettings = { ctx ->
        //     var current: Context? = ctx
        //     var activity: AppCompatActivity? = null
        //     while (current is ContextWrapper) {
        //         if (current is AppCompatActivity) {
        //             activity = current
        //             break
        //         }
        //         current = current.baseContext
        //     }
        //     if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
        //         try {
        //             ExampleSettingsFragment(this).show(activity.supportFragmentManager, "ExampleSettings")
        //         } catch (e: Throwable) {
        //             e.printStackTrace()
        //         }
        //     }
        // }
    }

    companion object {
        /**
         * CloudStream native key-value store persistence (survives app updates).
         */
        var cfCookies: String
            get() = getKey("EXAMPLE_CF_COOKIES") ?: ""
            set(value) {
                setKey("EXAMPLE_CF_COOKIES", value)
            }

        var cfUserAgent: String
            get() = getKey("EXAMPLE_CF_USER_AGENT") ?: ""
            set(value) {
                setKey("EXAMPLE_CF_USER_AGENT", value)
            }

        /**
         * Optional: Dynamic domain resolution.
         * Scraping domains frequently get blocked or change TLDs (.to -> .wf -> .is).
         * Host a simple JSON file on GitHub raw or pastebin to update domains remotely
         * without having to recompile the plugin.
         */
        private const val DOMAINS_URL = "https://raw.githubusercontent.com/example/domains/main/domains.json"
        private var cachedDomain: String? = null

        suspend fun getWorkingDomain(fallback: String = "https://example.com"): String {
            if (cachedDomain != null) return cachedDomain!!
            return try {
                val domains = app.get(DOMAINS_URL, timeout = 5).parsedSafe<DomainConfig>()
                val active = domains?.primaryDomain ?: fallback
                cachedDomain = active
                active
            } catch (_: Exception) {
                fallback
            }
        }

        @Serializable
        data class DomainConfig(
            @SerialName("primary") val primaryDomain: String? = null,
            @SerialName("backup") val backupDomain: String? = null,
        )
    }
}
