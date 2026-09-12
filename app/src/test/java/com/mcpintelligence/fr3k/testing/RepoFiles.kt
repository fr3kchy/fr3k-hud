package com.mcpintelligence.fr3k.testing

import java.io.File

/**
 * Portable repository-root resolution for the source-lint JVM tests.
 *
 * Several `:app` unit tests assert on repository sources as *text*
 * (no-blocking-UI contract, Shizuku wiring, Termux bridge contract). Those tests
 * used to read `/home/parrot/repos/fr3k-hud/...`, so they could only ever pass on
 * the single machine that wrote them and failed on any clean checkout.
 *
 * Resolution order:
 *   1. `FR3K_REPO_ROOT`, when set to an existing directory;
 *   2. walk up from the Gradle test working directory (the module directory)
 *      until `settings.gradle.kts` is found.
 *
 * Never reintroduce an absolute host path here.
 */
object RepoFiles {

    val root: File by lazy { locateRoot() }

    fun read(relativePath: String): String {
        val file = File(root, relativePath)
        if (!file.isFile) {
            error("missing file: $relativePath (repo root resolved to ${root.absolutePath})")
        }
        return file.readText()
    }

    private fun locateRoot(): File {
        val fromEnv = System.getenv("FR3K_REPO_ROOT")
        if (!fromEnv.isNullOrBlank()) {
            val env = File(fromEnv)
            if (env.isDirectory) return env.absoluteFile
        }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error(
            "could not locate the repository root (settings.gradle.kts) from " +
                System.getProperty("user.dir"),
        )
    }
}
