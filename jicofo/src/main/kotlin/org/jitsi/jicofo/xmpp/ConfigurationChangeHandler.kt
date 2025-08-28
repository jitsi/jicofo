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

import org.jitsi.jicofo.ConferenceStore
import org.jitsi.utils.logging2.createLogger
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.filter.MessageTypeFilter
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smackx.muc.packet.MUCUser
import org.jxmpp.jid.EntityBareJid

/**
 * Handle MUC Notification of Configuration Changes messages.
 * See https://xmpp.org/extensions/xep-0045.html#roomconfig-notify
 */
class ConfigurationChangeHandler(
    private val xmppProvider: XmppProvider,
    private val conferenceStore: ConferenceStore
) : XmppProvider.Listener, StanzaListener {
    private val logger = createLogger()

    init {
        xmppProvider.xmppConnection.addSyncStanzaListener(this, MessageTypeFilter.GROUPCHAT)
    }

    override fun processStanza(stanza: Stanza) {
        if (stanza !is Message) {
            logger.error("Not a message")
            return
        }

        MUCUser.from(stanza)?.let { mucUser ->
            // Code 104 is for MUC configuration form changes
            if (mucUser.status?.any { it.code == 104 } == true) {
                val roomJid = stanza.from
                if (roomJid !is EntityBareJid) {
                    logger.info("An occupant sending status 104?")
                    return
                }
                logger.info("Configuration changed for $roomJid")
                conferenceStore.getConference(roomJid)?.let { conference ->
                    conference.chatRoom?.let { chatRoom ->
                        chatRoom.queueXmppTask { chatRoom.reloadConfiguration() }
                    } ?: run {
                        logger.info("Configuration changed, but we don't have a chat room for ${conference.roomName}")
                    }
                } ?: run {
                    logger.info("Configuration changed for unknown conference.")
                }
            }
        }
    }

    fun shutdown() {
        xmppProvider.xmppConnection.removeSyncStanzaListener(this)
    }
}
