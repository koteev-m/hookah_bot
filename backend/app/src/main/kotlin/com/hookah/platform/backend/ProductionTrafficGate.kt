package com.hookah.platform.backend

import com.hookah.platform.backend.telegram.TelegramLongPollingWorker.PollingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** Operator decisions are bound to this JVM. Reusing a release SHA never reuses admission. */
internal class ProductionTrafficGate(
    private val directory: Path,
    revision: String,
    private val pollingMaxAgeNanos: Long = 60_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val logger = LoggerFactory.getLogger(ProductionTrafficGate::class.java)
    val instance: String = UUID.randomUUID().toString()
    private val decision = "$revision $instance"

    @Volatile
    private var poller: Job? = null

    fun recoveryHeld(): Boolean = !Files.notExists(directory.resolve("recovery-hold"), LinkOption.NOFOLLOW_LINKS)

    fun recoveryInstallation(): Boolean =
        recoveryHeld() || !Files.notExists(directory.resolve("recovery-accepted"), LinkOption.NOFOLLOW_LINKS)

    fun telegramRecoveryPending(): Boolean =
        recoveryInstallation() &&
            (
                !Files.isRegularFile(directory.resolve("telegram-recovery-cleared"), LinkOption.NOFOLLOW_LINKS) ||
                    !Files.notExists(directory.resolve("telegram-backlog"), LinkOption.NOFOLLOW_LINKS)
            )

    private fun pollingAllowed(): Boolean = revocation == null && matches("activate") && !telegramRecoveryPending()

    fun telegramDispatchAllowed(): Boolean = pollingState(true) == "running" && admitsHttp(true)

    fun recoveryProbeAllowed(
        method: String,
        path: String,
    ): Boolean = method == "GET" && (path in probePaths || recoveryAsset.matches(path))

    private fun matches(name: String): Boolean =
        try {
            !recoveryHeld() && Files.readString(directory.resolve(name)).trim() == decision
        } catch (_: IOException) {
            false
        }

    @Volatile
    private var lastPoll: Long? = null

    @Volatile
    private var revocation: String? = null

    @Volatile
    private var degradedSince: Long? = null

    private var databaseProbe: Deferred<Boolean>? = null

    @Synchronized
    private fun revoke(reason: String) {
        if (revocation != null) return
        revocation = reason
        logger.error("Production admission revoked reason={}", reason)
    }

    fun pollingFailed(failure: PollingFailure) {
        if (!failure.retryable) {
            revoke("POLLING_${failure.name}")
        } else if (degradedSince == null) {
            degradedSince = nanoTime()
            logger.warn("Production Telegram polling degraded reason={}", failure)
        }
    }

    fun pollingSucceeded() {
        lastPoll = nanoTime()
        degradedSince?.let {
            logger.info("Production Telegram polling recovered after {} ms", (nanoTime() - it) / 1_000_000)
        }
        degradedSince = null
    }

    fun pollingState(enabled: Boolean): String =
        when {
            revocation != null -> "revoked"
            !enabled -> "disabled"
            poller?.isActive != true -> "standby"
            degradedSince != null -> "degraded"
            lastPoll == null -> "standby"
            lastPoll?.let { nanoTime() - it <= pollingMaxAgeNanos } == true -> "running"
            else -> "degraded"
        }

    @Volatile
    private var admittedPoller: Job? = null

    // The operator requires a fresh poll to open. A later transport outage pauses Telegram
    // dispatch, while the same admitted JVM continues to serve DB-checked HTTP.
    fun admitsHttp(enabled: Boolean): Boolean {
        if (revocation != null || !matches("open") || !matches("activate") || enabled && telegramRecoveryPending()) {
            admittedPoller = null
            return false
        }
        if (!enabled) return true
        val current = poller?.takeIf { it.isActive } ?: return false
        if (current !== admittedPoller && pollingState(true) == "running") admittedPoller = current
        return current === admittedPoller
    }

    // Share only an in-flight JDBC round trip. Completed successes never admit a later request.
    suspend fun checkDatabase(
        scope: CoroutineScope,
        probe: suspend () -> Boolean,
    ): Boolean {
        val check =
            synchronized(this) {
                databaseProbe?.takeUnless { it.isCompleted } ?: scope.async {
                    val ok =
                        try {
                            probe()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                    if (!ok && matches("activate")) revoke("DATABASE")
                    ok
                }.also { databaseProbe = it }
            }
        return withTimeoutOrNull(2_500) { check.await() } ?: run {
            if (matches("activate")) revoke("DATABASE_TIMEOUT")
            false
        }
    }

    suspend fun awaitActivation() {
        while (!telegramDispatchAllowed()) delay(200)
    }

    fun supervise(
        scope: CoroutineScope,
        polling: Boolean = false,
        botEnabled: Boolean = true,
        start: suspend () -> Job,
    ): Job =
        scope.launch {
            while (isActive) {
                fun allowed() = if (polling) pollingAllowed() else admitsHttp(botEnabled)
                while (!allowed()) delay(200)
                if (polling) {
                    lastPoll = null
                    degradedSince = null
                }
                val worker = start()
                if (polling) poller = worker
                try {
                    while (worker.isActive && allowed()) delay(200)
                    check(worker.isActive || !allowed()) { "Production worker stopped during admission" }
                } finally {
                    if (polling) poller = null
                    worker.cancelAndJoin()
                }
            }
        }

    private companion object {
        val probePaths = setOf("/health", "/db/health", "/version", "/miniapp/")
        val recoveryAsset = Regex("/miniapp/assets/[A-Za-z0-9_-][A-Za-z0-9_.-]*")
    }
}
