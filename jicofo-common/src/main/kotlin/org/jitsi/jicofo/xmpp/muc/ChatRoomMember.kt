/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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
package org.jitsi.jicofo.xmpp.muc

import org.jitsi.jicofo.xmpp.Features
import org.jitsi.utils.OrderedJsonObject
import org.jivesoftware.smack.packet.Presence
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid

/**
 * This interface represents chat room participants. Instances are retrieved
 * through implementations of the <tt>ChatRoom</tt> interface and offer methods
 * that allow querying member properties, such as, moderation permissions,
 * associated chat room and other.
 *
 * @author Emil Ivov
 * @author Boris Grozev
 */
interface ChatRoomMember {
    val chatRoom: ChatRoom

    /** The ID of this member. Set to the resource part of the occupant JID. */
    val name: String

    /** The role of this chat room member in its containing room. */
    val role: MemberRole

    /** Returns the JID of the user (outside the MUC), i.e. the "real" JID. It may not always be known. */
    val jid: Jid?

    /** Get the latest [SourceInfo]s advertised by this chat member in presence. */
    val sourceInfos: Set<SourceInfo>

    /** The occupant JID of the member in the chat room */
    val occupantJid: EntityFullJid

    /** The last [Presence] packet received for this member (or null it if no presence has been received yet) */
    val presence: Presence?

    val isJigasi: Boolean
    val isTranscriber: Boolean
    val isJibri: Boolean
    val isAudioMuted: Boolean
    val isVideoMuted: Boolean

    /** Gets the region (e.g. "us-east") of this [ChatRoomMember]. */
    val region: String?

    /** The statistics id if any. */
    val statsId: String?

    /** The supported video codecs if any */
    val videoCodecs: List<String>?

    /**
     * The list of features advertised as XMPP capabilities. Note that although the features are cached (XEP-0115),
     * the first time [features] is accessed it may block waiting for a disco#info response!
     */
    val features: Set<Features>

    val debugState: OrderedJsonObject
}
