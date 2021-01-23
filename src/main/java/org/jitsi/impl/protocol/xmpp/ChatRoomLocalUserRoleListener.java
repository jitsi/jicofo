/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

/**
 * A listener that will be notified of changes in the role of the local
 * user participant in a particular chat room. Changes could be us being granted
 * any of the roles defined in <tt>ChatRoomMemberRole</tt>.
 *
 * @author Stephane Remy
 */
public interface ChatRoomLocalUserRoleListener
{
    /**
     * Called to notify interested parties that a change in the role of the
     * local user participant in a particular chat room has occurred.
     * @param evt the <tt>ChatRoomLocalUserRoleChangeEvent</tt> instance
     * containing the source chat room and role old and new state.
     */
    void localUserRoleChanged(ChatRoomLocalUserRoleChangeEvent evt);
}
