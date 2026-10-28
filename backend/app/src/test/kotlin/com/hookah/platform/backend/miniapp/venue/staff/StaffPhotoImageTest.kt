package com.hookah.platform.backend.miniapp.venue.staff

import com.drew.imaging.ImageMetadataReader
import com.hookah.platform.backend.api.StaffPhotoInvalidException
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StaffPhotoImageTest {
    @Test fun `jpeg png become bounded metadata-free JPEG`() {
        for (format in listOf("jpeg", "png")) {
            val result = StaffPhotoImage.normalize(syntheticPhoto(format, 1600, 800), "image/$format")
            assertEquals(1024, result.width)
            assertEquals(512, result.height)
            assertEquals(result.checksum, photoSha256(result.bytes))
            assertTrue(result.bytes.size < STAFF_PHOTO_MAX_OUTPUT_BYTES)
            assertEquals(0xff.toByte(), result.bytes[0])
            val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(result.bytes))
            assertFalse(metadata.directories.any { it.name.contains("Exif") || it.name.contains("GPS") })
        }
    }

    @Test fun `all EXIF orientations normalized and stripped for JPEG and PNG`() {
        for (orientation in 1..8) {
            val tiff =
                ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
                    .put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(1)
                    .putShort(0x112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0).putInt(0).array()
            for (format in listOf("jpeg", "png")) {
                val original = syntheticPhoto(format, 80, 40)
                val bytes =
                    if (format == "jpeg") {
                        val payload = "Exif\u0000\u0000".toByteArray() + tiff
                        original.copyOfRange(0, 2) +
                            byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (payload.size + 2).toByte()) +
                            payload + original.copyOfRange(2, original.size)
                    } else {
                        original.copyOfRange(0, 33) + chunk("eXIf", tiff) + original.copyOfRange(33, original.size)
                    }
                val result = StaffPhotoImage.normalize(bytes, "image/$format")
                assertEquals(if (orientation >= 5) 40 else 80, result.width)
                assertEquals(if (orientation >= 5) 80 else 40, result.height)
                val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(result.bytes))
                assertFalse(metadata.directories.any { it.name.contains("Exif") || it.name.contains("GPS") })
            }
        }
    }

    @Test fun `reject disguised formats mime mismatch truncation bad CRC and animation`() {
        val png = syntheticPhoto()
        val invalid =
            listOf(
                "<svg onload='x'/>".toByteArray(),
                byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray(),
                "RIFFxxxxWEBP".toByteArray(),
                png.copyOf(png.size - 4),
                png.copyOf().also { it[42] = (it[42].toInt() xor 1).toByte() },
                syntheticPhoto("jpeg").dropLast(2).toByteArray(),
                png.copyOfRange(0, 33) + chunk("acTL", ByteArray(8)) +
                    png.copyOfRange(33, png.size),
            )
        invalid.forEach { assertFailsWith<StaffPhotoInvalidException> { StaffPhotoImage.normalize(it, null) } }
        assertFailsWith<StaffPhotoInvalidException> { StaffPhotoImage.normalize(png, "image/jpeg") }
        assertFailsWith<StaffPhotoInvalidException> {
            StaffPhotoImage.normalize(ByteArray(STAFF_PHOTO_MAX_INPUT_BYTES + 1), null)
        }
        assertFailsWith<StaffPhotoInvalidException> {
            StaffPhotoImage.normalize(syntheticPhoto(width = 4097, height = 1), "image/png")
        }
    }

    @Test fun `compressed metadata is ignored and absent from normalized image`() {
        val png = syntheticPhoto()
        // Valid ancillary bytes need not be decompressed to decode the pixel plane.
        val input =
            png.copyOfRange(0, 33) +
                chunk("zTXt", "GPS\u0000\u0000synthetic-invalid-zlib".toByteArray()) +
                png.copyOfRange(33, png.size)
        val result = StaffPhotoImage.normalize(input, "image/png")
        assertNotNull(ImageIO.read(ByteArrayInputStream(result.bytes)))
        assertFalse(String(result.bytes, Charsets.ISO_8859_1).contains("GPS"))
    }

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val payload = type.toByteArray() + data
        return ByteBuffer.allocate(data.size + 12).putInt(data.size).put(payload).putInt(
            CRC32().also {
                it.update(payload)
            }.value.toInt(),
        ).array()
    }
}
