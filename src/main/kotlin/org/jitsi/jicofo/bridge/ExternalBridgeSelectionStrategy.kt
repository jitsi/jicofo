package org.jitsi.jicofo.bridge

import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.json.simple.JSONObject
import org.json.simple.JSONValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Suppress("unused")
class ExternalBridgeSelectionStrategy() : BridgeSelectionStrategy() {
    private val httpClient: HttpClient = HttpClient
        .newBuilder()
        .connectTimeout(ExternalBridgeSelectionStrategyConfig.config.timeout)
        .build()

    private val logger: Logger = LoggerImpl(ExternalBridgeSelectionStrategy::class.simpleName)

    private val fallbackStrategy: BridgeSelectionStrategy? by lazy {
        val fallbackStrategyName = ExternalBridgeSelectionStrategyConfig.config.fallbackStrategy ?: return@lazy null
        try {
            val clazz = Class.forName("${javaClass.getPackage().name}.$fallbackStrategyName")
            clazz.getConstructor().newInstance() as BridgeSelectionStrategy
        } catch (e: Exception) {
            val clazz = Class.forName(fallbackStrategyName)
            clazz.getConstructor().newInstance() as BridgeSelectionStrategy
        }
    }

    private fun fallback(
        bridges: MutableList<Bridge>?,
        conferenceBridges: MutableMap<Bridge, Int>?,
        participantRegion: String?
    ): Bridge {
        if (fallbackStrategy == null) {
            throw Exception("External bridge selection failed and no fallbackStrategy was provided.")
        }
        return fallbackStrategy!!.doSelect(bridges, conferenceBridges, participantRegion)
    }

    override fun doSelect(
        bridges: MutableList<Bridge>?,
        conferenceBridges: MutableMap<Bridge, Int>?,
        participantRegion: String?
    ): Bridge {
        val url = ExternalBridgeSelectionStrategyConfig.config.url
            ?: throw Exception("ExternalBridgeSelectionStrategy requires url to be provided")

        val requestBody = JSONObject()
        requestBody["bridges"] = bridges?.map {
            mapOf(
                "jid" to it.jid.toString(),
                "version" to it.version,
                "colibri2" to it.supportsColibri2(),
                "relay_id" to it.relayId,
                "region" to it.region,
                "stress" to it.stress,
                "operational" to it.isOperational,
                "graceful_shutdown" to it.isInGracefulShutdown,
                "draining" to it.isDraining,
            )
        }
        requestBody["conference_bridges"] = conferenceBridges?.mapKeys { it.key.jid.toString() }
        requestBody["participant_region"] = participantRegion
        requestBody["fallback_strategy"] = ExternalBridgeSelectionStrategyConfig.config.fallbackStrategy

        val request = HttpRequest
            .newBuilder()
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toJSONString()))
            .uri(URI.create(url))
            .headers("Content-Type", "application/json")
            .timeout(ExternalBridgeSelectionStrategyConfig.config.timeout)
            .build()

        val response: HttpResponse<String>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exc: Exception) {
            logger.error("ExternalBridgeSelectionStrategy: HTTP request failed with ${exc}, using fallback strategy")
            return fallback(bridges, conferenceBridges, participantRegion)
        }

        val statusCode = response.statusCode()
        if (statusCode !in 200..299) {
            logger.error("ExternalBridgeSelectionStrategy: HTTP request failed with ${statusCode}, using fallback strategy")
            return fallback(bridges, conferenceBridges, participantRegion)
        }

        val responseBody: JSONObject
        try {
            responseBody = JSONValue.parseWithException(response.body()) as JSONObject
        } catch (exc: Exception) {
            logger.error("ExternalBridgeSelectionStrategy: HTTP response parsing failed with ${exc}, using fallback strategy")
            return fallback(bridges, conferenceBridges, participantRegion)
        }

        val selectedBridgeIndex = responseBody["selected_bridge_index"] as? Number

        if (selectedBridgeIndex == null) {
            logger.error("ExternalBridgeSelectionStrategy: HTTP response selected_bridge_index missing or invalid, using fallback strategy")
            return fallback(bridges, conferenceBridges, participantRegion)
        }

        val bridge = bridges!![selectedBridgeIndex.toInt()]
        logger.info("ExternalBridgeSelectionStrategy: participantRegion=${participantRegion}, bridge=${bridge}")
        return bridge
    }
}