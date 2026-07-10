import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
}

// App version prefers CI/env overrides, then falls back to version.properties so
// local release scripts and `make version-bump` share one source of truth.
// Compose Desktop installer formats reject MAJOR=0, so defaults stay >= 1.0.0.
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

val macStatusBarResourcesDir = layout.buildDirectory.dir("generated/macosStatusBarResources")
val compileMacStatusBar by tasks.registering(Exec::class) {
    val source = layout.projectDirectory.file("src/desktopMain/native/macos/QuotaDogStatusBar.m")
    val output = macStatusBarResourcesDir.map { it.file("macos/libQuotaDogStatusBar.dylib") }

    onlyIf {
        System.getProperty("os.name").contains("Mac", ignoreCase = true)
    }
    inputs.file(source)
    outputs.file(output)

    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "clang",
        "-dynamiclib",
        "-fobjc-arc",
        "-framework",
        "AppKit",
        "-framework",
        "Foundation",
        "-o",
        output.get().asFile.absolutePath,
        source.asFile.absolutePath,
    )
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            export(projects.shared)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.components.resources)
            implementation(compose.ui)
            implementation(libs.kotlinx.datetime)
            implementation(libs.lucide.icons.cmp)
            api(projects.shared)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        val desktopMain by getting {
            resources.srcDir(macStatusBarResourcesDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.jna)
            }
        }
    }
}

android {
    namespace = "saien.quotadog"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    defaultConfig {
        applicationId = "saien.quotadog"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Optional release signing config wired through env vars. Release builds remain unsigned
    // when the env vars are missing, so contributors without a keystore can still build.
    // Prefers QUOTADOG_* env vars; falls back to legacy SAIEN_* names for backwards compatibility.
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
                        "QUOTADOG_KEY_ALIAS, QUOTADOG_KEY_PASSWORD."
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

tasks.named("desktopProcessResources") {
    dependsOn(compileMacStatusBar)
}

compose.desktop {
    application {
        mainClass = "saien.quotadog.MainKt"

        nativeDistributions {
            // Dmg = macOS, Msi = Windows installer, Deb = Debian/Ubuntu Linux.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "QuotaDog"
            // The desktop app uses the Ktor Java engine (`java.net.http`) and a local OAuth
            // callback server (`jdk.httpserver`). jlink does not infer these reliably from the
            // packaged classpath, so include them explicitly in the runtime image.
            modules("java.net.http", "jdk.httpserver")
            // Compose Desktop requires strict semver `X.Y.Z`; strip any suffix
            // such as "-dev" or "-rc.1" so local dev versions still package.
            packageVersion = releaseVersionName.substringBefore('-').let { stripped ->
                if (stripped.matches(Regex("\\d+\\.\\d+\\.\\d+"))) stripped else "1.0.0"
            }

            macOS {
                bundleID = "saien.quotadog"
                // Formal release signing is driven by scripts/build_release*.sh, which export
                // QUOTADOG_MAC_SIGN=1 and CODESIGN_IDENTITY (same Developer ID as Saytive).
                // CI / local unsigned packages leave those unset and stay unsigned.
                signing {
                    val providers = project.providers
                    val identityProvider = providers.environmentVariable("CODESIGN_IDENTITY")
                        .orElse(providers.gradleProperty("compose.desktop.mac.signing.identity"))
                        .orElse("")
                    val signRequested = providers.environmentVariable("QUOTADOG_MAC_SIGN")
                        .map { it == "1" || it.equals("true", ignoreCase = true) }
                        .orElse(
                            providers.gradleProperty("compose.desktop.mac.sign")
                                .map { it == "true" }
                                .orElse(false),
                        )
                    sign.set(signRequested.zip(identityProvider) { requested, identity ->
                        requested && identity.isNotBlank()
                    })
                    identity.set(identityProvider)
                }
            }
        }
    }
}
