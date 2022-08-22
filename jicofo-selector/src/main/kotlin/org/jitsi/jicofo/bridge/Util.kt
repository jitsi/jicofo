package org.jitsi.jicofo.bridge

import org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension

fun ColibriStatsExtension.getDouble(name: String): Double? = try {
    getValueAsString(name)?.toDouble()
} catch (e: Exception) {
    null
}
