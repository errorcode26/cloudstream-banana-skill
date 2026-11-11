// Use an integer for version numbers
version = 1

dependencies {
    // Upstream Material & UI dependencies
    implementation("com.google.android.material:material:1.12.0")

    // Local Headless JVM Unit Test Runner
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

android {
    namespace = "com.example.game"
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

cloudstream {
    authors = listOf("PluginAuthor")
    description = "Interactive Retro Emulation & Canvas Game Plugin Template"
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
        "Others"
    )

    // Remote icon for CloudStream repository listing
    iconUrl = "https://raw.githubusercontent.com/google/material-design-icons/master/png/hardware/gamepad/materialicons/48dp/2x/baseline_gamepad_black_48dp.png"

    isCrossPlatform = false
    requiresResources = true
}
