package org.jitsi.jicofo.conference.colibri

import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension

/**
 * Describes the resources allocated on a bridge for a single endpoint.
 */
data class ColibriAllocation(
    /** The sources advertised by the bridge. */
    val sources: ConferenceSourceMap,
    /** Encodes the bridge-side transport information (ICE candidates, DTLS fingerprint, etc.). */
    val transport: IceUdpTransportPacketExtension
)