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
package org.jitsi.jicofo;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;

import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.Logger;
import org.jitsi.eventadmin.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * Class handled MUC roles and presence for the focus in particular:
 * - ensures that focus has owner role after MUC room is joined
 * - elects owner and makes sure that there is one during the conference
 * - simplifies chat room events to 'member left', 'member joined'
 *
 * @author Pawel Domas
 */
public class ChatRoomRoleAndPresence
    implements ChatRoomMemberPresenceListener,
               ChatRoomMemberRoleListener,
               ChatRoomLocalUserRoleListener,
               AuthenticationListener
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    private static final Logger classLogger
        = Logger.getLogger(ChatRoomRoleAndPresence.class);

    /**
     * The {@link JitsiMeetConferenceImpl} for which this instance is handling
     * MUC related stuff.
     */
    private final JitsiMeetConferenceImpl conference;

    /**
     * The {@link ChatRoom} that is hosting Jitsi Meet conference.
     */
    private final ChatRoom chatRoom;

    /**
     * Authentication authority used to verify users.
     */
    private AuthenticationAuthority authAuthority;

    /**
     * The {@link ChatRoomMemberRole} of conference focus.
     */
    private ChatRoomMemberRole focusRole;

    /**
     * Flag indicates whether auto owner feature is active. First participant to
     * join the room will become conference owner. When the owner leaves the
     * room next participant will be selected as new owner.
     */
    private boolean autoOwner;

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
    private final Logger logger;

    /**
     * Current owner(other than the focus itself) of Jitsi Meet conference.
     */
    private ChatRoomMember owner;

    public ChatRoomRoleAndPresence(JitsiMeetConferenceImpl conference,
                                   ChatRoom chatRoom)
    {
        this.conference = Objects.requireNonNull(conference, "conference");
        this.chatRoom = Objects.requireNonNull(chatRoom, "chatRoom");

        this.logger = Logger.getLogger(classLogger, conference.getLogger());
    }

    /**
     * Initializes this instance, so that it starts doing it's job.
     */
    public void init()
    {
        autoOwner = conference.getGlobalConfig().isAutoOwnerEnabled();

        authAuthority = ServiceUtils2.getService(
                FocusBundleActivator.bundleContext,
                AuthenticationAuthority.class);

        if (authAuthority != null)
        {
            authAuthority.addAuthenticationListener(this);
        }

        chatRoom.addLocalUserRoleListener(this);
        chatRoom.addMemberPresenceListener(this);
        chatRoom.addMemberRoleListener(this);
    }

    /**
     * Disposes resources used and stops any future processing that might have
     * been done by this instance.
     */
    public void dispose()
    {
        chatRoom.removelocalUserRoleListener(this);
        chatRoom.removeMemberPresenceListener(this);
        chatRoom.removeMemberRoleListener(this);

        if (authAuthority != null)
        {
            authAuthority.removeAuthenticationListener(this);
            authAuthority = null;
        }
    }

    /**
     * Analyzes chat room events and simplifies them into 'member joined',
     * 'member left' and 'member kicked' events.
     *
     * {@inheritDoc}
     */
    @Override
    public void memberPresenceChanged(ChatRoomMemberPresenceChangeEvent evt)
    {
        logger.info("Chat room event " + evt);

        XmppChatMember sourceMember = (XmppChatMember)evt.getChatRoomMember();

        String eventType = evt.getEventType();
        if (ChatRoomMemberPresenceChangeEvent.MEMBER_JOINED.equals(eventType))
        {
            if (owner == null)
            {
                electNewOwner();
            }
            if (authAuthority != null)
            {
                checkGrantOwnerToAuthUser(sourceMember);
            }
            conference.onMemberJoined(sourceMember);
        }
        else if (ChatRoomMemberPresenceChangeEvent.MEMBER_LEFT.equals(eventType)
            || ChatRoomMemberPresenceChangeEvent.MEMBER_KICKED.equals(eventType)
            || ChatRoomMemberPresenceChangeEvent.MEMBER_QUIT.equals(eventType))
        {
            if (owner == sourceMember)
            {
                logger.info("Owner has left the room !");
                owner = null;
                electNewOwner();
            }
            if (ChatRoomMemberPresenceChangeEvent
                        .MEMBER_KICKED.equals(eventType))
            {
                conference.onMemberKicked(sourceMember);
            }
            else
            {
                conference.onMemberLeft(sourceMember);
            }
        }
        else
        {
            logger.warn("Unhandled event: " + evt.getEventType());
        }
    }

    /**
     * Elects new owner if the previous one has left the conference.
     * (Only if we do not work with external authentication).
     */
    private void electNewOwner()
    {
        if (!autoOwner)
            return;

        if (focusRole == null)
        {
            // We don't know if we have permissions yet
            logger.warn("Focus role unknown");

            ChatRoomMemberRole userRole = chatRoom.getUserRole();

            logger.info("Obtained focus role: " + userRole);

            if (userRole == null)
                return;

            focusRole = userRole;

            if (!verifyFocusRole())
                return;
        }

        if (authAuthority != null)
        {
            // If we have authentication authority we do not grant owner
            // role based on who enters first, but who is an authenticated user
            return;
        }

        for (ChatRoomMember member : chatRoom.getMembers())
        {
            if (conference.isFocusMember((XmppChatMember) member)
                || ((XmppChatMember) member).isRobot()
                // FIXME make Jigasi advertise itself as a robot
                || conference.isSipGateway(member))
            {
                continue;
            }
            else if (ChatRoomMemberRole.OWNER.compareTo(member.getRole()) >=0)
            {
                // Select existing owner
                owner = member;
                logger.info(
                    "Owner already in the room: " + member.getName());
                break;
            }
            else
            {
                // Elect new owner
                if (grantOwner(((XmppChatMember)member).getJid()))
                {
                    logger.info(
                        "Granted owner to " + member.getContactAddress());

                    owner = member;
                }
                break;
            }
        }
    }

    @Override
    public void memberRoleChanged(ChatRoomMemberRoleChangeEvent evt)
    {
        logger.info("Role update event " + evt);
        // FIXME: focus or owner might loose it's privileges
        // very unlikely(no such use case in client or anywhere in the app)
        // but lets throw an exception or log fatal error at least to spare
        // the time spent on debugging in future.

        //ChatRoomMember member = evt.getSourceMember();
        //if (JitsiMeetConference.isFocusMember(member))
        //{

        //}
    }

    private boolean verifyFocusRole()
    {
        if (ChatRoomMemberRole.OWNER.compareTo(focusRole) < 0)
        {
            logger.error("Focus must be an owner!");
            conference.stop();
            return false;
        }
        return true;
    }

    /**
     * Waits for initial focus role and refuses to join if owner is
     * not granted. Elects the first owner of the conference.
     */
    @Override
    public void localUserRoleChanged(ChatRoomLocalUserRoleChangeEvent evt)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Focus role: " + evt.getNewRole()
                        + " init: " + evt.isInitial()
                        + " room: " + conference.getRoomName());
        }

        focusRole = evt.getNewRole();
        if (!verifyFocusRole())
        {
            return;
        }

        if (evt.isInitial() && owner == null)
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
            logger.error(
                "Failed to grant owner status to " + jid , e);
        }
        return false;
    }

    private void checkGrantOwnerToAuthUser(ChatRoomMember member)
    {
        XmppChatMember xmppMember = (XmppChatMember) member;
        Jid jabberId = xmppMember.getJid();
        if (jabberId == null)
        {
            return;
        }

        if (ChatRoomMemberRole.OWNER.compareTo(member.getRole()) < 0)
        {
            String authSessionId = authAuthority.getSessionForJid(jabberId);
            if (authSessionId != null)
            {
                grantOwner(jabberId);

                // Notify that this member has been authenticated using
                // given session
                EventAdmin eventAdmin = FocusBundleActivator.getEventAdmin();

                if (eventAdmin == null)
                    return;

                eventAdmin.postEvent(
                    EventFactory.endpointAuthenticated(
                            authSessionId,
                            String.valueOf(conference.getId()),
                            Participant.getEndpointId(xmppMember)
                    )
                );
            }
        }
    }

    @Override
    public void jidAuthenticated(Jid realJid, String identity,
                                 String sessionId)
    {
        for (ChatRoomMember member : chatRoom.getMembers())
        {
            XmppChatMember xmppMember = (XmppChatMember) member;
            if (realJid.equals(xmppMember.getJid()))
            {
                checkGrantOwnerToAuthUser(member);
            }
        }
    }
}
