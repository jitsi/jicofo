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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.jicofo.recording.*;
import org.jitsi.jicofo.recording.jibri.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.util.*;

import org.jivesoftware.smack.packet.*;

/**
 * Class responsible for handling recording functionality of Jitsi Meet
 * conference.
 *
 * @author Pawel Domas
 */
public class JitsiMeetRecording
{
    /**
     * The logger used by MeetRecording class.
     */
    private final static Logger logger
        = Logger.getLogger(JitsiMeetRecording.class);

    /**
     * Keeps a record whether user has activated recording before other
     * participants has joined and the actual conference has been created.
     * This is not used with Jibri recorder.
     */
    private RecordingState earlyRecordingState = null;

    /**
     * The <tt>JitsiMeetConference</tt> for which this instance is dealing with
     * all the recording related stuff.
     */
    private final JitsiMeetConference meetConference;

    /**
     * Recording functionality implementation backend.
     */
    private Recorder recorder;

    /**
     * <tt>JitsiMeetServices</tt> instance used to retrieve info about recording
     * related components.
     */
    private final JitsiMeetServices services;

    /**
     * XMPP "operation set" used to send XMPP requests.
     */
    private final OperationSetDirectSmackXmpp xmppOpSet;


    /**
     * Creates new instance of <tt>JitsiMeetRecording</tt>.
     * @param meetConference parent <tt>JitsiMeetConference</tt> for which this
     *        instance will be taking care of the recording.
     * @param meetServices <tt>JitsiMeetServices</tt> instance which provides
     *        the info about recorder components availability.
     */
    public JitsiMeetRecording(JitsiMeetConference meetConference,
                              JitsiMeetServices meetServices)
    {
        this.meetConference = meetConference;
        this.services = meetServices;
        this.xmppOpSet
            = meetConference.getXmppProvider().getOperationSet(
                    OperationSetDirectSmackXmpp.class);
    }

    /**
     * Initializes the recording. Called by <tt>JitsiMeetConference</tt> just
     * after Jicofo enters the MUC.
     */
    public void init()
    {
        Recorder recorder = getRecorder();
        if (recorder != null)
        {
            recorder.init();
        }
    }

    /**
     * Called by parent <tt>JitsiMeetConference</tt> when it disposes/expires
     * Colibri conference.
     */
    void dispose()
    {
        if (recorder != null)
        {
            recorder.dispose();
            recorder = null;
        }
    }

    /**
     * Lazy initializer for {@link #recorder}. If there is Jirecon component
     * service available then {@link JireconRecorder} is used. Otherwise we fall
     * back to direct videobridge communication through {@link JvbRecorder}.
     *
     * @return {@link Recorder} implementation used by this instance.
     */
    private Recorder getRecorder()
    {
        if (recorder != null)
            return recorder;

        if (services.getJibriDetector() != null)
        {
            recorder = new JibriRecorder(
                    meetConference, xmppOpSet,
                    FocusBundleActivator.getSharedThreadPool(),
                    meetConference.getGlobalConfig());
            return recorder;
        }

        String recorderService = services.getJireconRecorder();
        if (!StringUtils.isNullOrEmpty(recorderService))
        {
            recorder
                = new JireconRecorder(
                        meetConference.getFocusJid(),
                        services.getJireconRecorder(), xmppOpSet);
            return recorder;
        }
        else
        {
            logger.warn("No recorder service discovered - using JVB");

            ColibriConference colibriConference
                = meetConference.getColibriConference();
            if(colibriConference == null)
            {
                return null;
            }

            String videobridge = colibriConference.getJitsiVideobridge();
            if (StringUtils.isNullOrEmpty(videobridge))
            {
                //Unable to create JVB recorder, conference not started yet
                return null;
            }

            recorder
                = new JvbRecorder(
                        colibriConference.getConferenceId(),
                        videobridge,
                        colibriConference.getName(),
                        xmppOpSet);
            return recorder;
        }
    }

    /**
     * Attempts to modify conference recording state.
     *
     * @param from JID of the participant that wants to modify recording state.
     * @param token recording security token that will be verified on modify
     *              attempt.
     * @param state the new recording state to set.
     * @param path output recording path(recorder implementation and deployment
     *             dependent).
     * @param to the received colibri packet destination.
     * @return new recording state(unchanged if modify attempt has failed).
     */
    public ColibriConferenceIQ.Recording.State modifyRecordingState(
            String from, String token,
            ColibriConferenceIQ.Recording.State state, String path, String to)
    {
        ChatRoomMember member = meetConference.findMember(from);
        if (member == null)
        {
            logger.error("No member found for address: " + from);
            return ColibriConferenceIQ.Recording.State.OFF;
        }
        if (ChatRoomMemberRole.MODERATOR.compareTo(member.getRole()) < 0)
        {
            logger.info("Recording - request denied, not a moderator: " + from);
            return ColibriConferenceIQ.Recording.State.OFF;
        }

        Recorder recorder = getRecorder();
        if (recorder == null)
        {
            if(state.equals(ColibriConferenceIQ.Recording.State.OFF))
            {
                earlyRecordingState = null;
                return ColibriConferenceIQ.Recording.State.OFF;
            }

            // save for later dispatching
            earlyRecordingState
                = new RecordingState(from, token, state, path, to);

            return ColibriConferenceIQ.Recording.State.PENDING;
        }

        boolean isTokenCorrect
            = recorder.setRecording(from, token, state, path);
        if (!isTokenCorrect)
        {
            logger.info(
                    "Incorrect recording token received ! Session: "
                        + meetConference.getRoomName());
        }

        return recorder.isRecording()
            ? ColibriConferenceIQ.Recording.State.ON
            : ColibriConferenceIQ.Recording.State.OFF;
    }

    /**
     * Called by parent {@link JitsiMeetConference} just after Colibri
     * conference has been allocated on the videobridge. It is when we trigger
     * "early recording request".
     */
    void onConferenceAllocated()
    {
        if (earlyRecordingState != null)
        {
            RecordingState recState = earlyRecordingState;
            earlyRecordingState = null;

            Recorder rec = getRecorder();
            if(rec == null)
            {
                logger.error(
                        "No recorder found, session: "
                            + meetConference.getRoomName());
            }
            else
            {
                boolean isTokenCorrect
                    = recorder.setRecording(
                            recState.from,
                            recState.token,
                            recState.state,
                            recState.path);

                if (!isTokenCorrect)
                {
                    logger.info(
                            "Incorrect recording token received ! Session: "
                                + meetConference.getRoomName());
                }

                if (recorder.isRecording())
                {
                    ColibriConferenceIQ response = new ColibriConferenceIQ();

                    response.setType(IQ.Type.SET);
                    response.setTo(recState.from);
                    response.setFrom(recState.to);

                    ColibriConference colibriConference
                        = meetConference.getColibriConference();
                    if(colibriConference != null)
                        response.setName(colibriConference.getName());

                    response.setRecording(
                            new ColibriConferenceIQ.Recording(
                                    ColibriConferenceIQ.Recording.State.ON));

                    xmppOpSet.getXmppConnection().sendPacket(response);
                }
            }
        }
    }

    /**
     * Saves early recording requests by user. Dispatched when new participant
     * joins.
     */
    private static class RecordingState
    {
        /**
         * JID of the participant that wants to modify recording state.
         */
        String from;

        /**
         * Recording security token that will be verified on modify attempt.
         */
        String token;

        /**
         * The new recording state to set.
         */
        ColibriConferenceIQ.Recording.State state;

        /**
         * Output recording path(recorder implementation
         * and deployment dependent).
         */
        String path;

        /**
         * The received colibri packet destination.
         */
        String to;

        public RecordingState(String from, String token,
                              ColibriConferenceIQ.Recording.State state,
                              String path, String to)
        {
            this.from = from;
            this.token = token;
            this.state = state;
            this.path = path;
            this.to = to;
        }
    }
}
