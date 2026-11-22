import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

val hostSyncVariant = rootProject.extra["hostSyncVariant"] as String
val hostSyncNative = rootProject.file("native/host-sync")

kotlin {
    jvmToolchain(21)

    android {
        namespace = "saien.quotadog.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
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
        val slice = target.name
        val out = hostSyncNative.resolve("build/$slice")
        val buildTls = tasks.register<Exec>("buildHostTls${slice.replaceFirstChar { it.uppercase() }}") {
            workingDir(hostSyncNative)
            commandLine("bash", "build-local.sh", slice)
            inputs.files(fileTree(hostSyncNative) { include("Cargo.*", "src/**", "include/**", "build-local.sh") })
            outputs.file(out.resolve("libquotadog_host_tls.a"))
        }
        val interop = target.compilations.getByName("main").cinterops.create("hostTls") {
            includeDirs(hostSyncNative.resolve("include"))
            extraOpts("-libraryPath", out.absolutePath, "-staticLibrary", "libquotadog_host_tls.a")
        }
        tasks.named(interop.interopProcessingTaskName) {
            dependsOn(buildTls)
            inputs.file(out.resolve("libquotadog_host_tls.a"))
        }
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            api(libs.multiplatform.settings)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.multiplatform.settings.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.java)
            }
        }
        val relay = hostSyncVariant.ifEmpty { "hostSyncProduction" }
        commonMain.get().kotlin.srcDir("src/$relay/kotlin")
        listOf(androidMain.get(), desktopMain).forEach {
            it.kotlin.srcDir("src/hostSyncJvm/kotlin")
        }
        if (hostSyncVariant == "hostSyncLocal") getByName("desktopTest").kotlin.srcDir("src/hostSyncTest/kotlin")
    }
}
