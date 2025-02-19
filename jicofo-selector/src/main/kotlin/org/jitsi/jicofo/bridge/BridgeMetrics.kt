package org.jitsi.jicofo.bridge

import org.jitsi.jicofo.metrics.JicofoMetricsContainer.Companion.instance as metricsContainer
class BridgeMetrics {
    companion object {
        /** Total number of participants that requested a restart for a specific bridge. */
        val restartRequestsMetric = metricsContainer.registerCounter(
            "bridge_restart_requests_total",
            "Total number of requests to restart a bridge",
            labelNames = listOf("jvb")
        )
        val endpoints = metricsContainer.registerLongGauge(
            "bridge_endpoints",
            "The number of endpoints on a bridge.",
            labelNames = listOf("jvb")
        )
    }
}
