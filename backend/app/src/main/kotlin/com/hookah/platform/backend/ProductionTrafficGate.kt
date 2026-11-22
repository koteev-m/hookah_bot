package com.hookah.platform.backend

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

    private fun pollingAllowed(): Boolean = !revoked && matches("activate") && !telegramRecoveryPending()

    fun telegramDispatchAllowed(): Boolean = pollingAllowed() && matches("open") && pollingState(true) == "running"

    fun recoveryProbeAllowed(
        method: String,
        path: String,
    ): Boolean = method == "GET" && (path in probePaths || recoveryAsset.matches(path))

    @Volatile
    private var recoveryPollReadyAt: Long? = null

    fun recordRecoveryPoll(state: String): String {
        recoveryPollReadyAt = if (state == "empty") nanoTime() else null
        return state
    }

    fun startDatabaseWriters(
        scope: CoroutineScope,
        botEnabled: Boolean = false,
        start: () -> Unit,
    ) {
        if (!recoveryInstallation()) {
            start()
            return
        }
        scope.launch {
            while (!admitsHttp(botEnabled)) delay(200)
            start()
        }
    }

    private fun matches(name: String): Boolean =
        try {
            !recoveryHeld() && Files.readString(directory.resolve(name)).trim() == decision
        } catch (_: IOException) {
            false
        }

    @Volatile
    private var lastPoll: Long? = null

    @Volatile
    private var revoked = false
    private var databaseProbe: Deferred<Boolean>? = null

    fun pollingSucceeded() {
        val now = nanoTime()
        if (lastPoll?.let { now - it > pollingMaxAgeNanos } == true && matches("open")) revoked = true
        lastPoll = now
    }

    fun pollingState(enabled: Boolean): String =
        when {
            revoked -> "revoked"
            !enabled -> "disabled"
            poller?.isActive == true && lastPoll?.let { nanoTime() - it <= pollingMaxAgeNanos } == true -> "running"
            else -> "standby"
        }

    fun admitsHttp(enabled: Boolean): Boolean {
        if (revoked || enabled && telegramRecoveryPending() || !matches("open") || !matches("activate")) return false
        if (enabled && pollingState(true) != "running") {
            // The recovery readiness probe never starts a worker. Give its first real poll
            // the normal freshness window after the atomic handover, with HTTP still closed.
            if (recoveryPollReadyAt?.let { nanoTime() - it <= pollingMaxAgeNanos } == true) return false
            revoked = true
        } else {
            recoveryPollReadyAt = null
        }
        return !revoked
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
                    if (!ok && matches("activate")) revoked = true
                    ok
                }.also { databaseProbe = it }
            }
        return withTimeoutOrNull(2_500) { check.await() } ?: run {
            if (matches("activate")) revoked = true
            false
        }
    }

    suspend fun awaitActivation() {
        while (!telegramDispatchAllowed()) delay(200)
    }

    fun supervise(
        scope: CoroutineScope,
        polling: Boolean = false,
        start: suspend () -> Job,
    ): Job =
        scope.launch {
            while (isActive) {
                fun allowed() = if (polling) pollingAllowed() else telegramDispatchAllowed()
                while (!allowed()) delay(200)
                if (polling) lastPoll = null
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
