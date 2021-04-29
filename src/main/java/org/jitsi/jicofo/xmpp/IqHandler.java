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

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

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

    /**
     * Disposes this instance and stop listening for extensions packets.
     */
    public void stop()
    {
        if (connection != null)
        {
            connection.unregisterIQRequestHandler(muteIqHandler);
            connection.unregisterIQRequestHandler(muteVideoIqHandler);
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
}
