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
package org.jitsi.impl.protocol.xmpp;

import net.java.sip.communicator.service.protocol.*;
import org.jivesoftware.smack.packet.*;

/**
 * Partial implementation of {@link OperationSetMeetToolsImpl}.
 *
 * @author Pawel Domas
 */
public class OperationSetMeetToolsImpl
    implements OperationSetJitsiMeetTools
{
    @Override
    public void addSupportedFeature(String featureName)
    {

    }

    @Override
    public void removeSupportedFeature(String s)
    {

    }

    @Override
    public void sendPresenceExtension(ChatRoom chatRoom,
                                      PacketExtension extension)
    {
        ((ChatRoomImpl)chatRoom).setPresenceExtension(extension, false);
    }

    @Override
    public void removePresenceExtension(ChatRoom chatRoom,
                                        PacketExtension extension)
    {
        ((ChatRoomImpl)chatRoom).setPresenceExtension(extension, true);
    }

    @Override
    public void setPresenceStatus(ChatRoom chatRoom, String statusMessage)
    {

    }

    @Override
    public void addRequestListener(
        JitsiMeetRequestListener listener)
    {

    }

    @Override
    public void removeRequestListener(
        JitsiMeetRequestListener listener)
    {

    }
}
