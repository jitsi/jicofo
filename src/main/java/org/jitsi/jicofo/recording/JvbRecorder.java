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
package org.jitsi.jicofo.recording;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.ColibriConferenceIQ.Recording.*;

import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.util.*;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Implements {@link Recorder} using direct Colibri queries sent to
 * the videobridge.
 *
 * @author Pawel Domas
 */
public class JvbRecorder
    extends Recorder
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    private final static Logger classLogger
        = Logger.getLogger(JvbRecorder.class);

    /**
     * Colibri conference identifier
     */
    private final String conferenceId;

    /**
     * Recording status.
     */
    boolean isRecording;

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
    private final Logger logger;

    private final String roomName;

    /**
     * Creates new instance of <tt>JvbRecorder</tt>.
     * @param conference parent {@link JitsiMeetConference} to be recorded
     *        by this instance.
     * @param videoBridgeComponentJid videobridge component address.
     * @param xmpp {@link OperationSetDirectSmackXmpp}
     *              for current XMPP connection.
     */
    public JvbRecorder(JitsiMeetConference conference,
                       String videoBridgeComponentJid,
                       OperationSetDirectSmackXmpp xmpp)
    {
        super(videoBridgeComponentJid, xmpp);

        Objects.requireNonNull(conference, "conference");

        ColibriConference colibriConference = conference.getColibriConference();

        Objects.requireNonNull(colibriConference, "colibriConference");

        this.conferenceId = colibriConference.getConferenceId();
        this.roomName = colibriConference.getName();
        this.logger = Logger.getLogger(classLogger, conference.getLogger());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRecording()
    {
        return isRecording;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean setRecording(String from, String token,
                                State doRecord, String path)
    {
        ColibriConferenceIQ toggleRecordingIq = new ColibriConferenceIQ();

        toggleRecordingIq.setID(conferenceId);
        toggleRecordingIq.setTo(recorderComponentJid);
        toggleRecordingIq.setType(IQ.Type.SET);
        toggleRecordingIq.setName(roomName);

        toggleRecordingIq.setRecording(
            new ColibriConferenceIQ.Recording(
                !isRecording ? State.ON : State.OFF, token));

        Packet reply
            = xmpp.getXmppConnection()
                    .sendPacketAndGetReply(toggleRecordingIq);
        logger.info("REC reply received: " + IQUtils.responseToXML(reply));
        if (reply instanceof ColibriConferenceIQ)
        {
            ColibriConferenceIQ colibriReply = (ColibriConferenceIQ) reply;
            ColibriConferenceIQ.Recording recording
                = colibriReply.getRecording();
            if (recording != null)
            {
                isRecording = recording.getState().equals(State.ON)
                    || recording.getState().equals(State.PENDING);
                logger.info("REC status: " + conferenceId + ": " + isRecording);
            }
            else
            {
                // Recording token is invalid
                return false;
            }
        }
        else
        {
            logger.error(
                    conferenceId + " unexpected response received: "
                        + IQUtils.responseToXML(reply));
        }
        return true;
    }

    @Override
    public boolean accept(Packet packet)
    {
        return false;
        /*if (!(packet instanceof ColibriConferenceIQ))
            return false;

        ColibriConferenceIQ colibriIQ = (ColibriConferenceIQ) packet;
        // Packets must be with <recording/> and from the bridge
        if (colibriIQ.getRecording() == null
                || !recorderComponentJid.equals(packet.getFrom()))
        {
            return false;
        }

        if (!conferenceId.equals(colibriIQ.getID()))
        {
            logger.warn(
                "Received colibri IQ from different conference: "
                        + colibriIQ.getID() + ", expected: " + conferenceId);
            return false;
        }

        return true;*/
    }

    @Override
    public void processPacket(Packet packet)
    {
        //FIXME: should notify the conference about status change,
        //       but currently all processing is done on the fly using
        //       XmppConnection.sendPacketAndGetReply

        /*ColibriConferenceIQ colibriIq = (ColibriConferenceIQ) packet;

        ColibriConferenceIQ.Recording recordingElem
            = colibriIq.getRecording();

        logger.info(
            conferenceId
                + " recording status from the bridge received: "
                + isRecording);

        isRecording = recordingElem.getState();*/
    }
}
