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
package org.jitsi.jicofo.xmpp

import org.jitsi.jicofo.xmpp.jingle.JingleSession
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.JingleAction
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.Jid

fun createSessionInitiate(from: Jid, to: Jid, sid: String, contents: List<ContentPacketExtension>) =
    JingleIQ(JingleAction.SESSION_INITIATE, sid).apply {
        this.from = from
        this.to = to
        initiator = from
        type = IQ.Type.set
        contents.forEach { addContent(it) }
    }

fun createTransportReplace(from: Jid, session: JingleSession, contents: List<ContentPacketExtension>) =
    JingleIQ(JingleAction.TRANSPORT_REPLACE, session.sid).apply {
        this.from = from
        this.to = session.remoteJid
        initiator = from
        type = IQ.Type.set
        contents.forEach { addContent(it) }
    }
