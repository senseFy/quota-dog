plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

apply(from = "gradle/build-identity.gradle.kts")

val iosLocal = System.getenv("PRODUCT_BUNDLE_IDENTIFIER") == "saien.quotadog.hostsync.local" &&
    System.getenv("PLATFORM_NAME") == "iphonesimulator" && System.getenv("CONFIGURATION") == "Debug"
val localSync = iosLocal || providers.gradleProperty("quotadog.hostSyncLocal").orNull == "true"
val stagingSync = providers.gradleProperty("quotadog.hostSyncStaging").orNull == "true"
require(!localSync || !stagingSync) { "Select a single host-sync test environment" }
require(!(localSync || stagingSync) || System.getenv("CONFIGURATION") != "Release") {
    "An Xcode release must use the production host-sync relay"
}
extra["hostSyncVariant"] = when {
    stagingSync -> "hostSyncStaging"
    localSync -> "hostSyncLocal"
    else -> ""
}
