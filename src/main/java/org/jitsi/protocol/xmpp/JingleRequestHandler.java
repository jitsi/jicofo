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

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

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
     */
    void onAddSource(JingleSession jingleSession,
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
     */
    void onRemoveSource(JingleSession jingleSession,
                        List<ContentPacketExtension> contents);

    /**
     * Callback fired when 'session-accept' is received from the client.
     *
     * @param jingleSession the session that has received the notification.
     * @param answer content list that describe peer media offer.
     */
    void onSessionAccept(JingleSession jingleSession,
                         List<ContentPacketExtension> answer);

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
     */
    void onTransportAccept(JingleSession jingleSession,
                           List<ContentPacketExtension> contents);

    /**
     * Called when 'transport-reject' IQ is received from the client.
     *
     * @param jingleSession the session that has received the notification
     * @param rejectIq full reject IQ provided for further analysis purposes
     */
    void onTransportReject(JingleSession jingleSession, JingleIQ rejectIq);
}
