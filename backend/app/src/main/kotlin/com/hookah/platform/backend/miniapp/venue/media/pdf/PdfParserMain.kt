package com.hookah.platform.backend.miniapp.venue.media.pdf

import com.hookah.platform.backend.api.VenueInfoMediaInvalidException
import java.io.DataInputStream
import kotlin.system.exitProcess

object PdfParserMain {
    @JvmStatic fun main(args: Array<String>) {
        PdfWorkerIsolation.requireEnforced()
        val input = DataInputStream(System.`in`)
        val length = input.readInt()
        if (length !in 1..PdfWorkerProtocol.MAX_BYTES) exitProcess(2)
        val bytes = ByteArray(length)
        input.readFully(bytes)
        if (input.read() != -1) exitProcess(2)
        try {
            PdfStructureValidator.validate(bytes)
        } catch (_: VenueInfoMediaInvalidException) {
            exitProcess(2)
        } catch (_: Exception) {
            exitProcess(2)
        }
        exitProcess(0)
    }
}
