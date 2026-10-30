package com.hookah.platform.backend.miniapp.venue.media.pdf

import com.hookah.platform.backend.api.VenueInfoMediaInvalidException
import com.hookah.platform.backend.miniapp.venue.media.VenueInfoPdfPreflight
import com.lowagie.text.pdf.PdfArray
import com.lowagie.text.pdf.PdfDictionary
import com.lowagie.text.pdf.PdfObject
import com.lowagie.text.pdf.PdfReader

// Never called by the backend. The runtime OS admission is mandatory even for direct invocation.
internal object PdfStructureValidator {
    fun validate(bytes: ByteArray) {
        PdfWorkerIsolation.requireEnforced()
        VenueInfoPdfPreflight.check(bytes)
        val text =
            Regex("#([a-fA-F0-9]{2})").replace(String(bytes, Charsets.ISO_8859_1)) {
                it.groupValues[1].toInt(16).toChar().toString()
            }

        fun largeId(match: MatchResult) = (match.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE) > 20000
        // Bound parser allocation before parsing; object streams are outside this first slice.
        if (!Regex("""(?s)%%EOF[\x00\t\n\r ]*$""").containsMatchIn(text) ||
            Regex("""/(?:ObjStm|XRef|Encrypt)\b""").containsMatchIn(text) ||
            Regex("""(?m)^([0-9]+)[ \t]+([0-9]+)[ \r]*$""").findAll(text).any {
                    match ->
                match.groupValues.drop(1).any { (it.toLongOrNull() ?: Long.MAX_VALUE) > 20000 }
            } ||
            Regex("""/Size\s+(\d+)""").findAll(text).any(::largeId) ||
            Regex("""\b(\d+)\s+\d+\s+obj\b""").findAll(text).any(::largeId)
        ) {
            throw VenueInfoMediaInvalidException(
                "PDF должен быть обычным незашифрованным документом без активного содержимого. " +
                    "Экспортируйте его заново в PDF.",
            )
        }
        PdfReader(bytes).use { reader ->
            if (
                reader.isEncrypted ||
                reader.isRebuilt ||
                reader.numberOfPages !in 1..200 ||
                reader.xrefSize > 20000
            ) {
                throw VenueInfoMediaInvalidException()
            }
            val forbidden =
                setOf(
                    "/JavaScript", "/JS", "/OpenAction", "/AA", "/Launch", "/EmbeddedFiles",
                    "/EmbeddedFile", "/Filespec", "/RichMedia", "/XFA", "/AcroForm", "/GoToR",
                    "/SubmitForm", "/ImportData", "/URI", "/GoToE", "/Movie", "/Sound", "/Rendition",
                    "/3D", "/RichMediaExecute", "/Screen", "/U3D",
                )

            fun inspect(
                obj: PdfObject?,
                depth: Int = 0,
            ) {
                if (depth > 40) throw VenueInfoMediaInvalidException()
                if (obj is com.lowagie.text.pdf.PdfName &&
                    ("/" + com.lowagie.text.pdf.PdfName.decodeName(obj.toString())) in forbidden
                ) {
                    throw VenueInfoMediaInvalidException()
                }
                if (obj is PdfDictionary) {
                    for (key in obj.keys) {
                        val keyName = "/" + com.lowagie.text.pdf.PdfName.decodeName(key.toString())
                        val valueName =
                            obj.get(key)?.toString()?.let {
                                "/" + com.lowagie.text.pdf.PdfName.decodeName(it)
                            }
                        if (keyName in forbidden || valueName in forbidden) {
                            throw VenueInfoMediaInvalidException(
                                "PDF содержит ссылки, формы или активные вложения. Экспортируйте плоский PDF.",
                            )
                        }
                        val value = obj.get(key)
                        if (value != null && !value.isIndirect) inspect(value, depth + 1)
                    }
                } else if (obj is PdfArray) {
                    for (value in obj.arrayList) if (!value.isIndirect) inspect(value, depth + 1)
                }
            }
            for (i in 0 until reader.xrefSize) inspect(reader.getPdfObject(i))
        }
    }
}
