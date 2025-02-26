plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10" apply false
    id("com.google.devtools.ksp") version "2.1.10-1.0.30" apply false

    id("androidx.navigation.safeargs") version "2.8.7" apply false
}

//configurations.configureEach {
//    resolutionStrategy {
//        force("com.android.support:support-v4:1.0.0")
//    }
//}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
