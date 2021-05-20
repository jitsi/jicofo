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
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.jitsi.impl.protocol.xmpp.ChatRoomMemberPresenceChangeEvent.*;

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
                RegistrationListener
{
    /**
     * The logger
     */
    private final Logger logger;

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
        xmppProvider.addRegistrationListener(this);

        maybeStart();
    }

    /**
     * Starts the whole thing if we have XMPP connection up and running.
     */
    private void maybeStart()
    {
        if (chatRoom == null && xmppProvider.isRegistered())
        {
            start();
        }
    }

    /**
     * Stops and releases allocated resources.
     */
    public void shutdown()
    {
        xmppProvider.removeRegistrationListener(this);

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
            maybeStart();
        }
        else
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
            chatRoom = xmppProvider.createRoom(breweryJid);
            chatRoom.addMemberPresenceListener(this);
            chatRoom.join();

            logger.info("Joined the room.");
        }
        catch (InterruptedException | SmackException | XMPPException | XmppProvider.RoomExistsException e)
        {
            logger.error("Failed to create room.", e);

            // cleanup on failure so we can retry
            if (chatRoom != null)
            {
                chatRoom.removeMemberPresenceListener(this);
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
            if(chatRoom != null)
            {
                chatRoom.removeMemberPresenceListener(this);
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

    @Override
    synchronized public void memberPresenceChanged(@NotNull ChatRoomMemberPresenceChangeEvent presenceEvent)
    {
        ChatRoomMember chatMember = presenceEvent.getChatRoomMember();
        if (presenceEvent instanceof Joined || presenceEvent instanceof PresenceUpdated)
        {
            // Process idle or busy
            processMemberPresence(chatMember);
        }
        else if (presenceEvent instanceof Left || presenceEvent instanceof Kicked)
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
     * Notify this {@link BaseBrewery} that the member with jid {@code member} experienced
     * an error which is expected to be transient.
     *
     * @param member the {@link Jid} of the member who experienced the error
     */
    synchronized public void memberHadTransientError(Jid member)
    {
        BrewInstance instance = find(member);
        if (instance != null)
        {
            logger.info("Jid member " + member + " had a transient error, moving to the back of the queue");
            // Move the instance to the back of the list
            removeInstance(instance);
            addInstance(instance);
        }
    }

    /**
     * Process chat room member status changed. Extract the appropriate
     * presence extension and use it to process it further.
     * @param member the chat member to process
     */
    private void processMemberPresence(ChatRoomMember member)
    {
        Presence presence = member.getPresence();

        if (presence == null)
        {
            return;
        }

        T ext = presence.getExtension(extensionElementName, extensionNamespace);

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
    protected void processInstanceStatusChanged(@NotNull Jid jid, @NotNull T extension)
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

        logger.debug("New presence from " + jid + ": " + extension.toXML());
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
    abstract protected void onInstanceStatusChanged(@NotNull Jid jid, @NotNull T status);

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
    public class BrewInstance
    {
        /**
         * Eg. "room@muc.server.net/nick"
         */
        @NotNull
        public final Jid jid;

        /**
         * One of {@link ExtensionElement}
         */
        @NotNull
        public T status;

        BrewInstance(@NotNull Jid jid, @NotNull T status)
        {
            this.jid = jid;
            this.status = status;
        }
    }
}
