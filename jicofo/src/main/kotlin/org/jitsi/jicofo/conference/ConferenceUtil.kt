/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2021 - present 8x8, Inc
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
package org.jitsi.jicofo.conference

import org.jitsi.impl.protocol.xmpp.ChatRoom
import org.jitsi.jicofo.visitors.VisitorsConfig
import org.jitsi.jicofo.xmpp.XmppProvider
import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceRtcpmuxPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension

internal fun ContentPacketExtension.toMedia(): Media? {
    val media = when (name.lowercase()) {
        "audio" -> Media.getBuilder().setType(MediaType.AUDIO)
        "video" -> Media.getBuilder().setType(MediaType.VIDEO)
        else -> return null
    }

    getFirstChildOfType(RtpDescriptionPacketExtension::class.java)?.let {
        it.payloadTypes.forEach { payloadTypePacketExtension ->
            media.addPayloadType(payloadTypePacketExtension)
        }
        it.extmapList.forEach { rtpHdrExtPacketExtension ->
            media.addRtpHdrExt(rtpHdrExtPacketExtension)
        }
        it.extmapAllowMixed?.let { allowMixed -> media.setExtmapAllowMixed(allowMixed) }
    }
    return media.build()
}

internal fun List<ContentPacketExtension>.getTransport(): IceUdpTransportPacketExtension? {
    val transport = firstNotNullOfOrNull { it.getFirstChildOfType(IceUdpTransportPacketExtension::class.java) }
        ?: return null
    // Insert rtcp-mux if missing. Is this still needed?
    if (!transport.isRtcpMux) {
        transport.addChildExtension(IceRtcpmuxPacketExtension())
    }
    return transport
}

internal fun selectVisitorNode(
    existingNodes: Map<String, ChatRoom>,
    allNodes: List<XmppProvider>
): String? {

    val min = existingNodes.minByOrNull { it.value.membersCount }
    if (min != null && min.value.membersCount < VisitorsConfig.config.maxVisitorsPerNode) {
        return min.key
    }

    val unusedNodes = allNodes.filterNot { existingNodes.keys.contains(it.config.name) }
    return unusedNodes.firstOrNull { it.registered }?.config?.name ?: min?.key
}
