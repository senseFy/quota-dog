import org.gradle.api.GradleException
import java.nio.file.Files
import java.nio.file.LinkOption

val fullCommitPattern = Regex("^[0-9a-f]{40}$")
val repositoryRoot = rootProject.layout.projectDirectory.asFile
val canonicalRepositoryRoot = repositoryRoot.canonicalFile
val hasGitMetadata = Files.exists(
    rootProject.layout.projectDirectory.file(".git").asFile.toPath(),
    LinkOption.NOFOLLOW_LINKS,
)
val quotadogProductVersion = providers.fileContents(
    rootProject.layout.projectDirectory.file("version.properties"),
).asText.map { contents ->
    val values = contents.lineSequence()
        .filter { it.startsWith("VERSION_NAME=") }
        .map { it.substringAfter('=') }
        .toList()
    check(values.size == 1) {
        "version.properties must contain exactly one VERSION_NAME value."
    }
    values.single().also { version ->
        check(version.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$"))) {
            "Invalid QuotaDog product version: $version"
        }
    }
}.get()

val gitRootOutput = if (hasGitMetadata) {
    providers.exec {
        workingDir(repositoryRoot)
        listOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_COMMON_DIR",
        ).forEach(environment::remove)
        commandLine("git", "rev-parse", "--show-toplevel")
        isIgnoreExitValue = true
    }
} else {
    null
}
val gitCommitOutput = if (hasGitMetadata) {
    providers.exec {
        workingDir(repositoryRoot)
        listOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_COMMON_DIR",
        ).forEach(environment::remove)
        commandLine("git", "rev-parse", "--verify", "HEAD")
        isIgnoreExitValue = true
    }
} else {
    null
}
val gitStatusOutput = if (hasGitMetadata) {
    providers.exec {
        workingDir(repositoryRoot)
        listOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_COMMON_DIR",
        ).forEach(environment::remove)
        commandLine("git", "status", "--porcelain", "--untracked-files=normal")
        isIgnoreExitValue = true
    }
} else {
    null
}

fun normalizeDirty(value: String): Boolean = when (value.trim().lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> throw GradleException("QUOTADOG_SOURCE_DIRTY must be true or false.")
}

val quotadogBuildIdentity: Map<String, String> = run {
    val overrideCommit = providers.environmentVariable("QUOTADOG_SOURCE_COMMIT")
        .orNull
        ?.trim()
        ?.lowercase()
    val overrideDirty = providers.environmentVariable("QUOTADOG_SOURCE_DIRTY")
        .orNull
        ?.let(::normalizeDirty)

    val commit: String
    val dirty: Boolean
    if (hasGitMetadata) {
        check(gitRootOutput!!.result.get().exitValue == 0) {
            "Could not resolve the QuotaDog Git checkout."
        }
        val checkoutRoot = file(
            gitRootOutput.standardOutput.asText.get().trim(),
        ).canonicalFile
        check(checkoutRoot == canonicalRepositoryRoot) {
            "The QuotaDog source root does not match its Git checkout root."
        }
        check(gitCommitOutput!!.result.get().exitValue == 0) {
            "Could not resolve the checked-out source commit."
        }
        check(gitStatusOutput!!.result.get().exitValue == 0) {
            "Could not determine the checked-out source state."
        }

        val checkoutCommit = gitCommitOutput.standardOutput.asText.get().trim().lowercase()
        check(checkoutCommit.matches(fullCommitPattern)) {
            "The checked-out source commit is not a full 40-character SHA."
        }
        if (overrideCommit != null) {
            check(overrideCommit.matches(fullCommitPattern)) {
                "QUOTADOG_SOURCE_COMMIT must be a full 40-character hexadecimal commit SHA."
            }
            check(overrideCommit == checkoutCommit) {
                "QUOTADOG_SOURCE_COMMIT does not match the checked-out HEAD ($checkoutCommit)."
            }
        }

        val checkoutDirty = gitStatusOutput.standardOutput.asText.get().isNotBlank()
        if (overrideDirty != null) {
            check(overrideDirty == checkoutDirty) {
                "QUOTADOG_SOURCE_DIRTY does not match the checked-out source state ($checkoutDirty)."
            }
        }
        commit = checkoutCommit
        dirty = overrideDirty ?: checkoutDirty
    } else {
        commit = overrideCommit
            ?.takeIf { it.matches(fullCommitPattern) }
            ?: throw GradleException(
                "A full QUOTADOG_SOURCE_COMMIT is required when building outside a Git checkout.",
            )
        dirty = overrideDirty
            ?: throw GradleException(
                "QUOTADOG_SOURCE_DIRTY is required when building outside a Git checkout.",
            )
    }

    linkedMapOf(
        "commit" to commit,
        "short" to commit.take(12),
        "dirty" to dirty.toString(),
    )
}

rootProject.extensions.extraProperties["quotadogBuildIdentity"] = quotadogBuildIdentity
rootProject.extensions.extraProperties["quotadogProductVersion"] = quotadogProductVersion

tasks.register("printProductVersion") {
    group = "help"
    description = "Prints QuotaDog's shared product version."
    inputs.property("productVersion", quotadogProductVersion)
    doLast {
        println(inputs.properties.getValue("productVersion"))
    }
}

val serializedQuotaDogBuildIdentity =
    "commit=${quotadogBuildIdentity.getValue("commit")}\n" +
        "short=${quotadogBuildIdentity.getValue("short")}\n" +
        "dirty=${quotadogBuildIdentity.getValue("dirty")}"

tasks.register("printBuildIdentity") {
    group = "help"
    description = "Prints the source identity embedded in QuotaDog artifacts."
    inputs.property("buildIdentity", serializedQuotaDogBuildIdentity)
    doLast {
        println(inputs.properties.getValue("buildIdentity"))
    }
}

tasks.register("verifyReleaseBuildIdentity") {
    group = "verification"
    description = "Requires a clean, immutable source identity for release artifacts."
    inputs.property("commit", quotadogBuildIdentity.getValue("commit"))
    inputs.property("dirty", quotadogBuildIdentity.getValue("dirty"))
    doLast {
        val commit = inputs.properties.getValue("commit").toString()
        val dirty = inputs.properties.getValue("dirty").toString()
        check(commit.matches(Regex("^[0-9a-f]{40}$"))) {
            "Release artifacts require a full source commit."
        }
        check(dirty == "false") {
            "Release artifacts require a clean source tree."
        }
    }
}
