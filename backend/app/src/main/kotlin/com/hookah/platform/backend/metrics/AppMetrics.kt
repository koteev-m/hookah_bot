package com.hookah.platform.backend.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class AppMetrics {
    private val inboundQueueDepthValue = AtomicLong(0)
    private val outboundQueueDepthValue = AtomicLong(0)

    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    private val outboundSendSuccessCounter: Counter =
        Counter
            .builder("outbound_send_success_total")
            .description("Количество успешных отправок в Telegram outbox")
            .register(registry)

    private val outboundSendFailedCounter: Counter =
        Counter
            .builder("outbound_send_failed_total")
            .description("Количество неуспешных попыток отправки в Telegram outbox")
            .register(registry)

    private val outbound429Counter: Counter =
        Counter
            .builder("outbound_429_total")
            .description("Количество ответов Telegram API с кодом 429")
            .register(registry)

    private val webhookProcessingLagTimer: Timer =
        Timer
            .builder("webhook_processing_lag_seconds")
            .description("Lag между приёмом Telegram webhook и началом обработки")
            .publishPercentileHistogram()
            .register(registry)

    init {
        Gauge
            .builder("inbound_queue_depth", inboundQueueDepthValue) { it.get().toDouble() }
            .description("Текущая глубина inbound webhook очереди")
            .register(registry)
        Gauge
            .builder("outbound_queue_depth", outboundQueueDepthValue) { it.get().toDouble() }
            .description("Текущая глубина outbound очереди отправки")
            .register(registry)
    }

    fun setInboundQueueDepth(depth: Long) {
        inboundQueueDepthValue.set(depth.coerceAtLeast(0L))
    }

    fun setOutboundQueueDepth(depth: Long) {
        outboundQueueDepthValue.set(depth.coerceAtLeast(0L))
    }

    fun incrementOutboundSendSuccess() {
        outboundSendSuccessCounter.increment()
    }

    fun incrementOutboundSendFailed() {
        outboundSendFailedCounter.increment()
    }

    fun incrementOutbound429() {
        outbound429Counter.increment()
    }

    fun recordWebhookProcessingLag(duration: Duration) {
        if (!duration.isNegative) {
            webhookProcessingLagTimer.record(duration)
        }
    }

    fun scrape(): String = registry.scrape()
}
