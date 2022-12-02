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

import javax.xml.namespace.*;

import edu.umd.cs.findbugs.annotations.*;
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

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Stripped implementation of <tt>ChatRoom</tt> using Smack library.
 *
 * @author Pawel Domas
 */
@SuppressFBWarnings(
        value = "JLM_JSR166_UTILCONCURRENT_MONITORENTER",
        justification = "We intentionally synchronize on [members] (a ConcurrentHashMap)."
)
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
    @NotNull
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
    private org.jivesoftware.smack.util.Consumer<PresenceBuilder> presenceInterceptor;

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

    private final Map<EntityFullJid, ChatRoomMemberImpl> members = new ConcurrentHashMap<>();

    /**
     * Local user role.
     */
    private MemberRole role;

    /**
     * Stores our last MUC presence packet for future update.
     */
    private PresenceBuilder lastPresenceSent;

    /**
     * The value of the "meetingId" field from the MUC form, if present.
     */
    private String meetingId = null;

    /**
     * The value of the "isbreakout" field from the MUC form, if present.
     */
    private boolean isBreakoutRoom = false;

    /**
     * The value of "breakout_main_room" field from the MUC form, if present.
     */
    private String mainRoom = null;

    /**
     * Indicates whether A/V Moderation is enabled for this room.
     */
    private final Map<MediaType, Boolean> avModerationEnabled = Collections.synchronizedMap(new HashMap<>());

    private Map<String, List<String>> whitelists = new HashMap<>();

    /**
     * The emitter used to fire events.
     */
    private final EventEmitter<ChatRoomListener> eventEmitter = new SyncEventEmitter<>();

    private static class MucConfigFields
    {
        static final String IS_BREAKOUT_ROOM =  "muc#roominfo_isbreakout";
        static final String MAIN_ROOM = "muc#roominfo_breakout_main_room";
        static final String MEETING_ID = "muc#roominfo_meetingId";
        static final String WHOIS = "muc#roomconfig_whois";
    }

    /**
     * The number of members that currently have their audio sources unmuted.
     */
    private int numAudioSenders;

    /**
     * The number of members that currently have their video sources unmuted.
     */
    private int numVideoSenders;

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
        logger.addContext("room", roomJid.toString());
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

    public void setStartMuted(boolean[] startMuted)
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
        resetState();
        joinAs(xmppProvider.getConfig().getUsername());
    }

    @Override
    public @NotNull XmppProvider getXmppProvider()
    {
        return xmppProvider;
    }

    @Override
    public boolean isBreakoutRoom()
    {
        return isBreakoutRoom;
    }

    @Override
    public String getMainRoom()
    {
        return mainRoom;
    }

    /**
     * Prepare this {@link ChatRoomImpl} for a call to {@link #joinAs(Resourcepart)}, which send initial presence to
     * the MUC. Resets any state that might have been set the previous time the MUC was joined.
     */
    private void resetState()
    {
        synchronized (members)
        {
            if (!members.isEmpty())
            {
                logger.warn("Removing " + members.size() + " stale members.");
                members.clear();
            }
        }

        synchronized (this)
        {
            role = null;
            lastPresenceSent = null;
            meetingId = null;
            logger.addContext("meeting_id", "");
            isBreakoutRoom = false;
            mainRoom = null;
            avModerationEnabled.clear();
            whitelists.clear();
        }
    }

    private void joinAs(Resourcepart nickname) throws SmackException, XMPPException, InterruptedException
    {
        this.myOccupantJid = JidCreate.entityFullFrom(roomJid, nickname);

        this.presenceInterceptor = presenceBuilder ->
        {
            // The initial presence sent by smack contains an empty "x"
            // extension. If this extension is included in a subsequent stanza,
            // it indicates that the client lost its synchronization and causes
            // the MUC service to re-send the presence of each occupant in the
            // room.
            synchronized (ChatRoomImpl.this)
            {
                Presence p = presenceBuilder.build();
                p.removeExtension(
                    MUCInitialPresence.ELEMENT,
                    MUCInitialPresence.NAMESPACE
                );
                lastPresenceSent = p.asBuilder();
            }
        };
        if (muc.isJoined())
        {
            muc.leave();
        }
        muc.addPresenceInterceptor(presenceInterceptor);
        muc.createOrJoin(nickname);

        Form config = muc.getConfigurationForm();

        // Read breakout rooms options
        FormField isBreakoutRoomField = config.getField(MucConfigFields.IS_BREAKOUT_ROOM);
        if (isBreakoutRoomField != null)
        {
            isBreakoutRoom = Boolean.parseBoolean(isBreakoutRoomField.getFirstValue());
            if (isBreakoutRoom)
            {
                FormField mainRoomField = config.getField(MucConfigFields.MAIN_ROOM);
                if (mainRoomField != null)
                {
                    mainRoom = mainRoomField.getFirstValue();
                }
            }
        }

        // Read meetingId
        FormField meetingIdField = config.getField(MucConfigFields.MEETING_ID);
        if (meetingIdField != null)
        {
            meetingId = meetingIdField.getFirstValue();
            if (meetingId != null)
            {
                logger.addContext("meeting_id", meetingId);
            }
        }

        // Make the room non-anonymous, so that others can recognize focus JID
        FillableForm answer = config.getFillableForm();
        answer.setAnswer(MucConfigFields.WHOIS, "anyone");

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
        TaskPools.getIoPool().execute(() ->
        {
            XMPPConnection connection = xmppProvider.getXmppConnection();
            try
            {
                // FIXME smack4: there used to be a custom dispose() method
                // if leave() fails, there might still be some listeners
                // lingering around
                if (isJoined())
                {
                    muc.leave();
                }
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
            ChatRoomMemberImpl member = members.get(occupantJid);
            if (member != null)
            {
                member.resetCachedRole();
            }
            else
            {
                logger.error("Role reset for: " + occupantJid + " who does not exist");
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
    public ChatRoomMemberImpl getChatMember(EntityFullJid occupantJid)
    {
        if (occupantJid == null)
        {
            return null;
        }

        return members.get(occupantJid);
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
    public synchronized boolean containsPresenceExtension(String elementName, String namespace)
    {
        return lastPresenceSent != null
            && lastPresenceSent.getExtension(new QName(namespace, elementName))
            != null;
    }

    @Override
    public void grantOwnership(@NotNull ChatRoomMember member)
    {
        logger.debug("Grant owner to " + member);

        // Have to construct the IQ manually as Smack version used here seems
        // to be using wrong namespace(muc#owner instead of muc#admin)
        // which does not work with the Prosody.
        MUCAdmin admin = new MUCAdmin();
        admin.setType(IQ.Type.set);
        admin.setTo(roomJid);

        MUCItem item = new MUCItem(MUCAffiliation.owner, member.getJid().asBareJid());
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

    public Occupant getOccupant(ChatRoomMemberImpl chatMember)
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
            return packet.getExtension(MUCUser.class);
        }

        return null;
    }

    @Override
    public void setPresenceExtension(ExtensionElement extension, boolean remove)
    {
        Presence presenceToSend = null;
        synchronized (this)
        {
            if (lastPresenceSent == null)
            {
                logger.error("No presence packet obtained yet");
                return;
            }

            boolean presenceUpdated = false;

            // Remove old
            ExtensionElement old = lastPresenceSent.getExtension(extension.getQName());
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
                presenceToSend = lastPresenceSent.build();
            }
        }

        if (presenceToSend != null)
        {
            sendPresence(presenceToSend);
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized Collection<ExtensionElement> getPresenceExtensions()
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
        Presence presenceToSend;
        synchronized (this)
        {
            if (lastPresenceSent == null)
            {
                logger.error("No presence packet obtained yet");
                return;
            }

            // Remove old
            if (toRemove != null)
            {
                toRemove.forEach(lastPresenceSent::removeExtension);
            }

            // Add new
            if (toAdd != null)
            {
                toAdd.forEach(lastPresenceSent::addExtension);
            }

            presenceToSend = lastPresenceSent.build();
        }

        sendPresence(presenceToSend);
    }

    /**
     * Sends a presence.
     */
    private void sendPresence(Presence presence)
    {
        UtilKt.tryToSendStanza(xmppProvider.getXmppConnection(), presence);
    }

    @Override
    public int getAudioSendersCount()
    {
        return numAudioSenders;
    }

    @Override
    public int getVideoSendersCount()
    {
        return numVideoSenders;
    }

    public void addAudioSender()
    {
        ++numAudioSenders;
        logger.debug(() -> "The number of audio senders has increased to " + numAudioSenders + ".");

        eventEmitter.fireEvent(handler -> {
            handler.numAudioSendersChanged(numAudioSenders);
            return Unit.INSTANCE;
        });
    }

    public void removeAudioSender()
    {
        --numAudioSenders;
        logger.debug(() -> "The number of audio senders has decreased to " + numAudioSenders + ".");

        eventEmitter.fireEvent(handler -> {
            handler.numAudioSendersChanged(numAudioSenders);
            return Unit.INSTANCE;
        });
    }

    public void addVideoSender()
    {
        ++numVideoSenders;
        logger.debug(() -> "The number of video senders has increased to " + numVideoSenders + ".");

        eventEmitter.fireEvent(handler -> {
            handler.numVideoSendersChanged(numVideoSenders);
            return Unit.INSTANCE;
        });
    }

    public void removeVideoSender()
    {
        --numVideoSenders;
        logger.debug(() -> "The number of video senders has decreased to " + numVideoSenders + ".");

        eventEmitter.fireEvent(handler -> {
            handler.numVideoSendersChanged(numVideoSenders);
            return Unit.INSTANCE;
        });
    }

    /**
     * Adds a new {@link ChatRoomMemberImpl} with the given JID to {@link #members}.
     * If a member with the given JID already exists, it returns the existing
     * instance.
     * @param jid the JID of the member.
     * @return the {@link ChatRoomMemberImpl} for the member with the given JID.
     */
    private ChatRoomMemberImpl addMember(EntityFullJid jid)
    {
        synchronized (members)
        {
            if (members.containsKey(jid))
            {
                return members.get(jid);
            }

            ChatRoomMemberImpl newMember = new ChatRoomMemberImpl(jid, ChatRoomImpl.this, logger);

            members.put(jid, newMember);

            if (!newMember.isAudioMuted())
                addAudioSender();
            if (!newMember.isVideoMuted())
                addVideoSender();

            return newMember;
        }
    }

    /**
     * Gets the "real" JID of an occupant of this room specified by its
     * occupant JID.
     * @param occupantJid the occupant JID.
     * @return the "real" JID of the occupant, or {@code null}.
     */
    public Jid getJid(EntityFullJid occupantJid)
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

        ChatRoomMemberImpl chatMember;
        boolean memberJoined = false;
        boolean memberLeft = false;

        synchronized (members)
        {
            chatMember = getChatMember(jid);
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

    @Override
    @NotNull
    public OrderedJsonObject getDebugState()
    {
        OrderedJsonObject o = new OrderedJsonObject();
        o.put("room_jid", roomJid.toString());
        o.put("my_occupant_jid", String.valueOf(myOccupantJid));
        OrderedJsonObject membersJson = new OrderedJsonObject();
        for (ChatRoomMemberImpl m : members.values())
        {
            membersJson.put(m.getJid(), m.getDebugState());
        }
        o.put("members", membersJson);
        o.put("role", String.valueOf(role));
        o.put("meeting_id", String.valueOf(meetingId));
        o.put("is_breakout_room", isBreakoutRoom);
        o.put("main_room", String.valueOf(mainRoom));
        o.put("num_audio_senders", numAudioSenders);
        o.put("num_video_senders", numVideoSenders);

        return o;
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

        private ChatRoomMemberImpl removeMember(EntityFullJid occupantJid)
        {
            synchronized (members)
            {
                ChatRoomMemberImpl removed = members.remove(occupantJid);

                if (removed == null)
                {
                    logger.error(occupantJid + " not in " + roomJid);
                }
                else
                {
                    if (!removed.isAudioMuted())
                        removeAudioSender();
                    if (!removed.isVideoMuted())
                        removeVideoSender();
                }

                return removed;
            }
        }

        /**
         * This needs to be prepared to run twice for the same member.
         */
        @Override
        public void left(EntityFullJid occupantJid)
        {
            ChatRoomMemberImpl member;

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
            ChatRoomMemberImpl member;

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
}
