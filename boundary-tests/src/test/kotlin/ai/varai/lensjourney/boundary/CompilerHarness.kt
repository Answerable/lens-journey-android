package ai.varai.lensjourney.boundary

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/** The outcome of compiling one Kotlin source file with the real Kotlin compiler. */
class CompileResult(val exitCode: ExitCode, val output: String) {
    val compiled get() = exitCode == ExitCode.OK
}

/**
 * Compiles a source snippet the way an app's build would, against an explicit classpath, and returns what the compiler said. This is
 * the boundary check: whether a line of app code is accepted is decided by the compiler, not by a mock.
 */
object CompilerHarness {
    val coreOnlyClasspath: String = requireNotNull(System.getProperty("boundary.coreOnlyClasspath")) { "boundary.coreOnlyClasspath is not set" }
    val coreAndDebugClasspath: String = requireNotNull(System.getProperty("boundary.coreAndDebugClasspath")) { "boundary.coreAndDebugClasspath is not set" }

    fun compile(source: String, classpath: String): CompileResult {
        val dir = Files.createTempDirectory("boundary-snippet").toFile()
        try {
            val file = File(dir, "Snippet.kt").apply { writeText(source) }
            val out = File(dir, "out").apply { mkdirs() }
            val captured = ByteArrayOutputStream()
            val exit = K2JVMCompiler().exec(
                PrintStream(captured, true, "UTF-8"),
                "-d", out.absolutePath,
                "-cp", classpath,
                "-no-stdlib", "-no-reflect", "-nowarn",
                "-jvm-target", "17",
                file.absolutePath,
            )
            return CompileResult(exit, captured.toString("UTF-8"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
