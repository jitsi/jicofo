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
package org.jitsi.protocol.xmpp;

import net.java.sip.communicator.service.protocol.*;

import org.jitsi.jicofo.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * Extended version of {@link ChatRoom} that adds methods specific to Jicofo.
 *
 * @author Pawel Domas
 */
public interface ChatRoom2
    extends ChatRoom
{
    /**
     * Gets the name of this chat room as a JID.
     * @return the name of this chat room as a JID.
     */
    EntityBareJid getRoomJid();

    /**
     * Finds chat member for given MUC jid.
     *
     * @param mucJid full MUC jid of the user for whom we want to find chat
     *               member instance. Ex. chatroom1@muc.server.com/nick1234
     *
     * @return an instance of <tt>XmppChatMember</tt> for given MUC jid or
     *         <tt>null</tt> if not found.
     */
    XmppChatMember findChatMember(Jid mucJid);

    /**
     * Returns the MUC address of our chat member.
     * @return our full MUC JID for example: room@conference.server.net/nickname
     */
    EntityFullJid getLocalOccupantJid();

    /**
     * @return the list of all our presence {@link ExtensionElement}s.
     */
    Collection<ExtensionElement> getPresenceExtensions();

    /**
     * Checks if a packet extension is already in the presence.
     *
     * @param elementName the name of XML element of the presence extension.
     * @param namespace the namespace of XML element of the presence extension.
     *
     * @return <tt>boolean</tt>
     */
    boolean containsPresenceExtension(String elementName, String namespace);

    /**
     * Modifies our current MUC presence by adding and/or removing specified
     * extensions. The extension are compared by instance equality.
     * @param toRemove the list of extensions to be removed.
     * @param toAdd the list of extension to be added.
     */
    void modifyPresence(Collection<ExtensionElement> toRemove,
                        Collection<ExtensionElement> toAdd);

    void setConference(JitsiMeetConference conference);
}
