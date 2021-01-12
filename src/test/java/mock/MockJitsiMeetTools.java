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
package mock;

import net.java.sip.communicator.service.protocol.*;
import org.jitsi.impl.protocol.xmpp.OperationSetJitsiMeetTools;
import org.jivesoftware.smack.packet.*;

/**
 * @author Pawel Domas
 */
public class MockJitsiMeetTools
    implements OperationSetJitsiMeetTools
{
    public MockJitsiMeetTools()
    {
    }

    @Override
    public void sendPresenceExtension(ChatRoom chatRoom, ExtensionElement extension)
    {
        //FIXME: to be tested
    }

    @Override
    public void removePresenceExtension(
        ChatRoom chatRoom, ExtensionElement packetExtension)
    {
        //FIXME: to be tested
    }
}
