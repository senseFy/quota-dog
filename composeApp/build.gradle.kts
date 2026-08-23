import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
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
    android {
        namespace = "saien.quotadog.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        androidResources {
            enable = true
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
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
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui)
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

tasks.named("desktopProcessResources") {
    dependsOn(compileMacStatusBar)
}

compose.desktop {
    application {
        mainClass = "saien.quotadog.MainKt"
        jvmArgs += "-Xdock:icon=${project.file("icons/QuotaDog.png").absolutePath}"

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

            windows {
                iconFile.set(project.file("icons/QuotaDog.ico"))
            }
            linux {
                iconFile.set(project.file("icons/QuotaDog.png"))
            }

            macOS {
                bundleID = "saien.quotadog"
                iconFile.set(project.file("icons/QuotaDog.icns"))
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
