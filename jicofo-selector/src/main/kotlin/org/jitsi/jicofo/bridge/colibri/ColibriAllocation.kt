/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
