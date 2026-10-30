package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.api.VenueInfoMediaInvalidException
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VenueInfoMediaPdfSecurityTest {
    @Test fun `dangerous PDF admission runs in a disposable heap and wall time bounded JVM`() {
        val loaderPaths =
            generateSequence(javaClass.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
                .map { Path.of(it.toURI()).toString() }.toList()
        val classpath =
            (loaderPaths + System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .distinct().joinToString(java.io.File.pathSeparator)
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m",
                "-Xss512k",
                "-XX:+UseSerialGC",
                "-cp",
                classpath,
                VenueInfoPdfSecurityProbe::class.java.name,
            ).redirectErrorStream(true)
                .also {
                    it.environment().remove("_JAVA_OPTIONS")
                    it.environment().remove("JAVA_TOOL_OPTIONS")
                    it.environment().remove("JDK_JAVA_OPTIONS")
                }.start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Disposable PDF check exceeded hard wall deadline")
            assertEquals(
                0,
                process.exitValue(),
                "Disposable PDF regression: " + String(process.inputStream.readNBytes(4096)),
            )
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "Disposable PDF process was not reaped")
        }
    }
}

object VenueInfoPdfSecurityProbe {
    @JvmStatic fun main(args: Array<String>) {
        // Never construct the vulnerable parser with attack input inside the Gradle test JVM.
        for (white in listOf(" ", "\t", "\n", "\r", "\r\n", "\u0000", "\u000c")) {
            reject(pdf("0${white}1000000000"))
        }
        reject(pdf("19999 2"))
        reject(pdf("0 2147483647"))
        reject(pdf("0 9223372036854775808"))
        reject(pdf("0 1", "/Size +1000000000"))
        reject(pdf("0 1", "/Size 1 /Prev 9"))
        reject(pdf("0 1", "/Size 1 /Pr#65v 9"))
        reject(pdf("0 1", "/Size 1 /XRefStm 9"))
        reject(pdf("0 1", "/Size 1 /A " + "[".repeat(10000) + "]".repeat(10000)))
        reject(pdf("0 1", "/Size 1 /A [" + "null ".repeat(100001) + "]"))
        VenueInfoPdfPreflight.check(syntheticPdf())
        try {
            VenueInfoMediaValidation.validate(syntheticPdf(), "application/pdf")
            error("Absent worker must fail closed")
        } catch (_: com.hookah.platform.backend.api.VenueInfoMediaUnavailableException) {
        }
    }

    private fun reject(bytes: ByteArray) {
        try {
            VenueInfoMediaValidation.validate(bytes, "application/pdf")
        } catch (_: VenueInfoMediaInvalidException) {
            return
        }
        error("Unsafe PDF was admitted")
    }

    private fun pdf(
        header: String,
        dictionary: String = "/Size 1",
    ): ByteArray {
        val prefix = "%PDF-1.4\n"
        return (
            prefix + "xref\n$header\n0000000000 65535 f \ntrailer\n<<$dictionary>>\n" +
                "startxref\n${prefix.length}\n%%EOF\n"
        ).toByteArray(Charsets.ISO_8859_1)
    }
}
