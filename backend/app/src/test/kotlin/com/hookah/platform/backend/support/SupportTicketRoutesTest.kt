package com.hookah.platform.backend.support

import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTableContextLifecycleRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTableTeardownCheckpoint
import com.hookah.platform.backend.miniapp.guest.db.GuestTableTeardownResult
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.PlatformGuestTableMutationResult
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.module
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SupportTicketRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ordinary Guest can create and reply to token-only table support without Telegram confirmation`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-token-only-ordinary-guest")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")
            ensureTelegramChatContextSchema(jdbcUrl)

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, GUEST_ID)
            val table = seedActiveTableContext(jdbcUrl, venueId)
            val guestToken = issueToken(config, GUEST_ID)

            val createResponse =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "category":"ORDER_SERVICE",
                          "message":"Нужна помощь за столом",
                          "tableToken":"${table.token}"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, createResponse.status)
            val thread =
                json.parseToJsonElement(createResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
            val threadId = thread.getValue("threadId").jsonPrimitive.content.toLong()
            assertEquals(venueId.toString(), thread.getValue("venueId").jsonPrimitive.content)
            assertEquals(table.tableId.toString(), thread.getValue("tableId").jsonPrimitive.content)
            assertTrue(thread["tableSessionId"] == null || thread["tableSessionId"] == JsonNull)

            deleteSupportReadMarker(jdbcUrl, threadId, GUEST_ID)
            assertEquals(null, supportReadAt(jdbcUrl, threadId, GUEST_ID))
            val detailResponse =
                client.get("/api/guest/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestToken") }
                }
            assertEquals(HttpStatusCode.OK, detailResponse.status)
            assertTrue(supportReadAt(jdbcUrl, threadId, GUEST_ID) != null)

            val replyResponse =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Дополняю обращение"}""")
                }
            assertEquals(HttpStatusCode.OK, replyResponse.status)
            assertEquals(2, supportMessageCount(jdbcUrl, threadId))
        }

    @Test
    fun `Platform Guest token-only denial is private and side effect free before and after exit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-token-only-platform-denial")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")
            ensureTelegramChatContextSchema(jdbcUrl)

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, platformOwnerId)
            val table = seedActiveTableContext(jdbcUrl, venueId)
            val platformToken = issueToken(config, platformOwnerId)
            val timingBefore = tableSessionTiming(jdbcUrl, table.tableSessionId)
            val tabsBefore = tableRowCount(jdbcUrl, "tab")
            val auditBefore = tableRowCount(jdbcUrl, "audit_log")

            suspend fun createTicket() =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "category":"ORDER_SERVICE",
                          "message":"Проверка приватного отказа",
                          "tableToken":"${table.token}"
                        }
                        """.trimIndent(),
                    )
                }

            val missingConfirmationResponse = createTicket()
            assertEquals(HttpStatusCode.Forbidden, missingConfirmationResponse.status)
            val missingConfirmationMessage = apiErrorMessage(missingConfirmationResponse.bodyAsText())

            seedPlatformTableConfirmation(
                jdbcUrl = jdbcUrl,
                chatId = platformOwnerId + 1,
                userId = platformOwnerId,
                venueId = venueId,
                table = table,
                tableToken = "mismatched-${UUID.randomUUID()}",
            )
            val mismatchedConfirmationResponse = createTicket()
            assertEquals(HttpStatusCode.Forbidden, mismatchedConfirmationResponse.status)
            val mismatchedConfirmationMessage = apiErrorMessage(mismatchedConfirmationResponse.bodyAsText())
            assertEquals(0, tableRowCount(jdbcUrl, "support_threads"))
            assertEquals(timingBefore, tableSessionTiming(jdbcUrl, table.tableSessionId))

            clearTelegramTableContext(jdbcUrl, platformOwnerId + 1)
            seedPlatformTableConfirmation(
                jdbcUrl = jdbcUrl,
                chatId = platformOwnerId + 1,
                userId = platformOwnerId,
                venueId = venueId,
                table = table,
            )
            seedUserExit(jdbcUrl, platformOwnerId, table.tableSessionId)

            val afterExitResponse = createTicket()
            assertEquals(HttpStatusCode.Forbidden, afterExitResponse.status)
            val afterExitMessage = apiErrorMessage(afterExitResponse.bodyAsText())

            assertEquals(PLATFORM_RECONFIRM_MESSAGE, missingConfirmationMessage)
            assertEquals(missingConfirmationMessage, mismatchedConfirmationMessage)
            assertEquals(missingConfirmationMessage, afterExitMessage)
            assertEquals(0, tableRowCount(jdbcUrl, "support_threads"))
            assertEquals(0, tableRowCount(jdbcUrl, "support_messages"))
            assertEquals(0, tableRowCount(jdbcUrl, "support_thread_reads"))
            assertEquals(tabsBefore, tableRowCount(jdbcUrl, "tab"))
            assertEquals(auditBefore, tableRowCount(jdbcUrl, "audit_log"))
            assertEquals(timingBefore, tableSessionTiming(jdbcUrl, table.tableSessionId))
        }

    @Test
    fun `Platform Guest thread detail marks read only while table context is confirmed`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-thread-detail-platform-confirmation")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, platformOwnerId)
            val table = seedActiveTableContext(jdbcUrl, venueId)
            seedPlatformTableConfirmation(
                jdbcUrl = jdbcUrl,
                chatId = platformOwnerId + 1,
                userId = platformOwnerId,
                venueId = venueId,
                table = table,
            )
            val platformToken = issueToken(config, platformOwnerId)
            val createResponse =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "category":"ORDER_SERVICE",
                          "message":"Проверка отметки чтения",
                          "tableToken":"${table.token}"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, createResponse.status)
            val threadId =
                json.parseToJsonElement(createResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive.content.toLong()

            deleteSupportReadMarker(jdbcUrl, threadId, platformOwnerId)
            val confirmedDetailResponse =
                client.get("/api/guest/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, confirmedDetailResponse.status)
            assertTrue(supportReadAt(jdbcUrl, threadId, platformOwnerId) != null)

            deleteSupportReadMarker(jdbcUrl, threadId, platformOwnerId)
            val timingBefore = tableSessionTiming(jdbcUrl, table.tableSessionId)
            val auditBefore = tableRowCount(jdbcUrl, "audit_log")
            seedUserExit(jdbcUrl, platformOwnerId, table.tableSessionId)

            val afterExitResponse =
                client.get("/api/guest/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }

            assertEquals(HttpStatusCode.Forbidden, afterExitResponse.status)
            assertEquals(PLATFORM_RECONFIRM_MESSAGE, apiErrorMessage(afterExitResponse.bodyAsText()))
            assertEquals(null, supportReadAt(jdbcUrl, threadId, platformOwnerId))
            assertEquals(timingBefore, tableSessionTiming(jdbcUrl, table.tableSessionId))
            assertEquals(auditBefore, tableRowCount(jdbcUrl, "audit_log"))
            assertEquals(0, tableRowCount(jdbcUrl, "tab"))
        }

    @Test
    fun `Platform Guest confirmed token-only support is atomic and post-exit mutations roll back`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-token-only-platform-confirmed")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, platformOwnerId)
            val table = seedActiveTableContext(jdbcUrl, venueId)
            seedPlatformTableConfirmation(
                jdbcUrl = jdbcUrl,
                chatId = platformOwnerId + 1,
                userId = platformOwnerId,
                venueId = venueId,
                table = table,
            )
            val platformToken = issueToken(config, platformOwnerId)

            val createResponse =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "category":"ORDER_SERVICE",
                          "message":"Подтверждённое обращение",
                          "tableToken":"${table.token}"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, createResponse.status)
            val thread =
                json.parseToJsonElement(createResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
            val threadId = thread.getValue("threadId").jsonPrimitive.content.toLong()
            assertEquals(table.tableSessionId.toString(), thread.getValue("tableSessionId").jsonPrimitive.content)
            assertEquals(0, tableRowCount(jdbcUrl, "tab"))

            val tokenAndSessionResponse =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "category":"ORDER_SERVICE",
                          "message":"Подтверждённое обращение с сессией",
                          "tableToken":"${table.token}",
                          "tableSessionId":${table.tableSessionId}
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, tokenAndSessionResponse.status)
            val tokenAndSessionThread =
                json.parseToJsonElement(tokenAndSessionResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
            assertEquals(
                table.tableSessionId.toString(),
                tokenAndSessionThread.getValue("tableSessionId").jsonPrimitive.content,
            )
            assertEquals(0, tableRowCount(jdbcUrl, "tab"))

            val statusBefore = supportThreadStatus(jdbcUrl, threadId)
            val messageCountBefore = supportMessageCount(jdbcUrl, threadId)
            val readAtBefore = supportReadAt(jdbcUrl, threadId, platformOwnerId)
            val auditBefore = tableRowCount(jdbcUrl, "audit_log")
            val timingBefore = tableSessionTiming(jdbcUrl, table.tableSessionId)
            seedUserExit(jdbcUrl, platformOwnerId, table.tableSessionId)

            val replyResponse =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Не должно сохраниться"}""")
                }
            val resolveResponse =
                client.post("/api/guest/support/threads/$threadId/resolve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }

            assertEquals(HttpStatusCode.Forbidden, replyResponse.status)
            assertEquals(HttpStatusCode.Forbidden, resolveResponse.status)
            assertEquals(PLATFORM_RECONFIRM_MESSAGE, apiErrorMessage(replyResponse.bodyAsText()))
            assertEquals(PLATFORM_RECONFIRM_MESSAGE, apiErrorMessage(resolveResponse.bodyAsText()))
            assertEquals(statusBefore, supportThreadStatus(jdbcUrl, threadId))
            assertEquals(messageCountBefore, supportMessageCount(jdbcUrl, threadId))
            assertEquals(readAtBefore, supportReadAt(jdbcUrl, threadId, platformOwnerId))
            assertEquals(auditBefore, tableRowCount(jdbcUrl, "audit_log"))
            assertEquals(timingBefore, tableSessionTiming(jdbcUrl, table.tableSessionId))
        }

    @Test
    fun `exit wins deterministic support reply race on the confirmed context row`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-exit-race")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")
            ensureTelegramChatContextSchema(jdbcUrl)

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, platformOwnerId)
            val table = seedActiveTableContext(jdbcUrl, venueId)
            val chatId = platformOwnerId + 1
            seedPlatformTableConfirmation(
                jdbcUrl = jdbcUrl,
                chatId = chatId,
                userId = platformOwnerId,
                venueId = venueId,
                table = table,
            )

            val dataSource =
                JdbcDataSource().apply {
                    setURL(jdbcUrl)
                    user = "sa"
                    password = ""
                }
            val supportThreadRepository = SupportThreadRepository(dataSource)
            val initial =
                supportThreadRepository.createTicket(
                    SupportTicketCreateInput(
                        guestUserId = platformOwnerId,
                        category = SupportThreadCategory.ORDER_SERVICE,
                        title = "Race support thread",
                        message = "Initial support message",
                        venueId = venueId,
                        tableId = table.tableId,
                        tableSessionId = table.tableSessionId,
                        assigneeScope = SupportAssigneeScope.VENUE,
                        createdSource = SupportThreadCreatedSource.GUEST_MINIAPP,
                        messageSource = SupportMessageSource.GUEST_MINIAPP,
                    ),
                )
            val exitHasContextLock = CountDownLatch(1)
            val releaseExit = CountDownLatch(1)
            val supportStarted = CountDownLatch(1)
            val supportMutationInvoked = AtomicBoolean(false)
            val analyticsEventRepository = AnalyticsEventRepository(dataSource)
            val lifecycleRepository =
                GuestTableContextLifecycleRepository(
                    dataSource = dataSource,
                    tableTokenRepository = TableTokenRepository(dataSource),
                    subscriptionRepository = SubscriptionRepository(dataSource),
                    tableSessionRepository = TableSessionRepository(dataSource, analyticsEventRepository),
                    guestTabsRepository = GuestTabsRepository(dataSource),
                    chatContextRepository = ChatContextRepository(dataSource),
                    dialogStateRepository = DialogStateRepository(dataSource, json),
                    teardownCheckpoint = { checkpoint ->
                        if (checkpoint == GuestTableTeardownCheckpoint.AFTER_CONTEXT_LOCK) {
                            exitHasContextLock.countDown()
                            check(releaseExit.await(10, TimeUnit.SECONDS))
                        }
                    },
                )
            val statusBefore = supportThreadStatus(jdbcUrl, initial.thread.id)
            val messagesBefore = supportMessageCount(jdbcUrl, initial.thread.id)
            val readAtBefore = supportReadAt(jdbcUrl, initial.thread.id, platformOwnerId)
            val timingBefore = tableSessionTiming(jdbcUrl, table.tableSessionId)

            coroutineScope {
                val exit =
                    async(Dispatchers.IO) {
                        lifecycleRepository.teardownByChat(
                            actorUserId = platformOwnerId,
                            chatId = chatId,
                        )
                    }
                assertTrue(withContext(Dispatchers.IO) { exitHasContextLock.await(10, TimeUnit.SECONDS) })
                val supportReply =
                    async(Dispatchers.IO) {
                        supportStarted.countDown()
                        lifecycleRepository.withConfirmedPlatformGuestMutation(
                            actorUserId = platformOwnerId,
                            platformOwnerUserId = platformOwnerId,
                            tableToken = table.token,
                            expectedVenueId = venueId,
                            expectedTableId = table.tableId,
                            expectedSessionId = table.tableSessionId,
                            ttl = java.time.Duration.ofHours(4),
                        ) { connection, _ ->
                            supportMutationInvoked.set(true)
                            val locked =
                                supportThreadRepository.lockGuestThread(
                                    connection = connection,
                                    userId = platformOwnerId,
                                    threadId = initial.thread.id,
                                ) ?: error("support thread disappeared")
                            supportThreadRepository.addMessage(
                                connection = connection,
                                threadId = locked.thread.id,
                                authorUserId = platformOwnerId,
                                authorRole = SupportMessageAuthorRole.GUEST,
                                source = SupportMessageSource.GUEST_MINIAPP,
                                text = "This reply must not commit after exit",
                            )
                            supportThreadRepository.markNonBookingThreadReadAfterThreadLock(
                                connection = connection,
                                threadId = locked.thread.id,
                                access = SupportThreadReadAccess.Guest(platformOwnerId),
                            )
                        }
                    }
                assertTrue(withContext(Dispatchers.IO) { supportStarted.await(10, TimeUnit.SECONDS) })
                releaseExit.countDown()

                assertIs<GuestTableTeardownResult.Cleared>(exit.await())
                assertEquals(PlatformGuestTableMutationResult.Denied, supportReply.await())
            }

            assertFalse(supportMutationInvoked.get())
            assertEquals(statusBefore, supportThreadStatus(jdbcUrl, initial.thread.id))
            assertEquals(messagesBefore, supportMessageCount(jdbcUrl, initial.thread.id))
            assertEquals(readAtBefore, supportReadAt(jdbcUrl, initial.thread.id, platformOwnerId))
            assertEquals(timingBefore, tableSessionTiming(jdbcUrl, table.tableSessionId))
            assertEquals(0, telegramContextCount(jdbcUrl, chatId))
            assertEquals(0, tableRowCount(jdbcUrl, "tab"))
        }

    @Test
    fun `venue scoped support ticket creation does not notify staff chat`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-ticket-no-staff-chat")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl, staffChatId = STAFF_CHAT_ID)
            seedUser(jdbcUrl, GUEST_ID)
            val table = seedActiveTableContext(jdbcUrl, venueId)
            val guestToken = issueToken(config, GUEST_ID)

            val response =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "category":"ORDER_SERVICE",
                          "message":"Нужна помощь по заказу",
                          "tableToken":"${table.token}",
                          "tableSessionId":${table.tableSessionId}
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val thread = body.getValue("thread").jsonObject
            assertEquals("false", body["queued"]?.jsonPrimitive?.content ?: "false")
            assertEquals(venueId.toString(), thread.getValue("venueId").jsonPrimitive.content)
            assertEquals(table.tableId.toString(), thread.getValue("tableId").jsonPrimitive.content)
            assertEquals(table.tableSessionId.toString(), thread.getValue("tableSessionId").jsonPrimitive.content)
            assertEquals("VENUE", thread.getValue("assigneeScope").jsonPrimitive.content)
            assertTrue(outboxTexts(jdbcUrl, STAFF_CHAT_ID).isEmpty())
        }

    @Test
    fun `support ticket replies do not notify staff chat`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-ticket-reply-no-staff-chat")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl, staffChatId = STAFF_CHAT_ID)
            seedUser(jdbcUrl, GUEST_ID)
            seedUser(jdbcUrl, OWNER_ID)
            seedUser(jdbcUrl, platformOwnerId)
            seedVenueMember(jdbcUrl, venueId, OWNER_ID, "OWNER")
            val threadId = seedVenueSupportTicket(jdbcUrl, venueId, GUEST_ID)

            val guestToken = issueToken(config, GUEST_ID)
            val ownerToken = issueToken(config, OWNER_ID)
            val platformToken = issueToken(config, platformOwnerId)
            assertTrue(outboxTexts(jdbcUrl, STAFF_CHAT_ID).isEmpty())

            val venueReplyResponse =
                client.post("/api/venue/$venueId/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $ownerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Ответим гостю без staff-chat."}""")
                }
            assertEquals(HttpStatusCode.OK, venueReplyResponse.status)
            assertTrue(outboxTexts(jdbcUrl, STAFF_CHAT_ID).isEmpty())

            val guestReplyResponse =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Спасибо, жду."}""")
                }
            assertEquals(HttpStatusCode.OK, guestReplyResponse.status)
            assertTrue(outboxTexts(jdbcUrl, STAFF_CHAT_ID).isEmpty())

            val platformReplyResponse =
                client.post("/api/platform/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Платформа тоже отвечает без staff-chat."}""")
                }
            assertEquals(HttpStatusCode.OK, platformReplyResponse.status)
            assertTrue(outboxTexts(jdbcUrl, STAFF_CHAT_ID).isEmpty())
        }

    @Test
    fun `denied generic guest notifications persist replies with queued false`() =
        assertGenericReplyTrafficPolicy(guestAllowed = false)

    @Test
    fun `allowed generic guest notifications persist replies with queued true`() =
        assertGenericReplyTrafficPolicy(guestAllowed = true)

    @Test
    fun `guest venue chat is reused and does not notify staff chat or platform support`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-chat-routing")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl, staffChatId = STAFF_CHAT_ID)
            val foreignVenueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, GUEST_ID)
            seedUser(jdbcUrl, OWNER_ID)
            seedUser(jdbcUrl, MANAGER_ID)
            seedUser(jdbcUrl, STAFF_ID)
            seedUser(jdbcUrl, platformOwnerId)
            seedVenueMember(jdbcUrl, venueId, OWNER_ID, "OWNER")
            seedVenueMember(jdbcUrl, foreignVenueId, MANAGER_ID, "MANAGER")
            seedVenueMember(jdbcUrl, venueId, STAFF_ID, "STAFF")

            val guestToken = issueToken(config, GUEST_ID)
            val ownerToken = issueToken(config, OWNER_ID)
            val foreignManagerToken = issueToken(config, MANAGER_ID)
            val staffToken = issueToken(config, STAFF_ID)
            val platformToken = issueToken(config, platformOwnerId)

            val createChatResponse =
                client.post("/api/guest/support/venue-chats") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"venueId":$venueId}""")
                }
            assertEquals(HttpStatusCode.OK, createChatResponse.status)
            val createdChat =
                json.parseToJsonElement(createChatResponse.bodyAsText())
                    .jsonObject
            val thread = createdChat.getValue("thread").jsonObject
            val threadId = thread.getValue("threadId").jsonPrimitive.content.toLong()
            assertEquals("VENUE_CHAT", thread.getValue("threadType").jsonPrimitive.content)
            assertEquals(venueId.toString(), thread.getValue("venueId").jsonPrimitive.content)
            assertEquals("VENUE", thread.getValue("assigneeScope").jsonPrimitive.content)
            assertTrue(createdChat.getValue("messages").jsonArray.isEmpty())
            assertTrue(outboxTexts(jdbcUrl, STAFF_CHAT_ID).isEmpty())

            val duplicateChatResponse =
                client.post("/api/guest/support/venue-chats") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"venueId":$venueId}""")
                }
            assertEquals(HttpStatusCode.OK, duplicateChatResponse.status)
            assertEquals(
                threadId.toString(),
                json.parseToJsonElement(duplicateChatResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content,
            )

            val venueChatList =
                client.get("/api/venue/$venueId/support/threads?threadType=VENUE_CHAT") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, venueChatList.status)
            assertEquals(
                threadId.toString(),
                json.parseToJsonElement(venueChatList.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content,
            )

            val staffList =
                client.get("/api/venue/$venueId/support/threads?threadType=VENUE_CHAT") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, staffList.status)

            val staffDetail =
                client.get("/api/venue/$venueId/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, staffDetail.status)

            val staffReply =
                client.post("/api/venue/$venueId/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"staff must not handle venue chat"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, staffReply.status)

            val foreignVenueDetail =
                client.get("/api/venue/$foreignVenueId/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $foreignManagerToken") }
                }
            assertEquals(HttpStatusCode.NotFound, foreignVenueDetail.status)

            val foreignVenueReply =
                client.post("/api/venue/$foreignVenueId/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $foreignManagerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"foreign venue must not see venue chat"}""")
                }
            assertEquals(HttpStatusCode.NotFound, foreignVenueReply.status)

            val platformVenueChats =
                client.get("/api/platform/support/threads?threadType=VENUE_CHAT") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, platformVenueChats.status)
            assertTrue(
                json.parseToJsonElement(platformVenueChats.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .isEmpty(),
            )

            val venueReply =
                client.post("/api/venue/$venueId/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $ownerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Можно задать вопрос здесь."}""")
                }
            assertEquals(HttpStatusCode.OK, venueReply.status)
            assertTrue(outboxTexts(jdbcUrl, GUEST_ID).last().contains("по чату"))
            assertTrue(outboxTexts(jdbcUrl, STAFF_CHAT_ID).isEmpty())

            val guestReply =
                client.post("/api/guest/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Спасибо, это не обращение."}""")
                }
            assertEquals(HttpStatusCode.OK, guestReply.status)
            assertTrue(outboxTexts(jdbcUrl, STAFF_CHAT_ID).isEmpty())
        }

    @Test
    fun `guest support outside table requires verified venue for venue related categories`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-ticket-venue-required")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            val suspendedVenueId = seedVenue(jdbcUrl, status = "SUSPENDED")
            seedUser(jdbcUrl, GUEST_ID)
            val guestToken = issueToken(config, GUEST_ID)

            val missingVenueResponse =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"category":"ORDER_SERVICE","message":"Проблема с обслуживанием"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, missingVenueResponse.status)

            val missingBookingContextResponse =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"category":"BOOKING","message":"Вопрос по брони"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, missingBookingContextResponse.status)

            val venueScopedResponse =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"category":"ORDER_SERVICE","venueId":$venueId,"message":"Проблема с обслуживанием"}""")
                }
            assertEquals(HttpStatusCode.OK, venueScopedResponse.status)
            val venueScopedThread =
                json.parseToJsonElement(venueScopedResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
            assertEquals(venueId.toString(), venueScopedThread.getValue("venueId").jsonPrimitive.content)
            assertEquals("VENUE", venueScopedThread.getValue("assigneeScope").jsonPrimitive.content)
            assertEquals("SUPPORT_TICKET", venueScopedThread.getValue("threadType").jsonPrimitive.content)

            val hiddenVenueChatResponse =
                client.post("/api/guest/support/venue-chats") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"venueId":$suspendedVenueId}""")
                }
            assertEquals(HttpStatusCode.NotFound, hiddenVenueChatResponse.status)
        }

    @Test
    fun `guest support ticket venue chat and support message routes are rate limited`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-ticket-rate-limit")
            val platformOwnerId = 900001L
            val config =
                buildConfig(
                    jdbcUrl = jdbcUrl,
                    platformOwnerId = platformOwnerId,
                    extra =
                        mapOf(
                            "guest.rateLimit.supportTicket.maxRequests" to "1",
                            "guest.rateLimit.supportTicket.windowSeconds" to "3600",
                            "guest.rateLimit.venueChat.maxRequests" to "1",
                            "guest.rateLimit.venueChat.windowSeconds" to "3600",
                            "guest.rateLimit.supportMessage.maxRequests" to "1",
                            "guest.rateLimit.supportMessage.windowSeconds" to "3600",
                        ),
                )

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, GUEST_ID)
            val guestToken = issueToken(config, GUEST_ID)

            val firstTicket =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"category":"MINIAPP_TECHNICAL","message":"Первое обращение"}""")
                }
            assertEquals(HttpStatusCode.OK, firstTicket.status)
            val ticketId =
                json.parseToJsonElement(firstTicket.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content
                    .toLong()

            val secondTicket =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"category":"MINIAPP_TECHNICAL","message":"Второе обращение"}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, secondTicket.status)

            val firstChat =
                client.post("/api/guest/support/venue-chats") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"venueId":$venueId}""")
                }
            assertEquals(HttpStatusCode.OK, firstChat.status)
            val chatId =
                json.parseToJsonElement(firstChat.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content
                    .toLong()

            val duplicateChatOpen =
                client.post("/api/guest/support/venue-chats") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"venueId":$venueId}""")
                }
            assertEquals(HttpStatusCode.OK, duplicateChatOpen.status)
            assertEquals(
                chatId.toString(),
                json.parseToJsonElement(duplicateChatOpen.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content,
            )

            deleteSupportThread(jdbcUrl, chatId)
            val secondNewChat =
                client.post("/api/guest/support/venue-chats") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"venueId":$venueId}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, secondNewChat.status)

            val firstMessage =
                client.post("/api/guest/support/threads/$ticketId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Первое уточнение"}""")
                }
            assertEquals(HttpStatusCode.OK, firstMessage.status)

            val secondMessage =
                client.post("/api/guest/support/threads/$ticketId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Второе уточнение"}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, secondMessage.status)
        }

    @Test
    fun `support tickets are scoped across guest venue staff and platform`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-ticket-routes")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, GUEST_ID)
            seedUser(jdbcUrl, OWNER_ID)
            seedUser(jdbcUrl, MANAGER_ID)
            seedUser(jdbcUrl, STAFF_ID)
            seedUser(jdbcUrl, platformOwnerId)
            seedVenueMember(jdbcUrl, venueId, OWNER_ID, "OWNER")
            seedVenueMember(jdbcUrl, venueId, MANAGER_ID, "MANAGER")
            seedVenueMember(jdbcUrl, venueId, STAFF_ID, "STAFF")

            val guestToken = issueToken(config, GUEST_ID)
            val ownerToken = issueToken(config, OWNER_ID)
            val managerToken = issueToken(config, MANAGER_ID)
            val staffToken = issueToken(config, STAFF_ID)
            val platformToken = issueToken(config, platformOwnerId)

            val platformOnlyResponse =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "category":"MINIAPP_TECHNICAL",
                          "title":"Mini App зависает",
                          "message":"Не открывается экран заказа",
                          "appVersion":"1.2.3",
                          "correlationId":"corr-support-test"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, platformOnlyResponse.status)
            val platformOnlyThread =
                json.parseToJsonElement(platformOnlyResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
            val platformOnlyThreadId = platformOnlyThread.getValue("threadId").jsonPrimitive.content.toLong()
            assertTrue(platformOnlyThread["venueId"] == null || platformOnlyThread["venueId"] == JsonNull)
            assertEquals(null, supportThreadVenueId(jdbcUrl, platformOnlyThreadId))
            assertEquals("SUPPORT_TICKET", platformOnlyThread.getValue("threadType").jsonPrimitive.content)
            assertEquals("PLATFORM", platformOnlyThread.getValue("assigneeScope").jsonPrimitive.content)
            assertEquals("NEW", platformOnlyThread.getValue("status").jsonPrimitive.content)
            assertEquals("Новый", platformOnlyThread.getValue("statusLabel").jsonPrimitive.content)
            assertEquals("MINIAPP_TECHNICAL", platformOnlyThread.getValue("category").jsonPrimitive.content)

            val platformOnlyUpdatedAt = supportThreadUpdatedAt(jdbcUrl, platformOnlyThreadId)
            val venueListForPlatformOnly =
                client.get("/api/venue/$venueId/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, venueListForPlatformOnly.status)
            assertTrue(
                json.parseToJsonElement(venueListForPlatformOnly.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .isEmpty(),
            )

            val platformList =
                client.get("/api/platform/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, platformList.status)
            assertEquals(
                platformOnlyThreadId.toString(),
                json.parseToJsonElement(platformList.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(platformOnlyUpdatedAt, supportThreadUpdatedAt(jdbcUrl, platformOnlyThreadId))

            val platformReply =
                client.post("/api/platform/support/threads/$platformOnlyThreadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Проверяем техническую проблему."}""")
                }
            assertEquals(HttpStatusCode.OK, platformReply.status)
            assertEquals(
                "WAITING_USER",
                json.parseToJsonElement(platformReply.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
            assertTrue(outboxTexts(jdbcUrl, GUEST_ID).last().contains("Проверяем техническую проблему."))

            val venueThreadId = seedVenueSupportTicket(jdbcUrl, venueId, GUEST_ID)
            val staffListResponse =
                client.get("/api/venue/$venueId/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, staffListResponse.status)

            val managerListResponse =
                client.get("/api/venue/$venueId/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, managerListResponse.status)
            assertEquals(
                venueThreadId.toString(),
                json.parseToJsonElement(managerListResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content,
            )

            val venueReplyResponse =
                client.post("/api/venue/$venueId/support/threads/$venueThreadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $ownerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Разберёмся на месте."}""")
                }
            assertEquals(HttpStatusCode.OK, venueReplyResponse.status)

            val escalateResponse =
                client.post("/api/venue/$venueId/support/threads/$venueThreadId/escalate") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, escalateResponse.status)
            val escalatedThread =
                json.parseToJsonElement(escalateResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
            assertEquals("PLATFORM", escalatedThread.getValue("assigneeScope").jsonPrimitive.content)
            assertEquals("WAITING_USER", escalatedThread.getValue("status").jsonPrimitive.content)

            val venueReplyWhilePlatformAssigned =
                client.post("/api/venue/$venueId/support/threads/$venueThreadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $ownerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Ответ после эскалации."}""")
                }
            assertEquals(HttpStatusCode.Forbidden, venueReplyWhilePlatformAssigned.status)

            val assignBackResponse =
                client.post("/api/platform/support/threads/$venueThreadId/assign") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"assigneeScope":"VENUE","venueId":$venueId}""")
                }
            assertEquals(HttpStatusCode.OK, assignBackResponse.status)
            assertEquals(
                "VENUE",
                json.parseToJsonElement(assignBackResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("assigneeScope")
                    .jsonPrimitive
                    .content,
            )

            val closeResponse =
                client.post("/api/platform/support/threads/$venueThreadId/status") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"status":"CLOSED"}""")
                }
            assertEquals(HttpStatusCode.OK, closeResponse.status)
            assertEquals(
                "CLOSED",
                json.parseToJsonElement(closeResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )

            val auditActions = supportAuditActions(jdbcUrl, venueThreadId)
            assertTrue("SUPPORT_TICKET_SCOPE_CHANGED" in auditActions)
            assertTrue("SUPPORT_TICKET_ESCALATED" in auditActions)
            assertTrue("SUPPORT_TICKET_ASSIGNED" in auditActions)
            assertTrue("SUPPORT_TICKET_STATUS_CHANGED" in auditActions)
            assertTrue("SUPPORT_TICKET_MESSAGE_ADDED" in auditActions)
        }

    @Test
    fun `staff and foreign venue users cannot open or reply to support tickets`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-ticket-rbac-denials")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueAId = seedVenue(jdbcUrl)
            val venueBId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, GUEST_ID)
            seedUser(jdbcUrl, STAFF_ID)
            seedUser(jdbcUrl, MANAGER_ID)
            seedUser(jdbcUrl, platformOwnerId)
            seedVenueMember(jdbcUrl, venueAId, STAFF_ID, "STAFF")
            seedVenueMember(jdbcUrl, venueBId, MANAGER_ID, "MANAGER")
            val threadId = seedVenueSupportTicket(jdbcUrl, venueAId, GUEST_ID)

            val staffToken = issueToken(config, STAFF_ID)
            val foreignManagerToken = issueToken(config, MANAGER_ID)
            val platformToken = issueToken(config, platformOwnerId)

            val staffDetailResponse =
                client.get("/api/venue/$venueAId/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, staffDetailResponse.status)

            val staffReplyResponse =
                client.post("/api/venue/$venueAId/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"staff reply must be forbidden"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, staffReplyResponse.status)

            val foreignVenueDetailResponse =
                client.get("/api/venue/$venueBId/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $foreignManagerToken") }
                }
            assertEquals(HttpStatusCode.NotFound, foreignVenueDetailResponse.status)

            val foreignVenueReplyResponse =
                client.post("/api/venue/$venueBId/support/threads/$threadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $foreignManagerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"foreign venue reply must be hidden"}""")
                }
            assertEquals(HttpStatusCode.NotFound, foreignVenueReplyResponse.status)

            val platformDetailResponse =
                client.get("/api/platform/support/threads/$threadId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, platformDetailResponse.status)
        }

    private fun assertGenericReplyTrafficPolicy(guestAllowed: Boolean) =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-generic-traffic-policy-${if (guestAllowed) "allowed" else "denied"}")
            val platformOwnerId = 900001L
            val actorIds = "$OWNER_ID,$platformOwnerId"
            val allowedIds = if (guestAllowed) "$actorIds,$GUEST_ID" else actorIds
            val config =
                buildConfig(
                    jdbcUrl = jdbcUrl,
                    platformOwnerId = platformOwnerId,
                    extra =
                        mapOf(
                            "telegram.trafficPolicy" to "ALLOWLIST",
                            "telegram.allowedUserIds" to allowedIds,
                            "telegram.allowedChatIds" to allowedIds,
                        ),
                )

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, GUEST_ID)
            seedUser(jdbcUrl, OWNER_ID)
            seedUser(jdbcUrl, platformOwnerId)
            seedVenueMember(jdbcUrl, venueId, OWNER_ID, "OWNER")
            val venueTicketId = seedVenueSupportTicket(jdbcUrl, venueId, GUEST_ID)
            val venueChatId = seedVenueChat(jdbcUrl, venueId, GUEST_ID)
            val platformTicketId = seedVenueSupportTicket(jdbcUrl, venueId, GUEST_ID)
            val ownerToken = issueToken(config, OWNER_ID)
            val platformToken = issueToken(config, platformOwnerId)
            var expectedOutboxCount = 0

            val venueReplyText = "Venue support reply"
            val venueReply =
                client.post("/api/venue/$venueId/support/threads/$venueTicketId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $ownerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"$venueReplyText"}""")
                }
            assertEquals(HttpStatusCode.OK, venueReply.status)
            val venueReplyBody = venueReply.bodyAsText()
            assertEquals(
                guestAllowed.toString(),
                json.parseToJsonElement(venueReplyBody).jsonObject["queued"]?.jsonPrimitive?.content ?: "false",
            )
            assertFalse(venueReplyBody.contains("allowlist", ignoreCase = true))
            assertFalse(venueReplyBody.contains("traffic policy", ignoreCase = true))
            assertEquals(2, supportMessageCount(jdbcUrl, venueTicketId))
            assertEquals(venueReplyText, supportMessageTexts(jdbcUrl, venueTicketId).last())
            assertEquals("WAITING_USER", supportThreadStatus(jdbcUrl, venueTicketId))
            assertTrue(supportReadAt(jdbcUrl, venueTicketId, OWNER_ID) != null)
            assertEquals(
                listOf("SUPPORT_TICKET_MESSAGE_ADDED", "SUPPORT_TICKET_STATUS_CHANGED"),
                supportAuditActions(jdbcUrl, venueTicketId),
            )
            if (guestAllowed) expectedOutboxCount += 1
            assertEquals(expectedOutboxCount, outboxTexts(jdbcUrl, GUEST_ID).size)

            val venueChatReplyText = "Venue chat reply"
            val venueChatReply =
                client.post("/api/venue/$venueId/support/threads/$venueChatId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $ownerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"$venueChatReplyText"}""")
                }
            assertEquals(HttpStatusCode.OK, venueChatReply.status)
            val venueChatReplyBody = venueChatReply.bodyAsText()
            assertEquals(
                guestAllowed.toString(),
                json.parseToJsonElement(venueChatReplyBody).jsonObject["queued"]?.jsonPrimitive?.content ?: "false",
            )
            assertFalse(venueChatReplyBody.contains("allowlist", ignoreCase = true))
            assertFalse(venueChatReplyBody.contains("traffic policy", ignoreCase = true))
            assertEquals(2, supportMessageCount(jdbcUrl, venueChatId))
            assertEquals(venueChatReplyText, supportMessageTexts(jdbcUrl, venueChatId).last())
            assertEquals("WAITING_USER", supportThreadStatus(jdbcUrl, venueChatId))
            assertTrue(supportReadAt(jdbcUrl, venueChatId, OWNER_ID) != null)
            assertTrue(supportAuditActions(jdbcUrl, venueChatId).isEmpty())
            if (guestAllowed) expectedOutboxCount += 1
            assertEquals(expectedOutboxCount, outboxTexts(jdbcUrl, GUEST_ID).size)

            val platformReplyText = "Platform support reply"
            val platformReply =
                client.post("/api/platform/support/threads/$platformTicketId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"$platformReplyText"}""")
                }
            assertEquals(HttpStatusCode.OK, platformReply.status)
            val platformReplyBody = platformReply.bodyAsText()
            assertEquals(
                guestAllowed.toString(),
                json.parseToJsonElement(platformReplyBody).jsonObject["queued"]?.jsonPrimitive?.content ?: "false",
            )
            assertFalse(platformReplyBody.contains("allowlist", ignoreCase = true))
            assertFalse(platformReplyBody.contains("traffic policy", ignoreCase = true))
            assertEquals(2, supportMessageCount(jdbcUrl, platformTicketId))
            assertEquals(platformReplyText, supportMessageTexts(jdbcUrl, platformTicketId).last())
            assertEquals("WAITING_USER", supportThreadStatus(jdbcUrl, platformTicketId))
            assertTrue(supportReadAt(jdbcUrl, platformTicketId, platformOwnerId) != null)
            assertEquals(
                listOf("SUPPORT_TICKET_MESSAGE_ADDED", "SUPPORT_TICKET_STATUS_CHANGED"),
                supportAuditActions(jdbcUrl, platformTicketId),
            )
            if (guestAllowed) expectedOutboxCount += 1
            val guestOutboxTexts = outboxTexts(jdbcUrl, GUEST_ID)
            assertEquals(expectedOutboxCount, guestOutboxTexts.size)
            if (guestAllowed) {
                assertTrue(guestOutboxTexts.any { venueReplyText in it })
                assertTrue(guestOutboxTexts.any { venueChatReplyText in it })
                assertTrue(guestOutboxTexts.any { platformReplyText in it })
            } else {
                assertTrue(guestOutboxTexts.isEmpty())
            }
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        platformOwnerId: Long,
        extra: Map<String, String> = emptyMap(),
    ): MapApplicationConfig =
        MapApplicationConfig(
            *(
                listOf(
                    "ktor.environment" to "test",
                    "app.env" to "test",
                    "db.jdbcUrl" to jdbcUrl,
                    "db.user" to "sa",
                    "db.password" to "",
                    "api.session.jwtSecret" to "secret-secret-secret-secret-secret",
                    "api.session.issuer" to "hookah",
                    "api.session.audience" to "miniapp",
                    "api.session.ttlSeconds" to "3600",
                    "platform.ownerUserId" to platformOwnerId.toString(),
                    "venue.staffInviteSecretPepper" to "invite-pepper",
                ) + extra.toList()
            ).toTypedArray(),
        )

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String {
        val tokenConfig =
            SessionTokenConfig(
                jwtSecret = config.property("api.session.jwtSecret").getString(),
                issuer = config.property("api.session.issuer").getString(),
                audience = config.property("api.session.audience").getString(),
                ttlSeconds = config.property("api.session.ttlSeconds").getString().toLong(),
            )
        return SessionTokenService(tokenConfig).issueToken(userId).token
    }

    private fun seedVenue(
        jdbcUrl: String,
        staffChatId: Long? = null,
        status: String = "PUBLISHED",
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status, staff_chat_id)
                    VALUES ('Support Venue', 'City', 'Address', ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, status)
                    if (staffChatId == null) {
                        statement.setNull(2, java.sql.Types.BIGINT)
                    } else {
                        statement.setLong(2, staffChatId)
                    }
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                VALUES (?, 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
            }
            venueId
        }

    private data class SeededTableContext(
        val tableId: Long,
        val tableSessionId: Long,
        val token: String,
    )

    private fun seedActiveTableContext(
        jdbcUrl: String,
        venueId: Long,
    ): SeededTableContext =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val tableId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_tables (venue_id, table_number, is_active)
                    VALUES (?, 4, TRUE)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val token = "support-table-${UUID.randomUUID()}"
            connection.prepareStatement(
                """
                INSERT INTO table_tokens (token, table_id, is_active)
                VALUES (?, ?, TRUE)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, token)
                statement.setLong(2, tableId)
                statement.executeUpdate()
            }
            val now = Instant.now()
            val sessionId =
                connection.prepareStatement(
                    """
                    INSERT INTO table_sessions (
                        venue_id,
                        table_id,
                        started_at,
                        last_activity_at,
                        expires_at,
                        status
                    )
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.setTimestamp(5, Timestamp.from(now.plusSeconds(3600)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            SeededTableContext(tableId = tableId, tableSessionId = sessionId, token = token)
        }

    private fun seedPlatformTableConfirmation(
        jdbcUrl: String,
        chatId: Long,
        userId: Long,
        venueId: Long,
        table: SeededTableContext,
        tableToken: String = table.token,
    ) {
        ensureTelegramChatContextSchema(jdbcUrl)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO telegram_chat_context (
                    chat_id,
                    user_id,
                    venue_id,
                    table_id,
                    table_token,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.setLong(2, userId)
                statement.setLong(3, venueId)
                statement.setLong(4, table.tableId)
                statement.setString(5, tableToken)
                statement.setTimestamp(6, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    private fun ensureTelegramChatContextSchema(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS telegram_chat_context (
                        chat_id BIGINT PRIMARY KEY,
                        user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
                        venue_id BIGINT NULL REFERENCES venues(id) ON DELETE SET NULL,
                        table_id BIGINT NULL REFERENCES venue_tables(id) ON DELETE SET NULL,
                        table_token VARCHAR(64) NULL,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_telegram_chat_context_user
                    ON telegram_chat_context (user_id)
                    """.trimIndent(),
                )
            }
        }
    }

    private fun clearTelegramTableContext(
        jdbcUrl: String,
        chatId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("DELETE FROM telegram_chat_context WHERE chat_id = ?").use { statement ->
                statement.setLong(1, chatId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedUserExit(
        jdbcUrl: String,
        userId: Long,
        tableSessionId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO guest_table_session_exits (user_id, table_session_id, exited_at)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, tableSessionId)
                statement.setTimestamp(3, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, first_name, updated_at)
                VALUES (?, ?, 'Name', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, "u$userId")
                statement.executeUpdate()
            }
        }
    }

    private fun seedVenueMember(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
        }
    }

    private fun seedVenueSupportTicket(
        jdbcUrl: String,
        venueId: Long,
        guestUserId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val threadId =
                connection.prepareStatement(
                    """
                    INSERT INTO support_threads (
                        venue_id,
                        guest_user_id,
                        category,
                        status,
                        title,
                        last_message_at,
                        thread_type,
                        assignee_scope,
                        created_source
                    )
                    VALUES (?, ?, 'OTHER', 'NEW', 'Проблема в зале', CURRENT_TIMESTAMP,
                            'SUPPORT_TICKET', 'VENUE', 'GUEST_MINIAPP')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, guestUserId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO support_messages (thread_id, author_user_id, author_role, source, text)
                VALUES (?, ?, 'GUEST', 'GUEST_MINIAPP', 'Нужна помощь в зале')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.setLong(2, guestUserId)
                statement.executeUpdate()
            }
            threadId
        }

    private fun seedVenueChat(
        jdbcUrl: String,
        venueId: Long,
        guestUserId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val threadId =
                connection.prepareStatement(
                    """
                    INSERT INTO support_threads (
                        venue_id,
                        guest_user_id,
                        category,
                        status,
                        title,
                        last_message_at,
                        thread_type,
                        assignee_scope,
                        created_source
                    )
                    VALUES (?, ?, 'OTHER', 'NEW', 'Вопрос заведению', CURRENT_TIMESTAMP,
                            'VENUE_CHAT', 'VENUE', 'GUEST_MINIAPP')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, guestUserId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO support_messages (thread_id, author_user_id, author_role, source, text)
                VALUES (?, ?, 'GUEST', 'GUEST_MINIAPP', 'Есть вопрос заведению')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.setLong(2, guestUserId)
                statement.executeUpdate()
            }
            threadId
        }

    private fun supportThreadUpdatedAt(
        jdbcUrl: String,
        threadId: Long,
    ): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT CAST(updated_at AS VARCHAR) AS updated_at
                FROM support_threads
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("updated_at")
                }
            }
        }

    private fun supportThreadVenueId(
        jdbcUrl: String,
        threadId: Long,
    ): Long? =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT venue_id
                FROM support_threads
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong("venue_id").takeIf { !rs.wasNull() }
                }
            }
        }

    private fun supportThreadStatus(
        jdbcUrl: String,
        threadId: Long,
    ): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT status FROM support_threads WHERE id = ?").use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("status")
                }
            }
        }

    private fun supportMessageCount(
        jdbcUrl: String,
        threadId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM support_messages WHERE thread_id = ?").use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun supportMessageTexts(
        jdbcUrl: String,
        threadId: Long,
    ): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT text FROM support_messages WHERE thread_id = ? ORDER BY id",
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getString("text"))
                        }
                    }
                }
            }
        }

    private fun supportReadAt(
        jdbcUrl: String,
        threadId: Long,
        userId: Long,
    ): Instant? =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT last_read_at
                FROM support_thread_reads
                WHERE thread_id = ?
                  AND user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.setLong(2, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getTimestamp("last_read_at")?.toInstant() else null
                }
            }
        }

    private fun deleteSupportReadMarker(
        jdbcUrl: String,
        threadId: Long,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "DELETE FROM support_thread_reads WHERE thread_id = ? AND user_id = ?",
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun tableSessionTiming(
        jdbcUrl: String,
        tableSessionId: Long,
    ): TableSessionTiming =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT last_activity_at, expires_at
                FROM table_sessions
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tableSessionId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    TableSessionTiming(
                        lastActivityAt = rs.getTimestamp("last_activity_at").toInstant(),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    )
                }
            }
        }

    private fun tableRowCount(
        jdbcUrl: String,
        tableName: String,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun telegramContextCount(
        jdbcUrl: String,
        chatId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM telegram_chat_context WHERE chat_id = ?",
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun apiErrorMessage(body: String): String =
        json.parseToJsonElement(body)
            .jsonObject
            .getValue("error")
            .jsonObject
            .getValue("message")
            .jsonPrimitive
            .content

    private fun deleteSupportThread(
        jdbcUrl: String,
        threadId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("DELETE FROM support_messages WHERE thread_id = ?").use { statement ->
                statement.setLong(1, threadId)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM support_threads WHERE id = ?").use { statement ->
                statement.setLong(1, threadId)
                statement.executeUpdate()
            }
        }
    }

    private fun outboxTexts(
        jdbcUrl: String,
        chatId: Long,
    ): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM telegram_outbox
                WHERE chat_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        val payload = json.parseToJsonElement(rs.getString("payload_json")).jsonObject
                        result.add(payload.getValue("text").jsonPrimitive.content)
                    }
                    result
                }
            }
        }

    private fun supportAuditActions(
        jdbcUrl: String,
        threadId: Long,
    ): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT action
                FROM audit_log
                WHERE entity_type = 'support_ticket'
                  AND entity_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        result.add(rs.getString("action"))
                    }
                    result
                }
            }
        }

    private companion object {
        private const val GUEST_ID = 424242L
        private const val OWNER_ID = 666666L
        private const val MANAGER_ID = 777777L
        private const val STAFF_ID = 888888L
        private const val STAFF_CHAT_ID = -100500L
        private const val PLATFORM_RECONFIRM_MESSAGE =
            "Откройте QR-код ещё раз и подтвердите вход в Telegram."
    }

    private data class TableSessionTiming(
        val lastActivityAt: Instant,
        val expiresAt: Instant,
    )
}
