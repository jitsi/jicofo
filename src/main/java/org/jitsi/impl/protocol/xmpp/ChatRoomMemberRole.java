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
 * Indicates roles that a chat room member detains in its containing chat room.
 *
 * TODO: get rid of this and use the smack roles directly.
 *
 * @author Emil Ivov
 * @author Valentin Martinet
 * @author Yana Stamcheva
 */
public enum ChatRoomMemberRole
    implements Comparable<ChatRoomMemberRole>
{
    /**
     * A role implying the full set of chat room permissions
     */
    OWNER,

    /**
     * A role implying administrative permissions.
     */
    ADMINISTRATOR,

    /**
     * A role implying moderator permissions.
     */
    MODERATOR,

    /**
     * A role implying standard participant permissions.
     */
    MEMBER,

    /**
     * A role implying standard participant permissions.
     */
    GUEST
}
