/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;
import org.jitsi.jicofo.auth.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.util.*;

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
     * The logger
     */
    private static final Logger logger
        = Logger.getLogger(ChatRoomRoleAndPresence.class);

    /**
     * The {@link JitsiMeetConference} for which this instance is handling MUC
     * related stuff.
     */
    private final JitsiMeetConference conference;

    /**
     * The {@link ChatRoom} that is hosting Jitsi Meet conference.
     */
    private final ChatRoom chatRoom;

    /**
     * Authentication authority used to verify users.
     */
    private AuthAuthority authAuthority;

    /**
     * The {@link ChatRoomMemberRole} of conference focus.
     */
    private ChatRoomMemberRole focusRole;

    /**
     * Current owner(other than the focus itself) of Jitsi Meet conference.
     */
    private ChatRoomMember owner;

    public ChatRoomRoleAndPresence(JitsiMeetConference conference,
                                   ChatRoom chatRoom)
    {
        this.conference = conference;
        this.chatRoom = chatRoom;
    }

    /**
     * Initializes this instance, so that it starts doing it's job.
     */
    public void init()
    {
        authAuthority = ServiceUtils.getService(
                FocusBundleActivator.bundleContext, AuthAuthority.class);

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
        logger.info("Participants count: "+evt.getChatRoom().getMembersCount());

        ChatRoomMember sourceMember = evt.getChatRoomMember();

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
        if (focusRole == null)
        {
            // We don't know if we have permissions yet
            logger.warn("Focus role unknown");
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
            if (conference.isFocusMember(member)
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
                try
                {
                    chatRoom.grantOwnership(
                            ((XmppChatMember)member).getJabberID());

                    logger.info(
                            "Granted owner to " + member.getContactAddress());

                    owner = member;
                    break;
                }
                catch (RuntimeException e)
                {
                    logger.error(
                        "Failed to grant owner status to " + member.getName()
                            , e);
                }
                //break; FIXME: should cancel event if exception occurs ?
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

    /**
     * Waits for initial focus role and refuses to join if owner is
     * not granted. Elects the first owner of the conference.
     */
    @Override
    public void localUserRoleChanged(ChatRoomLocalUserRoleChangeEvent evt)
    {
        logger.info(
            "Focus role: " + evt.getNewRole() + " init: " + evt.isInitial());

        focusRole = evt.getNewRole();
        if (ChatRoomMemberRole.OWNER.compareTo(focusRole) < 0)
        {
            logger.error("Focus must be an owner!");
            conference.stop();
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

    private void checkGrantOwnerToAuthUser(ChatRoomMember member)
    {
        XmppChatMember xmppMember = (XmppChatMember) member;
        String jabberId = xmppMember.getJabberID();
        if (StringUtils.isNullOrEmpty(jabberId))
        {
            return;
        }

        if (ChatRoomMemberRole.OWNER.compareTo(member.getRole())
                < 0)
        {
            if (authAuthority.isUserAuthenticated(jabberId, chatRoom.getName()))
            {
                chatRoom.grantOwnership(jabberId);
            }
        }
    }

    @Override
    public void jidAuthenticated(String realJid, String identity)
    {
        // FIXME: consider changing to debug log level once tested
        logger.info("Authenticate request for: " + realJid + " as " + identity);

        for (ChatRoomMember member : chatRoom.getMembers())
        {
            XmppChatMember xmppMember = (XmppChatMember) member;
            if (realJid.equals(xmppMember.getJabberID()))
            {
                checkGrantOwnerToAuthUser(member);
            }
        }
    }
}
