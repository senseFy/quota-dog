import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.language.jvm.tasks.ProcessResources
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

val updaterProperties = Properties().apply {
    rootProject.file("updater.properties").inputStream().use { load(it) }
}
val hostSyncVariant = rootProject.extra["hostSyncVariant"] as String
val acceptanceDirectory = providers.gradleProperty("quotadog.acceptanceDirectory").orNull
val updateSequence = (if (acceptanceDirectory != null) {
    providers.gradleProperty("quotadog.acceptanceSequence").get()
} else {
    versionProperties.getProperty("VERSION_CODE")
}).also { require(it.toLong() > 0) }
val desktopAppId = if (acceptanceDirectory != null) "saien.quotadog.acceptance" else "saien.quotadog"
val desktopBuildProperties = layout.buildDirectory.dir("generated/desktopBuildProperties")
val desktopMetadata = updaterProperties.stringPropertyNames().associateWith(updaterProperties::getProperty) + mapOf(
    "appId" to desktopAppId,
    "version" to releaseVersionName,
    "sequence" to updateSequence,
    "channel" to (providers.gradleProperty("quotadog.acceptanceChannel").orNull
        ?.takeIf { acceptanceDirectory != null } ?: "stable"),
    "acceptanceDirectory" to acceptanceDirectory.orEmpty(),
)
val generateDesktopBuildProperties by tasks.registering {
    inputs.properties(desktopMetadata)
    outputs.dir(desktopBuildProperties)
    doLast {
        val output = desktopBuildProperties.get().file("quotadog-build.properties").asFile
        output.parentFile.mkdirs()
        output.writeText(desktopMetadata.toSortedMap().entries.joinToString("\n", postfix = "\n") { (key, value) ->
            "$key=${value.replace("\\", "\\\\")}"
        })
    }
}

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
        "-framework",
        "QuartzCore",
        "-o",
        output.get().asFile.absolutePath,
        source.asFile.absolutePath,
    )
}

kotlin {
    jvmToolchain(21)

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
            jvmTarget.set(JvmTarget.JVM_21)
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
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        val desktopMain by getting {
            resources.srcDir(macStatusBarResourcesDir)
            resources.srcDir(desktopBuildProperties)
            if (hostSyncVariant.isNotEmpty()) {
                kotlin.srcDir("src/hostSyncDesktop/kotlin")
                dependencies {
                    implementation(libs.multiplatform.settings.test)
                }
            }
            dependencies {
                implementation(libs.ktor.client.java)
                implementation(libs.zxing.core)
                implementation(compose.desktop.currentOs)
                implementation(libs.jna)
                implementation(libs.kmp.updater.jvm)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        val desktopTest by getting {
            if (hostSyncVariant == "hostSyncLocal") kotlin.srcDir("src/hostSyncDesktopTest/kotlin")
            dependencies {
                implementation(libs.compose.ui.test.junit4)
            }
        }
    }
}

val hostSyncNative = rootProject.file("native/host-sync")
val buildMacHostTls by tasks.registering(Exec::class) {
    workingDir(hostSyncNative)
    commandLine("bash", "build-local.sh", "desktop")
    inputs.files(fileTree(hostSyncNative) { include("Cargo.*", "src/**", "build-local.sh") })
    inputs.property("architecture", System.getProperty("os.arch"))
    outputs.file(hostSyncNative.resolve("build/desktop/libquotadog_host_tls.dylib"))
}

tasks.named<ProcessResources>("desktopProcessResources") {
    dependsOn(compileMacStatusBar, generateDesktopBuildProperties)
    if (hostSyncVariant.isEmpty() && System.getProperty("os.name").startsWith("Mac")) {
        dependsOn(buildMacHostTls)
        from(buildMacHostTls) { into("macos") }
    }
}

if (hostSyncVariant.isNotEmpty()) {
    val rejectPilotDistribution = tasks.register("rejectPilotDistribution") {
        doLast { error("The host-sync pilot is for :composeApp:run only. Remove the test flag to package QuotaDog.") }
    }
    tasks.matching { it.name.endsWith("Distributable") || it.name.endsWith("UberJarForCurrentOS") }.configureEach {
        dependsOn(rejectPilotDistribution)
    }
}

compose.desktop {
    application {
        mainClass = when {
            hostSyncVariant.isNotEmpty() -> "saien.quotadog.hostsync.LocalHostKt"
            acceptanceDirectory != null -> "saien.quotadog.UpdaterAcceptanceKt"
            else -> "saien.quotadog.MainKt"
        }
        jvmArgs += "-Xdock:icon=${project.file("icons/QuotaDog.png").absolutePath}"

        buildTypes.release.proguard {
            // Restrict shrinking to unused Lucide icons; keep runtime-sensitive
            // application/framework code intact. No renaming or bytecode optimization.
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("proguard-desktop.pro"))
        }

        nativeDistributions {
            // Dmg = macOS, Msi = Windows installer, Deb = Debian/Ubuntu Linux.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = if (acceptanceDirectory == null) "QuotaDog" else "QuotaDog Acceptance"
            // The desktop app uses the Ktor Java engine (`java.net.http`) and a local OAuth
            // callback server (`jdk.httpserver`). jlink does not infer these reliably from the
            // packaged classpath, so include them explicitly in the runtime image.
            modules("java.net.http", "jdk.httpserver", "jdk.crypto.ec", "jdk.unsupported", "java.management", "java.naming")
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
                bundleID = desktopAppId
                minimumSystemVersion = "13.0"
                infoPlist {
                    extraKeysRawXml = "<key>KMPUpdaterRelease</key><string>$updateSequence</string>"
                }
                iconFile.set(project.file("icons/QuotaDog.icns"))
                // Direct Gradle signing remains optional. Release scripts sign after
                // embedding the updater helper and signing native libraries inside JARs.
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
