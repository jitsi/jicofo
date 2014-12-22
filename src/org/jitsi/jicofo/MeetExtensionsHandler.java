/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;

/**
 * Class handles various Jitsi Meet extensions IQs like {@link MuteIq} and
 * Colibri for recording.
 *
 * @author Pawel Domas
 */
public class MeetExtensionsHandler
    implements PacketFilter,
               PacketListener
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
    private OperationSetDirectSmackXmpp smackXmpp;

    /**
     * Creates new instance of {@link MeetExtensionsHandler}.
     * @param focusManager <tt>FocusManager</tt> that will be used by new
     *                     instance to access active conferences and focus
     *                     XMPP connection.
     */
    public MeetExtensionsHandler(FocusManager focusManager)
    {
        this.focusManager = focusManager;

        MuteIqProvider muteIqProvider = new MuteIqProvider();
        muteIqProvider.registerMuteIqProvider(
            ProviderManager.getInstance());
    }

    /**
     * Initializes this instance and bind packet listeners.
     */
    public void init()
    {
        this.smackXmpp
            = focusManager.getOperationSet(
                    OperationSetDirectSmackXmpp.class);

        smackXmpp.addPacketHandler(this, this);
    }

    /**
     * Disposes this instance and stop listening for extensions packets.
     */
    public void dispose()
    {
        if (smackXmpp != null)
        {
            smackXmpp.removePacketHandler(this);
            smackXmpp = null;
        }
    }

    @Override
    public boolean accept(Packet packet)
    {
        return acceptMuteIq(packet) || acceptColibriIQ(packet);
    }

    @Override
    public void processPacket(Packet packet)
    {
        if (smackXmpp == null)
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
        else
        {
            logger.error("Unexpected packet: " + packet.toXML());
        }
    }

    private boolean acceptColibriIQ(Packet packet)
    {
        return packet instanceof ColibriConferenceIQ
            // And with recording element
            && ((ColibriConferenceIQ)packet).getRecording() != null;
    }

    private void handleColibriIq(ColibriConferenceIQ colibriIQ)
    {
        ColibriConferenceIQ.Recording recording = colibriIQ.getRecording();
        String from = colibriIQ.getFrom();
        JitsiMeetConference conference
            = getConferenceForMucJid(colibriIQ.getFrom());
        if (conference == null)
        {
            logger.debug("Room not found for JID: " + from);
            return;
        }

        boolean recordingState =
            conference.modifyRecordingState(
                colibriIQ.getFrom(),
                recording.getToken(),
                recording.getState(),
                recording.getDirectory());

        ColibriConferenceIQ response = new ColibriConferenceIQ();

        response.setType(IQ.Type.RESULT);
        response.setPacketID(colibriIQ.getPacketID());
        response.setTo(colibriIQ.getFrom());
        response.setFrom(colibriIQ.getTo());

        response.setRecording(
            new ColibriConferenceIQ.Recording(recordingState));

        smackXmpp.getXmppConnection().sendPacket(response);
    }

    private boolean acceptMuteIq(Packet packet)
    {
        return packet instanceof MuteIq;
    }

    private String getRoomNameFromMucJid(String mucJid)
    {
        int atIndex = mucJid.indexOf("@");
        int slashIndex = mucJid.indexOf("/");
        if (atIndex == -1 || slashIndex == -1)
            return null;

        return mucJid.substring(0, slashIndex);
    }

    private JitsiMeetConference getConferenceForMucJid(String mucJid)
    {
        String roomName = getRoomNameFromMucJid(mucJid);
        if (roomName == null)
        {
            return null;
        }
        return focusManager.getConference(roomName);
    }

    private void handleMuteIq(MuteIq muteIq)
    {
        Boolean doMute = muteIq.getMute();
        String jid = muteIq.getJid();

        if (doMute == null || StringUtils.isNullOrEmpty(jid))
            return;

        String from = muteIq.getFrom();
        JitsiMeetConference conference = getConferenceForMucJid(from);
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
                muteStatusUpdate.setType(IQ.Type.SET);
                muteStatusUpdate.setTo(jid);

                muteStatusUpdate.setMute(doMute);

                smackXmpp.getXmppConnection().sendPacket(muteStatusUpdate);
            }
        }
        else
        {
            result = IQ.createErrorResponse(
                muteIq,
                new XMPPError(XMPPError.Condition.interna_server_error));
        }

        smackXmpp.getXmppConnection().sendPacket(result);
    }
}
