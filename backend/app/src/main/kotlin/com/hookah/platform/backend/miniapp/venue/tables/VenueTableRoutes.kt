package com.hookah.platform.backend.miniapp.venue.tables

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.hookah.platform.backend.api.ConfigException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection
import com.hookah.platform.backend.miniapp.security.enforceMiniAppRateLimit
import com.hookah.platform.backend.miniapp.security.throwMiniAppRateLimited
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.TelegramApiClient
import com.hookah.platform.backend.telegram.TelegramCallResult
import com.hookah.platform.backend.telegram.buildTelegramStartUrl
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Image
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfWriter
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    val fileName: String,
)

internal fun Route.venueTableRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueTableRepository: VenueTableRepository,
    auditLogRepository: AuditLogRepository,
    telegramBotUsername: String?,
    telegramApiClient: TelegramApiClient? = null,
    abuseProtection: MiniAppAbuseProtection = MiniAppAbuseProtection(),
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
                    tables =
                        tables.map { table ->
                            VenueTableDto(
                                tableId = table.tableId,
                                tableNumber = table.tableNumber,
                                tableLabel = "Стол №${table.tableNumber}",
                                isActive = table.isActive,
                                activeTokenIssuedAt = table.activeTokenIssuedAt?.toString(),
                            )
                        },
                ),
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
            val tableNumbers =
                request.tableNumbers?.also { numbers ->
                    if (request.startNumber != null || numbers.size != count || numbers.any { it <= 0 } ||
                        numbers.distinct().size != numbers.size
                    ) {
                        throw InvalidInputException("Укажите $count разных положительных номеров без startNumber.")
                    }
                } ?: run {
                    val start = request.startNumber ?: venueTableRepository.findNextTableNumber(venueId)
                    if (start <= 0 || start > Int.MAX_VALUE - count + 1) {
                        throw InvalidInputException("Номера столов должны быть от 1 до ${Int.MAX_VALUE}.")
                    }
                    List(count) { start + it }
                }
            val prefix = request.prefix?.trim().takeUnless { it.isNullOrBlank() }
            val created =
                try {
                    venueTableRepository.batchCreateTables(venueId, tableNumbers) { connection, tables ->
                        auditLogRepository.appendJson(
                            connection = connection,
                            actorUserId = userId,
                            action = "TABLES_BATCH_CREATE",
                            entityType = "venue",
                            entityId = venueId,
                            payload =
                                buildJsonObject {
                                    put("count", tables.size)
                                    if (request.tableNumbers == null) put("startNumber", tableNumbers.first())
                                    put(
                                        "tableNumbers",
                                        buildJsonArray { tables.forEach { add(JsonPrimitive(it.tableNumber)) } },
                                    )
                                    put(
                                        "tableIds",
                                        buildJsonArray { tables.forEach { add(JsonPrimitive(it.tableId)) } },
                                    )
                                },
                        )
                    }
                } catch (_: TableNumberConflictException) {
                    throw InvalidInputException(
                        "Один из номеров уже занят. Столы не созданы. Исправьте номера и повторите.",
                    )
                }
            call.respond(
                VenueTableBatchCreateResponse(
                    count = created.size,
                    tables =
                        created.map { table ->
                            val label = prefix?.let { "$it${table.tableNumber}" } ?: "Стол №${table.tableNumber}"
                            VenueTableCreatedDto(
                                tableId = table.tableId,
                                tableNumber = table.tableNumber,
                                tableLabel = label,
                                activeTokenIssuedAt = table.tokenIssuedAt.toString(),
                            )
                        },
                ),
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
            val tableId =
                call.parameters["tableId"]?.toLongOrNull()
                    ?: throw InvalidInputException("tableId must be a number")
            val rotated = venueTableRepository.rotateToken(venueId, tableId) ?: throw NotFoundException()
            auditLogRepository.appendJson(
                actorUserId = userId,
                action = "TABLE_TOKEN_ROTATE",
                entityType = "venue",
                entityId = venueId,
                payload =
                    buildJsonObject {
                        put("tableId", tableId)
                    },
            )
            call.respond(
                VenueTableTokenRotateResponse(
                    tableId = rotated.tableId,
                    tableNumber = rotated.tableNumber,
                    tableLabel = "Стол №${rotated.tableNumber}",
                    activeTokenIssuedAt = rotated.tokenIssuedAt.toString(),
                ),
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
                    payload =
                        buildJsonObject {
                            put("count", rotated.size)
                            put(
                                "tableIds",
                                buildJsonArray { rotated.forEach { add(JsonPrimitive(it.tableId)) } },
                            )
                        },
                )
                call.respond(
                    VenueTableRotateTokensResponse(
                        rotatedCount = rotated.size,
                        tableIds = rotated.map { it.tableId },
                    ),
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
                    payload =
                        buildJsonObject {
                            put("count", rotated.size)
                            put(
                                "tableIds",
                                buildJsonArray { rotated.forEach { add(JsonPrimitive(it.tableId)) } },
                            )
                        },
                )
                call.respond(
                    VenueTableRotateTokensResponse(
                        rotatedCount = rotated.size,
                        tableIds = rotated.map { it.tableId },
                    ),
                )
            }
        }

        get("/qr-package") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (!VenuePermissions.forRole(role).contains(VenuePermission.TABLE_QR_EXPORT)) throw ForbiddenException()
            val format = call.request.queryParameters["format"]?.trim()?.lowercase() ?: "zip"
            val rawTableId = call.request.queryParameters["tableId"]
            val tableId = rawTableId?.toLongOrNull()
            if (rawTableId != null && (tableId == null || tableId <= 0)) {
                throw InvalidInputException(
                    "Некорректный стол.",
                )
            }
            val file = prepareQrExport(venueTableRepository, venueId, telegramBotUsername, format, tableId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    file.filename,
                ).toString(),
            )
            auditLogRepository.appendJson(
                userId,
                "TABLE_QR_EXPORT",
                "venue",
                venueId,
                buildJsonObject {
                    put("format", format)
                    put("count", file.count)
                },
            )
            call.respondBytes(file.bytes, file.contentType)
        }

        post("/qr-delivery") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (userId <= 0 || !VenuePermissions.forRole(role).contains(VenuePermission.TABLE_QR_EXPORT)) {
                throw ForbiddenException()
            }
            val request = call.receive<VenueQrDeliveryRequest>()
            if (request.format !in setOf("pdf", "png")) {
                throw InvalidInputException(
                    "Для Telegram выберите PDF или PNG.",
                )
            }
            enforceMiniAppRateLimit(call, abuseProtection.tryQrDelivery(userId))
            val api =
                telegramApiClient ?: throw InvalidInputException(
                    "Бот временно недоступен. Попробуйте позже или скачайте файл.",
                )
            // JWT subject is the server-verified Telegram user id, never a client-supplied recipient.
            val chat = api.getChat(userId)
            if (chat == null || chat.id != userId || chat.type != "private") {
                throw InvalidInputException(
                    "Личный чат с ботом недоступен. Откройте бота, нажмите Старт и разблокируйте его, затем повторите.",
                )
            }
            val file =
                prepareQrExport(venueTableRepository, venueId, telegramBotUsername, request.format, request.tableId)
            // Recheck current membership after generation and before disclosing the file to Telegram.
            val currentRole = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (!VenuePermissions.forRole(
                    currentRole,
                ).contains(VenuePermission.TABLE_QR_EXPORT)
            ) {
                throw ForbiddenException()
            }
            when (val sent = api.sendDocumentBytes(userId, file.bytes, file.filename, file.contentType)) {
                is TelegramCallResult.Success -> Unit
                is TelegramCallResult.Failure -> {
                    if (sent.errorCode == null || sent.errorCode >= 500) throw qrDeliveryUnconfirmed()
                    if (sent.retryAfterSeconds != null) {
                        throwMiniAppRateLimited(
                            call,
                            sent.retryAfterSeconds.toLong().coerceAtLeast(1),
                        )
                    }
                    throw InvalidInputException(
                        "Отправка не подтверждена. Проверьте личный чат с ботом и блокировку. " +
                            "Перед повтором убедитесь, что файл не пришёл.",
                    )
                }
                TelegramCallResult.TrafficDenied -> throw InvalidInputException(
                    "Отправка временно ограничена. Попробуйте позже или скачайте файл.",
                )
            }
            try {
                auditLogRepository.appendJson(
                    userId,
                    "TABLE_QR_TELEGRAM_SENT",
                    "venue",
                    venueId,
                    buildJsonObject {
                        put("format", request.format)
                        put("count", file.count)
                    },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Telegram delivery cannot be rolled back when the subsequent audit fails.
                throw qrDeliveryUnconfirmed()
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(VenueQrDeliveryResponse(sent = true))
        }
    }
}

private fun qrDeliveryUnconfirmed() =
    InvalidInputException(
        "Файл мог быть отправлен. Проверьте личный чат с ботом перед повторной отправкой.",
        details = buildJsonObject { put("deliveryOutcome", "unknown") },
    )

private data class QrExport(
    val bytes: ByteArray,
    val filename: String,
    val contentType: ContentType,
    val count: Int,
)

private suspend fun prepareQrExport(
    repository: VenueTableRepository,
    venueId: Long,
    username: String?,
    format: String,
    tableId: Long?,
): QrExport {
    if (format !in setOf("pdf", "zip", "png") ||
        (format == "png") != (tableId != null) ||
        (tableId != null && tableId <= 0)
    ) {
        throw InvalidInputException("Выберите PDF/ZIP для всех столов или PNG для одного стола.")
    }
    val botUsername =
        username?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
            ?: throw ConfigException("telegram.botUsername is required for QR export")
    val tables = repository.listTablesWithTokens(venueId, tableId)
    if (tables.isEmpty()) {
        if (tableId != null) throw NotFoundException()
        throw InvalidInputException("Нет активных столов с QR для экспорта.")
    }
    if (tables.size > MAX_TABLE_EXPORT) {
        throw InvalidInputException(
            "Можно выдать не более $MAX_TABLE_EXPORT QR за раз.",
        )
    }
    val output = LimitedQrOutput()
    val contentType =
        when (format) {
            "pdf" -> {
                writePdfPackage(output, tables, botUsername)
                ContentType.Application.Pdf
            }
            "zip" -> {
                writeZipPackage(output, tables, botUsername)
                ContentType.Application.Zip
            }
            else -> {
                output.write(generateQrPng(buildTelegramStartUrl(botUsername, tables.single().token)))
                ContentType.Image.PNG
            }
        }
    val label = if (tableId == null) "tables" else "table-${tables.single().tableNumber}"
    val filename = "venue-$venueId-$label-${exportTimestampFormatter.format(Instant.now())}.$format"
    return QrExport(output.toByteArray(), filename, contentType, tables.size)
}

private class LimitedQrOutput : ByteArrayOutputStream() {
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkSize(length)
        super.write(bytes, offset, length)
    }

    override fun write(value: Int) {
        checkSize(1)
        super.write(value)
    }

    private fun checkSize(length: Int) {
        if (count.toLong() + length > TelegramApiClient.MAX_DOCUMENT_BYTES) {
            throw InvalidInputException("Файл превышает 16 МБ. Получите QR отдельных столов в PNG.")
        }
    }
}

private fun writeZipPackage(
    output: java.io.OutputStream,
    tables: List<VenueTableToken>,
    botUsername: String,
) {
    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
        val manifestEntries = mutableListOf<QrManifestEntry>()
        tables.forEach { table ->
            val label = "Стол №${table.tableNumber}"
            val url = buildTelegramStartUrl(botUsername, table.token)
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
                    fileName = fileName,
                ),
            )
        }
        val manifestEntry = ZipEntry("manifest.json").apply { time = 0 }
        zip.putNextEntry(manifestEntry)
        val manifestJson = Json.encodeToString(manifestEntries)
        zip.write(manifestJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}

private fun writePdfPackage(
    output: java.io.OutputStream,
    tables: List<VenueTableToken>,
    botUsername: String,
) {
    val document = Document(PageSize.A4)
    PdfWriter.getInstance(document, output)
    document.open()
    tables.forEachIndexed { index, table ->
        if (index > 0) {
            document.newPage()
        }
        val url = buildTelegramStartUrl(botUsername, table.token)
        val title = Paragraph("Table ${table.tableNumber}").apply { alignment = Element.ALIGN_CENTER }
        document.add(title)
        val qrBytes = generateQrPng(url)
        val image =
            Image.getInstance(qrBytes).apply {
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
