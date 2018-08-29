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
import net.java.sip.communicator.service.shutdown.*;
import net.java.sip.communicator.util.*;

import org.jitsi.jicofo.event.*;
import org.jitsi.service.configuration.*;
import org.jitsi.eventadmin.*;
import org.jitsi.util.Logger;

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Manages {@link JitsiMeetConference} on some server. Takes care of creating
 * and expiring conference focus instances. Manages focus XMPP connection.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class FocusManager
    implements JitsiMeetConferenceImpl.ConferenceListener,
               RegistrationStateChangeListener
{
    /**
     * The logger used by this instance.
     */
    private final static Logger logger = Logger.getLogger(FocusManager.class);

    /**
     * Name of configuration property for focus idle timeout.
     */
    public static final String IDLE_TIMEOUT_PNAME
        = "org.jitsi.focus.IDLE_TIMEOUT";

    /**
     * Default amount of time for which the focus is being kept alive in idle
     * mode (no peers in the room).
     */
    public static final long DEFAULT_IDLE_TIMEOUT = 15000;

    /**
     * The name of the configuration property that specifies server hostname to
     * which the focus user will connect to.
     */
    public static final String HOSTNAME_PNAME = "org.jitsi.jicofo.HOSTNAME";

    /**
     * The name of configuration property that specifies XMPP domain that hosts
     * the conference and will be used in components auto-discovery. This is the
     * domain on which the jitsi-videobridge runs.
     */
    public static final String XMPP_DOMAIN_PNAME
        = "org.jitsi.jicofo.XMPP_DOMAIN";

    /**
     * The name of the configuration property that specifies XMPP domain of
     * the focus user.
     */
    public static final String FOCUS_USER_DOMAIN_PNAME
        = "org.jitsi.jicofo.FOCUS_USER_DOMAIN";

    /**
     * The name of configuration property that specifies the user name used by
     * the focus to login to XMPP server.
     */
    public static final String FOCUS_USER_NAME_PNAME
        = "org.jitsi.jicofo.FOCUS_USER_NAME";

    /**
     * The name of the configuration property that specifies login password of
     * the focus user. If not provided then anonymous login method is used.
     */
    public static final String FOCUS_USER_PASSWORD_PNAME
        = "org.jitsi.jicofo.FOCUS_USER_PASSWORD";

    /**
     * The name of the configuration property that specifies if certificates of
     * the XMPP domain name should be verified, or always trusted. If not
     * provided then 'false' (should verify) is used.
     */
    public static final String ALWAYS_TRUST_PNAME
        = "org.jitsi.jicofo.ALWAYS_TRUST_MODE_ENABLED";

    /**
     * The name of the property used to configure a 1-byte identifier of this
     * Jicofo instance, used for the purpose of generating conference IDs unique
     * across a set of Jicofo instances.
     */
    public static final String JICOFO_SHORT_ID_PNAME
        = "org.jitsi.jicofo.SHORT_ID";

    /**
     * The name of the configuration property used to configure the PubSub node
     * to which videobridges are publishing their stats. It is used to discover
     * bridges automatically.
     */
    public static final String SHARED_STATS_PUBSUB_NODE_PNAME
        = "org.jitsi.jicofo.STATS_PUBSUB_NODE";

    /**
     * A property to enable health check debug. Enabling this will result an
     * extra thread which will be monitoring the health-checks execution times.
     * The thread will print thread dump in the logs for those health checks
     * which execute for more than 3 seconds.
     */
    private static final String HEALTH_CHECK_DEBUG_PROP_NAME =
        "org.jitsi.jicofo.rest.HEALTH_CHECK_DEBUG_ENABLED";

    /**
     * Whether health check debug is enabled. Off by default.
     */
    private boolean healthChecksDebugEnabled = false;

    /**
     * The pseudo-random generator which is to be used when generating IDs.
     */
    private static final Random RANDOM = new Random();

    /**
     * The XMPP domain used by the focus user to register to.
     */
    private DomainBareJid focusUserDomain;

    /**
     * The username used by the focus to login.
     */
    private Resourcepart focusUserName;

    /**
     * The thread that expires {@link JitsiMeetConference}s.
     */
    private final FocusExpireThread expireThread = new FocusExpireThread();

    /**
     * Jitsi Meet conferences mapped by MUC room names.
     *
     * Note that access to this field is almost always protected by a lock on
     * {@code this}. However, {@link #getConferenceCount()} executes
     * {@link Map#size()} on it, which wouldn't be safe with a
     * {@link HashMap} (as opposed to a {@link ConcurrentHashMap}.
     * I've chosen this solution, because I don't know whether the cleaner
     * solution of synchronizing on {@link #conferencesSyncRoot} in
     * {@link #getConferenceCount()} is safe.
     */
    private final Map<EntityBareJid, JitsiMeetConferenceImpl> conferences
        = new ConcurrentHashMap<>();

    /**
     * The set of the IDs of conferences in {@link #conferences}.
     */
    private final Set<String> conferenceIds = new HashSet<>();

    /**
     * The object used to synchronize access to {@link #conferences} and
     * {@link #conferenceIds}.
     */
    private final Object conferencesSyncRoot = new Object();

    // Convert to list when needed
    /**
     * FIXME: remove eventually if not used anymore
     * The list of {@link FocusAllocationListener}.
     */
    private FocusAllocationListener focusAllocListener;

    /**
     * XMPP protocol provider handler used by the focus.
     */
    private final ProtocolProviderHandler protocolProviderHandler
        = new ProtocolProviderHandler();

    /**
     * <tt>JitsiMeetServices</tt> instance that recognizes currently available
     * conferencing services like Jitsi videobridge or SIP gateway.
     */
    private JitsiMeetServices jitsiMeetServices;

    /**
     * Observes and discovers JVB instances and other conference components on
     * our XMPP domain.
     */
    private ComponentsDiscovery componentsDiscovery;

    /**
     * Indicates if graceful shutdown mode has been enabled and
     * no new conference request will be accepted.
     */
    private boolean shutdownInProgress;

    /**
     * Handler that takes care of pre-processing various Jitsi Meet extensions
     * IQs sent from conference participants to the focus.
     */
    private MeetExtensionsHandler meetExtensionsHandler;

    /**
     * Starts this manager for given <tt>hostName</tt>.
     */
    public void start()
        throws Exception
    {
        BundleContext bundleContext = FocusBundleActivator.bundleContext;

        expireThread.start();

        ConfigurationService config = FocusBundleActivator.getConfigService();
        String hostName = config.getString(HOSTNAME_PNAME);
        DomainBareJid xmppDomain = JidCreate.domainBareFrom(
                config.getString(XMPP_DOMAIN_PNAME));

        focusUserDomain = JidCreate.domainBareFrom(
                config.getString(FOCUS_USER_DOMAIN_PNAME));
        focusUserName = Resourcepart.from(
                config.getString(FOCUS_USER_NAME_PNAME));

        String focusUserPassword = config.getString(FOCUS_USER_PASSWORD_PNAME);

        protocolProviderHandler.start(
            hostName, focusUserDomain, focusUserPassword, focusUserName);

        jitsiMeetServices
            = new JitsiMeetServices(
                    protocolProviderHandler,
                    focusUserDomain);
        jitsiMeetServices.start(bundleContext);

        String statsPubSubNode
            = config.getString(SHARED_STATS_PUBSUB_NODE_PNAME);

        componentsDiscovery = new ComponentsDiscovery(jitsiMeetServices);
        componentsDiscovery.start(
            xmppDomain, statsPubSubNode, protocolProviderHandler);

        meetExtensionsHandler = new MeetExtensionsHandler(this);

        bundleContext.registerService(
                JitsiMeetServices.class,
                jitsiMeetServices,
                null);

        protocolProviderHandler.addRegistrationListener(this);
        protocolProviderHandler.register();

        healthChecksDebugEnabled
            = config.getBoolean(HEALTH_CHECK_DEBUG_PROP_NAME, false);
    }

    /**
     * Stops this instance.
     */
    public void stop()
    {
        expireThread.stop();

        if (componentsDiscovery != null)
        {
            componentsDiscovery.stop();
            componentsDiscovery = null;
        }

        if (jitsiMeetServices != null)
        {
            try
            {
                jitsiMeetServices.stop(FocusBundleActivator.bundleContext);
            }
            catch (Exception e)
            {
                logger.error("Error when trying to stop JitsiMeetServices", e);
            }
        }

        meetExtensionsHandler.dispose();

        protocolProviderHandler.stop();
    }

    /**
     * Allocates new focus for given MUC room.
     *
     * @param room the name of MUC room for which new conference has to be
     *             allocated.
     * @param properties configuration properties map included in the request.
     * @return <tt>true</tt> if conference focus is in the room and ready to
     *         handle session participants.
     * @throws Exception if for any reason we have failed to create
     *                   the conference
     */
    public boolean conferenceRequest(
            EntityBareJid          room,
            Map<String, String>    properties)
        throws Exception
    {
        return conferenceRequest(
                room,
                properties,
                null /* logging level - not specified which means that the one
                        from the logging configuration will be used */);
    }

    /**
     * Allocates new focus for given MUC room.
     *
     * @param room the name of MUC room for which new conference has to be
     *             allocated.
     * @param properties configuration properties map included in the request.
     * @param loggingLevel the logging level which should be used by the new
     * {@link JitsiMeetConference}
     *
     * @return <tt>true</tt> if conference focus is in the room and ready to
     *         handle session participants.
     * @throws Exception if for any reason we have failed to create
     *                   the conference
     */
    public boolean conferenceRequest(
            EntityBareJid          room,
            Map<String, String>    properties,
            Level                  loggingLevel)
        throws Exception
    {
        if (room == null)
            return false;

        JitsiMeetConferenceImpl conference;
        boolean isConferenceCreator;
        synchronized (conferencesSyncRoot)
        {
            conference = conferences.get(room);
            isConferenceCreator = conference == null;
            if (isConferenceCreator)
            {
                if (shutdownInProgress)
                {
                    return false;
                }

                conference = createConference(room, properties, loggingLevel);
            }
        }

        try
        {
            if (isConferenceCreator)
            {
                conference.start();
            }
        }
        catch (Exception e)
        {
            logger.info("Exception while trying to start the conference", e);

            // stop() method is called by the conference automatically in order
            // to not release the lock on JitsiMeetConference instance and avoid
            // a deadlock. It may happen when this thread is about to call
            // conference.stop() and another thread has entered the method
            // before us. That other thread will try to call
            // FocusManager.conferenceEnded, but we're still holding the lock
            // on FocusManager instance.

            //conference.stop();

            throw e;
        }


        return conference.isInTheRoom();
    }

    /**
     * Allocates conference for given <tt>room</tt>.
     *
     * Note: this should only be called by threads which hold the lock on
     * {@link #conferencesSyncRoot}.
     *
     * @param room name of the MUC room of Jitsi Meet conference.
     * @param properties configuration properties, see {@link JitsiMeetConfig}
     *                   for the list of valid properties.
     *
     * @returns new {@link JitsiMeetConferenceImpl} instance
     *
     * @throws Exception if any error occurs.
     */
    private JitsiMeetConferenceImpl createConference(
            EntityBareJid room, Map<String, String> properties, Level logLevel)
        throws Exception
    {
        JitsiMeetConfig config = new JitsiMeetConfig(properties);

        JitsiMeetGlobalConfig globalConfig
            = JitsiMeetGlobalConfig.getGlobalConfig(
                FocusBundleActivator.bundleContext);

        String id = generateConferenceId();
        JitsiMeetConferenceImpl conference
            = new JitsiMeetConferenceImpl(
                    room, focusUserName, protocolProviderHandler,
                    this, config, globalConfig, logLevel, id);

        conferences.put(room, conference);
        conferenceIds.add(id);

        if (conference.getLogger().isInfoEnabled())
        {
            StringBuilder sb = new StringBuilder("Created new focus for ");
            sb.append(room).append("@").append(focusUserDomain);
            sb.append(". Conference count ").append(conferences.size());
            sb.append(",").append("options: ");
            StringBuilder options = new StringBuilder();
            for (Map.Entry<String, String> option : properties.entrySet())
            {
                options.append(option.getKey())
                    .append("=")
                    .append(option.getValue())
                    .append(" ");
            }

            logger.info(sb);
        }

        // Send focus created event
        EventAdmin eventAdmin = FocusBundleActivator.getEventAdmin();
        if (eventAdmin != null)
        {
            eventAdmin.postEvent(
                EventFactory.focusCreated(
                    conference.getId(), conference.getRoomName()));
        }

        return conference;
    }

    /**
     * Generates a conference ID which is currently not used by an existing
     * conference in a specific format (6 hexadecimal symbols).
     * @return the generated ID.
     */
    private String generateConferenceId()
    {
        // TODO: verify the format
        String jicofoShortId
            = FocusBundleActivator.getConfigService()
                .getString(JICOFO_SHORT_ID_PNAME, "ff");

        String id;

        synchronized (conferencesSyncRoot)
        {
            do
            {
                id
                    = jicofoShortId +
                        Integer.toHexString(RANDOM.nextInt(0x1_0000));
            }
            while (conferenceIds.contains(id));
        }

        return id;
    }

    /**
     * Destroys the conference for given room name.
     * @param roomName full MUC room name to destroy.
     * @param reason optional reason string that will be advertised to the
     *               users upon exit.
     */
    public void destroyConference(EntityBareJid roomName, String reason)
    {
        synchronized (conferencesSyncRoot)
        {
            JitsiMeetConferenceImpl conference = getConference(roomName);
            if (conference == null)
            {
                logger.error(
                    "Unable to destroy the conference - not found: " + roomName);

                return;
            }

            // It is unclear whether this needs to execute while holding the
            // lock or not.
            conference.destroy(reason);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void conferenceEnded(JitsiMeetConferenceImpl conference)
    {
        EntityBareJid roomName = conference.getRoomName();

        synchronized (conferencesSyncRoot)
        {
            conferences.remove(roomName);
            conferenceIds.remove(conference.getId());

            if (conference.getLogger().isInfoEnabled())
                logger.info(
                    "Disposed conference for room: " + roomName
                        + " conference count: " + conferences.size());

            // It is not clear whether the code below necessarily needs to
            // hold the lock or not.
            if (focusAllocListener != null)
            {
                focusAllocListener.onFocusDestroyed(roomName);
            }

            // Send focus destroyed event
            EventAdmin eventAdmin = FocusBundleActivator.getEventAdmin();
            if (eventAdmin != null)
            {
                eventAdmin.postEvent(
                    EventFactory.focusDestroyed(
                        conference.getId(), conference.getRoomName()));
            }

            maybeDoShutdown();
        }
    }

    /**
     * Returns {@link JitsiMeetConference} for given MUC {@code roomName} or
     * {@code null} if no conference has been allocated yet.
     *
     * @param roomName the name of MUC room for which we want get the
     * {@code JitsiMeetConference} instance.
     * @return the {@code JitsiMeetConference} for the specified
     * {@code roomName} or {@code null} if no conference has been allocated yet
     */
    public JitsiMeetConferenceImpl getConference(EntityBareJid roomName)
    {
        synchronized (conferencesSyncRoot)
        {
            return conferences.get(roomName);
        }
    }

    /**
     * Enables shutdown mode which means that no new focus instances will
     * be allocated. After conference count drops to zero the process will exit.
     */
    public void enableGracefulShutdownMode()
    {
        if (!this.shutdownInProgress)
        {
            logger.info("Focus entered graceful shutdown mode");
        }
        this.shutdownInProgress = true;
        maybeDoShutdown();
    }

    private void maybeDoShutdown()
    {
        synchronized (conferencesSyncRoot)
        {
            if (shutdownInProgress && conferences.isEmpty())
            {
                logger.info("Focus is shutting down NOW");

                // It is not clear whether the code below necessarily needs to
                // hold the lock or not. Presumably it is safe to call it
                // multiple times.
                ShutdownService shutdownService
                    = ServiceUtils.getService(
                    FocusBundleActivator.bundleContext,
                    ShutdownService.class);

                shutdownService.beginShutdown();
            }
        }
    }

    /**
     * Returns the number of currently allocated focus instances.
     */
    public int getConferenceCount()
    {
        return conferences.size();
    }

    /**
     * Returns <tt>true</tt> if graceful shutdown mode has been enabled and
     * the process is going to be finished once conference count drops to zero.
     */
    public boolean isShutdownInProgress()
    {
        return shutdownInProgress;
    }

    /**
     * Sets the listener that will be notified about conference focus
     * allocation/disposal.
     * @param l the listener instance to be registered.
     */
    public void setFocusAllocationListener(FocusAllocationListener l)
    {
        this.focusAllocListener = l;
    }

    /**
     * Returns instance of <tt>JitsiMeetServices</tt> used in conferences.
     */
    public JitsiMeetServices getJitsiMeetServices()
    {
        return jitsiMeetServices;
    }

    /**
     * Interface used to listen for focus lifecycle events.
     */
    public interface FocusAllocationListener
    {
        /**
         * Method fired when focus is destroyed.
         * @param roomName the name of the conference room for which focus
         *                 has been destroyed.
         */
        void onFocusDestroyed(EntityBareJid roomName);

        // Add focus allocated method if needed
    }

    /**
     * Returns operation set instance for focus XMPP connection.
     *
     * @param opsetClass operation set class.
     * @param <T> the class of Operation Set to be returned
     * @return operation set instance of given class or <tt>null</tt> if
     * given operation set is not implemented by focus XMPP provider.
     */
    public <T extends OperationSet> T getOperationSet(Class<T> opsetClass)
    {
        return protocolProviderHandler.getOperationSet(opsetClass);
    }

    /**
     * Gets the {@code ProtocolProviderSerivce} for focus XMPP connection.
     *
     * @return  the {@code ProtocolProviderService} for focus XMPP connection
     */
    public ProtocolProviderService getProtocolProvider()
    {
        return protocolProviderHandler.getProtocolProvider();
    }

    @Override
    public void registrationStateChanged(RegistrationStateChangeEvent evt)
    {
        RegistrationState registrationState = evt.getNewState();
        logger.info("XMPP provider reg state: " + registrationState);
        if (RegistrationState.REGISTERED.equals(registrationState))
        {
            // Do initializations which require valid connection
            meetExtensionsHandler.init();
        }
    }

    /**
     * {@see FocusManager#healthChecksDebugEnabled}
     * @return true if enabled.
     */
    public boolean isHealthChecksDebugEnabled()
    {
        return healthChecksDebugEnabled;
    }

    /**
     * Class takes care of stopping {@link JitsiMeetConference} if there is no
     * active session for too long.
     */
    private class FocusExpireThread
    {
        private static final long POLL_INTERVAL = 5000;

        private final long timeout;

        private Thread timeoutThread;

        private final Object sleepLock = new Object();

        private boolean enabled;

        public FocusExpireThread()
        {
            timeout = FocusBundleActivator.getConfigService()
                        .getLong(IDLE_TIMEOUT_PNAME, DEFAULT_IDLE_TIMEOUT);
        }

        void start()
        {
            if (timeoutThread != null)
            {
                throw new IllegalStateException();
            }

            timeoutThread = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    expireLoop();
                }
            }, "FocusExpireThread");

            enabled = true;

            timeoutThread.start();
        }

        void stop()
        {
            if (timeoutThread == null)
            {
                return;
            }

            enabled = false;

            synchronized (sleepLock)
            {
                sleepLock.notifyAll();
            }

            try
            {
                if (Thread.currentThread() != timeoutThread)
                {
                    timeoutThread.join();
                }
                timeoutThread = null;
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        private void expireLoop()
        {
            while (enabled)
            {
                // Sleep
                try
                {
                    synchronized (sleepLock)
                    {
                        sleepLock.wait(POLL_INTERVAL);
                    }
                }
                catch (InterruptedException e)
                {
                    // Continue to check the enabled flag
                    // if we're still supposed to run
                }

                if (!enabled)
                    break;

                try
                {
                    ArrayList<JitsiMeetConferenceImpl> conferenceCopy;
                    synchronized (FocusManager.this.conferencesSyncRoot)
                    {
                        conferenceCopy = new ArrayList<>(conferences.values());
                    }

                    // Loop over conferences
                    for (JitsiMeetConferenceImpl conference : conferenceCopy)
                    {
                        long idleStamp = conference.getIdleTimestamp();
                        // Is active ?
                        if (idleStamp == -1)
                        {
                            continue;
                        }
                        if (System.currentTimeMillis() - idleStamp > timeout)
                        {
                            if (conference.getLogger().isInfoEnabled())
                                logger.info(
                                        "Focus idle timeout for "
                                            + conference.getRoomName());

                            conference.stop();
                        }
                    }
                }
                catch (Exception ex)
                {
                    logger.warn(
                        "Error while checking for timed out conference", ex);
                }
            }
        }
    }
}
