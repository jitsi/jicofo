/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.protocol.xmpp;

import net.java.sip.communicator.service.protocol.*;

/**
 * Extended interface {@link ChatRoomMember} for the purpose of Jitsi-meet.
 *
 * @author Pawel Domas
 */
public interface MeetChatMember
    extends ChatRoomMember
{
    /**
     * Returns ths original user's connection Jabber ID and not the MUC address.
     */
    String getJabberID();

    /**
     * Returns unique Colibri endpoint ID for conference participant represented
     * by this chat member.
     */
    String getEndpointID();

    /**
     * Returns number based on the order of joining of the members in the room.
     * @return number based on the order of joining of the members in the room.
     */
    int getJoinOrderNumber();
}
