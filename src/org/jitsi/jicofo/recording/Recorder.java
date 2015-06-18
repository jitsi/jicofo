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

import org.jitsi.protocol.xmpp.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;

/**
 * Abstract class used by {@link org.jitsi.jicofo.JitsiMeetConference} for
 * controlling recording functionality.
 *
 * @author Pawel Domas
 */
public abstract class Recorder
    implements PacketListener,
               PacketFilter
{
    /**
     * Recorder component XMPP address.
     */
    protected final String recorderComponentJid;

    /**
     * Smack operation set for current XMPP connection.
     */
    protected final OperationSetDirectSmackXmpp xmpp;

    public Recorder(String recorderComponentJid,
                    OperationSetDirectSmackXmpp xmpp)
    {
        this.recorderComponentJid = recorderComponentJid;
        this.xmpp = xmpp;
        xmpp.addPacketHandler(this, this);
    }

    /**
     * Releases resources and stops any future processing.
     */
    public void dispose()
    {
        xmpp.removePacketHandler(this);
    }

    /**
     * Returns current conference recording status.
     * @return <tt>true</tt> if the conference is currently being recorded
     *         or <tt>false</tt> otherwise.
     */
    public abstract boolean isRecording();

    /**
     * Toggles recording status of the conference handled by this instance.
     *
     * @param from JID of the user that wants to modify recording status.
     * @param token recording security token(check by the implementation).
     * @param doRecord <tt>true</tt> to enable recording.
     * @param path output recording path(implementation specific).
     *
     * @return <tt>true</tt> if security token was successfully verified and
     *         appropriate control actions have been taken or <tt>false</tt>
     *         otherwise.
     */
    public abstract boolean setRecording(
        String from, String token, boolean doRecord, String path);
}
