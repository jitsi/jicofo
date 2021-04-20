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
package org.jitsi.jicofo.xmpp;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.bridge.Bridge;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.xmpp.extensions.rayo.*;

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.id.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.stream.*;

import static org.jitsi.jicofo.JitsiMeetConferenceImpl.MuteResult.*;

/**
 * Class handles various Jitsi Meet extensions IQs like {@link MuteIq}.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class IqHandler
{
    /**
     * The logger
     */
    private final static Logger logger = new LoggerImpl(IqHandler.class.getName());

    /**
     * <tt>FocusManager</tt> instance for accessing info about all active
     * conferences.
     */
    private final FocusManager focusManager;

    /** The currently used XMPP connection. */
    private AbstractXMPPConnection connection;

    private final MuteIqHandler muteIqHandler = new MuteIqHandler();
    private final MuteVideoIqHandler muteVideoIqHandler = new MuteVideoIqHandler();
    private final DialIqHandler dialIqHandler = new DialIqHandler();
    @NotNull
    private final ConferenceIqHandler conferenceIqHandler;
    private final AuthenticationIqHandler authenticationIqHandler;

    /**
     * @param focusManager The <tt>FocusManager</tt> to use to access active conferences.
     */
    public IqHandler(
            FocusManager focusManager,
            @NotNull ConferenceIqHandler conferenceIqHandler,
            AuthenticationIqHandler authenticationIqHandler)
    {
        this.focusManager = focusManager;
        this.conferenceIqHandler = conferenceIqHandler;
        this.authenticationIqHandler = authenticationIqHandler;

        MuteIqProvider.registerMuteIqProvider();
        MuteVideoIqProvider.registerMuteVideoIqProvider();
        new RayoIqProvider().registerRayoIQs();
        StartMutedProvider.registerStartMutedProvider();
    }

    /**
     * Initializes this instance and bind packet listeners.
     */
    public void init(AbstractXMPPConnection connection)
    {
        this.connection = connection;

        logger.info("Registering IQ handlers with XmppConnection.");
        connection.registerIQRequestHandler(muteIqHandler);
        connection.registerIQRequestHandler(muteVideoIqHandler);
        connection.registerIQRequestHandler(dialIqHandler);
        connection.registerIQRequestHandler(conferenceIqHandler);
        if (authenticationIqHandler != null)
        {
            connection.registerIQRequestHandler(authenticationIqHandler.getLoginUrlIqHandler());
            connection.registerIQRequestHandler(authenticationIqHandler.getLogoutIqHandler());
        }
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

    private class MuteVideoIqHandler extends AbstractIqRequestHandler
    {
        MuteVideoIqHandler()
        {
            super(
                MuteVideoIq.ELEMENT_NAME,
                MuteVideoIq.NAMESPACE,
                IQ.Type.set,
                Mode.sync);
        }

        @Override
        public IQ handleIQRequest(IQ iqRequest)
        {
            return handleMuteVideoIq((MuteVideoIq) iqRequest);
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
            return handleRayoIQ((RayoIqProvider.DialIq) iqRequest, 2, new ArrayList<>());
        }
    }

    /**
     * Disposes this instance and stop listening for extensions packets.
     */
    public void stop()
    {
        if (connection != null)
        {
            connection.unregisterIQRequestHandler(muteIqHandler);
            connection.unregisterIQRequestHandler(muteVideoIqHandler);
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
            return IQ.createErrorResponse(muteIq, XMPPError.getBuilder(XMPPError.Condition.item_not_found));
        }

        Jid from = muteIq.getFrom();
        JitsiMeetConferenceImpl conference = getConferenceForMucJid(from);
        if (conference == null)
        {
            logger.debug("Mute error: room not found for JID: " + from);
            return IQ.createErrorResponse(muteIq, XMPPError.getBuilder(XMPPError.Condition.item_not_found));
        }

        IQ response;

        JitsiMeetConferenceImpl.MuteResult result
                = conference.handleMuteRequest(muteIq.getFrom(), jid, doMute, MediaType.AUDIO);

        if (result == SUCCESS)
        {
            response = IQ.createResultIQ(muteIq);

            if (!muteIq.getFrom().equals(jid))
            {
                MuteIq muteStatusUpdate = new MuteIq();
                muteStatusUpdate.setActor(from);
                muteStatusUpdate.setType(IQ.Type.set);
                muteStatusUpdate.setTo(jid);

                muteStatusUpdate.setMute(doMute);

                UtilKt.tryToSendStanza(connection, muteStatusUpdate);
            }
        }
        else if (result == NOT_ALLOWED)
        {
            response = IQ.createErrorResponse(muteIq, XMPPError.getBuilder(XMPPError.Condition.not_allowed));
        }
        else
        {
            response = IQ.createErrorResponse(muteIq, XMPPError.getBuilder(XMPPError.Condition.internal_server_error));
        }

        return response;
    }

    private IQ handleMuteVideoIq(MuteVideoIq muteVideoIq)
    {
        Boolean doMute = muteVideoIq.getMute();
        Jid jid = muteVideoIq.getJid();

        if (doMute == null || jid == null)
        {
            return IQ.createErrorResponse(muteVideoIq, XMPPError.getBuilder(XMPPError.Condition.item_not_found));
        }

        Jid from = muteVideoIq.getFrom();
        JitsiMeetConferenceImpl conference = getConferenceForMucJid(from);
        if (conference == null)
        {
            logger.debug("Mute error: room not found for JID: " + from);
            return IQ.createErrorResponse(muteVideoIq, XMPPError.getBuilder(XMPPError.Condition.item_not_found));
        }

        IQ response;

        JitsiMeetConferenceImpl.MuteResult result
                = conference.handleMuteRequest(muteVideoIq.getFrom(), jid, doMute, MediaType.VIDEO);

        if (result == SUCCESS)
        {
            response = IQ.createResultIQ(muteVideoIq);

            if (!muteVideoIq.getFrom().equals(jid))
            {
                MuteVideoIq muteStatusUpdate = new MuteVideoIq();
                muteStatusUpdate.setActor(from);
                muteStatusUpdate.setType(IQ.Type.set);
                muteStatusUpdate.setTo(jid);

                muteStatusUpdate.setMute(doMute);

                UtilKt.tryToSendStanza(connection, muteStatusUpdate);
            }
        }
        else if (result == NOT_ALLOWED)
        {
            response = IQ.createErrorResponse(muteVideoIq, XMPPError.getBuilder(XMPPError.Condition.not_allowed));
        }
        else
        {
            response = IQ.createErrorResponse(
                muteVideoIq, XMPPError.getBuilder(XMPPError.Condition.internal_server_error));
        }

        return response;
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
    private IQ handleRayoIQ(RayoIqProvider.DialIq dialIq, int retryCount, @NotNull List<Jid> exclude)
    {
        Jid from = dialIq.getFrom();

        JitsiMeetConferenceImpl conference = getConferenceForMucJid(from);

        if (conference == null)
        {
            logger.debug("Dial error: room not found for JID: " + from);
            return IQ.createErrorResponse(dialIq, XMPPError.getBuilder(XMPPError.Condition.item_not_found));
        }

        MemberRole role = conference.getRoleForMucJid(from);

        if (role == null)
        {
            // Only room members are allowed to send requests
            return IQ.createErrorResponse(dialIq, XMPPError.getBuilder(XMPPError.Condition.forbidden));
        }

        if (!role.hasModeratorRights())
        {
            // Moderator permission is required
            return IQ.createErrorResponse(dialIq, XMPPError.getBuilder(XMPPError.Condition.not_allowed));
        }

        Set<String> bridgeRegions = conference.getBridges().keySet().stream()
            .map(Bridge::getRegion)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        // Check if Jigasi is available
        JicofoServices jicofoServices = Objects.requireNonNull(JicofoServices.jicofoServicesSingleton);
        JigasiDetector detector = jicofoServices.getJigasiDetector();
        Jid jigasiJid = detector == null ? null : detector.selectSipJigasi(exclude, bridgeRegions);

        if (jigasiJid == null)
        {
            // Not available
            return IQ.createErrorResponse(
                    dialIq, XMPPError.getBuilder(XMPPError.Condition.service_unavailable).build());
        }

        // Redirect original request to Jigasi component
        RayoIqProvider.DialIq forwardDialIq = new RayoIqProvider.DialIq(dialIq);
        forwardDialIq.setFrom((Jid)null);
        forwardDialIq.setTo(jigasiJid);
        forwardDialIq.setStanzaId(StanzaIdUtil.newStanzaId());

        try
        {
            connection.sendIqWithResponseCallback(
                forwardDialIq,
                reply ->
                {
                    // Send Jigasi response back to the client
                    reply.setFrom((Jid)null);
                    reply.setTo(from);
                    reply.setStanzaId(dialIq.getStanzaId());

                    connection.sendStanza(reply);
                },
                exception ->
                {
                    logger.error("Error sending dialIQ to "+ jigasiJid + " " + exception.getMessage());

                    try
                    {
                        if (retryCount > 0)
                        {
                            exclude.add(jigasiJid);

                            // let's retry lowering the number of attempts
                            IQ result = this.handleRayoIQ(dialIq, retryCount - 1, exclude);
                            if (result != null)
                            {
                                connection.sendStanza(result);
                            }

                            return;
                        }

                        if (exception instanceof SmackException.NoResponseException)
                        {
                            connection.sendStanza(IQ.createErrorResponse(
                                dialIq, XMPPError.getBuilder(XMPPError.Condition.remote_server_timeout)));
                        }
                        else
                        {
                            connection.sendStanza(IQ.createErrorResponse(
                                dialIq, XMPPError.getBuilder(XMPPError.Condition.undefined_condition)));
                        }
                    }
                    catch(InterruptedException | SmackException.NotConnectedException e)
                    {
                        logger.error("Cannot send back dialIq error response", e);
                    }
                });

            return null;
        }
        catch (SmackException.NotConnectedException | InterruptedException e)
        {
            logger.error("Failed to send DialIq - XMPP disconnected", e);
            return IQ.createErrorResponse(
                dialIq,
                XMPPError.getBuilder(XMPPError.Condition.internal_server_error)
                    .setDescriptiveEnText("Failed to forward DialIq"));
        }
    }
}
