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
package org.jitsi.jicofo.xmpp;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.util.*;
import org.jitsi.assertions.*;
import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * <tt>BaseBrewery</tt> manages the pool of service instances which
 * exist in the current session. Does that by joining "brewery" room where
 * instances connect to and publish their's status in MUC presence.
 * <tt>PacketExtension</tt> is the packet extension that will be used
 * from that service to publish its presence.
 *
 * @author Pawel Domas
 * @author Damian Minkov
 */
public abstract class BaseBrewery<T extends ExtensionElement>
    implements ChatRoomMemberPresenceListener,
               ChatRoomMemberPropertyChangeListener,
               RegistrationStateChangeListener
{
    /**
     * The logger
     */
    private static final Logger logger = Logger.getLogger(BaseBrewery.class);

    /**
     * The name fo XMPP MUC room where all service instances gather to brew
     * together.
     */
    private final String breweryName;

    /**
     * The <tt>ProtocolProviderHandler</tt> for Jicofo's XMPP connection.
     */
    private final ProtocolProviderHandler protocolProvider;

    /**
     * The <tt>ChatRoom</tt> instance for the brewery.
     */
    private ChatRoom chatRoom;

    /**
     * The list of all currently known instances.
     */
    protected final List<BrewInstance> instances = new CopyOnWriteArrayList<>();

    /**
     * The presence extension element name.
     */
    private final String extensionElementName;

    /**
     * The presence extension namespace.
     */
    private final String extensionNamespace;

    /**
     * Creates new instance of <tt>BaseBrewery</tt>
     * @param protocolProvider the instance fo <tt>ProtocolProviderHandler</tt>
     * for Jicofo's XMPP connection.
     * @param breweryName the name of the brewery MUC room where all
     * brewing instance will gather. Can be just roomName or the full room id:
     * roomName@muc-servicename.jabserver.com. In case of just room name a
     * the muc service will be discovered from server and in case of multiple
     * will use the first one.
     * @param presenceExtensionElementName the element name of the extension
     * @param presenceExtensionNamespace the namespace of the extension
     */
    public BaseBrewery(ProtocolProviderHandler protocolProvider,
        String breweryName,
        String presenceExtensionElementName,
        String presenceExtensionNamespace)
    {
        this.protocolProvider
            = Objects.requireNonNull(protocolProvider, "protocolProvider");
        Assert.notNullNorEmpty(breweryName, "breweryName");

        this.breweryName = breweryName;
        this.extensionElementName = presenceExtensionElementName;
        this.extensionNamespace = presenceExtensionNamespace;
    }

    /**
     * Checks whether or not there are any service instances connected.
     * @return <tt>true</tt> if there are any instances currently
     * connected to the brewery room or <tt>false</tt> otherwise.
     */
    public boolean isAnyInstanceConnected()
    {
        return instances.size() > 0;
    }

    /**
     * Initializes this instance.
     */
    public void init()
    {
        protocolProvider.addRegistrationListener(this);

        maybeStart();
    }

    /**
     * Starts the whole thing if we have XMPP connection up and running.
     */
    private void maybeStart()
    {
        if (chatRoom == null
            && protocolProvider.getProtocolProvider().isRegistered())
        {
            start();
        }
    }

    /**
     * Stops and releases allocated resources.
     */
    public void dispose()
    {
        protocolProvider.removeRegistrationListener(this);

        stop();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void registrationStateChanged(
        RegistrationStateChangeEvent registrationStateChangeEvent)
    {
        RegistrationState newState = registrationStateChangeEvent.getNewState();

        if (RegistrationState.REGISTERED.equals(newState))
        {
            maybeStart();
        }
        else if (RegistrationState.UNREGISTERED.equals(newState))
        {
            stop();
        }
    }

    /**
     * Starts the detector by joining it to the brewery room.
     */
    private void start()
    {
        try
        {
            OperationSetMultiUserChat muc
                = protocolProvider.getOperationSet(
                    OperationSetMultiUserChat.class);

            Objects.requireNonNull(muc, "OperationSetMultiUserChat");

            chatRoom = muc.createChatRoom(breweryName, null);
            chatRoom.addMemberPresenceListener(this);
            chatRoom.addMemberPropertyChangeListener(this);
            chatRoom.join();

            logger.info("Joined brewery room: " + breweryName);
        }
        catch (OperationFailedException | OperationNotSupportedException e)
        {
            logger.error("Failed to create room: " + breweryName, e);
        }
    }

    /**
     * Stops detector by removing listeners and leaving brewery room.
     */
    private void stop()
    {
        if (chatRoom != null)
        {
            chatRoom.removeMemberPresenceListener(this);
            chatRoom.removeMemberPropertyChangeListener(this);
            chatRoom.leave();
            chatRoom = null;

            logger.info("Left brewery room: " + breweryName);
        }

        // Clean up the list of service instances
        List<BrewInstance> instancesCopy = new ArrayList<>(instances);
        for (BrewInstance i : instancesCopy)
        {
            removeInstance(i);
        }
    }

    @Override
    synchronized public void memberPresenceChanged(
        ChatRoomMemberPresenceChangeEvent presenceEvent)
    {
        XmppChatMember chatMember
            = (XmppChatMember) presenceEvent.getChatRoomMember();
        String eventType = presenceEvent.getEventType();
        if (ChatRoomMemberPresenceChangeEvent.MEMBER_JOINED.equals(eventType))
        {
            // Process idle or busy
            processMemberPresence(chatMember);
        }
        else if (ChatRoomMemberPresenceChangeEvent.MEMBER_LEFT.equals(eventType)
            || ChatRoomMemberPresenceChangeEvent.MEMBER_KICKED.equals(eventType)
            || ChatRoomMemberPresenceChangeEvent.MEMBER_QUIT.equals(eventType))
        {
            // Process offline status
            BrewInstance instance = find(chatMember.getOccupantJid());

            if (instance != null)
            {
                removeInstance(instance);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void chatRoomPropertyChanged(
        ChatRoomMemberPropertyChangeEvent memberPropertyEvent)
    {
        XmppChatMember member
            = (XmppChatMember) memberPropertyEvent.getSourceChatRoomMember();

        processMemberPresence(member);
    }

    /**
     * Process chat room member status changed. Extract the appropriate
     * presence extension and use it to process it further.
     * @param member the chat member to process
     */
    private void processMemberPresence(XmppChatMember member)
    {
        Presence presence = member.getPresence();

        if (presence == null)
        {
            return;
        }

        ExtensionElement ext
            = presence.getExtension(extensionElementName, extensionNamespace);

        // if the extension is missing skip processing
        if (ext == null)
        {
            return;
        }

        processInstanceStatusChanged(member.getOccupantJid(), (T) ext);
    }

    /**
     * Process a MUC member status presence changed. Use the presence extension
     * to notify implementors for the change. Stores instance if we do not
     * have it cached locally, otherwise just update the new status.
     * @param jid the occupant (MUC) JID of the member.
     * @param extension the presence extension representing this brewing
     * instance status.
     */
    private void processInstanceStatusChanged(
        EntityFullJid jid, T extension)
    {
        BrewInstance instance = find(jid);

        if (instance == null)
        {
            instance = new BrewInstance(jid, extension);
            instances.add(instance);

            logger.info("Added brewery instance: " + jid);
        }
        else
        {
            instance.status = extension;
        }

        onInstanceStatusChanged(instance.jid, extension);
    }

    /**
     * Notified for MUC service status update by providing its presence
     * extension.
     *
     * @param jid the brewing instance muc address
     * @param status the updated status for that instance
     */
    abstract protected void onInstanceStatusChanged(
        EntityFullJid jid, T status);

    /**
     * Finds instance by muc address.
     *
     * @param jid the address to use while searching for muc instance.
     * @return the brewing instance or null if not found.
     */
    private BrewInstance find(EntityFullJid jid)
    {
        return instances.stream()
            .filter(i -> i.jid.equals(jid))
            .findFirst()
            .orElse(null);
    }

    /**
     * Removes an instance from the cache and notifies that instance is going
     * offline.
     *
     * @param i the brewing instance
     */
    private void removeInstance(BrewInstance i)
    {
        instances.remove(i);

        logger.info("Removed brewery instance: " + i.jid);

        notifyInstanceOffline(i.jid);
    }

    /**
     * Notifies that a brewing instance is going offline.
     * @param jid the instance muc address
     */
    abstract protected void notifyInstanceOffline(EntityFullJid jid);

    /**
     * Internal structure for storing information about brewing instances.
     */
    protected class BrewInstance
    {
        /**
         * Eg. "room@muc.server.net/nick"
         */
        public final EntityFullJid jid;

        /**
         * One of {@link ExtensionElement}
         */
        public T status;

        BrewInstance(EntityFullJid jid, T status)
        {
            this.jid = jid;
            this.status = status;
        }
    }
}
