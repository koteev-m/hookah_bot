package com.hookah.platform.backend.miniapp.venue.media.pdf

import java.nio.file.Path

/** Fixed-path diagnostic client; never parses PDF and has no container-management capability. */
object PdfClientMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && args[0] in setOf("validate", "ping"))
        val bytes =
            if (args[0] == "validate") {
                System.`in`.readNBytes(
                    PdfWorkerProtocol.MAX_BYTES + 1,
                )
            } else {
                byteArrayOf()
            }
        val result =
            try {
                if (bytes.size > PdfWorkerProtocol.MAX_BYTES) {
                    1
                } else {
                    PdfWorkerProtocol.request(
                        Path.of(PdfWorkerProtocol.SOCKET_PATH),
                        if (args[0] == "ping") 0 else 1,
                        bytes,
                    )
                }
            } catch (_: Exception) {
                2
            }
        println("PDF_RESULT=$result")
    }
}
