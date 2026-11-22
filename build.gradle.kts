plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

apply(from = "gradle/build-identity.gradle.kts")

val localSync = providers.gradleProperty("quotadog.hostSyncLocal").orNull == "true"
val stagingSync = providers.gradleProperty("quotadog.hostSyncStaging").orNull == "true"
require(!localSync || !stagingSync) { "Select a single host-sync test environment" }
extra["hostSyncVariant"] = when {
    stagingSync -> "hostSyncStaging"
    localSync -> "hostSyncLocal"
    else -> ""
}
