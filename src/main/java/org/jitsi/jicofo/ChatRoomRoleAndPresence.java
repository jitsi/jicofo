/*
 * Jicofo, the Jitsi Conference Focus.
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
package org.jitsi.jicofo;

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.logging2.*;
import org.jxmpp.jid.*;

/**
 * Manages the roles of members in a chat room:
 * - If authentication is used, grants the OWNER role to authenticated users.
 * - Otherwise, if the the {@link #autoOwner} feature is enabled, ensures that one of the room members always has the
 * OWNER role.
 *
 * @author Pawel Domas
 */
public class ChatRoomRoleAndPresence
    implements AuthenticationListener
{
    @NotNull
    private final ChatRoom chatRoom;

    /**
     * Authentication authority used to verify users.
     */
    private AuthenticationAuthority authAuthority;

    private final ChatRoomListener chatRoomListener = new ChatRoomListenerImpl();

    /**
     * A flag which indicates whether the auto owner feature is enabled. The first participant to
     * join the room will become conference owner. When the owner leaves the
     * room next participant will be selected as new owner.
     */
    private final boolean autoOwner = ConferenceConfig.config.enableAutoOwner();

    private final Logger logger;

    /**
     * The current member chosen to be granted the OWNER role.
     */
    private ChatRoomMember owner;

    public ChatRoomRoleAndPresence(@NotNull ChatRoom chatRoom, @NotNull Logger parentLogger)
    {
        this.chatRoom = chatRoom;
        this.logger = parentLogger.createChildLogger(getClass().getName());

        authAuthority = JicofoServices.jicofoServicesSingleton == null
                ? null : JicofoServices.jicofoServicesSingleton.getAuthenticationAuthority();
        if (authAuthority != null)
        {
            authAuthority.addAuthenticationListener(this);
        }

        chatRoom.addListener(chatRoomListener);
    }

    /**
     * Disposes resources used and stops any future processing that might have
     * been done by this instance.
     */
    public void dispose()
    {
        chatRoom.removeListener(chatRoomListener);

        if (authAuthority != null)
        {
            authAuthority.removeAuthenticationListener(this);
            authAuthority = null;
        }
    }

    private void memberJoined(@NotNull ChatRoomMember member)
    {
        if (owner == null)
        {
            electNewOwner();
        }
        if (authAuthority != null)
        {
            checkGrantOwnerToAuthUser(member);
        }
    }

    private void memberLeft(@NotNull ChatRoomMember member)
    {
        if (owner == member)
        {
            logger.debug("Owner has left the room.");
            owner = null;
            if (autoOwner)
            {
                electNewOwner();
            }
        }
    }

    /**
     * Elects new owner if the previous one has left the conference.
     * (Only if we do not work with external authentication).
     */
    private void electNewOwner()
    {
        if (chatRoom.getUserRole() != MemberRole.OWNER)
        {
            return;
        }

        if (authAuthority != null)
        {
            // If we have authentication authority we do not grant owner role based on who enters first, but who is an
            // authenticated user.
            return;
        }

        for (ChatRoomMember member : chatRoom.getMembers())
        {
            if (member.isRobot())
            {
                continue;
            }
            else if (member.getRole().hasOwnerRights())
            {
                // Select existing owner
                owner = member;
                logger.info("Owner already in the room: " + member.getName());
                break;
            }
            else
            {
                // Elect new owner
                if (grantOwner(member.getJid()))
                {
                    logger.info("Granted owner to " + member.getName());

                    owner = member;
                }
                break;
            }
        }
    }

    /**
     * Waits for initial focus role and refuses to join if owner is
     * not granted. Elects the first owner of the conference.
     */
    private void localRoleChanged(MemberRole newRole, MemberRole oldRole)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Local role changed, old= " + oldRole + " new=" + newRole);
        }

        if (newRole != MemberRole.OWNER)
        {
            return;
        }

        // Recognize the initial setting of the role.
        if (oldRole == null && owner == null)
        {
            if (authAuthority != null)
            {
                grantOwnersToAuthUsers();
            }
            else
            {
                electNewOwner();
            }
        }
    }

    private void grantOwnersToAuthUsers()
    {
        for (ChatRoomMember member : chatRoom.getMembers())
        {
            checkGrantOwnerToAuthUser(member);
        }
    }

    private boolean grantOwner(Jid jid)
    {
        try
        {
            chatRoom.grantOwnership(jid.toString());
            return true;
        }
        catch(RuntimeException e)
        {
            logger.error("Failed to grant owner status to " + jid , e);
        }
        return false;
    }

    private void checkGrantOwnerToAuthUser(ChatRoomMember member)
    {
        Jid jabberId = member.getJid();
        if (jabberId == null)
        {
            return;
        }

        if (member.getRole() != MemberRole.OWNER)
        {
            String authSessionId = authAuthority.getSessionForJid(jabberId);
            if (authSessionId != null)
            {
                grantOwner(jabberId);
            }
        }
    }

    @Override
    public void jidAuthenticated(Jid realJid, String identity, String sessionId)
    {
        for (ChatRoomMember member : chatRoom.getMembers())
        {
            if (realJid.equals(member.getJid()))
            {
                checkGrantOwnerToAuthUser(member);
            }
        }
    }

    private class ChatRoomListenerImpl extends DefaultChatRoomListener
    {
        @Override
        public void localRoleChanged(@NotNull MemberRole newRole, MemberRole oldRole)
        {
            ChatRoomRoleAndPresence.this.localRoleChanged(newRole, oldRole);
        }

        @Override
        public void memberJoined(@NotNull ChatRoomMember member)
        {
            ChatRoomRoleAndPresence.this.memberJoined(member);
        }

        @Override
        public void memberLeft(@NotNull ChatRoomMember member)
        {
            ChatRoomRoleAndPresence.this.memberLeft(member);
        }

        @Override
        public void memberKicked(@NotNull ChatRoomMember member)
        {
            ChatRoomRoleAndPresence.this.memberLeft(member);
        }
    }
}
