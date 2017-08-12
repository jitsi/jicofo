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
package org.jitsi.jicofo;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.rayo.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.eventadmin.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.id.*;
import org.jivesoftware.smackx.nick.packet.*;
import org.jxmpp.jid.*;

/**
 * Class handles various Jitsi Meet extensions IQs like {@link MuteIq}.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class MeetExtensionsHandler
    implements StanzaFilter,
               StanzaListener
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(MeetExtensionsHandler.class);

    /**
     * <tt>FocusManager</tt> instance for accessing info about all active
     * conferences.
     */
    private final FocusManager focusManager;

    /**
     * Operation set that provider XMPP connection.
     */
    private XmppConnection connection;

    /**
     * Creates new instance of {@link MeetExtensionsHandler}.
     * @param focusManager <tt>FocusManager</tt> that will be used by new
     *                     instance to access active conferences and focus
     *                     XMPP connection.
     */
    public MeetExtensionsHandler(FocusManager focusManager)
    {
        this.focusManager = focusManager;

        MuteIqProvider.registerMuteIqProvider();
        new RayoIqProvider().registerRayoIQs();
        StartMutedProvider.registerStartMutedProvider();
    }

    /**
     * Initializes this instance and bind packet listeners.
     */
    public void init()
    {
        this.connection
            = focusManager.getOperationSet(
                    OperationSetDirectSmackXmpp.class).getXmppConnection();

        connection.addAsyncStanzaListener(this, this);
    }

    /**
     * Disposes this instance and stop listening for extensions packets.
     */
    public void dispose()
    {
        if (connection != null)
        {
            connection.removeAsyncStanzaListener(this);
            connection = null;
        }
    }

    @Override
    public boolean accept(Stanza packet)
    {
        return acceptMuteIq(packet)
                || acceptColibriIQ(packet)
                || acceptRayoIq(packet)
                || acceptPresence(packet);
    }

    @Override
    public void processStanza(Stanza packet)
    {
        if (connection == null)
        {
            logger.error("Not initialized");
            return;
        }

        if (packet instanceof ColibriConferenceIQ)
        {
            handleColibriIq((ColibriConferenceIQ) packet);
        }
        else if (packet instanceof MuteIq)
        {
            handleMuteIq((MuteIq) packet);
        }
        else if (packet instanceof RayoIqProvider.DialIq)
        {
            handleRayoIQ((RayoIqProvider.DialIq) packet);
        }
        else if (packet instanceof Presence)
        {
            handlePresence((Presence) packet);
        }
        else
        {
            logger.error("Unexpected packet: " + packet.toXML());
        }
    }

    private boolean acceptColibriIQ(Stanza packet)
    {
        return packet instanceof ColibriConferenceIQ
            // And with recording element
            && ((ColibriConferenceIQ)packet).getRecording() != null;
    }

    private void handleColibriIq(ColibriConferenceIQ colibriIQ)
    {
        Jid from = colibriIQ.getFrom();
        JitsiMeetConferenceImpl conference
            = getConferenceForMucJid(colibriIQ.getFrom());
        if (conference == null)
        {
            logger.debug("Room not found for JID: " + from);
            return;
        }

        ColibriConferenceIQ response = new ColibriConferenceIQ();

        response.setType(IQ.Type.result);
        response.setStanzaId(colibriIQ.getStanzaId());
        response.setTo(colibriIQ.getFrom());
        response.setFrom(colibriIQ.getTo());
        response.setName(colibriIQ.getName());

        connection.sendStanza(response);
    }

    private boolean acceptMuteIq(Stanza packet)
    {
        return packet instanceof MuteIq;
    }

    private JitsiMeetConferenceImpl getConferenceForMucJid(Jid mucJid)
    {
        EntityBareJid roomName = mucJid.asEntityBareJidIfPossible();
        if (roomName == null)
        {
            return null;
        }
        return focusManager.getConference(roomName);
    }

    private void handleMuteIq(MuteIq muteIq)
    {
        Boolean doMute = muteIq.getMute();
        Jid jid = muteIq.getJid();

        if (doMute == null || jid == null)
            return;

        Jid from = muteIq.getFrom();
        JitsiMeetConferenceImpl conference = getConferenceForMucJid(from);
        if (conference == null)
        {
            logger.debug("Mute error: room not found for JID: " + from);
            return;
        }

        IQ result;

        if (conference.handleMuteRequest(muteIq.getFrom(), jid, doMute))
        {
            result = IQ.createResultIQ(muteIq);

            if (!muteIq.getFrom().equals(jid))
            {
                MuteIq muteStatusUpdate = new MuteIq();
                muteStatusUpdate.setType(IQ.Type.set);
                muteStatusUpdate.setTo(jid);

                muteStatusUpdate.setMute(doMute);

                connection.sendStanza(muteStatusUpdate);
            }
        }
        else
        {
            result = IQ.createErrorResponse(
                muteIq,
                XMPPError.getBuilder(XMPPError.Condition.internal_server_error));
        }

        connection.sendStanza(result);
    }

    private boolean acceptRayoIq(Stanza p)
    {
        return p instanceof RayoIqProvider.DialIq;
    }

    private void handleRayoIQ(RayoIqProvider.DialIq dialIq)
    {
        Jid from = dialIq.getFrom();

        JitsiMeetConferenceImpl conference = getConferenceForMucJid(from);

        if (conference == null)
        {
            logger.debug("Mute error: room not found for JID: " + from);
            return;
        }

        ChatRoomMemberRole role = conference.getRoleForMucJid(from);

        if (role == null)
        {
            // Only room members are allowed to send requests
            IQ error = IQ.createErrorResponse(
                dialIq, XMPPError.getBuilder(XMPPError.Condition.forbidden));

            connection.sendStanza(error);

            return;
        }

        if (ChatRoomMemberRole.MODERATOR.compareTo(role) < 0)
        {
            // Moderator permission is required
            IQ error = IQ.createErrorResponse(
                dialIq, XMPPError.getBuilder(XMPPError.Condition.not_allowed));

            connection.sendStanza(error);

            return;
        }

        // Check if Jigasi is available
        Jid jigasiJid;
        JigasiDetector detector = conference.getServices().getJigasiDetector();
        if (detector == null || (jigasiJid = detector.selectJigasi()) == null)
            jigasiJid = conference.getServices().getSipGateway();

        if (jigasiJid == null)
        {
            // Not available
            IQ error = IQ.createErrorResponse(
                dialIq,
                XMPPError.getBuilder(
                        XMPPError.Condition.service_unavailable).build());

            connection.sendStanza(error);

            return;
        }

        // Redirect original request to Jigasi component
        RayoIqProvider.DialIq forwardDialIq = new RayoIqProvider.DialIq(dialIq);
        forwardDialIq.setFrom((Jid)null);
        forwardDialIq.setTo(jigasiJid);
        forwardDialIq.setStanzaId(StanzaIdUtil.newStanzaId());

        try
        {
            IQ reply = connection.sendPacketAndGetReply(forwardDialIq);

            // Send Jigasi response back to the client
            reply.setFrom((Jid)null);
            reply.setTo(from);
            reply.setStanzaId(dialIq.getStanzaId());
            connection.sendStanza(reply);
        }
        catch (OperationFailedException e)
        {
            logger.error("Failed to send DialIq - XMPP disconnected", e);
        }
    }

    private boolean acceptPresence(Stanza packet)
    {
        return packet instanceof Presence;
    }

    /**
     * Handles presence stanzas
     * @param presence
     */
    private void handlePresence(Presence presence)
    {
        // unavailable is sent when user leaves the room
        if (!presence.isAvailable())
        {
            return;
        }

        Jid from = presence.getFrom();
        JitsiMeetConferenceImpl conference = getConferenceForMucJid(from);

        if (conference == null)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Room not found for JID: " + from);
            }
            return;
        }

        if (conference.isFocusMember(from))
        {
            return; // Not interested in local presence
        }

        ChatRoomMemberRole role = conference.getRoleForMucJid(from);
        if (role == null)
        {
            // FIXME this is printed every time new user joins the room, because
            // PacketListener is fired before MUC knows the user is in the room.
            // This might be a problem if it would be the only presence ever
            // received from such participant although very unlikely with
            // the current client code.
            logger.warn("Failed to get user's role for: " + from);
        }
        else if (role.compareTo(ChatRoomMemberRole.MODERATOR) < 0)
        {
            StartMutedPacketExtension ext
                = presence.getExtension(
                            StartMutedPacketExtension.ELEMENT_NAME,
                            StartMutedPacketExtension.NAMESPACE);

            if (ext != null)
            {
                boolean[] startMuted
                    = { ext.getAudioMuted(), ext.getVideoMuted() };

                conference.setStartMuted(startMuted);
            }
        }
    }
}
