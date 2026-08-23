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

tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(verifyReleaseBuildIdentity)
    }
}

android {
    namespace = "saien.quotadog"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

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
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
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
