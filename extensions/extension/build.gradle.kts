extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.wazegearslip.extension"

    defaultConfig {
        // PackageInfo.signingInfo; Waze itself needs far newer.
        minSdk = 28
    }
}
