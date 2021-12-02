/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021-Present 8x8, Inc.
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
package org.jitsi.jicofo.conference.colibri

import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension

/**
 * Additional structures associated with a [Participant] that may need to persist between [BridgeSession]s (when the
 * participant moves from one bridge to another).
 * Boris: I am not sure if all of these are actually needed. I'm moving them here in an attempt to isolate them to the
 * "old colibri" code as opposed to [Participant] itself.
 */
class ParticipantInfo(
    /** The map of the most recently received RTP description for each Colibri content. */
    var rtpDescriptionMap: Map<String, RtpDescriptionPacketExtension>? = null,
    /** Whether this participant has an associated active [BridgeSession]? */
    var hasColibriSession: Boolean = false,
    var transport: IceUdpTransportPacketExtension? = null,
    var colibriChannels: ColibriConferenceIQ? = null
)
