plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10" apply false
    id("com.google.devtools.ksp") version "2.1.10-1.0.29" apply false
}

configurations.configureEach {
    resolutionStrategy {
        force("com.android.support:support-v4:1.0.0")
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
