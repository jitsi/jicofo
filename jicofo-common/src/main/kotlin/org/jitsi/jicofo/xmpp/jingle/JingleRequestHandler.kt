/*
 * Jicofo, the Jitsi Conference Focus.
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
package org.jitsi.jicofo.xmpp.jingle

import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension
import org.jitsi.xmpp.extensions.jingle.JingleIQ
import org.jivesoftware.smack.packet.StanzaError

/**
 * Listener class notified about Jingle requests received during a session.
 *
 * @author Pawel Domas
 */
interface JingleRequestHandler {
    /**
     * A 'source-add' IQ was received.
     *
     * @return a [StanzaError] if an error should be returned as response to the original request or null if
     * processing was successful.
     */
    fun onAddSource(jingleSession: JingleSession, contents: List<ContentPacketExtension>): StanzaError? = null

    /**
     * A 'source-remove' IQ was received.
     *
     * @return a [StanzaError] if an error should be returned as response to the original request or null if
     * processing was successful.
     */
    fun onRemoveSource(jingleSession: JingleSession, contents: List<ContentPacketExtension>): StanzaError? = null

    /**
     * A 'session-accept' IQ was received.
     *
     * @return a [StanzaError] if an error should be returned as response to the original request or null if
     * processing was successful.
     */
    fun onSessionAccept(jingleSession: JingleSession, contents: List<ContentPacketExtension>): StanzaError? = null

    /**
     * A 'session-info' IQ was received.
     *
     * @return a [StanzaError] if an error should be returned as response to the original request or null if
     * processing was successful.
     */
    fun onSessionInfo(jingleSession: JingleSession, iq: JingleIQ): StanzaError? = null

    /**
     * A 'session-terminate' IQ was received.
     *
     * @return a [StanzaError] if an error should be returned as response to the original request or null if
     * processing was successful.
     */
    fun onSessionTerminate(jingleSession: JingleSession, iq: JingleIQ): StanzaError? = null

    /**
     * A 'transport-info' IQ was received.
     */
    fun onTransportInfo(jingleSession: JingleSession, contents: List<ContentPacketExtension>): StanzaError? = null

    /**
     * A 'transport-accept' IQ was received.
     *
     * @return a [StanzaError] if an error should be returned as response to the original request or null if
     * processing was successful.
     */
    fun onTransportAccept(jingleSession: JingleSession, contents: List<ContentPacketExtension>): StanzaError? = null

    /**
     * A 'transport-reject' IQ was received.
     */
    fun onTransportReject(jingleSession: JingleSession, iq: JingleIQ) { }
}

/** Export a default impl so it can be used in java without -Xjvm-default */
class NoOpJingleRequestHandler : JingleRequestHandler
