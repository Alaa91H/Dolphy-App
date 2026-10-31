plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.chaquopy) apply false
    alias(libs.plugins.detekt)
}

val detektConfig = rootProject.file("config/detekt/detekt.yml")

subprojects {
    apply(plugin = "dev.detekt")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(detektConfig)
        // Legacy findings are recorded per module so the gate fails only on new
        // violations; regenerate with `./gradlew detektBaseline` after paying debt down.
        baseline = layout.projectDirectory.file("detekt-baseline.xml").asFile
        source.setFrom(
            files(
                "src/main/java",
                "src/main/kotlin",
                "src/test/java",
                "src/test/kotlin",
            ),
        )
    }

    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        reports {
            html.required.set(true)
            sarif.required.set(true)
        }
    }
}
