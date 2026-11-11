package com.example.manga

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExampleMangaPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the manga/comic reader provider
        registerMainAPI(ExampleMangaProvider())
    }
}
