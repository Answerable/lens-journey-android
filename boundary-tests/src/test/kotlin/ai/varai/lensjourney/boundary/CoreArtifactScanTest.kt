package ai.varai.lensjourney.boundary

import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scans the BUILT core jar (every class, every string constant, every entry name) for debug wire names and the debug provider. The
 * compiler tests prove an app cannot name them. This proves they are not in the artifact at all, so no Java caller, reflection or
 * deserialisation can reach code that does not exist. The debug artifact's jar is scanned as the positive control: a scan that finds
 * nothing anywhere would prove nothing.
 */
class CoreArtifactScanTest {
    private val coreJar = jarFor("lens-journey-android-")
    private val debugJar = jarFor("debug-attestation-")

    private fun jarFor(prefix: String): File =
        CompilerHarness.coreAndDebugClasspath.split(File.pathSeparator).map(::File)
            .single { it.name.startsWith(prefix) && it.name.endsWith(".jar") && !it.name.contains("-sources") && !it.name.contains("-javadoc") }

    /** Every entry as (name, bytes). */
    private fun entries(jar: File): List<Pair<String, ByteArray>> = JarFile(jar).use { jf ->
        jf.entries().asSequence().filter { !it.isDirectory }.map { it.name to jf.getInputStream(it).readBytes() }.toList()
    }

    /** The printable-ASCII runs of an entry that contain [needle], case-insensitively. Class-file string constants are such runs. */
    private fun runsContaining(bytes: ByteArray, needle: String): List<String> =
        Regex("[\\x20-\\x7e]*" + needle.map { "[${it.lowercaseChar()}${it.uppercaseChar()}]" }.joinToString("") + "[\\x20-\\x7e]*")
            .findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value }.toList()

    // The only places the letters "debug" appear in the core, none of them about attestation: the https check's message
    // ("allowInsecureHttp = true is for local debugging only") and three names the Kotlin compiler stamps into classes
    // (SourceDebugExtension and KotlinDebug for inline-function line tables, DebugMetadata for suspend functions). A run is an offender
    // when "debug" is still in it after those exact tokens are removed, so the scan cannot be fooled by hiding a wire name next to one.
    private val allowedDebugRun = "local debugging only"
    private val compilerArtifacts = listOf("SourceDebugExtension", "KotlinDebug", "DebugMetadata", allowedDebugRun)

    private fun debugRuns(bytes: ByteArray): List<String> =
        runsContaining(bytes, "debug").filter { run -> compilerArtifacts.fold(run) { acc, token -> acc.replace(token, "") }.contains("debug", ignoreCase = true) }

    @Test
    fun `the core jar has no string, class, member or entry name that mentions debug except the https message`() {
        val offenders = mutableListOf<String>()
        for ((name, bytes) in entries(coreJar)) {
            if (name.contains("debug", ignoreCase = true)) offenders += "entry name: $name"
            for (run in debugRuns(bytes)) offenders += "$name: $run"
        }
        assertEquals(emptyList<String>(), offenders, "the core artifact must hold nothing that names debug attestation")
    }

    @Test
    fun `the core jar has no restricted payload, origin guard or friend-module marker`() {
        val offenders = mutableListOf<String>()
        for ((name, bytes) in entries(coreJar)) {
            for (needle in listOf("Restricted", "ATTESTATION_PAYLOAD_NOT_ALLOWED", "lens-journey-debug-attestation", "DebugAttestation")) {
                runsContaining(bytes, needle).forEach { offenders += "$name [$needle]: $it" }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the core jar writes exactly the ios and android bindings on install-token`() {
        val transport = entries(coreJar).single { it.first == "ai/varai/lensjourney/MobileKeyTransport.class" }.second
        val text = String(transport, Charsets.ISO_8859_1)
        assertTrue(text.contains("integrity_token") && text.contains("key_id") && text.contains("attestation_object"), "the scan reads the real transport class")
        // The wire fields the core writes next to install_id/platform/nonce: no third binding.
        assertEquals(emptyList<String>(), debugRuns(transport))
    }

    @Test
    fun `control - the scan does find the debug wire field and provider in the debug artifact`() {
        val found = entries(debugJar).flatMap { (name, bytes) -> debugRuns(bytes).map { "$name: $it" } }
        assertTrue(found.any { it.endsWith("debug") || it.contains("DebugAttestation") }, "the scanner must see the debug wire field in the debug artifact: $found")
        assertTrue(entries(debugJar).any { it.first.startsWith("ai/varai/lensjourney/debug/") })
    }

    @Test
    fun `control - the scan reads the core classes, it does see the allowed https message`() {
        val seen = entries(coreJar).flatMap { runsContaining(it.second, "debug") }
        assertTrue(seen.any { it.contains(allowedDebugRun) }, "the scanner must read string constants in the core jar: $seen")
    }

    @Test
    fun `the core sources have no debug payload, provider or wire field`() {
        val root = File(requireNotNull(System.getProperty("boundary.coreSourceDir")))
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                Regex("\"debug\"|DebugAttestation|AttestationPayload\\.Debug|Restricted|DEBUG_ATTESTATION").containsMatchIn(line)
            }.map { "${file.name}:${it.index + 1}: ${it.value.trim()}" }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
