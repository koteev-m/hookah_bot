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

    private fun matches(name: String): Boolean =
        try {
            Files.readString(directory.resolve(name)).trim() == decision
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
        if (revoked || !matches("open") || !matches("activate")) return false
        if (enabled && pollingState(true) != "running") revoked = true
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
        while (!matches("activate")) delay(200)
    }

    fun supervise(
        scope: CoroutineScope,
        polling: Boolean = false,
        start: suspend () -> Job,
    ): Job =
        scope.launch {
            while (isActive) {
                awaitActivation()
                if (polling) lastPoll = null
                val worker = start()
                if (polling) poller = worker
                try {
                    while (worker.isActive && matches("activate")) delay(200)
                    check(worker.isActive || !matches("activate")) { "Production worker stopped during admission" }
                } finally {
                    if (polling) poller = null
                    worker.cancelAndJoin()
                }
            }
        }
}
