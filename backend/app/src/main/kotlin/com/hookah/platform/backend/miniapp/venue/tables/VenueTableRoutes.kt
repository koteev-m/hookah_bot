package com.hookah.platform.backend.miniapp.venue.tables

import com.hookah.platform.backend.api.ConfigException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.telegram.buildWebAppUrl
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.io.BufferedOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Image
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream

private const val MAX_TABLE_EXPORT = 500
private const val MAX_TABLE_CREATE = 500
private const val MAX_TABLE_ROTATE = 500
private const val QR_IMAGE_SIZE = 512

private val exportTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

@Serializable
private data class QrManifestEntry(
    val tableId: Long,
    val tableNumber: Int,
    val tableLabel: String,
    val url: String,
    val fileName: String
)

fun Route.venueTableRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueTableRepository: VenueTableRepository,
    auditLogRepository: AuditLogRepository,
    webAppPublicUrl: String?
) {
    route("/venue/tables") {
        get {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.TABLE_VIEW)) {
                throw ForbiddenException()
            }
            val tables = venueTableRepository.listTables(venueId)
            call.respond(
                VenueTablesResponse(
                    tables = tables.map { table ->
                        VenueTableDto(
                            tableId = table.tableId,
                            tableNumber = table.tableNumber,
                            tableLabel = "Стол №${table.tableNumber}",
                            isActive = table.isActive,
                            activeTokenIssuedAt = table.activeTokenIssuedAt?.toString()
                        )
                    }
                )
            )
        }

        post("/batch-create") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.TABLE_MANAGE)) {
                throw ForbiddenException()
            }
            val request = call.receive<VenueTableBatchCreateRequest>()
            val count = request.count
            if (count <= 0 || count > MAX_TABLE_CREATE) {
                throw InvalidInputException("count must be between 1 and $MAX_TABLE_CREATE")
            }
            val startNumber = request.startNumber ?: venueTableRepository.findNextTableNumber(venueId)
            if (startNumber <= 0) {
                throw InvalidInputException("startNumber must be a positive number")
            }
            if (startNumber > Int.MAX_VALUE - count + 1) {
                throw InvalidInputException("startNumber and count exceed maximum table number")
            }
            val prefix = request.prefix?.trim().takeUnless { it.isNullOrBlank() }
            try {
                venueTableRepository.ensureTablesAvailable(venueId, startNumber, count)
            } catch (_: TableNumberConflictException) {
                throw InvalidInputException("Table numbers already exist in the requested range")
            }
            val created = try {
                venueTableRepository.batchCreateTables(venueId, startNumber, count)
            } catch (_: TableNumberConflictException) {
                throw InvalidInputException("Table numbers already exist in the requested range")
            }
            auditLogRepository.appendJson(
                actorUserId = userId,
                action = "TABLES_BATCH_CREATE",
                entityType = "venue",
                entityId = venueId,
                payload = buildJsonObject {
                    put("count", created.size)
                    put("startNumber", startNumber)
                    prefix?.let { put("prefix", it) }
                    put(
                        "tableIds",
                        buildJsonArray {
                            created.forEach { add(JsonPrimitive(it.tableId)) }
                        }
                    )
                }
            )
            call.respond(
                VenueTableBatchCreateResponse(
                    count = created.size,
                    tables = created.map { table ->
                        val label = prefix?.let { "$it${table.tableNumber}" } ?: "Стол №${table.tableNumber}"
                        VenueTableCreatedDto(
                            tableId = table.tableId,
                            tableNumber = table.tableNumber,
                            tableLabel = label,
                            activeTokenIssuedAt = table.tokenIssuedAt.toString()
                        )
                    }
                )
            )
        }

        post("/{tableId}/rotate-token") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.TABLE_TOKEN_ROTATE)) {
                throw ForbiddenException()
            }
            val tableId = call.parameters["tableId"]?.toLongOrNull()
                ?: throw InvalidInputException("tableId must be a number")
            val rotated = venueTableRepository.rotateToken(venueId, tableId) ?: throw NotFoundException()
            auditLogRepository.appendJson(
                actorUserId = userId,
                action = "TABLE_TOKEN_ROTATE",
                entityType = "venue",
                entityId = venueId,
                payload = buildJsonObject {
                    put("tableId", tableId)
                }
            )
            call.respond(
                VenueTableTokenRotateResponse(
                    tableId = rotated.tableId,
                    tableNumber = rotated.tableNumber,
                    tableLabel = "Стол №${rotated.tableNumber}",
                    activeTokenIssuedAt = rotated.tokenIssuedAt.toString()
                )
            )
        }

        post("/rotate-tokens") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            val request = call.receive<VenueTableRotateTokensRequest>()
            val requestedTableIds = request.tableIds
            if (requestedTableIds == null) {
                if (!permissions.contains(VenuePermission.TABLE_TOKEN_ROTATE_ALL)) {
                    throw ForbiddenException()
                }
                val allTables = venueTableRepository.listTableIds(venueId)
                val rotated = venueTableRepository.rotateTokens(venueId, allTables)
                auditLogRepository.appendJson(
                    actorUserId = userId,
                    action = "TABLE_TOKEN_ROTATE_ALL",
                    entityType = "venue",
                    entityId = venueId,
                    payload = buildJsonObject {
                        put("count", rotated.size)
                        put(
                            "tableIds",
                            buildJsonArray { rotated.forEach { add(JsonPrimitive(it.tableId)) } }
                        )
                    }
                )
                call.respond(
                    VenueTableRotateTokensResponse(
                        rotatedCount = rotated.size,
                        tableIds = rotated.map { it.tableId }
                    )
                )
            } else {
                if (!permissions.contains(VenuePermission.TABLE_TOKEN_ROTATE)) {
                    throw ForbiddenException()
                }
                if (requestedTableIds.isEmpty()) {
                    throw InvalidInputException("tableIds must not be empty")
                }
                if (requestedTableIds.size > MAX_TABLE_ROTATE) {
                    throw InvalidInputException("Maximum rotate size is $MAX_TABLE_ROTATE tables")
                }
                if (requestedTableIds.any { it <= 0 }) {
                    throw InvalidInputException("tableIds must contain only positive numbers")
                }
                val tableIds = requestedTableIds.distinct()
                if (tableIds.size != requestedTableIds.size) {
                    throw InvalidInputException("tableIds must not contain duplicates")
                }
                val rotated = venueTableRepository.rotateTokens(venueId, tableIds)
                auditLogRepository.appendJson(
                    actorUserId = userId,
                    action = "TABLE_TOKEN_ROTATE_BATCH",
                    entityType = "venue",
                    entityId = venueId,
                    payload = buildJsonObject {
                        put("count", rotated.size)
                        put(
                            "tableIds",
                            buildJsonArray { rotated.forEach { add(JsonPrimitive(it.tableId)) } }
                        )
                    }
                )
                call.respond(
                    VenueTableRotateTokensResponse(
                        rotatedCount = rotated.size,
                        tableIds = rotated.map { it.tableId }
                    )
                )
            }
        }

        get("/qr-package") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.TABLE_QR_EXPORT)) {
                throw ForbiddenException()
            }
            val format = call.request.queryParameters["format"]?.trim()?.lowercase() ?: "zip"
            val baseUrl = webAppPublicUrl?.takeIf { it.isNotBlank() }
                ?: throw ConfigException("webAppPublicUrl is required for QR export")
            val tables = venueTableRepository.listTablesWithTokens(venueId)
            if (tables.isEmpty()) {
                throw InvalidInputException("No tables to export")
            }
            if (tables.size > MAX_TABLE_EXPORT) {
                throw InvalidInputException("Maximum export size is $MAX_TABLE_EXPORT tables")
            }
            val timestamp = exportTimestampFormatter.format(Instant.now())
            val filenameBase = "venue-${venueId}-tables-$timestamp"
            call.response.header(HttpHeaders.CacheControl, "no-store")
            when (format) {
                "zip" -> {
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(
                            ContentDisposition.Parameters.FileName,
                            "$filenameBase.zip"
                        ).toString()
                    )
                    call.respondOutputStream(ContentType.Application.Zip) {
                        writeZipPackage(this, tables, baseUrl)
                    }
                }
                "pdf" -> {
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(
                            ContentDisposition.Parameters.FileName,
                            "$filenameBase.pdf"
                        ).toString()
                    )
                    call.respondOutputStream(ContentType.Application.Pdf) {
                        writePdfPackage(this, tables, baseUrl)
                    }
                }
                else -> throw InvalidInputException("format must be zip or pdf")
            }
            auditLogRepository.appendJson(
                actorUserId = userId,
                action = "TABLE_QR_EXPORT",
                entityType = "venue",
                entityId = venueId,
                payload = buildJsonObject {
                    put("format", format)
                    put("count", tables.size)
                }
            )
        }
    }
}

private fun writeZipPackage(output: java.io.OutputStream, tables: List<VenueTableToken>, baseUrl: String) {
    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
        val manifestEntries = mutableListOf<QrManifestEntry>()
        tables.forEach { table ->
            val label = "Стол №${table.tableNumber}"
            val url = buildWebAppUrl(baseUrl, mapOf("table_token" to table.token, "screen" to "menu"))
            val fileName = "table_${table.tableNumber}.png"
            val entry = ZipEntry(fileName).apply { time = 0 }
            zip.putNextEntry(entry)
            val qrBytes = generateQrPng(url)
            zip.write(qrBytes)
            zip.closeEntry()
            manifestEntries.add(
                QrManifestEntry(
                    tableId = table.tableId,
                    tableNumber = table.tableNumber,
                    tableLabel = label,
                    url = url,
                    fileName = fileName
                )
            )
        }
        val manifestEntry = ZipEntry("manifest.json").apply { time = 0 }
        zip.putNextEntry(manifestEntry)
        val manifestJson = Json.encodeToString(manifestEntries)
        zip.write(manifestJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}

private fun writePdfPackage(output: java.io.OutputStream, tables: List<VenueTableToken>, baseUrl: String) {
    val document = Document(PageSize.A4)
    PdfWriter.getInstance(document, output)
    document.open()
    tables.forEachIndexed { index, table ->
        if (index > 0) {
            document.newPage()
        }
        val label = "Стол №${table.tableNumber}"
        val url = buildWebAppUrl(baseUrl, mapOf("table_token" to table.token, "screen" to "menu"))
        val title = Paragraph(label).apply { alignment = Element.ALIGN_CENTER }
        document.add(title)
        val qrBytes = generateQrPng(url)
        val image = Image.getInstance(qrBytes).apply {
            alignment = Element.ALIGN_CENTER
            scaleToFit(360f, 360f)
        }
        document.add(image)
    }
    document.close()
}

private fun generateQrPng(payload: String): ByteArray {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, QR_IMAGE_SIZE, QR_IMAGE_SIZE)
    val output = ByteArrayOutputStream()
    MatrixToImageWriter.writeToStream(matrix, "PNG", output)
    return output.toByteArray()
}
