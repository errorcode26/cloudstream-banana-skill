// Use an integer for version numbers
version = 1

dependencies {
    // Upstream Material & UI dependencies for BottomSheets and Settings
    implementation("com.google.android.material:material:1.12.0")

    // Local Headless JVM Unit Test Runner & CloudStream Runtime Mocking
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation(files("${System.getProperty("user.home")}/.gradle/caches/cloudstream/cloudstream/cloudstream.jar"))
}

android {
    namespace = "com.example.plugin"
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

cloudstream {
    authors = listOf("PluginAuthor")
    description = "Generic CloudStream 3 Scraper Plugin Template"
    language = "en"

    /**
     * Status integer:
     * 0: Down (Disabled / excluded from build)
     * 1: Ok (Active & verified)
     * 2: Slow
     * 3: Beta only
     */
    status = 0 // Keep 0 while developing; change to 1 when verified

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )

    // Optional remote icon for CloudStream repository listing
    iconUrl = "https://raw.githubusercontent.com/google/material-design-icons/master/png/av/movie/materialicons/48dp/2x/baseline_movie_black_48dp.png"

    isCrossPlatform = false
    requiresResources = false
}
