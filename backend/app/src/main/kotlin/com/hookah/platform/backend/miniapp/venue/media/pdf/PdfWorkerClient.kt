package com.hookah.platform.backend.miniapp.venue.media.pdf

import com.hookah.platform.backend.api.VenueInfoMediaInvalidException
import com.hookah.platform.backend.api.VenueInfoMediaUnavailableException
import java.nio.file.Path

internal class PdfWorkerClient(
    private val enabled: Boolean = System.getenv("VENUE_INFO_PDF_WORKER_ENABLED") == "true",
    private val socket: Path = Path.of(PdfWorkerProtocol.SOCKET_PATH),
) {
    fun validate(bytes: ByteArray) {
        if (!enabled) throw unavailable()
        val result =
            try {
                PdfWorkerProtocol.request(socket, 1, bytes)
            } catch (_: Exception) {
                throw unavailable()
            }
        when (result) {
            0 -> Unit
            1 -> throw VenueInfoMediaInvalidException("PDF не поддерживается. Экспортируйте обычный плоский PDF.")
            else -> throw unavailable()
        }
    }

    private fun unavailable() =
        VenueInfoMediaUnavailableException(
            "Проверка PDF временно недоступна. Прежние вложения сохранены. " +
                "Попробуйте позже или загрузите JPEG/PNG.",
        )
}
