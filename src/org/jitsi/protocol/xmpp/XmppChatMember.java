/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.protocol.xmpp;

import net.java.sip.communicator.service.protocol.*;

/**
 * XMPP extended interface of {@link ChatRoomMember}.
 *
 * @author Pawel Domas
 */
public interface XmppChatMember
    extends ChatRoomMember
{
    /**
     * Returns ths original user's connection Jabber ID and not the MUC address.
     */
    String getJabberID();

    /**
     * Returns number based on the order of joining of the members in the room.
     * @return number based on the order of joining of the members in the room.
     */
    int getJoinOrderNumber();
}
