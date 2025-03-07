plugins {
    id("com.android.application") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20-RC2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20-RC2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20-RC2" apply false
    id("com.google.devtools.ksp") version "2.1.20-RC2-1.0.31" apply false

    id("androidx.navigation.safeargs") version "2.8.8" apply false

    alias(libs.plugins.detekt.plugin) apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
