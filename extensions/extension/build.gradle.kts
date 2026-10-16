extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.wazegearslip.extension"

    defaultConfig {
        // LocationManager's Executor and LocationRequest overloads (API 31); Waze 5.24 itself needs API 32.
        minSdk = 31
    }
}

// Plain Java: keep the Kotlin stdlib out of the dex, so it can't clash with Waze's own copy.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
