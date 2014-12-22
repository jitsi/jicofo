/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.protocol.xmpp.colibri;

import net.java.sip.communicator.service.protocol.*;

/**
 * Operation set exposes an interface for direct Colibri protocol communication
 * with the videobridge. Allows to allocate new channels, update transport info
 * and finally expire colibri channels.
 *
 * @author Pawel Domas
 */
public interface OperationSetColibriConference
    extends OperationSet
{
    /**
     * Creates new colibri conference. It provides Colibri protocol operations
     * on single conference.
     * @return new instance of <tt>ColibriConference</tt> without any
     *         channels allocated nor conference ID on the bridge.
     */
    ColibriConference createNewConference();
}
