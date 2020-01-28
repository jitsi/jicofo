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

import mock.*;
import mock.jvb.*;
import mock.xmpp.*;
import mock.xmpp.pubsub.*;

import org.jitsi.jicofo.bridge.*;
import org.jitsi.xmpp.extensions.colibri.*;
import net.java.sip.communicator.util.*;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.*;

import org.jivesoftware.smack.packet.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.mockito.*;

import java.util.*;
import java.util.concurrent.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Basic test for bridge discovery through PubSub stats.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class PubSubBridgeSelectorTest
{
    static OSGiHandler osgi = OSGiHandler.getInstance();

    private static Jid jvb1Jid;
    private static Jid jvb2Jid;
    private static Jid jvb3Jid;

    private static String sharedPubSubNode = "sharedJvbStats";

    private static final long MAX_STATS_AGE = 400;

    private static final long x2_MAX_STATS_AGE = MAX_STATS_AGE * 2;

    private static final int HEALTH_CHECK_INT = 150;

    private static JitsiMeetServices meetServices;

    private static MockProtocolProvider mockProvider;

    private static BridgeSelector selector;

    private static MockSubscriptionOpSetImpl subOpSet;

    private static MockSetSimpleCapsOpSet capsOpSet;

    private static XmppConnection xmppConnection;

    private static MockVideobridge jvb1;

    private static MockVideobridge jvb2;

    private static MockVideobridge jvb3;

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
        System.clearProperty(
            BridgeSelector.BRIDGE_TO_PUBSUB_PNAME);

        System.setProperty(
            FocusManager.SHARED_STATS_PUBSUB_NODE_PNAME, sharedPubSubNode);

        System.setProperty(
            ComponentsDiscovery
                .ThroughPubSubDiscovery.MAX_STATS_REPORT_AGE_PNAME,
            String.valueOf(MAX_STATS_AGE));

        System.setProperty(
            JvbDoctor.HEALTH_CHECK_INTERVAL_PNAME, "" + HEALTH_CHECK_INT);

        osgi.init();

        meetServices
            = ServiceUtils.getService(osgi.bc, JitsiMeetServices.class);

        ProviderListener providerListener
            = new ProviderListener(FocusBundleActivator.bundleContext);

        mockProvider
            = (MockProtocolProvider) providerListener.obtainProvider(1000);

        xmppConnection = mockProvider.getXmppConnection();

        selector = meetServices.getBridgeSelector();

        subOpSet
            = mockProvider.getMockSubscriptionOpSet();

        capsOpSet = mockProvider.getMockCapsOpSet();

        createMockJvbs();
    }

    @Before
    public void before()
    {
        assertFalse(osgi.isDeadlocked());
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
    {
        try
        {
            jvb1.stop(osgi.bc);
            jvb2.stop(osgi.bc);
            jvb3.stop(osgi.bc);
        }
        catch(NullPointerException npe)
        {
            // ignore
        }
        finally
        {
            osgi.shutdown();
        }
    }

    static private void createMockJvbs()
        throws Exception
    {
        jvb1Jid = JidCreate.from("jvb1.test.domain.net");
        jvb2Jid = JidCreate.from("jvb2.test.domain.net");
        jvb3Jid = JidCreate.from("jvb3.test.domain.net");
        jvb1 = createMockJvb(JidCreate.from(jvb1Jid));
        jvb2 = createMockJvb(JidCreate.from(jvb2Jid));
        jvb3 = createMockJvb(JidCreate.from(jvb3Jid));
    }

    static private MockVideobridge createMockJvb(Jid jvbJid)
        throws Exception
    {
        MockVideobridge mockBridge
            = new MockVideobridge(new MockXmppConnection(jvbJid), jvbJid);

        MockCapsNode jvbNode
            = new MockCapsNode(
                    jvbJid, JitsiMeetServices.VIDEOBRIDGE_FEATURES);

        mockBridge.start(osgi.bc);

        capsOpSet.addChildNode(jvbNode);

        return mockBridge;
    }

    @Test
    public void selectorTest()
        throws InterruptedException
    {
        // FIXME: remove sleep
        Thread.sleep(2000);

        triggerJvbStats(jvb1Jid, 0);
        assertTrue(selector.isJvbOnTheList(jvb1Jid));

        triggerJvbStats(jvb2Jid, 0);
        assertTrue(selector.isJvbOnTheList(jvb2Jid));

        triggerJvbStats(jvb3Jid, 0);
        assertTrue(selector.isJvbOnTheList(jvb3Jid));

        Thread.sleep(x2_MAX_STATS_AGE);

        assertFalse(selector.isJvbOnTheList(jvb1Jid));
        assertFalse(selector.isJvbOnTheList(jvb2Jid));
        assertFalse(selector.isJvbOnTheList(jvb3Jid));

        triggerJvbStats(jvb2Jid, 0);
        assertTrue(selector.isJvbOnTheList(jvb2Jid));
    }

    /**
     * If the bridge is restarted and we have health check failed on it, but
     * before {@link ComponentsDiscovery.ThroughPubSubDiscovery} has timed out
     * this instance we will not re-discover it through the PubSub.
     */
    // FIXME randomly fails
    //@Test
    public void clearPubSubBridgeIssueTest()
    {
        System.err.println("Running clearPubSubBridgeIssueTest");

        // Make sure that jvb advertises features with health-check support
        String[] features
            = ArrayUtils.add(
                    JitsiMeetServices.VIDEOBRIDGE_FEATURES,
                    String.class,
                    DiscoveryUtil.FEATURE_HEALTH_CHECK);
        MockCapsNode jvbNode = new MockCapsNode(jvb1Jid, features);
        capsOpSet.addChildNode(jvbNode);

        // Remove all JVBS
        selector.removeJvbAddress(jvb1Jid);
        selector.removeJvbAddress(jvb2Jid);
        selector.removeJvbAddress(jvb3Jid);

        EventHandler eventSpy = mock(EventHandler.class);
        EventUtil.registerEventHandler(
            osgi.bc,
            new String[] {
                BridgeEvent.BRIDGE_UP,
                BridgeEvent.BRIDGE_DOWN,
                BridgeEvent.HEALTH_CHECK_FAILED },
            eventSpy);

        // Trigger PubSub, so that the bridge is discovered
        triggerJvbStats(jvb1Jid, 0);

        // Verify that the bridge has been discovered
        verify(eventSpy, timeout(100))
            .handleEvent(BridgeEvent.createBridgeUp(jvb1Jid));

        // Now make the bridge return health-check failure
        jvb1.setReturnServerError(true);

        // Here we verify that first there was HEALTH_CHECK_FAILED event
        // send by JvbDoctor
        verify(eventSpy, timeout(HEALTH_CHECK_INT * 2))
            .handleEvent(BridgeEvent.createHealthFailed(jvb1Jid));

        // and after that BRIDGE_DOWN should be triggered
        // by BridgeSelector
        verify(eventSpy, timeout(100))
            .handleEvent(BridgeEvent.createBridgeDown(jvb1Jid));

        // Now we fix back the bridge and send some PubSub stats
        jvb1.setReturnServerError(false);

        triggerJvbStats(jvb1Jid, 1);
        triggerJvbStats(jvb1Jid, 0);

        // Assert the bridge has been discovered again
        verify(eventSpy,  times(2))
            .handleEvent(BridgeEvent.createBridgeUp(jvb1Jid));

        // Verify events order
        InOrder eventsOrder = inOrder(eventSpy);

        eventsOrder
            .verify(eventSpy)
            .handleEvent(BridgeEvent.createBridgeUp(jvb1Jid));

        eventsOrder
            .verify(eventSpy)
            .handleEvent(BridgeEvent.createHealthFailed(jvb1Jid));

        eventsOrder
            .verify(eventSpy)
            .handleEvent(BridgeEvent.createBridgeDown(jvb1Jid));

        eventsOrder
            .verify(eventSpy)
            .handleEvent(BridgeEvent.createBridgeUp(jvb1Jid));
    }

    @Test
    public void deadlockTest()
        throws InterruptedException, ExecutionException
    {
        final JitsiMeetServices meetServices
            = ServiceUtils.getService(osgi.bc(), JitsiMeetServices.class);

        ExecutorService executorService
            = Executors.newFixedThreadPool(30, new DaemonThreadFactory());

        List<Future> executors = new LinkedList<>();
        for (int i=0; i<500;i++)
        {
            executors.add(executorService.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    triggerJvbStats(jvb2Jid, 1);
                }
            }));
            executors.add(executorService.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    meetServices.nodeNoLongerAvailable(jvb1Jid);
                }
            }));
            executors.add(executorService.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    triggerJvbStats(jvb1Jid, 1);
                }
            }));
            executors.add(executorService.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    meetServices.nodeNoLongerAvailable(jvb1Jid);
                }
            }));
        }

        // Wait for all tasks to finish
        for (Future f : executors)
        {
            try
            {
                f.get(3, TimeUnit.SECONDS);
            }
            catch (TimeoutException e)
            {
                osgi.setDeadlocked(true);

                fail("One of the executors got blocked!");
            }
        }
    }

    private ExtensionElement triggerJvbStats(Jid itemId, int conferenceCount)
    {
        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                CONFERENCES, "" + conferenceCount));

        subOpSet.fireSubscriptionNotification(
            sharedPubSubNode, itemId.toString(), statsExtension);

        return statsExtension;
    }
}
