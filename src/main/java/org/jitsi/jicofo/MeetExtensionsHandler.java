/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.xmpp.extensions.rayo.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.id.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.stream.*;

/**
 * Class handles various Jitsi Meet extensions IQs like {@link MuteIq}.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class MeetExtensionsHandler
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

    /** The currently used XMPP connection. */
    private XmppConnection connection;

    private MuteIqHandler muteIqHandler;
    private DialIqHandler dialIqHandler;

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

        muteIqHandler = new MuteIqHandler();
        dialIqHandler = new DialIqHandler();
        connection.registerIQRequestHandler(muteIqHandler);
        connection.registerIQRequestHandler(dialIqHandler);
    }

    private class MuteIqHandler extends AbstractIqRequestHandler
    {
        MuteIqHandler()
        {
            super(
                MuteIq.ELEMENT_NAME,
                MuteIq.NAMESPACE,
                IQ.Type.set,
                Mode.sync);
        }

        @Override
        public IQ handleIQRequest(IQ iqRequest)
        {
            return handleMuteIq((MuteIq) iqRequest);
        }
    }

    private class DialIqHandler extends AbstractIqRequestHandler
    {
        DialIqHandler()
        {
            super(RayoIqProvider.DialIq.ELEMENT_NAME,
                RayoIqProvider.NAMESPACE,
                IQ.Type.set,
                Mode.sync);
        }

        @Override
        public IQ handleIQRequest(IQ iqRequest)
        {
            // let's retry 2 times sending the rayo
            // by default we have 15 seconds timeout waiting for reply
            // 3 timeouts will give us 45 seconds to reply to user with an error
            return handleRayoIQ((RayoIqProvider.DialIq) iqRequest, 2, null);
        }
    }

    /**
     * Disposes this instance and stop listening for extensions packets.
     */
    public void dispose()
    {
        if (connection != null)
        {
            connection.unregisterIQRequestHandler(muteIqHandler);
            connection.unregisterIQRequestHandler(dialIqHandler);
            connection = null;
        }
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

    private IQ handleMuteIq(MuteIq muteIq)
    {
        Boolean doMute = muteIq.getMute();
        Jid jid = muteIq.getJid();

        if (doMute == null || jid == null)
        {
            return IQ.createErrorResponse(muteIq, XMPPError.getBuilder(
                XMPPError.Condition.item_not_found));
        }

        Jid from = muteIq.getFrom();
        JitsiMeetConferenceImpl conference = getConferenceForMucJid(from);
        if (conference == null)
        {
            logger.debug("Mute error: room not found for JID: " + from);
            return IQ.createErrorResponse(muteIq, XMPPError.getBuilder(
                XMPPError.Condition.item_not_found));
        }

        IQ result;

        if (conference.handleMuteRequest(muteIq.getFrom(), jid, doMute))
        {
            result = IQ.createResultIQ(muteIq);

            if (!muteIq.getFrom().equals(jid))
            {
                MuteIq muteStatusUpdate = new MuteIq();
                muteStatusUpdate.setActor(from);
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

        return result;
    }

    /**
     * Checks whether sending the rayo message is ok (checks member, moderators)
     * and sends the message to the selected jigasi (from brewery muc or to the
     * component service).
     * @param dialIq the iq to send.
     * @param retryCount the number of attempts to be made for sending this iq,
     * if no reply is received from the remote side.
     * @param exclude <tt>null</tt> or a list of jigasi Jids which
     * we already tried sending in attempt to retry.
     *
     * @return the iq to be sent as a reply.
     */
    private IQ handleRayoIQ(RayoIqProvider.DialIq dialIq, int retryCount,
                            List<Jid> exclude)
    {
        Jid from = dialIq.getFrom();

        JitsiMeetConferenceImpl conference = getConferenceForMucJid(from);

        if (conference == null)
        {
            logger.debug("Dial error: room not found for JID: " + from);
            return IQ.createErrorResponse(dialIq, XMPPError.getBuilder(
                XMPPError.Condition.item_not_found));
        }

        ChatRoomMemberRole role = conference.getRoleForMucJid(from);

        if (role == null)
        {
            // Only room members are allowed to send requests
            return IQ.createErrorResponse(
                dialIq, XMPPError.getBuilder(XMPPError.Condition.forbidden));
        }

        if (ChatRoomMemberRole.MODERATOR.compareTo(role) < 0)
        {
            // Moderator permission is required
            return IQ.createErrorResponse(
                dialIq, XMPPError.getBuilder(XMPPError.Condition.not_allowed));
        }


        Set<String> bridgeRegions = conference.getBridges().keySet().stream()
            .map(b -> b.getRegion())
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        // Check if Jigasi is available
        Jid jigasiJid;
        JigasiDetector detector = conference.getServices().getJigasiDetector();
        if (detector == null
            || (jigasiJid = detector.selectJigasi(
                    exclude, bridgeRegions)) == null)
        {
            jigasiJid = conference.getServices().getSipGateway();
        }

        if (jigasiJid == null)
        {
            // Not available
            return IQ.createErrorResponse(
                dialIq,
                XMPPError.getBuilder(
                        XMPPError.Condition.service_unavailable).build());
        }

        // Redirect original request to Jigasi component
        RayoIqProvider.DialIq forwardDialIq = new RayoIqProvider.DialIq(dialIq);
        forwardDialIq.setFrom((Jid)null);
        forwardDialIq.setTo(jigasiJid);
        forwardDialIq.setStanzaId(StanzaIdUtil.newStanzaId());

        try
        {
            IQ reply = connection.sendPacketAndGetReply(forwardDialIq);

            if (reply == null)
            {
                if (retryCount > 0)
                {
                    if (exclude == null)
                    {
                        exclude = new ArrayList<>();
                    }
                    exclude.add(jigasiJid);

                    // let's retry lowering the number of attempts
                    return this.handleRayoIQ(dialIq, retryCount - 1, exclude);
                }
                else
                {
                    return IQ.createErrorResponse(
                        dialIq,
                        XMPPError.getBuilder(
                            XMPPError.Condition.remote_server_timeout));
                }
            }

            // Send Jigasi response back to the client
            reply.setFrom((Jid)null);
            reply.setTo(from);
            reply.setStanzaId(dialIq.getStanzaId());
            return reply;
        }
        catch (OperationFailedException e)
        {
            logger.error("Failed to send DialIq - XMPP disconnected", e);
            return IQ.createErrorResponse(
                dialIq,
                XMPPError.getBuilder(
                        XMPPError.Condition.internal_server_error)
                    .setDescriptiveEnText("Failed to forward DialIq"));
        }
    }
}
