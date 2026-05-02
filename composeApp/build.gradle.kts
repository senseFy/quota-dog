import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
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
        versionCode = 1
        versionName = "1.0.0"
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
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "QuotaDog"
            packageVersion = "1.0.0"
        }
    }
}
