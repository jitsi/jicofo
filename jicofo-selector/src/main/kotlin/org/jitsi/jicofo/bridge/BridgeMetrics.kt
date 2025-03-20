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
        val failingIce = metricsContainer.registerBooleanMetric(
            "bridge_failing_ice",
            "Whether a bridge is currently in the failing ICE state.",
            labelNames = listOf("jvb")
        )
        val endpointsMoved = metricsContainer.registerCounter(
            "bridge_endpoints_moved",
            "Total number of endpoints moved away from a bridge for automatic load redistribution.",
            labelNames = listOf("jvb")
        )
    }
}
