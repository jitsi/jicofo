package org.jitsi.jicofo.bridge.colibri

import org.jitsi.jicofo.conference.source.ConferenceSourceMap
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension

/**
 * Describes the resources allocated on a bridge for a single endpoint.
 */
data class ColibriAllocation(
    /** The sources advertised by the bridge. */
    val sources: ConferenceSourceMap,
    /** Encodes the bridge-side transport information (ICE candidates, DTLS fingerprint, etc.). */
    val transport: IceUdpTransportPacketExtension,
    /** The region of the bridge */
    val region: String?,
    /** An identifier for the session */
    val bridgeSessionId: String?,
    /** The SCTP port (used for both local and remote port) advertised by the bridge, if any */
    val sctpPort: Int?
)
