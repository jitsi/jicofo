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
package org.jitsi.impl.protocol.xmpp;

import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging2.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.SmackException.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.muc.packet.*;
import org.jivesoftware.smackx.xdata.*;
import org.jivesoftware.smackx.xdata.form.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Stripped implementation of <tt>ChatRoom</tt> using Smack library.
 *
 * @author Pawel Domas
 */
public class ChatRoomImpl
    implements ChatRoom, PresenceListener
{
    /**
     * The logger used by this class.
     */
    private final Logger logger;

    /**
     * Parent MUC operation set.
     */
    @NotNull private final XmppProvider xmppProvider;

    /**
     * The room JID (e.g. "room@service").
     */
    private final EntityBareJid roomJid;

    /**
     * {@link MemberListener} instance.
     */
    private final MemberListener memberListener = new MemberListener();

    /**
     * {@link LocalUserStatusListener} instance.
     */
    private final LocalUserStatusListener userListener = new LocalUserStatusListener();

    /**
     * Listener for presence that smack sends on our behalf.
     */
    private PresenceListener presenceInterceptor;

    /**
     * Smack multi user chat backend instance.
     */
    private final MultiUserChat muc;

    /**
     * Our full Multi User Chat XMPP address.
     */
    private EntityFullJid myOccupantJid;

    /**
     * Callback to call when the room is left.
     */
    private final Consumer<ChatRoomImpl> leaveCallback;

    private final Map<EntityFullJid, ChatMemberImpl> members = new HashMap<>();

    /**
     * Local user role.
     */
    private MemberRole role;

    /**
     * Stores our last MUC presence packet for future update.
     */
    private Presence lastPresenceSent;

    /**
     * The value of the "meetingId" field from the MUC form, if present.
     */
    private String meetingId = null;

    /**
     * Indicates whether A/V Moderation is enabled for this room.
     */
    private final Map<MediaType, Boolean> avModerationEnabled = Collections.synchronizedMap(new HashMap<>());

    private Map<String, List<String>> whitelists = new HashMap<>();

    /**
     * The emitter used to fire events. By default we fire them synchronously, unless an executor is set via
     * {@link #setEventExecutor(Executor)}
     */
    private EventEmitter<ChatRoomListener> eventEmitter = new SyncEventEmitter<>();

    /**
     * Creates new instance of <tt>ChatRoomImpl</tt>.
     *
     * @param roomJid the room JID (e.g. "room@service").
     */
    public ChatRoomImpl(
            @NotNull XmppProvider xmppProvider,
            @NotNull EntityBareJid roomJid,
            Consumer<ChatRoomImpl> leaveCallback)
    {
        logger = new LoggerImpl(getClass().getName());
        logger.addContext("room", roomJid.getResourceOrEmpty().toString());
        this.xmppProvider = xmppProvider;
        this.roomJid = roomJid;
        this.leaveCallback = leaveCallback;

        MultiUserChatManager manager = MultiUserChatManager.getInstanceFor(xmppProvider.getXmppConnection());
        muc = manager.getMultiUserChat(this.roomJid);

        muc.addParticipantStatusListener(memberListener);
        muc.addUserStatusListener(userListener);
        muc.addParticipantListener(this);
    }

    public String getMeetingId()
    {
        return meetingId;
    }

    @Override
    public void addListener(@NotNull ChatRoomListener listener)
    {
        eventEmitter.addHandler(listener);
    }

    @Override
    public void removeListener(@NotNull ChatRoomListener listener)
    {
        eventEmitter.removeHandler(listener);
    }

    void setStartMuted(boolean[] startMuted)
    {
        eventEmitter.fireEvent(handler -> {
            handler.startMutedChanged(startMuted[0], startMuted[1]);
            return Unit.INSTANCE;
        });
    }

    @Override
    public EntityBareJid getRoomJid()
    {
        return roomJid;
    }

    @Override
    public void join()
        throws SmackException, XMPPException, InterruptedException
    {
        // TODO: clean-up the way we figure out what nickname to use.
        joinAs(xmppProvider.getConfig().getUsername());
    }

    private void joinAs(Resourcepart nickname) throws SmackException, XMPPException, InterruptedException
    {
        this.myOccupantJid = JidCreate.entityFullFrom(roomJid, nickname);

        this.presenceInterceptor = new PresenceListener()
        {
            @Override
            public void processPresence(Presence packet)
            {
                lastPresenceSent = packet;
            }
        };
        muc.addPresenceInterceptor(presenceInterceptor);

        synchronized (muc)
        {
            clearMucOccupantsMap(muc);
            muc.createOrJoin(nickname);
        }

        // Make the room non-anonymous, so that others can recognize focus JID
        Form config = muc.getConfigurationForm();
        String meetingIdFieldName = "muc#roominfo_meetingId";
        FormField meetingIdField = config.getField(meetingIdFieldName);
        if (meetingIdField != null)
        {
            meetingId = meetingIdField.getValuesAsString().stream().findFirst().orElse(null);
            if (meetingId != null)
            {
                logger.addContext("meeting_id", meetingId);
            }
        }

        FillableForm answer = config.getFillableForm();
        // Room non-anonymous
        String whoisFieldName = "muc#roomconfig_whois";
        answer.setAnswer(whoisFieldName, "anyone");

        muc.sendConfigurationForm(answer);
    }

    @Override
    public boolean isJoined()
    {
        return muc != null && muc.isJoined();
    }

    private void leave(String reason, EntityBareJid jid)
    {
        logger.info("Leave, reason: " + reason + " alt-jid: " + jid);

        leave();
    }

    @Override
    public void leave()
    {
        if (presenceInterceptor != null)
        {
            muc.removePresenceInterceptor(presenceInterceptor);
        }

        muc.removeParticipantStatusListener(memberListener);
        muc.removeUserStatusListener(userListener);
        muc.removeParticipantListener(this);

        if (leaveCallback != null)
        {
            leaveCallback.accept(this);
        }

        // Call MultiUserChat.leave() in an IO thread, because it now (with Smack 4.4.3) blocks waiting for a response
        // from the XMPP server (and we want ChatRoom#leave to return immediately).
        TaskPools.getIoPool().submit(() ->
        {
            XMPPConnection connection = xmppProvider.getXmppConnection();
            try
            {
                // FIXME smack4: there used to be a custom dispose() method
                // if leave() fails, there might still be some listeners
                // lingering around
                muc.leave();
            }
            catch (NotConnectedException | InterruptedException | NoResponseException | XMPPException.XMPPErrorException
                    | MultiUserChatException.MucNotJoinedException e)
            {
                // when the connection is not connected and we get NotConnectedException, this is expected (skip log)
                if (connection.isConnected() || !(e instanceof NotConnectedException))
                {
                    logger.error("Failed to properly leave " + muc, e);
                }
            }
        });
    }

    @Override
    public MemberRole getUserRole()
    {
        if (this.role == null)
        {
            Occupant o = muc.getOccupant(myOccupantJid);

            if (o == null)
            {
                return null;
            }
            else
            {
                this.role = MemberRole.fromSmack(o.getRole(), o.getAffiliation());
            }
        }

        return this.role;
    }

    /**
     * Resets cached role instance so that it will be refreshed when {@link
     * #getUserRole()} is called.
     */
    private void resetCachedUserRole()
    {
        role = null;
    }

    /**
     * Resets cached role instance for given occupantJid.
     * @param occupantJid full mucJID of the occupant for whom we want to
     * reset the cached role instance.
     */
    private void resetRoleForOccupant(EntityFullJid occupantJid)
    {
        if (occupantJid.getResourcepart().equals(myOccupantJid.getResourcepart()))
        {
            resetCachedUserRole();
        }
        else
        {
            ChatMemberImpl member = members.get(occupantJid);
            if (member != null)
            {
                member.resetCachedRole();
            }
            else
            {
                logger.error(
                    "Role reset for: " + occupantJid + " who does not exist");
            }
        }
    }

    /**
     * Sets the new role for the local user in the context of this chat room.
     */
    private void setLocalUserRole(@NotNull MemberRole newRole)
    {
        MemberRole oldRole = role;
        this.role = newRole;
        if (oldRole != newRole)
        {
            eventEmitter.fireEvent(handler -> {
                handler.localRoleChanged(newRole, this.role);
                return Unit.INSTANCE;
            });
        }
    }

    @Override
    public List<ChatRoomMember> getMembers()
    {
        synchronized (members)
        {
            return new ArrayList<>(members.values());
        }
    }

    @Override
    public ChatMemberImpl findChatMember(Jid occupantJid)
    {
        if (occupantJid == null)
        {
            return null;
        }

        synchronized (members)
        {
            for (ChatMemberImpl member : members.values())
            {
                if (occupantJid.equals(member.getOccupantJid()))
                {
                    return member;
                }
            }
        }

        return null;
    }

    @Override
    public int getMembersCount()
    {
        return members.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsPresenceExtension(String elementName, String namespace)
    {
        return lastPresenceSent != null && lastPresenceSent.getExtension(elementName, namespace) != null;
    }

    @Override
    public void grantOwnership(String address)
    {
        logger.debug("Grant owner to " + address);

        // Have to construct the IQ manually as Smack version used here seems
        // to be using wrong namespace(muc#owner instead of muc#admin)
        // which does not work with the Prosody.
        MUCAdmin admin = new MUCAdmin();
        admin.setType(IQ.Type.set);
        admin.setTo(roomJid);

        Jid jidAddress;
        try
        {
            jidAddress = JidCreate.from(address);
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException(e);
        }

        MUCItem item = new MUCItem(MUCAffiliation.owner, jidAddress);
        admin.addItem(item);

        AbstractXMPPConnection connection = xmppProvider.getXmppConnection();

        try
        {
            IQ reply = UtilKt.sendIqAndGetResponse(connection, admin);
            if (reply == null || reply.getType() != IQ.Type.result)
            {
                // XXX FIXME throw a declared exception.
                throw new RuntimeException("Failed to grant owner: " + (reply == null ? "" : reply.toXML()));
            }
        }
        catch (SmackException.NotConnectedException e)
        {
            // XXX FIXME throw a declared exception.
            throw new RuntimeException("Failed to grant owner - XMPP disconnected", e);
        }
    }

    @Override
    public boolean destroy(String reason, String alternateAddress)
    {
        try
        {
            muc.destroy(reason,
                alternateAddress != null ?
                    JidCreate.entityBareFrom(alternateAddress) : null);
        }
        catch (XMPPException
                | XmppStringprepException
                | InterruptedException
                | NoResponseException
                | NotConnectedException e)
        {
            //FIXME: should not be runtime, but OperationFailed is not
            // included in interface signature(see also other methods
            // catching XMPPException in this class)
            throw new RuntimeException(e);
        }
        return false;
    }

    Occupant getOccupant(ChatMemberImpl chatMember)
    {
        return muc.getOccupant(chatMember.getOccupantJid());
    }

    /**
     * Returns the MUCUser packet extension included in the packet or
     * <tt>null</tt> if none.
     *
     * @param packet the packet that may include the MUCUser extension.
     * @return the MUCUser found in the packet.
     */
    private MUCUser getMUCUserExtension(Presence packet)
    {
        if (packet != null)
        {
            // Get the MUC User extension
            return packet.getExtension(MUCInitialPresence.ELEMENT, MUCInitialPresence.NAMESPACE);
        }

        return null;
    }

    @Override
    public void setPresenceExtension(ExtensionElement extension, boolean remove)
    {
        if (lastPresenceSent == null)
        {
            logger.error("No presence packet obtained yet");
            return;
        }

        boolean presenceUpdated = false;

        // Remove old
        ExtensionElement old = lastPresenceSent.getExtension(extension.getElementName(), extension.getNamespace());
        if (old != null)
        {
            lastPresenceSent.removeExtension(old);
            presenceUpdated = true;
        }

        if (!remove)
        {
            // Add new
            lastPresenceSent.addExtension(extension);
            presenceUpdated = true;
        }

        if (presenceUpdated)
        {
            sendLastPresence();
        }
    }

    /**
     * {@inheritDoc}
     */
    public Collection<ExtensionElement> getPresenceExtensions()
    {
        return lastPresenceSent != null
            ? new ArrayList<>(lastPresenceSent.getExtensions())
            : Collections.emptyList();
    }

    /**
     * {@inheritDoc}
     */
    public void modifyPresence(Collection<ExtensionElement> toRemove,
                               Collection<ExtensionElement> toAdd)
    {
        if (lastPresenceSent == null)
        {
            logger.error("No presence packet obtained yet");
            return;
        }

        // Remove old
        if (toRemove != null)
        {
            toRemove.forEach(old -> lastPresenceSent.removeExtension(old));
        }

        // Add new
        if (toAdd != null)
        {
            toAdd.forEach(newExt -> lastPresenceSent.addExtension(newExt));
        }

        sendLastPresence();
    }

    /**
     * Prepares and sends the last seen presence.
     * Removes the initial <x> extension and sets new id.
     */
    private void sendLastPresence()
    {
        // The initial presence sent by smack contains an empty "x"
        // extension. If this extension is included in a subsequent stanza,
        // it indicates that the client lost its synchronization and causes
        // the MUC service to re-send the presence of each occupant in the
        // room.
        lastPresenceSent = lastPresenceSent.cloneWithNewId();
        lastPresenceSent.removeExtension(MUCInitialPresence.ELEMENT, MUCInitialPresence.NAMESPACE);

        UtilKt.tryToSendStanza(xmppProvider.getXmppConnection(), lastPresenceSent);
    }

    /**
     * Adds a new {@link ChatMemberImpl} with the given JID to {@link #members}.
     * If a member with the given JID already exists, it returns the existing
     * instance.
     * @param jid the JID of the member.
     * @return the {@link ChatMemberImpl} for the member with the given JID.
     */
    private ChatMemberImpl addMember(EntityFullJid jid)
    {
        synchronized (members)
        {
            if (members.containsKey(jid))
            {
                return members.get(jid);
            }

            ChatMemberImpl newMember = new ChatMemberImpl(jid, ChatRoomImpl.this, members.size() + 1);

            members.put(jid, newMember);

            return newMember;
        }
    }

    /**
     * Gets the "real" JID of an occupant of this room specified by its
     * occupant JID.
     * @param occupantJid the occupant JID.
     * @return the "real" JID of the occupant, or {@code null}.
     */
    Jid getJid(EntityFullJid occupantJid)
    {
        Occupant occupant = muc.getOccupant(occupantJid);
        if (occupant == null)
        {
            logger.error("Unable to get occupant for " + occupantJid);
            return null;
        }
        return occupant.getJid();
    }

    /**
     * Processes a <tt>Presence</tt> packet addressed to our own occupant
     * JID.
     * @param presence the packet to process.
     */
    private void processOwnPresence(Presence presence)
    {
        MUCUser mucUser = getMUCUserExtension(presence);

        if (mucUser != null)
        {
            MUCAffiliation affiliation = mucUser.getItem().getAffiliation();
            MUCRole role = mucUser.getItem().getRole();

            // this is the presence for our member initial role and
            // affiliation, as smack do not fire any initial
            // events lets check it and fire events
            MemberRole jitsiRole = MemberRole.fromSmack(role, affiliation);

            if (!presence.isAvailable()
                && MUCAffiliation.none == affiliation
                && MUCRole.none == role)
            {
                Destroy destroy = mucUser.getDestroy();
                if (destroy == null)
                {
                    // the room is unavailable to us, there is no
                    // message we will just leave
                    leave();
                }
                else
                {
                    leave(destroy.getReason(), destroy.getJid());
                }
            }
            else
            {
                setLocalUserRole(jitsiRole);
            }
        }
    }

    /**
     * Process a <tt>Presence</tt> packet sent by one of the other room
     * occupants.
     * @param presence the presence.
     */
    private void processOtherPresence(Presence presence)
    {
        EntityFullJid jid = presence.getFrom().asEntityFullJidIfPossible();
        if (jid == null)
        {
            logger.warn("Presence without a valid jid: " + presence.getFrom());
            return;
        }

        ChatMemberImpl chatMember;
        boolean memberJoined = false;
        boolean memberLeft = false;

        synchronized (members)
        {
            chatMember = findChatMember(jid);
            if (chatMember == null)
            {
                if (presence.getType().equals(Presence.Type.available))
                {
                    // This is how we detect that a new member has joined. We
                    // do not use the ParticipantStatusListener#joined callback.
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("Joined " + jid + " room: " + roomJid);
                    }
                    chatMember = addMember(jid);
                    memberJoined = true;
                }
                else
                {
                    // We received presence from an unknown member which doesn't
                    // look like a new member's presence. Ignore it.
                    // The member might have been just removed via left(), which
                    // is fine.
                    return;
                }
            }
            else if (presence.getType().equals(Presence.Type.unavailable))
            {
                memberLeft = true;
            }
        }

        if (chatMember != null)
        {
            chatMember.processPresence(presence);

            if (memberJoined)
            {
                // Trigger member "joined"
                ChatRoomMember finalMember = chatMember;
                eventEmitter.fireEvent(handler -> {
                    handler.memberJoined(finalMember);
                    return Unit.INSTANCE;
                });
            }
            else if (memberLeft)
            {
                // In some cases smack fails to call left(). We'll call it here
                // any time we receive presence unavailable
                memberListener.left(jid);
            }

            if (!memberLeft)
            {
                ChatRoomMember finalMember = chatMember;
                eventEmitter.fireEvent(handler -> {
                    handler.memberPresenceChanged(finalMember);
                    return Unit.INSTANCE;
                });
            }
        }
    }

    /**
     * Processes an incoming presence packet.
     *
     * @param presence the incoming presence.
     */
    @Override
    public void processPresence(Presence presence)
    {
        if (presence == null || presence.getError() != null)
        {
            logger.warn("Unable to handle packet: " + (presence == null ? "null" : presence.toXML()));
            return;
        }

        if (logger.isTraceEnabled())
        {
            logger.trace("Presence received " + presence.toXML());
        }

        // Should never happen, but log if something is broken
        if (myOccupantJid == null)
        {
            logger.error("Processing presence when we're not aware of our address");
        }

        if (myOccupantJid != null && myOccupantJid.equals(presence.getFrom()))
        {
            processOwnPresence(presence);
        }
        else
        {
            processOtherPresence(presence);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean isAvModerationEnabled(MediaType mediaType)
    {
        Boolean value = this.avModerationEnabled.get(mediaType);

        // must be non null and true
        return value != null && value;
    }

    /**
     * {@inheritDoc}
     */
    public void setAvModerationEnabled(MediaType mediaType, boolean value)
    {
        this.avModerationEnabled.put(mediaType, value);
    }

    /**
     * {@inheritDoc}
     */
    public void updateAvModerationWhitelists(@NotNull Map<String, List<String>> whitelists)
    {
        this.whitelists = whitelists;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isMemberAllowedToUnmute(Jid jid, MediaType mediaType)
    {
        if (!this.isAvModerationEnabled(mediaType))
        {
            return true;
        }

        List<String> whitelist = this.whitelists.get(mediaType.toString());
        return whitelist != null && whitelist.contains(jid.toString());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventExecutor(@NotNull Executor executor)
    {
        this.eventEmitter = new AsyncEventEmitter<>(executor, eventEmitter.getEventHandlers());
    }

    class MemberListener implements ParticipantStatusListener
    {
        @Override
        public void joined(EntityFullJid mucJid)
        {
            // When a new member joins, Smack seems to fire
            // ParticipantStatusListener#joined and
            // PresenceListener#processPresence in a non-deterministic order.

            // In order to ensure that we have all the information contained
            // in presence at the time that we create a new ChatMemberImpl,
            // we completely ignore this joined event. Instead, we rely on
            // processPresence to detect when a new member has joined and
            // trigger the creation of a ChatMemberImpl by calling
            // ChatRoomImpl#memberJoined()

            if (logger.isDebugEnabled())
            {
                logger.debug("Ignore a member joined event for " + mucJid);
            }
        }

        private ChatMemberImpl removeMember(EntityFullJid occupantJid)
        {
            synchronized (members)
            {
                ChatMemberImpl removed = members.remove(occupantJid);

                if (removed == null)
                {
                    logger.error(occupantJid + " not in " + roomJid);
                }

                return removed;
            }
        }

        /**
         * This needs to be prepared to run twice for the same member.
         * @param occupantJid
         */
        @Override
        public void left(EntityFullJid occupantJid)
        {
            ChatMemberImpl member;

            synchronized (members)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Left " + occupantJid + " room: " + roomJid);
                }

                member = removeMember(occupantJid);
            }

            if (member != null)
            {
                eventEmitter.fireEvent(handler -> {
                    handler.memberLeft(member);
                    return Unit.INSTANCE;
                });
            }
            else
            {
                logger.info("Member left event for non-existing member: " + occupantJid);
            }
        }

        @Override
        public void kicked(EntityFullJid occupantJid, Jid actor, String reason)
        {
            ChatMemberImpl member;

            synchronized (members)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Kicked: " + occupantJid + ", " + actor + ", " + reason);
                }

                member = removeMember(occupantJid);
            }

            if (member == null)
            {
                logger.error("Kicked member does not exist: " + occupantJid);
                return;
            }

            eventEmitter.fireEvent(handler -> {
                handler.memberKicked(member);
                return Unit.INSTANCE;
            });
        }

        @Override
        public void voiceGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Voice granted: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void voiceRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Voice revoked: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void banned(EntityFullJid s, Jid actor, String reason)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Banned: " + s + ", " + actor + ", " + reason);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void membershipGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Membership granted: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void membershipRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Membership revoked: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void moderatorGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Moderator granted: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void moderatorRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Moderator revoked: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void ownershipGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Ownership granted: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void ownershipRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Ownership revoked: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void adminGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Admin granted: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void adminRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Admin revoked: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void nicknameChanged(EntityFullJid oldNickname,
                                    Resourcepart newNickname)
        {
            logger.error("nicknameChanged - NOT IMPLEMENTED");
            /*synchronized (members)
            {
                removeMember(oldNickname);

                addMember(newNickname);
            }*/
        }
    }

    /**
     * Listens for room destroyed and pass it to the conference.
     */
    class LocalUserStatusListener
        implements UserStatusListener
    {
        @Override
        public void roomDestroyed(MultiUserChat alternateMUC, String reason)
        {
            eventEmitter.fireEvent(handler -> {
                handler.roomDestroyed(reason);
                return Unit.INSTANCE;
            });
        }
    }

    /**
     * Due to a race in Smack 4.4.3 handling presence while leaving, there are cases where the MultiUserChat
     * object's occupantsMap object is not empty, as it should be, when we first reference it for the next
     * chat instance.  This function uses reflection to hack the internal state to fix the problem.
     */
    private void clearMucOccupantsMap(MultiUserChat muc)
    {
        assert(!muc.isJoined());

        Field occupantsMapField = null;
        try
        {
            occupantsMapField = muc.getClass().getDeclaredField("occupantsMap");
            occupantsMapField.setAccessible(true);

            Map<EntityFullJid, Presence> occupantsMap = (Map<EntityFullJid, Presence>)occupantsMapField.get(muc);
            if (!occupantsMap.isEmpty())
            {
                logger.warn("MultiUserChat occupantsMap is not empty, clearing.");
                occupantsMap.clear();
            }
        }
        catch (NoSuchFieldException | IllegalAccessException e)
        {
            logger.error("Unable to reset MultiUserChat occupantsMap", e);
        }
    }
}
