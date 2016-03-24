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

import org.jitsi.protocol.xmpp.colibri.*;

/**
 * Class adds utility methods that require use of package protected methods of
 * {@link JitsiMeetConference}. These are used only for test purposes, so are
 * placed in separate class to reduce size of the conference focus class.
 *
 * @author Pawel Domas
 */
public class ConferenceUtility
{
    /**
     * Conference instance.
     */
    private final JitsiMeetConference conference;

    /**
     * Creates new instance for given <tt>JitsiMeetConference</tt>.
     * @param conference the conference that wil be used by this instance.
     */
    public ConferenceUtility(JitsiMeetConference conference)
    {
        this.conference = conference;
    }

    /**
     * Returns the ID of Colibri conference hosted on the videobridge.
     */
    public String getJvbConferenceId()
    {
        ColibriConference colibriConference = conference.getColibriConference();
        return colibriConference != null
            ? colibriConference.getConferenceId() : null;
    }

    /**
     * Returns the id of video channel allocated for the participant with given
     * JID.
     * @param participantJid the MUC JID of the participant for whom we want to
     *                       get video channel id.
     */
    public String getParticipantVideoChannelId(String participantJid)
    {
        Participant participant
            = conference.findParticipantForRoomJid(participantJid);

        ColibriConferenceIQ channelsInfo
            = participant.getColibriChannelsInfo();

        ColibriConferenceIQ.Content videoContent
            = channelsInfo.getContent("video");

        ColibriConferenceIQ.Channel videoChannel
            = videoContent.getChannel(0);

        return videoChannel.getID();
    }
}
