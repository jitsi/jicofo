/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.protocol.xmpp.util;

/**
 * Utility class for XMPP MUC operations.
 *
 * @author Pawel Domas
 */
public class MucUtil
{
    /**
     * Extracts room name from the full MUC address. If given room name is
     * already in the simple form then it will be returned unmodified.
     *
     * @param roomName room name in the form of {room_name}@{muc.server.net}.
     *
     * @return room name extracted from full address: {room_name}@muc.server
     *         .net
     */
    public static String extractName(String roomName)
    {
        if (roomName.contains("@"))
        {
            roomName = roomName.substring(0, roomName.indexOf("@"));
        }
        return roomName;
    }
}
