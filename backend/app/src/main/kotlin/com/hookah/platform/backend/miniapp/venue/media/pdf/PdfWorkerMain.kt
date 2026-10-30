package com.hookah.platform.backend.miniapp.venue.media.pdf

import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object PdfWorkerMain {
    @JvmStatic fun main(args: Array<String>) {
        PdfWorkerIsolation.requireEnforced()
        val socket = Path.of(PdfWorkerProtocol.SOCKET_PATH)
        if (args.contentEquals(arrayOf("probe"))) {
            check(PdfWorkerProtocol.request(socket, 0, byteArrayOf()) == 0)
            return
        }
        require(args.isEmpty())
        Files.deleteIfExists(socket)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket), 1)
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"))
            while (true) {
                server.accept().use { channel ->
                    channel.configureBlocking(false)
                    try {
                        PdfWorkerIsolation.requireEnforced()
                        val receiveDeadline = System.nanoTime() + 2_000_000_000
                        val header = ByteBuffer.allocate(11)
                        PdfWorkerProtocol.transfer(channel, header, false, receiveDeadline)
                        val data = header.array()
                        check(data.copyOfRange(0, 6).contentEquals(PdfWorkerProtocol.magic))
                        val op = data[6].toInt()
                        val length = ByteBuffer.wrap(data, 7, 4).int
                        check((op == 0 && length == 0) || (op == 1 && length in 1..PdfWorkerProtocol.MAX_BYTES))
                        val bytes = ByteArray(length)
                        PdfWorkerProtocol.transfer(channel, ByteBuffer.wrap(bytes), false, receiveDeadline)
                        val trailing = ByteBuffer.allocate(1)
                        while (true) {
                            val n = channel.read(trailing)
                            if (n == -1) break
                            check(n == 0 && System.nanoTime() < receiveDeadline)
                            Thread.sleep(5)
                        }
                        val result = if (op == 0) 0 else parse(bytes)
                        PdfWorkerProtocol.transfer(
                            channel,
                            ByteBuffer.wrap(PdfWorkerProtocol.magic + result.toByte()),
                            true,
                            System.nanoTime() + 1_000_000_000,
                        )
                    } catch (_: Exception) {
                        // No document content, provider detail or untrusted error text is logged.
                        System.err.println("PDF_WORKER_REQUEST_REJECTED")
                    }
                }
            }
        }
    }

    private fun parse(bytes: ByteArray): Int {
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx96m", "-Xss512k", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-XX:-UsePerfData",
                "-XX:-CreateCoredumpOnCrash", "-XX:-HeapDumpOnOutOfMemoryError", "-XX:ErrorFile=/dev/null",
                "-XX:MaxDirectMemorySize=8m", "-XX:MaxMetaspaceSize=64m", "-XX:ReservedCodeCacheSize=32m",
                "-cp", System.getProperty("java.class.path"), PdfParserMain::class.java.name,
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment().clear() }.start()
        val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "pdf-input").apply { isDaemon = true } }
        val started = System.nanoTime()
        try {
            val sent =
                writer.submit {
                    DataOutputStream(process.outputStream).use {
                        it.writeInt(bytes.size)
                        it.write(bytes)
                    }
                }
            if (!process.waitFor(PdfWorkerProtocol.PARSE_MILLIS, TimeUnit.MILLISECONDS)) return 2
            sent.get(100, TimeUnit.MILLISECONDS)
            return when (process.exitValue()) {
                0 -> 0
                2 -> 1
                else -> 2
            }
        } finally {
            process.destroyForcibly()
            if (!process.waitFor(2, TimeUnit.SECONDS)) Runtime.getRuntime().halt(70)
            process.outputStream.close()
            writer.shutdownNow()
            if (!writer.awaitTermination(1, TimeUnit.SECONDS)) Runtime.getRuntime().halt(70)
            System.err.println("PDF_WORKER_PARSE_FINISHED elapsed_ms=" + (System.nanoTime() - started) / 1_000_000)
        }
    }
}
