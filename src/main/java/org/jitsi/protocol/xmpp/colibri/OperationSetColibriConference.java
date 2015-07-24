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
