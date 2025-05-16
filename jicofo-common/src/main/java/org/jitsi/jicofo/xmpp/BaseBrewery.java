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
package org.jitsi.jicofo.xmpp;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.tcp.*;
import org.jxmpp.jid.*;

import java.lang.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * <tt>BaseBrewery</tt> manages the pool of service instances which
 * exist in the current session. Does that by joining "brewery" room where
 * instances connect to and publish their status in MUC presence.
 * <tt>PacketExtension</tt> is the packet extension that will be used
 * from that service to publish its presence.
 *
 * @author Pawel Domas
 * @author Damian Minkov
 */
public abstract class BaseBrewery<T extends ExtensionElement>
    implements XmppProvider.Listener
{
    /**
     * The logger
     */
    private final Logger logger;

    private final ChatRoomListener chatRoomListener = new ChatRoomListenerImpl();
    /**
     * The MUC JID of the room which this detector will join.
     */
    private final EntityBareJid breweryJid;

    private final XmppProvider xmppProvider;

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
     * Reconnect timer. Used to stop the conference if XMPP connection is not restored in a given time.
     */
    private Future<?> reconnectTimeout;

    /**
     * Creates new instance of <tt>BaseBrewery</tt>
     * @param breweryJid the MUC JID of the room which this detector will join,
     * e.g. {@code roomName@muc-servicename.jabserver.com}.
     * @param presenceExtensionElementName the element name of the extension
     * which this brewery will look for.
     * @param presenceExtensionNamespace the namespace of the extension
     * which this brewery will look for.
     */
    public BaseBrewery(
        @NotNull XmppProvider xmppProvider,
        @NotNull EntityBareJid breweryJid,
        String presenceExtensionElementName,
        String presenceExtensionNamespace,
        Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.xmppProvider = xmppProvider;
        this.breweryJid = breweryJid;
        logger.addContext("brewery", breweryJid.getLocalpartOrThrow().toString());
        this.extensionElementName = presenceExtensionElementName;
        this.extensionNamespace = presenceExtensionNamespace;
        logger.info("Initialized with JID=" + breweryJid);
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
        xmppProvider.addListener(this);

        maybeStart();
    }

    /**
     * Starts the whole thing if we have XMPP connection up and running.
     */
    private void maybeStart()
    {
        if (chatRoom == null && xmppProvider.getRegistered())
        {
            start();
        }
    }

    /**
     * Stops and releases allocated resources.
     */
    public void shutdown()
    {
        xmppProvider.removeListener(this);

        stop();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void registrationChanged(boolean registered)
    {
        if (registered)
        {
            if (this.reconnectTimeout != null)
            {
                this.reconnectTimeout.cancel(true);
                this.reconnectTimeout = null;
            }

            AbstractXMPPConnection connection = xmppProvider.getXmppConnection();
            if (connection instanceof XMPPTCPConnection && !((XMPPTCPConnection) connection).streamWasResumed())
            {
                // We are not resuming the stream, so we need to stop and start clean
                stop();
            }

            maybeStart();
        }
        else
        {
            reconnectTimeout = TaskPools.getScheduledPool().schedule(
                this::stop,
                XmppConfig.service.getReplyTimeout().toMillis(),
                TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void componentsChanged(@NotNull Set<XmppProvider.Component> components)
    {
    }

    /**
     * Starts the detector by joining it to the brewery room.
     */
    private void start()
    {
        try
        {
            chatRoom = xmppProvider.createRoom(breweryJid);
            chatRoom.addListener(chatRoomListener);
            chatRoom.join();

            logger.info("Joined the room.");
        }
        catch (InterruptedException | SmackException | XMPPException | XmppProvider.RoomExistsException e)
        {
            logger.error("Failed to create room.", e);

            // cleanup on failure so we can retry
            if (chatRoom != null)
            {
                chatRoom.removeListener(chatRoomListener);
                chatRoom = null;
            }
        }
    }

    /**
     * Stops detector by removing listeners and leaving brewery room.
     */
    private void stop()
    {
        try
        {
            if (chatRoom != null)
            {
                chatRoom.removeListener(chatRoomListener);
                chatRoom.leave();

                logger.info("Left the room.");
            }
        }
        finally
        {
            // even if leave fails we want to cleanup, so we can retry
            chatRoom = null;

            // Clean up the list of service instances
            instances.forEach(this::removeInstance);
        }
    }

    private class ChatRoomListenerImpl extends DefaultChatRoomListener
    {
        @Override
        public void memberJoined(@NotNull ChatRoomMember member)
        {
            // Process idle or busy
            processMemberPresence(member);
        }

        @Override
        public void memberLeft(@NotNull ChatRoomMember member)
        {
            removeInstanceForMember(member);
        }

        @Override
        public void memberKicked(@NotNull ChatRoomMember member)
        {
            removeInstanceForMember(member);
        }

        @Override
        public void memberPresenceChanged(@NotNull ChatRoomMember member)
        {
            // Process idle or busy
            processMemberPresence(member);
        }

        private void removeInstanceForMember(@NotNull ChatRoomMember member)
        {
            // Process offline status
            BrewInstance instance = find(member.getOccupantJid());

            if (instance != null)
            {
                removeInstance(instance);
            }
        }
    }

    /**
     * Process chat room member status changed. Extract the appropriate
     * presence extension and use it to process it further.
     * @param member the chat member to process
     *
     * Temporarily exposed for testing.
     */
    public void processMemberPresence(@NotNull ChatRoomMember member)
    {
        Presence presence = member.getPresence();

        if (presence == null)
        {
            return;
        }

        @SuppressWarnings("unchecked")
        T ext = (T) presence.getExtensionElement(extensionElementName, extensionNamespace);

        // if the extension is missing skip processing
        if (ext == null)
        {
            return;
        }

        processInstanceStatusChanged(member.getOccupantJid(), ext);
    }

    /**
     * Process a MUC member status presence changed. Use the presence extension
     * to notify implementors for the change. Stores instance if we do not
     * have it cached locally, otherwise just update the new status.
     * @param jid the occupant (MUC) JID of the member.
     * @param extension the presence extension representing this brewing
     * instance status.
     */
    protected void processInstanceStatusChanged(@NotNull EntityFullJid jid, @NotNull T extension)
    {
        BrewInstance instance = find(jid);

        if (instance == null)
        {
            instance = new BrewInstance(jid, extension);
            addInstance(instance);
        }
        else
        {
            instance.status = extension;
        }

        logger.debug(() -> "New presence from " + jid + ": " + extension.toXML());
        onInstanceStatusChanged(jid, extension);
    }

    public int getInstanceCount()
    {
        return instances.size();
    }

    public int getInstanceCount(@NotNull Predicate<? super BrewInstance> filter)
    {
        return (int) instances.stream().filter(filter) .count();
    }

    /**
     * Notified for MUC service status update by providing its presence
     * extension.
     *
     * @param jid the brewing instance muc address
     * @param status the updated status for that instance
     */
    abstract protected void onInstanceStatusChanged(@NotNull EntityFullJid jid, @NotNull T status);

    /**
     * Finds instance by muc address.
     *
     * @param jid the address to use while searching for muc instance.
     * @return the brewing instance or null if not found.
     */
    private BrewInstance find(Jid jid)
    {
        return instances.stream()
            .filter(i -> i.jid.equals(jid))
            .findFirst()
            .orElse(null);
    }

    private void addInstance(BrewInstance i)
    {
        instances.add(i);
        logger.info("Added brewery instance: " + i.jid);
    }

    /**
     * Removes an instance from the cache and notifies that instance is going
     * offline.
     *
     * @param i the brewing instance
     */
    private void removeInstance(@NotNull BrewInstance i)
    {
        instances.remove(i);
        logger.info("Removed brewery instance: " + i.jid);
        notifyInstanceOffline(i.jid);
    }

    /**
     * Notifies that a brewing instance is going offline.
     * @param jid the instance muc address
     */
    abstract protected void notifyInstanceOffline(@NotNull Jid jid);

    /**
     * Internal structure for storing information about brewing instances.
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public class BrewInstance
    {
        /**
         * Eg. "room@muc.server.net/nick"
         */
        @NotNull
        public final EntityFullJid jid;

        /**
         * One of {@link ExtensionElement}
         */
        @NotNull
        public T status;

        BrewInstance(@NotNull EntityFullJid jid, @NotNull T status)
        {
            this.jid = jid;
            this.status = status;
        }
    }
}
