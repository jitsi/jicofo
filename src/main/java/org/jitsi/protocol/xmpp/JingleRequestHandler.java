/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.protocol.xmpp;

import org.jitsi.xmpp.extensions.jingle.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Listener class notified about Jingle requests received during the session.
 *
 * @author Pawel Domas
 */
public interface JingleRequestHandler
{
    /**
     * Callback fired when 'source-add' proprietary Jingle notification is
     * received.
     *
     * @param jingleSession the session that has received the notification.
     * @param contents contents list that describe media SSRCs. We expect
     *                 to find {@link net.java.sip.communicator.impl.protocol
     *                 .jabber.extensions.colibri.SourcePacketExtension} inside
     *                 of <tt>RtpDescriptionPacketExtension</tt> or in the
     *                 <tt>ContentPacketExtension</tt> directly.
     *
     * @return <tt>XMPPError</tt> if an error should be returned as response to
     * the original request or <tt>null</tt> if the processing was successful.
     */
    XMPPError onAddSource(JingleSession jingleSession,
                     List<ContentPacketExtension> contents);

    /**
     * Callback fired when 'source-remove' proprietary Jingle notification is
     * received.
     *
     * @param jingleSession the session that has received the notification.
     * @param contents contents list that describe media SSRCs. We expect
     *                 to find {@link net.java.sip.communicator.impl.protocol
     *                 .jabber.extensions.colibri.SourcePacketExtension} inside
     *                 of <tt>RtpDescriptionPacketExtension</tt> or in the
     *                 <tt>ContentPacketExtension</tt> directly.
     *
     * @return <tt>XMPPError</tt> if an error should be returned as response to
     * the original request or <tt>null</tt> if the processing was successful.
     */
    XMPPError onRemoveSource(JingleSession jingleSession,
                             List<ContentPacketExtension> contents);

    /**
     * Callback fired when 'session-accept' is received from the client.
     *
     * @param jingleSession the session that has received the notification.
     * @param answer content list that describe peer media offer.
     *
     * @return <tt>XMPPError</tt> if an error should be returned as response to
     * the original request or <tt>null</tt> if the processing was successful.
     */
    XMPPError onSessionAccept(JingleSession jingleSession,
                         List<ContentPacketExtension> answer);

    /**
     * Callback fired when 'session-info' is received from the client.
     *
     * @param jingleSession the session that has received the notification.
     * @param iq the full message sent by the client.
     *
     * @return <tt>XMPPError</tt> if an error should be returned as response to
     * the original request or <tt>null</tt> if the processing was successful.
     */
    XMPPError onSessionInfo(JingleSession jingleSession, JingleIQ iq);

    /**
     * Callback fired when 'session-terminate' is received from the client.
     *
     * @param jingleSession the session that has received the notification.
     * @param iq the full message sent by the client.
     *
     * @return <tt>XMPPError</tt> if an error should be returned as response to the original request or <tt>null</tt>
     * to reply with RESULT.
     */
    XMPPError onSessionTerminate(JingleSession jingleSession, JingleIQ iq);

    /**
     * Callback fired when 'transport-info' is received from the client.
     *
     * @param jingleSession the session that has received the notification.
     * @param contents content list that contains media transport description.
     */
    void onTransportInfo(JingleSession jingleSession,
                         List<ContentPacketExtension> contents);

    /**
     * Called when 'transport-accept' IQ is received from the client.
     *
     * @param jingleSession the session that has received the notification
     * @param contents content list that contains media transport description
     *
     * @return <tt>XMPPError</tt> if an error should be returned as response to
     * the original request or <tt>null</tt> if the processing was successful.
     */
    XMPPError onTransportAccept(JingleSession jingleSession,
                           List<ContentPacketExtension> contents);

    /**
     * Called when 'transport-reject' IQ is received from the client.
     *
     * @param jingleSession the session that has received the notification
     * @param rejectIq full reject IQ provided for further analysis purposes
     */
    void onTransportReject(JingleSession jingleSession, JingleIQ rejectIq);
}
