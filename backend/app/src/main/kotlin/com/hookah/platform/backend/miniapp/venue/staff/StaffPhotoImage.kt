package com.hookah.platform.backend.miniapp.venue.staff

import com.drew.imaging.jpeg.JpegMetadataReader
import com.drew.lang.ByteArrayReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifReader
import com.hookah.platform.backend.api.StaffPhotoInvalidException
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class SanitizedStaffPhoto(val bytes: ByteArray, val width: Int, val height: Int, val checksum: String)

internal fun photoSha256(bytes: ByteArray): String =
    MessageDigest.getInstance(
        "SHA-256",
    ).digest(bytes).joinToString("") {
        "%02x".format(it)
    }

object StaffPhotoImage {
    fun normalize(
        bytes: ByteArray,
        contentType: String?,
        maxBytes: Int = STAFF_PHOTO_MAX_INPUT_BYTES,
    ): SanitizedStaffPhoto {
        if (bytes.isEmpty() || bytes.size > maxBytes) {
            throw StaffPhotoInvalidException(
                "Фото должно быть не больше 5 МБ.",
            )
        }
        try {
            val jpeg = bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
            val png = bytes.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            if (!jpeg && !png) {
                throw StaffPhotoInvalidException(
                    "Поддерживаются JPEG и PNG. HEIC/HEIF и WebP пока не поддерживаются; экспортируйте фото в JPEG.",
                )
            }
            val actual = if (jpeg) "image/jpeg" else "image/png"
            if (contentType != null &&
                contentType.substringBefore(';').lowercase() !in setOf(actual, "application/octet-stream")
            ) {
                throw StaffPhotoInvalidException()
            }
            if (jpeg &&
                (
                    bytes[bytes.size - 2] != 0xff.toByte() ||
                        bytes.last() != 0xd9.toByte()
                )
            ) {
                throw StaffPhotoInvalidException()
            }
            val pngExif = if (png) validatePng(bytes) else null
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) throw StaffPhotoInvalidException()
                val reader = readers.next()
                try {
                    reader.setInput(input, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 16_000_000) {
                        throw StaffPhotoInvalidException(
                            "Фото слишком большое: максимум 4096 пикселей по стороне и 16 Мп.",
                        )
                    }
                    var warning = false
                    reader.addIIOReadWarningListener { _, _ -> warning = true }
                    val decoded = reader.read(0) ?: throw StaffPhotoInvalidException()
                    if (warning) throw StaffPhotoInvalidException()
                    val metadata =
                        if (jpeg) {
                            JpegMetadataReader.readMetadata(ByteArrayInputStream(bytes), listOf(ExifReader()))
                        } else {
                            Metadata().also { if (pngExif != null) ExifReader().extract(ByteArrayReader(pngExif), it) }
                        }
                    val orientation =
                        metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                            ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION) ?: 1
                    if (orientation !in 1..8) throw StaffPhotoInvalidException()
                    val scale = minOf(1.0, 1024.0 / max(width, height))
                    val w = max(1, (width * scale).roundToInt())
                    val h = max(1, (height * scale).roundToInt())
                    val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                    scaled.createGraphics().let { graphics ->
                        try {
                            graphics.color = Color.WHITE
                            graphics.fillRect(0, 0, w, h)
                            graphics.setRenderingHint(
                                RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                            )
                            graphics.drawImage(decoded, 0, 0, w, h, null)
                        } finally {
                            graphics.dispose()
                            decoded.flush()
                        }
                    }
                    val rotated =
                        BufferedImage(
                            if (orientation >= 5) h else w,
                            if (orientation >= 5) w else h,
                            BufferedImage.TYPE_INT_RGB,
                        )
                    for (y in 0 until h) for (x in 0 until w) {
                        val (xx, yy) =
                            when (orientation) {
                                2 -> w - 1 - x to y
                                3 -> w - 1 - x to h - 1 - y
                                4 -> x to h - 1 - y
                                5 -> y to x
                                6 -> h - 1 - y to x
                                7 -> h - 1 - y to w - 1 - x
                                8 -> y to w - 1 - x
                                else -> x to y
                            }
                        rotated.setRGB(xx, yy, scaled.getRGB(x, y))
                    }
                    scaled.flush()
                    val output =
                        ByteArrayOutputStream().use { stream ->
                            check(ImageIO.write(rotated, "jpeg", stream))
                            stream.toByteArray()
                        }
                    rotated.flush()
                    if (output.size > STAFF_PHOTO_MAX_OUTPUT_BYTES) throw StaffPhotoInvalidException()
                    return SanitizedStaffPhoto(output, rotated.width, rotated.height, photoSha256(output))
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: StaffPhotoInvalidException) {
            throw e
        } catch (_: Exception) {
            throw StaffPhotoInvalidException()
        }
    }

    private fun validatePng(bytes: ByteArray): ByteArray? {
        var offset = 8
        var exif: ByteArray? = null
        var ended = false
        while (offset + 12 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, offset, 4).int
            if (size < 0 || size.toLong() + offset + 12 > bytes.size) throw StaffPhotoInvalidException()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val crc = CRC32().also { it.update(bytes, offset + 4, size + 4) }.value
            if (crc != (ByteBuffer.wrap(bytes, offset + size + 8, 4).int.toLong() and 0xffffffffL)) {
                throw StaffPhotoInvalidException()
            }
            if (type == "acTL") throw StaffPhotoInvalidException("Анимированные изображения не поддерживаются.")
            if (type == "eXIf") {
                if (size > 65_536 || exif != null) throw StaffPhotoInvalidException()
                exif = bytes.copyOfRange(offset + 8, offset + 8 + size)
            }
            offset += size + 12
            if (type == "IEND") {
                ended = true
                break
            }
        }
        if (!ended || offset != bytes.size) throw StaffPhotoInvalidException()
        return exif
    }
}
