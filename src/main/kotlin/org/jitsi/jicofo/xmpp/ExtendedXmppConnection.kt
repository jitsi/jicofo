/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2020 - present 8x8, Inc
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

import org.jitsi.impl.protocol.xmpp.OperationFailedException
import org.jitsi.utils.logging2.Logger
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.StanzaCollector
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jxmpp.jid.parts.Resourcepart

/**
 * Extend a Smack [XMPPConnection] with utility methods.
 */
class ExtendedXmppConnectionImpl(
    /**
     * This still has to be exposed because Smack exposes certain services based on the [AbstractXMPPConnection]
     * instance (e.g. [ReconnectionManager]).
     */
    private val xmppConnection: AbstractXMPPConnection,
    parentLogger: Logger
) : XMPPConnection by xmppConnection, ExtendedXmppConnection {

    val logger: Logger = parentLogger.createChildLogger(javaClass.name)

    override fun tryToSendStanza(packet: Stanza) {
        try {
            sendStanza(packet)
        } catch (e: SmackException.NotConnectedException) {
            logger.error("No connection - unable to send packet: " + packet.toXML(), e)
        } catch (e: InterruptedException) {
            logger.error("Failed to send packet: " + packet.toXML().toString(), e)
        }
    }

    @Throws(OperationFailedException::class)
    override fun sendPacketAndGetReply(stanza: IQ): IQ? {
        return try {
            val packetCollector: StanzaCollector = createStanzaCollectorAndSend(stanza)
            try {
                packetCollector.nextResult()
            } finally {
                packetCollector.cancel()
            }
        } catch (e: InterruptedException)
        {
            throw OperationFailedException(
                "No response or failed otherwise: " + stanza.toXML(),
                OperationFailedException.GENERAL_ERROR,
                e
            )
        } catch (e: SmackException.NotConnectedException) {
            throw OperationFailedException(
                "No connection - unable to send packet: " + stanza.toXML(),
                OperationFailedException.PROVIDER_NOT_REGISTERED,
                e
            )
        }
    }

    /**
     * This is part of [AbstractXMPPConnection], but not [XMPPConnection], so we have to delegate explicitly.
     */
    fun connect(): AbstractXMPPConnection? =  xmppConnection.connect()

    /**
     * This is part of [AbstractXMPPConnection], but not [XMPPConnection], so we have to delegate explicitly.
     */
    fun disconnect() = xmppConnection.disconnect()

    /**
     * This is part of [AbstractXMPPConnection], but not [XMPPConnection], so we have to delegate explicitly.
     */
    fun login(username: CharSequence , password: String?, resource: Resourcepart) =
        xmppConnection.login(username, password, resource)

    override fun getSmackXMPPConnection() = xmppConnection
}

interface ExtendedXmppConnection : XMPPConnection {
    fun tryToSendStanza(packet: Stanza)
    @Throws(OperationFailedException::class)
    fun sendPacketAndGetReply(stanza: IQ): IQ?

    /**
     * Get the [AbstractXMPPConnection] that Smack knows about.
     */
    fun getSmackXMPPConnection(): AbstractXMPPConnection
}