package com.hookah.platform.backend.miniapp.venue.media.pdf

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Path

internal object PdfWorkerProtocol {
    val magic = byteArrayOf(72, 84, 80, 68, 70, 1)
    const val SOCKET_PATH = "/run/hookah-pdf/worker.sock"
    const val MAX_BYTES = 10 * 1024 * 1024
    const val PARSE_MILLIS = 5000L
    const val CLIENT_MILLIS = 9000L

    fun transfer(
        channel: SocketChannel,
        buffer: ByteBuffer,
        writing: Boolean,
        deadline: Long,
    ) {
        Selector.open().use { selector ->
            channel.register(selector, if (writing) SelectionKey.OP_WRITE else SelectionKey.OP_READ)
            while (buffer.hasRemaining()) {
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted) {
                    throw IOException(
                        "PDF_IPC_TIMEOUT",
                    )
                }
                val originalLimit = buffer.limit()
                // JDK heap-buffer I/O allocates a temporary direct buffer sized by remaining().
                buffer.limit(minOf(originalLimit, buffer.position() + 8192))
                val n =
                    try {
                        if (writing) channel.write(buffer) else channel.read(buffer)
                    } finally {
                        buffer.limit(originalLimit)
                    }
                if (n < 0) throw IOException("PDF_IPC_TRUNCATED")
                if (n == 0) {
                    selector.select(50)
                    selector.selectedKeys().clear()
                }
            }
        }
    }

    fun request(
        path: Path,
        operation: Byte,
        bytes: ByteArray,
        timeoutMillis: Long = CLIENT_MILLIS,
    ): Int {
        require(timeoutMillis in 1..CLIENT_MILLIS)
        require(bytes.size <= MAX_BYTES)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            if (!channel.connect(UnixDomainSocketAddress.of(path))) {
                Selector.open().use { selector ->
                    channel.register(selector, SelectionKey.OP_CONNECT)
                    while (!channel.finishConnect()) {
                        if (System.nanoTime() >= deadline) throw IOException("PDF_IPC_TIMEOUT")
                        selector.select(50)
                        selector.selectedKeys().clear()
                    }
                }
            }
            val header = ByteBuffer.allocate(11).put(magic).put(operation).putInt(bytes.size).flip()
            transfer(channel, header, true, deadline)
            transfer(channel, ByteBuffer.wrap(bytes), true, deadline)
            channel.shutdownOutput()
            val response = ByteBuffer.allocate(7)
            transfer(channel, response, false, deadline)
            if (!response.array().copyOfRange(0, 6).contentEquals(magic)) throw IOException("PDF_IPC_PROTOCOL")
            val result = response.array()[6].toInt()
            if (result !in 0..2) throw IOException("PDF_IPC_PROTOCOL")
            val extra = ByteBuffer.allocate(1)
            while (true) {
                val n = channel.read(extra)
                if (n == -1) return result
                if (n > 0 || System.nanoTime() >= deadline) throw IOException("PDF_IPC_PROTOCOL")
                Thread.sleep(5)
            }
        }
    }
}
