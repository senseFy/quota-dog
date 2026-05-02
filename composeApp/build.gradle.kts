import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
}

// App version is read from env vars so CI release jobs can inject the tag-derived
// version. Local builds fall back to `1.0.0` because Compose Desktop's installer
// formats reject MAJOR=0; that constraint also rules out "*-dev" suffixes here.
val releaseVersionName: String =
    System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() } ?: "1.0.0"
val releaseVersionCode: Int =
    System.getenv("RELEASE_VERSION_CODE")?.toIntOrNull() ?: 1

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
            dependencies {
                implementation(compose.desktop.currentOs)
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

compose.desktop {
    application {
        mainClass = "saien.quotadog.MainKt"

        nativeDistributions {
            // Dmg = macOS, Msi = Windows installer, Deb = Debian/Ubuntu Linux.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "QuotaDog"
            // Compose Desktop requires strict semver `X.Y.Z`; strip any suffix
            // such as "-dev" or "-rc.1" so local dev versions still package.
            packageVersion = releaseVersionName.substringBefore('-').let { stripped ->
                if (stripped.matches(Regex("\\d+\\.\\d+\\.\\d+"))) stripped else "1.0.0"
            }
        }
    }
}
