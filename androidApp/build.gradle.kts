import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
}

val versionProperties = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val releaseVersionName: String =
    System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() }
        ?: versionProperties.getProperty("VERSION_NAME")?.takeIf { it.isNotBlank() }
        ?: "1.0.0"
val releaseVersionCode: Int =
    System.getenv("RELEASE_VERSION_CODE")?.toIntOrNull()
        ?: versionProperties.getProperty("VERSION_CODE")?.toIntOrNull()
        ?: 1

@Suppress("UNCHECKED_CAST")
val buildIdentity = rootProject.extensions.extraProperties
    .get("quotadogBuildIdentity") as Map<String, String>
val verifyReleaseBuildIdentity = rootProject.tasks.named("verifyReleaseBuildIdentity")
val hostSyncVariant = rootProject.extra["hostSyncVariant"] as String

val hostSyncNative = rootProject.file("native/host-sync")
val buildAndroidHostTls by tasks.registering(Exec::class) {
    workingDir(hostSyncNative)
    commandLine("bash", "build-local.sh", "android")
    inputs.files(fileTree(hostSyncNative) { include("Cargo.*", "src/**", "build-local.sh") })
    outputs.files(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").map {
        hostSyncNative.resolve("build/android/$it/libquotadog_host_tls.so")
    })
}

tasks.configureEach {
    if (name == "preBuild" && hostSyncVariant.isEmpty()) dependsOn(buildAndroidHostTls)
    if (name == "preReleaseBuild") {
        dependsOn(verifyReleaseBuildIdentity)
    }
}

android {
    namespace = "saien.quotadog"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    sourceSets.getByName("main").jniLibs.srcDir(hostSyncNative.resolve("build/android"))

    defaultConfig {
        applicationId = "saien.quotadog"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        manifestPlaceholders["quotadogBuildCommit"] = buildIdentity.getValue("commit")
        manifestPlaceholders["quotadogBuildCommitShort"] = buildIdentity.getValue("short")
        manifestPlaceholders["quotadogBuildDirty"] = buildIdentity.getValue("dirty")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            fun envOrNull(vararg names: String): String? =
                names.firstNotNullOfOrNull { System.getenv(it)?.takeIf { value -> value.isNotBlank() } }

            val keystorePath = envOrNull("QUOTADOG_KEYSTORE_PATH", "SAIEN_KEYSTORE_PATH")
            val keystorePass = envOrNull("QUOTADOG_KEYSTORE_PASSWORD", "SAIEN_KEYSTORE_PASSWORD")
            val signingKeyAlias = envOrNull("QUOTADOG_KEY_ALIAS", "SAIEN_KEY_ALIAS")
            val signingKeyPass = envOrNull("QUOTADOG_KEY_PASSWORD", "SAIEN_KEY_PASSWORD")

            if (keystorePath != null && keystorePass != null &&
                signingKeyAlias != null && signingKeyPass != null
            ) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPass
            } else {
                logger.warn(
                    "Release signing env vars missing; release builds will be unsigned. " +
                        "Set QUOTADOG_KEYSTORE_PATH, QUOTADOG_KEYSTORE_PASSWORD, " +
                        "QUOTADOG_KEY_ALIAS, QUOTADOG_KEY_PASSWORD.",
                )
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
        if (hostSyncVariant.isNotEmpty()) {
            val staging = hostSyncVariant == "hostSyncStaging"
            create(hostSyncVariant) {
                initWith(getByName("debug"))
                applicationIdSuffix = if (staging) ".hostsync.staging" else ".hostsync.local"
                ndk.abiFilters += "arm64-v8a" // The pilot's rustls JNI library is built only for this ABI.
                manifestPlaceholders["hostSyncLabel"] = if (staging) "QuotaDog Staging" else "QuotaDog Local"
                manifestPlaceholders["hostSyncNetworkPolicy"] = if (staging) "@xml/relay_tls" else "@xml/relay_loopback"
                matchingFallbacks += "debug"
            }
            sourceSets.getByName(hostSyncVariant).apply {
                if (staging) {
                    manifest.srcFile("src/hostSyncLocal/AndroidManifest.xml")
                }
            }
        }
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.zxing.core)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

abstract class GenerateBuildIdentityAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val template: RegularFileProperty

    @get:Input
    abstract val tokens: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = template.get().asFile
        val rendered = tokens.get().entries.fold(source.readText()) { text, (key, value) ->
            text.replace("@$key@", value)
        }
        val destination = outputDirectory.get().asFile.resolve(source.name)
        destination.parentFile.mkdirs()
        destination.writeText(rendered)
    }
}

val buildIdentityTokens = mapOf(
    "PACKAGE_NAME" to "saien.quotadog",
    "VERSION_NAME" to releaseVersionName,
    "VERSION_CODE" to releaseVersionCode.toString(),
    "COMMIT_SHA" to buildIdentity.getValue("commit"),
    "SHORT_COMMIT_SHA" to buildIdentity.getValue("short"),
    "DIRTY" to buildIdentity.getValue("dirty"),
)

androidComponents {
    // A pilot selects a different relay at compile time. Never put it in the regular app ID.
    if (hostSyncVariant.isNotEmpty()) beforeVariants { it.enable = it.buildType == hostSyncVariant }
    onVariants { variant ->
        val generateTask = tasks.register<GenerateBuildIdentityAsset>(
            "generate${variant.name.replaceFirstChar(Char::uppercase)}BuildIdentityAsset",
        ) {
            template.set(
                layout.projectDirectory.file(
                    "src/buildIdentity/quotadog-build-identity.properties",
                ),
            )
            tokens.set(buildIdentityTokens)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateTask,
            GenerateBuildIdentityAsset::outputDirectory,
        )
    }
}
