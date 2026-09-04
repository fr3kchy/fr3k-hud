package com.mcpintelligence.fr3k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Build metadata contract for FR3K HUD.
 *
 * Locks the runtime version metadata and the artefact naming convention so
 * the artefact on disk matches the metadata baked into the APK. A mismatch
 * is a release-process bug, not a documentation bug.
 *
 * Baseline values track the current shipped artefact (0.4.15). When the
 * version is bumped, bump these constants in the same commit.
 */
class BuildMetadataContractTest {

    // Baseline version this test pins. Bump both this constant and
    // app/build.gradle.kts versionName/versionCode in the same commit.
    private val baselineVersionName = "0.4.15"
    private val baselineVersionCode = 415

    private val projectRoot: File by lazy {
        // unitTest runs from <repo>/app when invoked as :app:testDebugUnitTest
        val candidate = File(System.getProperty("user.dir") ?: ".")
        if (candidate.name == "app") candidate.parentFile!! else candidate
    }

    private val appGradle: File get() = File(projectRoot, "app/build.gradle.kts")
    private val buildScript: File get() = File(projectRoot, "build.sh")

    @Test fun versionNameMatchesBaseline() {
        val versionName = readVersionName(appGradle)
        assertEquals(
            "versionName must equal the baseline " + baselineVersionName +
                " — bump both this constant and app/build.gradle.kts in the same commit",
            baselineVersionName,
            versionName,
        )
    }

    @Test fun versionCodeIsAtLeastBaseline() {
        val versionCode = readVersionCode(appGradle)
        assertTrue(
            "versionCode must be >= " + baselineVersionCode +
                " (baseline " + baselineVersionName + ") but was " + versionCode,
            versionCode >= baselineVersionCode,
        )
    }

    @Test fun versionNameFollowsSemverLikePattern() {
        val versionName = readVersionName(appGradle)
        val pattern = Regex("^\\d+\\.\\d+\\.\\d+$")
        assertTrue(
            "versionName must look like MAJOR.MINOR.PATCH (was '" + versionName + "')",
            pattern.matches(versionName),
        )
    }

    @Test fun buildScriptCopiesArtifactUsingVersionName() {
        val script = readRequired(buildScript)
        // The script must copy app-debug.apk into an artifacts/ directory
        // and embed a version-derived suffix in the destination filename.
        // Allow either a literal "artifacts/...FR3K-HUD-*.apk" or an indirect
        // "$ARTIFACT_DIR/FR3K-HUD-${VERSION_NAME}.apk" construction.
        val literal = Regex("artifacts/.*FR3K-HUD.*\\.apk").containsMatchIn(script)
        val indirect = script.contains("FR3K-HUD") &&
            Regex("\\$\\{?VERSION_NAME\\}?").containsMatchIn(script) &&
            script.contains("artifacts")
        assertTrue(
            "build.sh must cp the built APK into artifacts/ with a " +
                "FR3K-HUD-*.apk name derived from versionName (currently " +
                "artefacts keep the fixed debug name)",
            literal || indirect,
        )
    }

    @Test fun buildScriptDoesNotHardcodeLiteralVersionString() {
        val script = readRequired(buildScript)
        // If "0.4.15" is typed literally the script will silently drift
        // every release. The artefact must derive from app/build.gradle.kts.
        val hasLiteral = script.contains("\"0.4.15\"") || script.contains("'0.4.15'")
        assertEquals(
            "build.sh must not hardcode '" + baselineVersionName +
                "' — derive the artefact name from the versionName in app/build.gradle.kts",
            false,
            hasLiteral,
        )
    }

    @Test fun buildScriptDerivesArtifactNameFromGradleVersion() {
        val script = readRequired(buildScript)
        // Must reference the gradle version value (or grep it out of the file).
        val derives = script.contains("versionName") ||
            script.contains("version_code") ||
            (script.contains("grep") && script.contains("build.gradle.kts"))
        assertTrue(
            "build.sh must derive the artefact name from app/build.gradle.kts " +
                "versionName/versionCode, not from a typed literal",
            derives,
        )
    }

    @Test fun buildScriptPrintsArtifactSha256() {
        val script = readRequired(buildScript)
        assertTrue(
            "build.sh must print a SHA-256 of the produced APK so releases " +
                "can be verified by hash",
            script.contains("sha256sum") || script.contains("sha256"),
        )
    }

    @Test fun buildScriptCopiesToArtifactsDirectory() {
        val script = readRequired(buildScript)
        assertTrue(
            "build.sh must create / write into an artifacts/ directory",
            script.contains("artifacts/"),
        )
    }

    // ---------- helpers ----------

    private fun readVersionName(file: File): String {
        val text = readRequired(file)
        val match = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(text)
            ?: error("versionName not found in " + file.absolutePath)
        return match.groupValues[1]
    }

    private fun readVersionCode(file: File): Int {
        val text = readRequired(file)
        val match = Regex("versionCode\\s*=\\s*(\\d+)").find(text)
            ?: error("versionCode not found in " + file.absolutePath)
        return match.groupValues[1].toInt()
    }

    private fun readRequired(file: File): String {
        assertTrue("missing file: " + file.absolutePath, file.exists())
        return file.readText()
    }
}