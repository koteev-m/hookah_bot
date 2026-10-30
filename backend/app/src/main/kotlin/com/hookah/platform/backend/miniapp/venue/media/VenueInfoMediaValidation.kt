package com.hookah.platform.backend.miniapp.venue.media

import com.drew.imaging.jpeg.JpegMetadataReader
import com.drew.lang.ByteArrayReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifReader
import com.hookah.platform.backend.api.VenueInfoMediaInvalidException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

const val VENUE_INFO_MEDIA_MAX_BYTES = 10 * 1024 * 1024

internal fun mediaDigest(bytes: ByteArray): String =
    MessageDigest.getInstance(
        "SHA-256",
    ).digest(bytes).joinToString("") {
        "%02x".format(it)
    }

data class ValidatedVenueInfoMedia(val bytes: ByteArray, val mime: String) {
    val type get() = if (mime == "application/pdf") "pdf" else "image"
    val checksum get() = mediaDigest(bytes)
}

object VenueInfoMediaValidation {
    fun validate(
        bytes: ByteArray,
        declared: String?,
        pdfValidator: (
            ByteArray,
        ) -> Unit = com.hookah.platform.backend.miniapp.venue.media.pdf.PdfWorkerClient()::validate,
    ): ValidatedVenueInfoMedia {
        if (bytes.isEmpty() || bytes.size > VENUE_INFO_MEDIA_MAX_BYTES) {
            throw VenueInfoMediaInvalidException(
                "Файл должен быть не больше 10 МБ.",
            )
        }
        val mime =
            when {
                bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray()) -> "application/pdf"
                bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
                bytes.size > 4 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() -> "image/jpeg"
                else -> throw VenueInfoMediaInvalidException()
            }
        if (declared?.substringBefore(';')?.lowercase() !in setOf(null, mime, "application/octet-stream")) {
            throw VenueInfoMediaInvalidException()
        }
        try {
            if (mime == "application/pdf") {
                VenueInfoPdfPreflight.check(bytes)
                pdfValidator(bytes)
                return ValidatedVenueInfoMedia(bytes, mime)
            }
            val pngExif = if (mime == "image/png") validatePng(bytes) else null
            if (
                mime == "image/jpeg" &&
                (
                    bytes[bytes.size - 2] != (-1).toByte() ||
                        bytes.last() != (-39).toByte()
                )
            ) {
                throw VenueInfoMediaInvalidException()
            }
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) throw VenueInfoMediaInvalidException()
                val reader = readers.next()
                try {
                    reader.setInput(input, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 32_000_000) {
                        throw VenueInfoMediaInvalidException(
                            "Максимум 8192 пикселя по стороне и 32 Мп.",
                        )
                    }
                    var warning = false
                    reader.addIIOReadWarningListener { _, _ -> warning = true }
                    val decoded = reader.read(0) ?: throw VenueInfoMediaInvalidException()
                    try {
                        if (warning) throw VenueInfoMediaInvalidException()
                        val metadata =
                            if (mime == "image/jpeg") {
                                JpegMetadataReader.readMetadata(
                                    ByteArrayInputStream(bytes),
                                    listOf(ExifReader()),
                                )
                            } else {
                                Metadata().also {
                                    if (pngExif != null) ExifReader().extract(ByteArrayReader(pngExif), it)
                                }
                            }
                        val orientation =
                            metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                                ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION) ?: 1
                        if (orientation !in 1..8) throw VenueInfoMediaInvalidException()
                        val normalized =
                            if (orientation == 1) {
                                decoded
                            } else {
                                BufferedImage(
                                    if (orientation >= 5) height else width,
                                    if (orientation >= 5) width else height,
                                    if (mime == "image/png") {
                                        BufferedImage.TYPE_INT_ARGB
                                    } else {
                                        BufferedImage.TYPE_INT_RGB
                                    },
                                ).also {
                                        target ->
                                    for (y in 0 until height) for (x in 0 until width) {
                                        val (xx, yy) =
                                            when (orientation) {
                                                2 -> width - 1 - x to y
                                                3 -> width - 1 - x to height - 1 - y
                                                4 -> x to height - 1 - y
                                                5 -> y to x
                                                6 -> height - 1 - y to x
                                                7 -> height - 1 - y to width - 1 - x
                                                8 -> y to width - 1 - x
                                                else -> x to y
                                            }
                                        target.setRGB(xx, yy, decoded.getRGB(x, y))
                                    }
                                }
                            }
                        val output = ByteArrayOutputStream()
                        try {
                            check(ImageIO.write(normalized, if (mime == "image/png") "png" else "jpeg", output))
                        } finally {
                            if (normalized !== decoded) normalized.flush()
                        }
                        if (output.size() > VENUE_INFO_MEDIA_MAX_BYTES) throw VenueInfoMediaInvalidException()
                        return ValidatedVenueInfoMedia(output.toByteArray(), mime)
                    } finally {
                        decoded.flush()
                    }
                } finally {
                    reader.dispose()
                }
            }
        } catch (
            e: VenueInfoMediaInvalidException,
        ) {
            throw e
        } catch (e: com.hookah.platform.backend.api.VenueInfoMediaUnavailableException) {
            throw e
        } catch (_: Exception) {
            throw VenueInfoMediaInvalidException()
        }
    }

    private fun validatePng(bytes: ByteArray): ByteArray? {
        var offset = 8
        var exif: ByteArray? = null
        while (offset + 12 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, offset, 4).int
            if (size < 0 || size.toLong() + offset + 12 > bytes.size) throw VenueInfoMediaInvalidException()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val crc = CRC32().also { it.update(bytes, offset + 4, size + 4) }.value
            if (
                crc != (ByteBuffer.wrap(bytes, offset + size + 8, 4).int.toLong() and 0xffffffffL) ||
                type == "acTL"
            ) {
                throw VenueInfoMediaInvalidException()
            }
            if (type == "eXIf") {
                if (size > 65536 || exif != null) throw VenueInfoMediaInvalidException()
                exif = bytes.copyOfRange(offset + 8, offset + 8 + size)
            }
            offset += size + 12
            if (type == "IEND") {
                if (offset != bytes.size) throw VenueInfoMediaInvalidException()
                return exif
            }
        }
        throw VenueInfoMediaInvalidException()
    }
}
