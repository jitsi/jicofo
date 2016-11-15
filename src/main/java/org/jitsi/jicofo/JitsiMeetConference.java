/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2016 Atlassian Pty Ltd
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

import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.util.*;

/**
 * The conference interface extracted from {@link JitsiMeetConferenceImpl} for
 * the unit tests purpose. There are many method that would be a good candidate
 * for ending up in this interface, but until the only reason for this interface
 * are tests, only methods actually used there are listed here.
 *
 * @author Pawel Domas
 */
public interface JitsiMeetConference
{
    /**
     * @return the <tt>Logger</tt> instance which might be used to inherit
     * the logging level assigned on the per conference basis.
     */
    Logger getLogger();

    /**
     * Checks how many {@link Participant}s are in the conference.
     * @return an integer greater than 0.
     */
    int getParticipantCount();

    /**
     * Find {@link Participant} for given MUC JID.
     *
     * @param mucJid participant's MUC jid (ex. "room@muc.server.com/nickname").
     *
     * @return {@link Participant} instance or <tt>null</tt> if not found.
     */
    Participant findParticipantForRoomJid(String mucJid);

    /**
     * The {@link ColibriConference} for this instance.
     *
     * @return {@link ColibriConference} currently allocated on the JVB for the
     * conference handled by this {@link JitsiMeetConference} instance or
     * <tt>null</tt> if there isn't any for some reason (not started, broken or
     * ended).
     */
    ColibriConference getColibriConference();
}
