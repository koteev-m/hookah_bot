package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.api.VenueInfoMediaUnavailableException
import com.hookah.platform.backend.miniapp.venue.media.pdf.PdfWorkerClient
import com.hookah.platform.backend.miniapp.venue.media.pdf.PdfWorkerProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VenueInfoPdfClientTest {
    @Test fun `absent disabled or malformed worker never falls back to local parsing`() {
        val bytes = syntheticPdf()
        assertFailsWith<VenueInfoMediaUnavailableException> { PdfWorkerClient(false).validate(bytes) }
        assertFailsWith<VenueInfoMediaUnavailableException> {
            PdfWorkerClient(true, Path.of("/private/tmp/ht-pdf-no-such-socket")).validate(bytes)
        }
        withServer { socket ->
            val server =
                thread {
                    socket.accept().use { channel ->
                        val bytesIn = ByteBuffer.allocate(11)
                        while (bytesIn.hasRemaining()) channel.read(bytesIn)
                        channel.write(ByteBuffer.wrap(byteArrayOf(1, 2)))
                    }
                }
            assertFailsWith<Exception> { PdfWorkerProtocol.request(path(socket), 0, byteArrayOf(), 500) }
            server.join(2000)
            assertTrue(!server.isAlive)
        }
    }

    @Test fun `stall deadline closes IPC and a later connection can succeed`() =
        withServer { socket ->
            val server =
                thread {
                    socket.accept().use { Thread.sleep(200) }
                    socket.accept().use { channel ->
                        val request = ByteBuffer.allocate(11)
                        while (request.hasRemaining()) channel.read(request)
                        channel.write(ByteBuffer.wrap(PdfWorkerProtocol.magic + 0.toByte()))
                    }
                }
            assertFailsWith<Exception> { PdfWorkerProtocol.request(path(socket), 0, byteArrayOf(), 100) }
            assertEquals(0, PdfWorkerProtocol.request(path(socket), 0, byteArrayOf(), 1000))
            server.join(2000)
            assertTrue(!server.isAlive)
        }

    @Test fun `pending failed PDF does not consume JPEG PNG upload slots`() =
        runBlocking {
            MediaFixture(com.hookah.platform.backend.miniapp.venue.staff.photoTestDataSource(false)).use { f ->
                f.init()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val service =
                    VenueInfoMediaService(f.repository, f.sections, f.storage) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        throw VenueInfoMediaUnavailableException()
                    }
                val pdf =
                    async(Dispatchers.IO) {
                        assertFailsWith<VenueInfoMediaUnavailableException> {
                            service.upload(
                                f.venue,
                                f.section,
                                101,
                                0,
                                java.util.UUID.randomUUID().toString(),
                                "application/pdf",
                                null,
                            ) { syntheticPdf() }
                        }
                    }
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
                    service.upload(
                        f.venue,
                        f.section,
                        101,
                        0,
                        java.util.UUID.randomUUID().toString(),
                        "image/png",
                        null,
                    ) { com.hookah.platform.backend.miniapp.venue.staff.syntheticPhoto() }
                    assertEquals(1, f.current().media.size)
                } finally {
                    release.countDown()
                }
                pdf.await()
                // A failed worker wait must release the PDF slot for a subsequent request.
                assertFailsWith<VenueInfoMediaUnavailableException> {
                    service.upload(
                        f.venue,
                        f.section,
                        101,
                        f.current().revision,
                        java.util.UUID.randomUUID().toString(),
                        "application/pdf",
                        null,
                    ) { syntheticPdf() }
                }
                assertEquals(1, f.current().media.size)
            }
        }

    private fun path(server: ServerSocketChannel): Path = (server.localAddress as UnixDomainSocketAddress).path

    private fun withServer(block: (ServerSocketChannel) -> Unit) {
        val dir =
            Files.createTempDirectory(
                Path.of(if (System.getProperty("os.name") == "Mac OS X") "/private/tmp" else "/tmp"),
                "htpdf-",
            )
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(dir.resolve("socket")))
                block(server)
            }
        } finally {
            Files.deleteIfExists(dir.resolve("socket"))
            Files.deleteIfExists(dir)
        }
    }
}
