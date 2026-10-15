group = "app.wazegearslip"

patches {
    about {
        name = "waze-gearslip-patch"
        description = "One patch: lets Gearslip, the Android Auto alternative, show Waze on the car screen."
        source = "git@github.com:VoltoxderEchte/waze-gearslip-morphe-patch.git"
        author = "VoltoxderEchte"
        contact = "https://github.com/VoltoxderEchte/waze-gearslip-morphe-patch/issues"
        website = "https://github.com/VoltoxderEchte/waze-gearslip-morphe-patch"
        license = "AGPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
